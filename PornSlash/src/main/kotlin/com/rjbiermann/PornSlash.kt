// PornSlash (pornslash.com) — a KVS-engine tube serving full-length movies and a shorts
// feed. S1 (#1643) landed the scaffold + parse seam (PornSlashParse.kt); S2 (#1644) the
// home rows and search; S3 (#1645) the detail page and its streams; S4 (#1646) the shorts
// surface — one `/watch/<id>` route covers long-form and shorts items. S5 (#1647) adds the model
// browse surface in `search()`: the site's `/suggest/` directory maps a query that names a model
// exactly onto its `/pornstar/<slug>/` filmography. The story's `getFilter()` bullet has no
// framework member at this pin (`MainAPI` has no `getFilter`, and upstream's `GenreSelector`
// surface is still commented out), so no provider filter UI exists to wire it to. S6 (#1648)
// closed out the play path: the `og:image` mirror S3/S4 shipped as the stream is the cards'
// 10-second hover preview, so the full-length HLS master the page's own player loads is now the
// primary source (decoded per load — the tunnel rotates) and the preview stays as last resort.
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

    /** Session-scoped search-surface decisions — see [SearchSurface]. */
    private val searchSurfaces = SearchSurface()

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
        val (cards, hasNext) = when (request.data) {
            // The shorts feed pages off its raw item count: a dropped malformed item must not
            // make a full page look partial and hide the pages after it.
            PornSlashParse.SHORTS -> shortsRow(url).let {
                it.cards to PornSlashParse.hasNext(it.rawCount, PornSlashParse.PAGE_SIZE_SHORTS)
            }
            PornSlashParse.VIDEOS -> row(url).let {
                it to PornSlashParse.hasNext(it.size, PornSlashParse.PAGE_SIZE_VIDEOS)
            }
            else -> row(url).let {
                it to PornSlashParse.hasNext(it.size, PornSlashParse.PAGE_SIZE_SEARCH)
            }
        }
        return newHomePageResponse(
            request,
            cards.map { it.toSearchResponse() },
            hasNext = hasNext,
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
     * each linking to the `/watch/<item_id>` route the long-form row uses. Pagination comes
     * from the feed's raw item count, so one malformed item on a full page still advances. A
     * feed that stops answering is one empty row, not a dead home page.
     */
    private suspend fun shortsRow(url: String): PornSlashParse.ShortsPage = try {
        PornSlashParse.shortsPage(app.get(url, referer = "$mainUrl/").text)
    } catch (e: Exception) {
        Log.d(name, "shorts $url: ${e.message}")
        PornSlashParse.ShortsPage(emptyList(), 0)
    }

    // Card quality and duration stay out of the search response: this API's SearchResponse
    // quality is a coarse SearchQuality badge, and the exact `1080`/`720` label plus the
    // second-exact duration are what S3's load()/loadLinks() need. Only the poster is shown here.
    private fun SlashCard.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(title, PornSlashParse.watchUrl(id), TvType.NSFW) {
            posterUrl = poster
        }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // A query that names a model exactly browses that model's own filmography (S5 #1647):
        // `/suggest/` is the site's directory lookup, so the `/pornstar/<slug>` route needs no
        // five-page index scrape and no home-row slot (S2's home is at the ten-row cap). Every
        // other query — including a model-name prefix — stays the ordinary keyword search. The
        // decision is made once per query, not once per page (see [modelSlug]).
        val slug = modelSlug(query)
        val url = if (slug != null) {
            PornSlashParse.pageUrl(PornSlashParse.pornstarUrl(slug), page)
        } else {
            PornSlashParse.searchUrl(query, page)
        }
        val cards = PornSlashParse.cards(app.get(url, referer = "$mainUrl/").document)
        return newSearchResponseList(
            cards.map { it.toSearchResponse() },
            hasNext = PornSlashParse.hasNext(cards.size, PornSlashParse.PAGE_SIZE_SEARCH),
        )
    }

    /**
     * The model `query` browses, or null for the keyword surface. Decided once per query and
     * remembered (see [SearchSurface]): `search()` is handed nothing but the query and the page, so
     * re-deciding on page 2 would let a `/suggest/` hiccup swap page 2 of a model filmography for
     * page 2 of the keyword result set — skipped and unrelated cards.
     */
    private suspend fun modelSlug(query: String): String? =
        searchSurfaces.surface(query) {
            PornSlashParse.suggestUrl(query)
                ?.let { PornSlashParse.modelSlug(query, suggest(it)) }
        }

    /**
     * A directory lookup that stops answering is "no model match", never a dead search.
     */
    private suspend fun suggest(url: String): String? = try {
        app.get(url, referer = "$mainUrl/").text
    } catch (e: Exception) {
        Log.d(name, "suggest $url: ${e.message}")
        null
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
        // The play path is the page's own player: `<tunnel>/master/<encid>`, the HLS master the
        // site loads (144p…1080p, the site's real ladder — nothing is labelled or invented here),
        // decoded per load because the `*.trycloudflare.com` tunnel rotates between loads.
        // The derived `/p/<encid>.mp4` is the cards' 10-second hover preview, not the movie; it
        // is emitted only when a page carries no player base at all, so a degraded page still
        // yields a source instead of a failed load.
        val hls = detail.hls
        if (hls != null) {
            callback(
                newExtractorLink(name, name, hls, type = ExtractorLinkType.M3U8) {
                    this.referer = mainUrl
                }
            )
            return true
        }
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
