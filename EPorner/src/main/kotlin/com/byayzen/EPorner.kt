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
 * Pure Parse function for page-URL construction (TDD seam, issue #293).
 *
 * Segment-style list rows (`/most-viewed/`, `/longest/`) 301-redirect their
 * suffix-paginated form (`/most-viewed/2/` → `/most-viewed/`, i.e. page 1 again);
 * the site paginates them as `/2/<list>/`. Other rows (root, top-rated, tags,
 * cats) paginate as `<path>/<n>/`. Both forms verified live 2026-09-11.
 */
object EPornerParse {
    private val swapSegments = setOf("most-viewed", "longest")

    fun pageUrl(baseUrl: String, page: Int): String {
        if (page <= 1) return baseUrl
        val trimmed = baseUrl.trimEnd('/')
        val first = trimmed.substringAfterLast('/')
        return if (baseUrl.count { it == '/' } >= 4 && first in swapSegments)
            "https://www.eporner.com/$page/$first/"
        else "$trimmed/$page/"
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
        "$mainUrl/cat/housewives/" to "Housewives"

    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = EPornerParse.pageUrl(request.data, page)
        val home = app.get(url).document.select("div#vidresults div.mb").mapNotNull { searchCard(it, "p.mbtit a", posterSel = "div.mbimg img") }
        return newHomePageResponse(HomePageList(request.name, home, true), true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val formattedQuery = query.replace(" ", "-")
        val url = if (page <= 1) "$mainUrl/search/$formattedQuery/" else "$mainUrl/search/$formattedQuery/$page/"
        val results = app.get(url).document.select("div#vidresults div.mb").mapNotNull { searchCard(it, "p.mbtit a", posterSel = "div.mbimg img") }
        return newSearchResponseList(results, true)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
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

            val responsetext = app.get(
                xhrurl,
                headers = mapOf(
                    "Referer" to embedurl,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text
            Log.d(name, "xhr yanit uzunlugu: ${responsetext.length}")

            """labelShort"\s*:\s*"(\d{3,4}p)[^"]*"\s*,\s*"src"\s*:\s*"([^"]+)"""".toRegex()
                .findAll(responsetext).forEach { match ->
                    val quality = match.groupValues[1]
                    val videourl = match.groupValues[2]
                    if (!videourl.contains("/dload/")) {
                        Log.d(name, "kalite: $quality - url: $videourl")
                        callback.invoke(
                            newExtractorLink(
                                name = name,
                                source = name,
                                url = videourl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = getQualityFromName(quality)
                            }
                        )
                        videolink = true
                    }
                }

            val hlsmatch = """"srcFallback"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""".toRegex().find(responsetext)
            if (hlsmatch != null) {
                Log.d(name, "hls: ${hlsmatch.groupValues[1]}")
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = "${name}:HLS",
                        url = hlsmatch.groupValues[1],
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.P1080.value
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
