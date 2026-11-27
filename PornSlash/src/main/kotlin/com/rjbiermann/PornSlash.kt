// PornSlash (pornslash.com) — a KVS-engine tube serving full-length movies and a shorts
// feed. S1 (#1643) landed the scaffold + parse seam (PornSlashParse.kt); S2 (#1644) lands the
// home rows and search below. Detail/streams land in S3 (#1645), shorts in S4 (#1646),
// browse filters in S5 (#1647) — so search() carries no filter/sort argument yet and a
// result URL is the plain `/watch/<id>` route S3 will read.
package com.rjbiermann

import com.kraptor.registerHostExtractors
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

class PornSlash : MainAPI() {
    override var mainUrl = PornSlashParse.MAIN
    override var name = "PornSlash"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    // Evidence, not assumption (re-probed 2026-11-27): `/search/suggest?q=milf` answers with the
    // search page itself (`200 text/html`, not JSON) and `/suggest` + `/api/suggest` are 404 —
    // there is no suggestion endpoint to call.
    override val hasQuickSearch = false

    /**
     * `Latest` (50 cards/page) plus the eight curated category rows (25 cards/page), pinned from
     * `/categories/` — see [PornSlashParse.CURATED_CATEGORIES]. Nine rows, under the spec's
     * hard cap of ten.
     */
    override val mainPage = mainPageOf(
        PornSlashParse.VIDEOS to "Latest",
        *PornSlashParse.CURATED_CATEGORIES
            .map { (slug, label) -> PornSlashParse.categoryUrl(slug) to label }
            .toTypedArray(),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageSize =
            if (request.data == PornSlashParse.VIDEOS) PornSlashParse.PAGE_SIZE_VIDEOS
            else PornSlashParse.PAGE_SIZE_SEARCH
        val cards = row(PornSlashParse.pageUrl(request.data, page))
        return newHomePageResponse(
            request,
            cards.map { it.toSearchResponse() },
            hasNext = PornSlashParse.hasNext(cards.size, pageSize),
        )
    }

    /**
     * A curated row the site retires answers 404 (`/search/`) or 500 (`/videos/`, `/cat/`) —
     * one empty row, never a dead home page.
     */
    private suspend fun row(url: String): List<SlashCard> = try {
        PornSlashParse.cards(app.get(url, referer = "$mainUrl/").document)
    } catch (e: Exception) {
        Log.d(name, "row $url: ${e.message}")
        emptyList()
    }

    // Card quality and duration stay out of the search response: this API's SearchResponse
    // quality is a coarse SearchQuality badge, and the exact `1080`/`720` label plus the
    // second-exact duration are what S3's load()/loadLinks() need. Only the poster is shown here.
    private fun SlashCard.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(title, "$mainUrl/watch/$id", TvType.NSFW) {
            posterUrl = poster
        }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val cards = PornSlashParse.cards(
            app.get(PornSlashParse.searchUrl(query, page), referer = "$mainUrl/").document
        )
        return newSearchResponseList(
            cards.map { it.toSearchResponse() },
            hasNext = PornSlashParse.hasNext(cards.size, PornSlashParse.PAGE_SIZE_SEARCH),
        )
    }
}

@CloudstreamPlugin
class PornSlashPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PornSlash())
        registerHostExtractors()
    }
}
