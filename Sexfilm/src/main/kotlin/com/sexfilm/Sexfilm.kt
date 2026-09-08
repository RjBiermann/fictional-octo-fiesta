package com.sexfilm

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Sexfilm : MainAPI() {
    override var mainUrl = "https://en.sex-film.biz"
    override var name = "Sexfilm"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/porno-video/" to "Porno Video",
        "$mainUrl/hd-porno-movies/" to "HD Porno Movies",
        "$mainUrl/fullhd-porn-movie/" to "FullHD Movies",
        "$mainUrl/porno-parodies/" to "Parodies",
        "$mainUrl/vintagexxx/" to "Vintage",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url).document
        val items = doc.select("div.short").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a.short-poster[href], a.th-title[href]") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val title = this.selectFirst("a.th-title")?.text()
            ?: a.selectFirst("img")?.attr("alt")
            ?: return null
        val poster = fixUrlNull(a.selectFirst("img")?.attr("data-src")
            ?: a.selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (page > 1) return newSearchResponseList(emptyList(), false)
        // raw spaces in the query break the request (HTTP 000); encode like Film1k/Cat3Film
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/index.php?do=search&subaction=search&story=$q").document
        val results = doc.select("div.short").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, false)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1#s-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: url.substringAfterLast('/').substringBefore(".html")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val desc = doc.selectFirst("div#s-desc")?.text()?.trim()
        val recommendations = doc.select("div.sect-c div.short").mapNotNull { it.toSearchResult() }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = desc
            this.recommendations = recommendations
        }
    }

    // Dean Edwards packed JS: eval(function(p,a,c,k,e,d){...}('payload',a,c,'keys'.split('|')))
    private fun unpackPacked(html: String): String {
        val m = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(html) ?: return html
        val payload = m.groupValues[1].replace("\\'", "'")
        val radix = m.groupValues[2].toIntOrNull() ?: 36
        val keys = m.groupValues[4].split('|')
        val map = HashMap<String, String>()
        keys.forEachIndexed { i, v -> if (v.isNotEmpty()) map[i.toString(radix)] = v }
        return Regex("""\b[a-z0-9]+\b""", RegexOption.IGNORE_CASE)
            .replace(payload) { mr -> map[mr.value] ?: mr.value }
    }

    private suspend fun addSource(embedUrl: String, callback: (ExtractorLink) -> Unit) {
        runCatching {
            val html = app.get(embedUrl, referer = "$mainUrl/").text
            // filmcdn.top variant: signed m3u8 sits directly in the page source
            Regex("""https://[a-z0-9.\-]*cfglobalcdn\.com[^'"\s]{20,}?\.m3u8""").find(html)?.let {
                callback(
                    newExtractorLink(
                        name, name, it.value,
                        type = ExtractorLinkType.M3U8
                    ) { this.referer = "$mainUrl/" }
                )
                return
            }
            // filmcdm.top variant: unpack the JW player config
            val unpacked = unpackPacked(html)
            Regex(""""hls\d":"([^"]*master\.m3u8[^"]*)"""").findAll(unpacked).forEach { m ->
                val link = m.groupValues[1]
                val full = if (link.startsWith("http")) link
                    else "https://" + embedUrl.substringAfter("https://").substringBefore('/') + link
                callback(
                    newExtractorLink(
                        name, name, full,
                        type = ExtractorLinkType.M3U8
                    ) { this.referer = "$mainUrl/" }
                )
            }
        }.onFailure { Log.w(name, "embed failed: $embedUrl") }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text
        // embeds are click-injected by inline JS: s2.src = "https://host/e/key"
        Regex("""https://(?:filmcdm\.top|s2\.filmcdn\.top)/e/[A-Za-z0-9_\-]+""")
            .findAll(html).map { it.value }.distinct().forEach { addSource(it, callback) }
        return true
    }
}

@CloudstreamPlugin
class SexfilmPlugin : Plugin() {
    override fun load() {
        registerMainAPI(Sexfilm())
    }
}
