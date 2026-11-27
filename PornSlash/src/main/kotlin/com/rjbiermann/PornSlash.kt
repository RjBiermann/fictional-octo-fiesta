// PornSlash (pornslash.com) — a KVS-engine tube serving full-length movies and a shorts
// feed. S1 (#1643) landed the scaffold + parse seam (PornSlashParse.kt); S2 (#1644) the
// home rows and search; S3 (#1645) the detail page and its streams; S4 (#1646) the shorts
// surface — one `/watch/<id>` route covers long-form and shorts items, and the play path is
// the single derived mp4 (no tunnel base, no per-playback round-trip). Filters land in S5
// (#1647), so search() carries no filter/sort argument and a result URL is the plain
// `/watch/<id>` route.
package com.rjbiermann

import com.kraptor.registerHostExtractors
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
     * `Latest` (50 cards/page), the shorts feed (10 cards/page) and the eight curated category
     * rows (25 cards/page), pinned from `/categories/` — see
     * [PornSlashParse.CURATED_CATEGORIES]. Ten rows, the spec's hard cap.
     */
    override val mainPage = mainPageOf(
        PornSlashParse.VIDEOS to "Latest",
        PornSlashParse.SHORTS to "Shorts",
        *PornSlashParse.CURATED_CATEGORIES
            .map { (slug, label) -> PornSlashParse.categoryUrl(slug) to label }
            .toTypedArray(),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = PornSlashParse.pageUrl(request.data, page)
        val (cards, pageSize) = when (request.data) {
            PornSlashParse.SHORTS -> shortsRow(url) to PornSlashParse.PAGE_SIZE_SHORTS
            PornSlashParse.VIDEOS -> row(url) to PornSlashParse.PAGE_SIZE_VIDEOS
            else -> row(url) to PornSlashParse.PAGE_SIZE_SEARCH
        }
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

    /**
     * The shorts row is the same contract over a JSON body: cards from the feed's own fields,
     * each linking to the `/watch/<item_id>` route the long-form row uses. A feed that stops
     * answering is one empty row, not a dead home page.
     */
    private suspend fun shortsRow(url: String): List<SlashCard> = try {
        PornSlashParse.shortsCards(app.get(url, referer = "$mainUrl/").text)
    } catch (e: Exception) {
        Log.d(name, "shorts $url: ${e.message}")
        emptyList()
    }

    // Card quality and duration stay out of the search response: this API's SearchResponse
    // quality is a coarse SearchQuality badge, and the exact `1080`/`720` label plus the
    // second-exact duration are what S3's load()/loadLinks() need. Only the poster is shown here.
    private fun SlashCard.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(title, PornSlashParse.watchUrl(id), TvType.NSFW) {
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

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, referer = "$mainUrl/")
        val detail = PornSlashParse.detail(response.document) ?: return null
        return newMovieLoadResponse(detail.title, url, TvType.NSFW, url) {
            this.posterUrl = detail.poster
            this.plot = detail.description
            this.tags = detail.tags
            this.duration = detail.duration
            this.recommendations = detail.recommendations.map { it.toSearchResponse() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, referer = "$mainUrl/")
        val detail = PornSlashParse.detail(response.document) ?: return false
        // The one play path: the mp4 the thumbnail's `/t/<encid>.jpeg` path mirrors. The
        // per-video *.pages.dev CDN currently serves `access-control-allow-origin: *` and plays
        // with no Referer, so the referer is cheap insurance rather than a requirement. Long-form
        // and shorts items resolve through this same route, so a shorts item is no special case
        // and playback needs no `/get/shorts` round-trip.
        val mp4 = detail.mp4 ?: return false
        callback(
            newExtractorLink(name, name, mp4, type = ExtractorLinkType.VIDEO) {
                this.quality = getQualityFromName(detail.quality)
                this.referer = mainUrl
            }
        )
        return true
    }
}

@CloudstreamPlugin
class PornSlashPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PornSlash())
        registerHostExtractors()
    }
}
