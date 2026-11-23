package com.byayzen

import com.kraptor.JsonLdParse
import com.kraptor.searchCard
import com.kraptor.registerHostExtractors
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.math.BigInteger

/**
 * Pure Parse function for page-URL construction (TDD seams, issues #293 and #537).
 *
 * #293 (probe 2026-09-11): segment-style list rows (`/most-viewed/`, `/longest/`)
 * 301-redirect their suffix-paginated form (`/most-viewed/2/` → `/most-viewed/`, page 1
 * again); the site paginates them as `/2/<list>/`. Other rows (root, top-rated, tags, cats)
 * paginate as `<path>/<n>/`.
 *
 * #537 (probe 2026-10-10): `/search/<q>/<n>/` 301s in two legs to the tag base
 * (`/search/<q>/` → `/search/<q>-<hash>/` → `/tag/<q>/`) — the page number never survives,
 * so every in-app search page rendered page-1 duplicates. `/tag/<q>/<n>/` paginates
 * correctly (p1∩p2: 0 of 65 card ids), so search routes pages ≥ 2 through `/tag/<q>/<n>/`.
 */
object EPornerParse {
    private val swapSegments = setOf("most-viewed", "longest")

    /**
     * Video title from the watch page's h1 (2026-10-16 deep pass): the live template
     * nests span.vid-length / span.vid-quality chips inside the h1, so h1.text()
     * glued duration+quality onto every title ("Tobrut Enak 9min 720p"). The title
     * is the h1's own text; if a future template moves the title into a child
     * element, fall back to the old text() behavior instead of dropping the title.
     */
    fun videoTitle(h1: org.jsoup.nodes.Element?): String? {
        val own = h1?.ownText()?.trim()
        return own?.takeIf { it.isNotEmpty() }
            ?: h1?.text()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * One playable source from the `/xhr/video/<id>` player config. [label] is the site's
     * own quality token ("1080p", "2160p"); CloudStream's `getQualityFromName` maps it.
     */
    data class VideoSource(val url: String, val label: String?)

    private val mapper = ObjectMapper()

    private fun sourcesNode(config: String): com.fasterxml.jackson.databind.JsonNode? = try {
        mapper.readTree(config)?.get("sources")
    } catch (_: Exception) {
        null
    }

    /**
     * mp4 ladder from the player config (2026-11-23 video-sources audit, #1449).
     *
     * The old text regex (`"labelShort"…"src"`) depended on the JSON key order the
     * player happened to emit; the config is parsed structurally instead. `/dload/`
     * entries are the download endpoint, never a player source.
     */
    fun mp4Sources(config: String): List<VideoSource> {
        val mp4 = sourcesNode(config)?.get("mp4") ?: return emptyList()
        return mp4.fields().asSequence().mapNotNull { (key, node) ->
            val url = node.get("src")?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (url.contains("/dload/")) return@mapNotNull null
            VideoSource(url, node.get("labelShort")?.asText()?.takeIf { it.isNotBlank() } ?: key)
        }.toList()
    }

    /**
     * HLS ladder from the player config, or null when the video exposes none.
     *
     * Live shapes (2026-11-23): when both URLs exist, `src` is the **AV1** ladder and
     * `srcFallback` the H.264 one; ~3 of 8 sampled videos ship `src` only, and that
     * ladder is already H.264. Prefer H.264 (plays on every ExoPlayer device) and keep
     * a plain `src` as the fallback — the old `srcFallback`-only regex dropped the whole
     * HLS leg on those pages.
     */
    fun hlsSource(config: String): VideoSource? {
        val hls = sourcesNode(config)?.get("hls") ?: return null
        for ((_, node) in hls.fields()) {
            val url = node.get("srcFallback")?.asText()?.takeIf { it.startsWith("http") }
                ?: node.get("src")?.asText()
                    ?.takeIf { it.startsWith("http") && !it.contains("-av1") }
                ?: continue
            return VideoSource(url, hlsQuality(url)?.let { "${it}p" })
        }
        return null
    }

    /**
     * Top rung of the ladder encoded in the HLS URL path, e.g.
     * `…/hls/19055511-,240p,360p,…,2160p,.mp4.urlset/master.m3u8` → 2160. The path is the
     * site's own quality label, so the link is reported at the quality it really serves
     * (the master playlist's RESOLUTION is scaled down on some CDN nodes).
     */
    fun hlsQuality(url: String): Int? =
        Regex("""(\d{3,4})p""")
            .findAll(url.substringBefore(".mp4.urlset").substringAfterLast('/'))
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull()

    fun pageUrl(baseUrl: String, page: Int): String {
        if (page <= 1) return baseUrl
        val trimmed = baseUrl.trimEnd('/')
        val first = trimmed.substringAfterLast('/')
        // Search branch must precede the swap branch: a query slug like "most-viewed"
        // satisfies the swap guard but must route to /tag/<q>/<page>/, not the home list.
        return when {
            trimmed.startsWith("https://www.eporner.com/search/", ignoreCase = true) ->
                "https://www.eporner.com/tag/$first/$page/"
            baseUrl.count { it == '/' } >= 4 && first in swapSegments ->
                "https://www.eporner.com/$page/$first/"
            else -> "$trimmed/$page/"
        }
    }
}

class EPorner : MainAPI() {
    override var mainUrl = "https://www.eporner.com"
    override var name = "EPorner"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Most recent",
        "$mainUrl/most-viewed/" to "Most viewed",
        "$mainUrl/top-rated/" to "Top rated",
        "$mainUrl/longest/" to "Longest",
        "$mainUrl/tag/cowgirl/" to "Cowgirl",
        "$mainUrl/tag/riding/" to "Riding",
        "$mainUrl/tag/turkish/" to "Turkish",
        "$mainUrl/cat/housewives/" to "Housewives",
        "$mainUrl/cat/vintage/" to "Vintage"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = EPornerParse.pageUrl(request.data, page)
        val home = app.get(url).document.select("div#vidresults div.mb").mapNotNull { searchCard(it, "p.mbtit a", posterSel = "div.mbimg img") }
        return newHomePageResponse(HomePageList(request.name, home, true), true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val formattedQuery = query.replace(" ", "-")
        val url = EPornerParse.pageUrl("$mainUrl/search/$formattedQuery/", page)
        val results = app.get(url).document.select("div#vidresults div.mb").mapNotNull { searchCard(it, "p.mbtit a", posterSel = "div.mbimg img") }
        return newSearchResponseList(results, true)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = EPornerParse.videoTitle(document.selectFirst("h1")) ?: return null
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("video#EPvideo")?.attr("poster")
        )
        val tags = document.select("div#video-info-tags ul li.vit-category a").map { it.text() }
        // span.C markup is gone from video pages; year lives in the JSON-LD VideoObject
        // (uploadDate), which JsonLdParse also handles for other providers.
        val year = JsonLdParse.year(jsonLdText(document))
        val duration = document.selectFirst("span.vid-length")?.text()?.replace("min", "")?.trim()
            ?.toIntOrNull()
        val description =
            document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val recommendations =
            document.select("div#relateddiv div.mb").mapNotNull { searchCard(it, "p.mbtit a", posterSel = "div.mbimg img") }
        // Cast markup was removed from video pages; primary source is the per-actor
        // links (li.vit-pornstar.starw a), with JSON-LD and og:description fallbacks.
        val actors = document.select("li.vit-pornstar.starw a").map { Actor(it.text()) }
            .ifEmpty { document.select("span.valor a").map { Actor(it.text()) } }
            .ifEmpty {
                jsonLdActors(document).ifEmpty {
                // ponytail: og:description heuristics — "Starring: X" clause, else the
                // "Watch <title> , <Actor>. Duration" comma variant; revisit if the
                // description format changes again.
                val names = description?.substringAfter("Starring:", "")?.takeIf { it.isNotEmpty() }
                    ?: """\s,\s*(.+?)\. Duration""".toRegex().findAll(description ?: "")
                        .lastOrNull()?.groupValues?.get(1)
                    names?.substringBefore(". Duration")
                    ?.split(",")
                    ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
                    ?.map { Actor(it) }
                    ?: emptyList()
                }
            }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.duration = duration
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    private fun jsonLdText(document: org.jsoup.nodes.Document): String? =
        document.select("script[type=application/ld+json]").firstOrNull()?.data()

    private fun jsonLdActors(document: org.jsoup.nodes.Document): List<Actor> {
        for (script in document.select("script[type=application/ld+json]")) {
            try {
                val obj = ObjectMapper().readTree(script.data())
                val actor = obj.get("actor")
                if (actor is ArrayNode && actor.size() > 0) {
                    return actor.mapNotNull { it.get("name")?.asText()?.takeIf(String::isNotEmpty) }
                        .map(::Actor)
                }
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = fixUrl(data)
        var videolink = false

        try {
            val vidmatch = """/(?:embed|video)-([a-zA-Z0-9]+)""".toRegex().find(url)
            val vid = vidmatch?.groupValues?.get(1) ?: return false

            val embedurl = "$mainUrl/embed/$vid/"
            val embedhtml = app.get(embedurl).text

            val md5hash = """EP\.video\.player\.hash\s*=\s*'([^']+)'""".toRegex().find(embedhtml)?.groupValues?.get(1)
            if (md5hash == null) {
                Log.d(name, "hash bulunamadi")
                return false
            }
            val convertedhash = md5hash.chunked(8).map { chunk ->
                BigInteger(chunk, 16).toString(36)
            }.joinToString("")
            Log.d(name, "vid: $vid - hash: $convertedhash")
            val xhrurl = "$mainUrl/xhr/video/$vid?hash=$convertedhash&domain=www.eporner.com&pixelRatio=1&playerWidth=0&playerHeight=0&fallback=false&embed=true&supportedFormats=hls,dash,h265,vp9,av1,mp4&_=${System.currentTimeMillis()}"

            val config = app.get(
                xhrurl,
                headers = mapOf(
                    "Referer" to embedurl,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text
            Log.d(name, "xhr yanit uzunlugu: ${config.length}")

            // Player config → source list (#1449): mp4 ladder at the site's own labels,
            // then one HLS ladder reported at its real top rung.
            EPornerParse.mp4Sources(config).forEach { source ->
                Log.d(name, "kalite: ${source.label} - url: ${source.url}")
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = source.url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = getQualityFromName(source.label)
                    }
                )
                videolink = true
            }

            EPornerParse.hlsSource(config)?.let { source ->
                Log.d(name, "hls: ${source.url}")
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = "${name}:HLS",
                        url = source.url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = getQualityFromName(source.label)
                    }
                )
                videolink = true
            }

            if (!videolink) {
                Log.d(name, "xhr icerisinde video linki bulunmadı")
            }

        } catch (e: Exception) {
            Log.d(name, "hata: ${e.message}")
        }

        return videolink
    }
}

@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class EPornerPlugin : com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(EPorner())
        registerHostExtractors()
    }
}
