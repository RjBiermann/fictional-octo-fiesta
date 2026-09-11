package com.rjbiermann

import com.kraptor.registerHostExtractors
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// Shape: embed/extractor site. Selectors + endpoints from FINDINGS.md.
class Javmost : MainAPI() {

    object Parse {
        data class Info(val year: Int?, val duration: Int?, val actors: List<String>, val tags: List<String>)

        /** Video-page card-block: Release date, Time minutes, star/category anchors. */
        fun cardBlock(root: org.jsoup.nodes.Element): Info {
            val block = root.selectFirst("div.card-block") ?: return Info(null, null, emptyList(), emptyList())
            val text = block.text()
            val year = Regex("Release (\\d{4})-").find(text)?.groupValues?.get(1)?.toInt()
            val duration = Regex("Time (\\d+)").find(text)?.groupValues?.get(1)?.toInt()
            val actors = block.select("a[href*='/star/']").map { it.text().trim() }.filter { it.isNotBlank() }
            val tags = block.select("a[href*='/category/']").map { it.text().trim() }.filter { it.isNotBlank() }
            return Info(year, duration, actors, tags)
        }

        /** Poster URL from a listing/related card: prefer <source data-srcset> (real image);
         *  img[data-src]/src are the white lazyload placeholder (issue #239) — never returned. */
        fun parseCardUrl(card: Element): String? {
            val set = card.selectFirst("source[data-srcset]")?.attr("data-srcset")?.trim()
            if (!set.isNullOrBlank()) return set
            val img = card.selectFirst("img[data-src]")?.attr("data-src")?.takeIf { !it.contains("preload") }
                ?: card.selectFirst("img")?.attr("src")?.takeIf { !it.contains("preload") }
            return img?.takeIf { it.isNotBlank() }
        }

        data class Rec(val url: String, val title: String, val poster: String?)

        /** Related-video cards (issue #300): the <a alt> anchor is the PARENT of div.card on live
         *  video pages — selecting div.card and searching descendants only ever found the self
         *  anchor. Iterate from the anchor side; poster via source[data-srcset] on the child card. */
        fun recs(doc: org.jsoup.nodes.Document, mainUrl: String): List<Rec> =
            doc.select("a[alt]").mapNotNull { a ->
                try {
                    val href = a.attr("href")
                    if (!href.contains(mainUrl)) return@mapNotNull null
                    val title = a.attr("alt").trim()
                    if (title.isBlank()) return@mapNotNull null
                    Rec(href, title, parseCardUrl(a.selectFirst("div.card") ?: doc.createElement("div")))
                } catch (e: Exception) { null }
            }

        /** showlist2 listing title (issue #300): full_name carries HTML entities — decode before use. */
        fun title(name: String?, fullName: String?): String =
            org.jsoup.parser.Parser.unescapeEntities(name ?: "", false).trim().ifBlank {
                org.jsoup.parser.Parser.unescapeEntities(fullName ?: "", false).trim()
            }

        /** issue #332 finding 1: showlist2 "pending" bucket entries (all/1/category) carry null
         *  release AND null star — every such URL 404s. Returns why the entry is dead, or null.
         *  NOTE: null-meta items in OTHER groups (uncensor, e.g. CARIBBEANCOM) are live — callers
         *  must scope this filter to the "all" group only. */
        fun pendingReason(el: com.fasterxml.jackson.databind.JsonNode): String? {
            fun isNull(f: String) = el.get(f)?.isNull ?: true
            return if (isNull("release") && isNull("star")) "pending bucket: no metadata, url 404s" else null
        }

        /** Video-page synopsis (issue #270): a[alt] anchor inside the video's own card-block whose
         *  href matches the video url — the code-only anchor is skipped. og:description is boilerplate, not used. */
        fun plot(doc: org.jsoup.nodes.Document, videoUrl: String): String? =
            doc.selectFirst("div.card-block")?.select("a[alt]")
                ?.filter { it.attr("href") == videoUrl }
                ?.maxByOrNull { it.attr("alt").trim().length }   // synopsis anchor, not the short code anchor
                ?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }

        /** parses the /ri3123o235r/ AJAX response {"status":"success","data":["<embed-url>"]}
         *  (slashes JSON-escaped) → first url, null on any other shape. issue #332 finding 2: the
         *  dooplayer.com/mostplayer.com embeds this can return are dead (dooplayer times out,
         *  mostplayer /embed/e/ → 204 No Content), so loadLinks only keeps the emturbovid ones. */
        fun ajaxEmbed(json: String): String? =
            json.substringAfter("\"data\":[\"", "").substringBefore("\"")
                .replace("\\/", "/").takeIf { it.contains("http") }
    }
    override var mainUrl        = "https://www.javmost.ws"
    override var name           = "Javmost"
    override val hasMainPage    = true
    override var lang           = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    // data format for mainPage entries: "group::type" → /showlist2/{group}/{page}/{type}/
    // issue #332 finding 1: the all group's page-1 "All Movies" listing is the site's pending
    // bucket — every entry is null-metadata and 404s (fixture showlist2-all-page1.json); the row
    // is dropped until the site backfills. Censored/Uncensored cover the catalogue.
    override val mainPage = mainPageOf(
        "uncensor::category" to "Uncensored",
        "censor::category" to "Censored",
        "new::release" to "New Releases",
    )

    // FINDINGS 2026-09-08: listing pages (/search, /category, /release) now serve an unrendered
    // JS template; the browser fills them via JSON /showlist2/{group}/{page}/{type}/.
    private suspend fun showlist(group: String, type: String, page: Int): List<SearchResponse> {
        val res = app.get("$mainUrl/showlist2/$group/$page/$type/")
        val root = com.fasterxml.jackson.databind.ObjectMapper().readTree(res.text)
        return root.get("result")?.mapNotNull { el ->
            try {
                val url = el.get("url")?.asText() ?: return@mapNotNull null
                // issue #332 finding 1: in the all group null-meta entries (release AND star null) are
                // the site's dead pending bucket; null-meta items in other groups are live, so scope to all/
                if (group == "all" && Parse.pendingReason(el) != null) { return@mapNotNull null }
                val title = Parse.title(el.get("name")?.asText(), el.get("full_name")?.asText())
                if (title.isBlank()) return@mapNotNull null
                newMovieSearchResponse(title, url, TvType.NSFW) {
                    posterUrl = fixUrlNull(el.get("cover")?.asText())
                }
            } catch (e: Exception) { null }
        } ?: emptyList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (group, type) = request.data.split("::")
        val home = showlist(group, type, page)
        return newHomePageResponse(
            HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = home.isNotEmpty(),
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val results = showlist(query, "search", page)
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    // FINDINGS 2026-09-11 (issue #300): related cards wrap div.card from the outside — use anchor-side Parse.recs
    private fun recommendationsFor(document: org.jsoup.nodes.Document, url: String) =
        Parse.recs(document, mainUrl).map { rec ->
            newMovieSearchResponse(rec.title, rec.url, TvType.NSFW) { posterUrl = fixUrlNull(rec.poster) }
        }.filter { it.url != url }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        // FINDINGS: og:title = "Watch {CODE} JAV movie online free streaming. Genre: X."
        val ogTitle = document.selectFirst("meta[property=\"og:title\"]")?.attr("content") ?: ""
        val title = ogTitle.removePrefix("Watch ").substringBefore(" JAV movie").trim().ifBlank { url }
        // FINDINGS 2026-09-09: card-block carries full metadata (year, duration, actors, genres)
        val info = Parse.cardBlock(document)
        val tags = info.tags.ifEmpty {
            Regex("Genre: (.+?)\\.").find(ogTitle)?.groupValues?.get(1)
                ?.split(",")?.map { it.trim() } ?: emptyList()
        }
        val poster = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")

        val recommendations = recommendationsFor(document, url)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(poster)
            this.tags = tags
            this.year = info.year
            this.duration = info.duration
            this.actors = info.actors.map { ActorData(Actor(it)) }
            this.plot = Parse.plot(document, url)
            this.recommendations = recommendations
        }
    }

    private suspend fun resolveEmturbovid(embedUrl: String, referer: String): String? {
        return try {
            // FINDINGS: emturbovid.com/t/<id> 301 -> turbovidhls.com/t/<id>, m3u8 in data-hash / urlPlay
            val page = app.get(embedUrl, referer = referer).text
            Regex("urlPlay\\s*=\\s*'([^']+m3u8[^']*)'").find(page)?.groupValues?.get(1)
                ?: Regex("data-hash=\"([^\"]+m3u8[^\"]*)\"").find(page)?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.d(name, "resolveEmturbovid: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        // FINDINGS: constant on every video page, feeds the AJAX value param
        val value = Regex("YWRzMQo\\s*=\\s*'([^']+)'").find(document.html())?.groupValues?.get(1) ?: return true
        // FINDINGS: select_part('1','<group>',this,'parent','<code>','<code2>','<code3>')
        val servers = Regex(
            "select_part\\('1','(\\d+)',this,'parent','([A-Za-z0-9+/=]+)','([A-Za-z0-9+/=]+)','([A-Za-z0-9+/=]+)'\\)"
        ).findAll(document.html())

        for (m in servers) {
            try {
                val body = app.post(
                    "$mainUrl/ri3123o235r/",                     // FINDINGS: AJAX endpoint
                    headers = mapOf("Referer" to data),
                    data = mapOf(
                        "group" to m.groupValues[1],
                        "part" to "1",
                        "code" to m.groupValues[2],
                        "code2" to m.groupValues[3],
                        "code3" to m.groupValues[4],
                        "value" to value,
                        "sound" to "av",
                    )
                ).text
                val embed = Parse.ajaxEmbed(body) ?: continue
                if (embed.contains("emturbovid.com")) {
                    val m3u8 = resolveEmturbovid(embed, data)
                    if (m3u8 == null) continue
                    callback.invoke(
                        newExtractorLink(name, name, m3u8) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                            this.type = ExtractorLinkType.M3U8
                        }
                    )
                } else continue
                // issue #332 finding 2: the dooplayer/mostplayer branch is REMOVED — dooplayer.com
                // times out and mostplayer /embed/e/ returns 204 No Content (even for live
                // mostplayer IDs), so the x-embed chain can never resolve. Dooplayer-only titles
                // (PPPE-443/445, START-631) fail fast here with 0 links instead of hanging.
            } catch (e: Exception) {
                Log.d(name, "loadLinks: ${e.message}")
            }
        }
        return true
    }
}

@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class JavmostPlugin : com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(Javmost())
        registerHostExtractors()
    }
}
