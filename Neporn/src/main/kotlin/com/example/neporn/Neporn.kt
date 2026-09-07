package com.example.neporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Neporn : MainAPI() {
    override var mainUrl = "https://neporn.com"
    override var name = "Neporn"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updates/" to "Latest Updates",
        "$mainUrl/most-popular/" to "Most Popular",
        "$mainUrl/top-rated/" to "Top Rated",
        "$mainUrl/categories/amateur/" to "Amateur",
        "$mainUrl/categories/anal/" to "Anal",
        "$mainUrl/categories/asian/" to "Asian",
        "$mainUrl/categories/big-ass/" to "Big Ass",
        "$mainUrl/categories/big-boobs/" to "Big Boobs",
        "$mainUrl/categories/blonde/" to "Blonde",
        "$mainUrl/categories/creampie/" to "Creampie",
        "$mainUrl/categories/interracial/" to "Interracial",
        "$mainUrl/categories/latina/" to "Latina",
        "$mainUrl/categories/milf/" to "MILF",
        "$mainUrl/categories/teen/" to "Teen (18+)",
    )

    private fun Element.toResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("strong.title")?.text()?.trim()
            ?: this.selectFirst("a")?.attr("title")?.trim() ?: return null
        val poster = fixUrlNull(
            this.selectFirst("img.thumb")?.attr("src")
                ?: this.selectFirst("img.thumb")?.attr("data-webp")
        )
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}$page/"
        val doc = app.get(url).document
        // first list-videos block on home is "watching right now", second is most recent — just take all items
        val items = doc.select("div.list-videos div.item").mapNotNull { it.toResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // KVS async block pagination; /search/{q}/2/ returns 404, raw spaces break the request
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val url = if (page <= 1) "$mainUrl/search/$q/"
        else "$mainUrl/search/?q=$q&mode=async&function=get_block&block_id=list_videos_videos_list_search_result&from_videos=$page"
        val doc = app.get(url).document
        val items = doc.select("div.list-videos div.item").mapNotNull { it.toResult() }.distinctBy { it.url }
        // KVS renders a "last" page link unless the current page is the final one
        val hasNext = doc.selectFirst("li.page.last:not(.page-current)") != null
        return newSearchResponseList(items, hasNext && items.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()
            ?.removePrefix("Video: ")?.trim()
            ?: throw ErrorLoadingException("No title at $url")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val duration = doc.selectFirst("div.info span:contains(Duration:) em")?.text()?.trim()
        val tags = doc.select("div.info-content a[href*=/tags/]").map { it.text().trim() }
        val categories = doc.select("div.info-content a[href*=/categories/]").map { it.text().trim() }
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val actors = doc.select("div.info-content a[href*=/models/]").map { it.text().trim() }.filter { it.isNotBlank() }
        // schema.org ld+json uploadDate, e.g. "2026-03-18T17:35:00Z"
        val year = Regex(""""uploadDate"\s*:\s*"(\d{4})""""").find(doc.select("script[type=application/ld+json]").html())?.groupValues?.get(1)?.toIntOrNull()
        val recommendations = doc.select("div.related-videos div.item, div.list-videos div.item")
            .mapNotNull { it.toResult() }.distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.tags = (categories + tags).filter { it.isNotBlank() }.distinct()
            this.plot = plot
            this.actors = actors.map { ActorData(Actor(it)) }
            this.year = year
            addDuration(duration?.trim())
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text
        // KVS flashvars: single source, e.g. video_url: 'https://neporn.com/get_file/.../41865_720p.mp4/?v-acctoken=...'
        val sources = listOf("video_url", "video_alt_url", "video_alt_url2").mapNotNull { key ->
            Regex("""$key\s*:\s*'([^']+)'""").find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        }.distinct()
        for (src in sources) {
            callback(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = src,
                    referer = mainUrl,
                    quality = if (src.contains("_720p")) Qualities.P720.value else Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
        return sources.isNotEmpty()
    }
}

@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class NepornPlugin : com.lagradost.cloudstream3.plugins.Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(Neporn())
    }
}
