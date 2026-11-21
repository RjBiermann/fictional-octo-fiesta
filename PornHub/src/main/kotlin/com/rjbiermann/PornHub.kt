package com.rjbiermann

import com.kraptor.registerHostExtractors
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Pure parsing for the /video listing cards (TDD-first, ADR-0005) — fixtures in
 * `src/test/resources/`, every selector transcribed in
 * `docs/archive/providers/PornHub/FINDINGS.md`.
 */
object Parse {
    const val BASE = "https://www.pornhub.com"

    /**
     * The listing block. Scoping to `ul#videoCategory` is load-bearing: the header's
     * "Hottest" dropdown renders the same `li.pcVideoListItem` cards on every page, so a
     * bare `li.pcVideoListItem` would return those duplicates on every row and page.
     */
    private const val LISTING = "ul#videoCategory li.pcVideoListItem"

    /** The card link; ad slots (`li.sniperModeEngaged`) carry no such anchor and are dropped. */
    private const val LINK = "a[href^=\"/view_video.php?viewkey=\"]"

    /** One listing card. [durationMinutes] / [views] are the card's own metadata (FINDINGS). */
    data class VideoCard(
        val title: String,
        val url: String,
        val poster: String?,
        val durationMinutes: Int?,
        val views: String?,
    )

    /** Parse function: listing HTML → cards. Broken cards are dropped, never fail the list. */
    fun cards(html: String): List<VideoCard> =
        Jsoup.parse(html, BASE).select(LISTING).mapNotNull { card(it) }

    /** Row URL for [page]: page 1 is the row's own URL, deeper pages append `page`. */
    fun pageUrl(base: String, page: Int): String =
        if (page <= 1) base else "$base${if (base.contains("?")) "&" else "?"}page=$page"

    /** Site clock ("9:25", "1:02:03") → minutes. */
    fun clockMinutes(text: String?): Int? {
        val match = text?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { Regex("^(?:(\\d+):)?(\\d+):(\\d+)$").find(it) } ?: return null
        val (hours, minutes) = match.destructured
        return (hours.toIntOrNull() ?: 0) * 60 + (minutes.toIntOrNull() ?: return null)
    }

    private fun card(li: Element): VideoCard? = try {
        val link = li.selectFirst(LINK) ?: return null
        val title = li.selectFirst("span.title a")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: link.attr("title").trim().takeIf { it.isNotEmpty() } ?: return null
        VideoCard(
            title = title,
            url = BASE + link.attr("href"),
            poster = poster(li),
            durationMinutes = clockMinutes(li.selectFirst("var.duration")?.text()),
            views = li.selectFirst("span.views var")?.text()?.trim()?.takeIf { it.isNotEmpty() },
        )
    } catch (e: Exception) {
        null
    }

    // The thumb <img> is lazyload-heavy: data-image carries the same signed still as src,
    // and the theme's other lazyload attrs are probed before it. Placeholder `data:` URIs
    // are skipped, never emitted as a poster.
    private fun poster(li: Element): String? {
        val img = li.selectFirst("img") ?: return null
        for (attr in listOf("data-image", "data-src", "data-original", "src")) {
            val value = img.attr(attr).trim()
            if (value.isEmpty() || value.startsWith("data:")) continue
            return if (value.startsWith("//")) "https:$value" else value
        }
        return null
    }
}

/**
 * Parse seam: listing HTML → [SearchResponse]s, no HTTP. The [MainAPI] receiver is only what
 * the repo's emission helper needs (`newMovieSearchResponse` stamps the provider `apiName`).
 */
fun MainAPI.parseVideoCards(html: String): List<SearchResponse> =
    Parse.cards(html).mapNotNull { card ->
        try {
            newMovieSearchResponse(card.title, card.url, TvType.NSFW) {
                this.posterUrl = card.poster
            }
        } catch (e: Exception) {
            null
        }
    }

class PornHub : MainAPI() {
    override var mainUrl = Parse.BASE
    override var name = "PornHub"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    // FINDINGS §Homepage: all four rows are the /video listing, `o=` selects the sort, and
    // the labels are the site's own nav labels (`o=cm` is Newest — "sort_newest" in the page).
    override val mainPage = mainPageOf(
        "$mainUrl/video" to "Home",
        "$mainUrl/video?o=mv" to "Most Viewed",
        "$mainUrl/video?o=tr" to "Top Rated",
        "$mainUrl/video?o=cm" to "Newest",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val videos = parseVideoCards(app.get(Parse.pageUrl(request.data, page)).text)
        return newHomePageResponse(
            HomePageList(name = request.name, list = videos, isHorizontalImages = false),
            hasNext = true, // FINDINGS §Pagination (home): every page through the last one has a next
        )
    }

    // S2 (#1408) ships the real search; the stub keeps the app off MainAPI's NotImplementedError.
    override suspend fun search(query: String, page: Int): SearchResponseList =
        newSearchResponseList(emptyList(), hasNext = false)

    // S3 (#1409) ships metadata + HLS links; a null load drops the card instead of throwing.
    override suspend fun load(url: String): LoadResponse? = null

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = false
}

@CloudstreamPlugin
class PornHubPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PornHub())
        registerHostExtractors()
    }
}
