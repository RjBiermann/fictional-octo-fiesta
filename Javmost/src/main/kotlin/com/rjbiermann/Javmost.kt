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
    }
    override var mainUrl        = "https://www.javmost.ws"
    override var name           = "Javmost"
    override val hasMainPage    = true
    override var lang           = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    // data format for mainPage entries: "group::type" → /showlist2/{group}/{page}/{type}/
    override val mainPage = mainPageOf(
        "all::category" to "All Movies",
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
                val title = (el.get("name")?.asText() ?: "").ifBlank {
                    el.get("full_name")?.asText() ?: ""
                }.trim()
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

    // video pages are still server-rendered (FINDINGS 2026-09-08) — recommendations use div.card
    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a[href*=\"$mainUrl/\"]") ?: return null
        val href = link.attr("href")
        if (!href.contains(mainUrl)) return null
        val title = link.attr("alt").ifBlank {
            selectFirst("h2.card-title")?.text() ?: ""
        }.trim()
        if (title.isBlank()) return null
        val poster = selectFirst("img[data-src]")?.attr("data-src")
            ?: selectFirst("source[data-srcset]")?.attr("data-srcset")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            posterUrl = fixUrlNull(poster)
        }
    }

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

        val recommendations = document.select("div.card").mapNotNull {
            try { it.toSearchResult() } catch (e: Exception) { null }
        }.filter { it.url != url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(poster)
            this.tags = tags
            this.year = info.year
            this.duration = info.duration
            this.actors = info.actors.map { ActorData(Actor(it)) }
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
                // FINDINGS: {"status":"success","data":["<embed-url>"]}
                // JSON escapes slashes (https:\/\/...) — unescape
                val embed = body.substringAfter("\"data\":[\"").substringBefore("\"")
                    .replace("\\/", "/").takeIf { it.contains("http") } ?: continue
                // FINDINGS: dooplayer embeds are JS-only (204) — only emturbovid resolves server-side
                if (!embed.contains("emturbovid.com")) continue
                val m3u8 = resolveEmturbovid(embed, data) ?: continue
                callback.invoke(
                    newExtractorLink(name, name, m3u8) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.M3U8
                    }
                )
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
