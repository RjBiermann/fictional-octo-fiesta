package com.allclassic

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AllClassicPorn : MainAPI() {
    override var mainUrl = "https://allclassic.porn"
    override var name = "AllClassicPorn"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val tag = "AllClassicPorn"

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "New Videos",  // pagination appends {page}/ → /page/N/ (FINDINGS)
        "$mainUrl/40s/" to "40s",
        "$mainUrl/50s/" to "50s",
        "$mainUrl/60s/" to "60s",
        "$mainUrl/70s/" to "70s",
        "$mainUrl/80s/" to "80s",
        "$mainUrl/90s/" to "90s",
        "$mainUrl/2000s/" to "2000s",
        "$mainUrl/best/" to "Best",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}$page/" else request.data
        val document = app.get(url, referer = mainUrl).document

        val home = document.select("a.th.item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        return try {
            val href = fixUrlNull(this.attr("href")) ?: return null
            val title = this.selectFirst("div.th-description")?.text()?.trim()
                ?: this.selectFirst("img")?.attr("alt")?.trim()
                ?: return null
            val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            null  // one broken card must not kill the list
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/search/${query.trim().replace(" ", "-")}/" + if (page > 1) "$page/" else ""
        val document = app.get(url, referer = mainUrl).document
        val results = document.select("a.th.item").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(tag, "Load : $url")
        val document = app.get(url, referer = mainUrl).document

        val title = document.selectFirst("meta[property=\"og:title\"]")?.attr("content")?.trim()
            ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
        val description = document.selectFirst("meta[property=\"og:description\"]")?.attr("content")
            ?.replace(Regex("<[^>]+>"), "")?.trim()
        // Note: KVS video pages expose only global nav category links, no per-video tags section (verified) — no tags.
        val duration = document.selectFirst("meta[itemprop=\"duration\"]")?.attr("content")
            ?.let { Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").find(it) }
            ?.let { m -> (m.groupValues[1].toIntOrNull() ?: 0) * 60 + (m.groupValues[2].toIntOrNull() ?: 0) } // minutes, repo convention
            ?.takeIf { it > 0 }
        val recommendations = document
            .select("#list_videos_related_videos_items a.th.item")
            .mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.duration = duration
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(tag, "data = $data")
        val html = app.get(data, referer = mainUrl).text

        // Direct KVS flashvars: video_url + video_url_text pairs; skip ?login upsell "alt" urls.
        val quality = Regex("video_url_text:\\s*'([^']+)'").find(html)?.groupValues?.get(1)
        val videoUrl = Regex("video_url:\\s*'([^']+)'").find(html)?.groupValues?.get(1) ?: return false

        callback(
            newExtractorLink(
                source = name,
                name = name + (quality?.let { " - $it" } ?: ""),
                url = videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = data
            }
        )

        return true
    }
}

@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class AllClassicPornPlugin : com.lagradost.cloudstream3.plugins.Plugin() {
    override fun load() {
        registerMainAPI(AllClassicPorn())
    }
}
