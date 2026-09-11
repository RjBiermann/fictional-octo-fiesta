package com.rjbiermann

import com.kraptor.registerHostExtractors
import com.kraptor.searchCard
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLEncoder

/** Pure helpers for the /tags/ search flow; unit-tested against live-page fixtures. */
object Parse {
    private const val MAIN_URL = "https://pxp.news"
    // The site's real search is /tags/<query> (2.js rewrites the form submit); ?q= is ignored.
    fun searchUrl(query: String, page: Int): String {
        // URLEncoder uses '+' for spaces; the site's own links use %20 — match that form
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return if (page <= 1) "$MAIN_URL/tags/$q" else "$MAIN_URL/tags/$q?page=$page"
    }

    // A tag page with a single result page carries an empty #pages block; fetching its
    // page 2 falls back to the generic grid (duplicate/unfiltered cards) — stop instead.
    fun searchHasNext(document: Document): Boolean =
        document.selectFirst("#pages a[href*=page]") != null
}

class PornXP : MainAPI() {
    override var mainUrl = "https://pxp.news"
    override var name = "PornXP"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "New Videos",
        "$mainUrl/best/" to "Best Videos",
        "$mainUrl/released/" to "New Releases",
        "$mainUrl/hd/" to "HD"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/?page=$page"
        val document = app.get(url).document
        val videos = document.select(".item_cont").mapNotNull { searchCard(it, ".item_title", hrefSel = "a[href*=videos]", posterSel = ".item_thumb img") }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = videos,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.get(Parse.searchUrl(query, page)).document
        val videos = document.select(".item_cont").mapNotNull { searchCard(it, ".item_title", hrefSel = "a[href*=videos]", posterSel = ".item_thumb img") }
        return newSearchResponseList(videos, hasNext = Parse.searchHasNext(document))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1).items
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = app.get(url).document
// header carries a banner <h1> (backup-domain notice); the video title is the h1 inside .player_details
            val title = document.selectFirst(".player_details h1")?.text()?.trim() ?: return null
            // Poster lives on the <video id="player"> poster attribute (no og:image on these pages)
            val poster = document.selectFirst("#player")?.attr("poster")?.let { fixUrl(it) }
            val description = document.selectFirst("#desc")?.text()?.trim()
            val tags = document.select(".tags a").map { it.text().trim() }
            val uploadDateText = document.selectFirst("#desc")?.text()?.let { text ->
                val uploadMatch = """Uploaded: (\d{4}\.\d{2}\.\d{2})""".toRegex().find(text)
                uploadMatch?.groupValues?.get(1)
            }
            
            // Get recommendations from related videos
            val recommendations = document.select(".item_cont").mapNotNull { searchCard(it, ".item_title", hrefSel = "a[href*=videos]", posterSel = ".item_thumb img") }

            return newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.year = uploadDateText?.split(".")?.get(0)?.toIntOrNull()
            }
        } catch (e: Exception) {
            return null
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(data).document
            // Emit one ExtractorLink per <source> quality (FINDINGS documents 360p/720p/1080p)
            val sources = document.select("#player source")
            for (source in sources) {
                val videoUrl = source.attr("src")
                if (videoUrl.isEmpty()) continue
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixUrl(videoUrl),
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = getQualityFromName(source.attr("title"))
                        this.referer = mainUrl
                    }
                )
            }
            return sources.isNotEmpty()
        } catch (e: Exception) {
            return false
        }
    }
}

@CloudstreamPlugin
class PornXPPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PornXP())
        registerHostExtractors()
    }
}