package com.sexfilm

import com.kraptor.JsonLdParse
import com.kraptor.PackedJs
import com.kraptor.registerHostExtractors
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/** Pure field-parsing helpers for load(); unit-tested against live-page fixtures. */
object Parse {
    // meta[itemprop=genre] holds only "HD porn movies"; the real Genre row is the /tags/ anchors
    fun tags(doc: Element): List<String> {
        val fromRow = doc.select("ul.flist-col li a[href*=/tags/]").map { it.text().trim() }.filter { it.isNotEmpty() }
        if (fromRow.isNotEmpty()) return fromRow
        return doc.selectFirst("meta[itemprop=genre]")?.attr("content")
            ?.split("\u00a0,\u00a0", ",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    fun actors(doc: Element): List<String> =
        doc.select("ul.flist-col li")
            .firstOrNull { it.selectFirst("span")?.text()?.contains("Casting") == true }
            ?.select("a[href*=/watch/name/]")?.map { it.text().trim() }
            ?.filter { it.isNotEmpty() } ?: emptyList()

    fun year(doc: Element): Int? =
        doc.selectFirst("span.gv a[href*=/watch/year/]")?.text()?.trim()?.toIntOrNull()

    // click-injected iframe srcs; playmogo.com is Cloudflare-gated from the runner
    // (403 "Just a moment") so it is intentionally not matched
    fun embeds(html: String): List<String> =
        Regex("""https://(?:filmcdm\.top|s2\.filmcdn\.top)/e/[A-Za-z0-9_\-]+|https://morencius\.com/embed/[A-Za-z0-9_\-]+""")
            .findAll(html).map { it.value }.distinct().toList()
}

class Sexfilm : MainAPI() {
    override var mainUrl = "https://en.sex-film.biz"
    override var name = "Sexfilm"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/porno-video/" to "Porno Video",
        // these 4 sections 301 from top-level to /movies/<sub>/; use canonical paths
        // so page/N pagination hits the right page instead of redirecting to page 1
        "$mainUrl/movies/hd-porno-movies/" to "HD Porno Movies",
        "$mainUrl/movies/fullhd-porn-movie/" to "FullHD Movies",
        "$mainUrl/movies/porno-parodies/" to "Parodies",
        "$mainUrl/movies/vintage/" to "Vintage",
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
        // meta[itemprop=duration] is ISO-8601 PT8173S; JsonLdParse floors to minutes (repo convention)
        val duration = JsonLdParse.minutes(doc.selectFirst("meta[itemprop=duration]")?.attr("content"))
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = desc
            this.tags = Parse.tags(doc)
            this.actors = Parse.actors(doc).map { ActorData(Actor(it)) }
            this.year = Parse.year(doc)
            this.duration = duration
            this.recommendations = recommendations
        }
    }

    // Dean Edwards packed JS now lives in the shared PackedJs Parse function; pages
    // without one keep the raw html (filmcdn.top signed-m3u8 path is checked first).
    private fun unpackPacked(html: String): String = PackedJs.unpack(html) ?: html

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
        Parse.embeds(html).forEach { addSource(it, callback) }
        return true
    }
}

@CloudstreamPlugin
class SexfilmPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Sexfilm())
        registerHostExtractors()
    }
}
