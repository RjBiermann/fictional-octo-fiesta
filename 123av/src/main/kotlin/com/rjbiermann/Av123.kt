// 123AV (123av.com) — a Laravel + Alpine JAV tube.
//
// S1 (#1653) was evidence only: both gate studios (1pondo, heyzo) resolve to plain HLS behind
// an embed-host referer, so the site is buildable. S2 (#1654) landed the scaffold, the
// listing-card Parse seam (Av123Parse.kt) and the two non-maker home rows. This story (S3,
// #1655) adds search and the eleven ura maker rows — the reason #1423 exists. S4 owns the
// video page, S5 the streams; `load()` is therefore still the base implementation.
package com.rjbiermann

import com.kraptor.registerHostExtractors
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

class Av123 : MainAPI() {
    override var mainUrl = Av123Parse.MAIN
    override var name = "123AV"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    // S1 evidence: the only search surface is the GET form `/en/search?keyword=`; the served
    // pages and the client bundle carry no suggest/autocomplete route, so there is nothing to
    // quick-search against (FINDINGS §Quick search).
    override val hasQuickSearch = false

    /**
     * `New Releases` (the `/en` row, listed at `/en/new`), `Hot` (most viewed), and the eleven
     * ura maker rows (`Av123Parse.homeRows()`, tested for order). Every row is the site's own
     * paginated listing, so `getMainPage` needs no row-specific path logic. The homepage `/en`
     * itself does not paginate, so its rows are not addressable as home rows; each maker row
     * pages over `/en/makers/<slug>?page=N`.
     */
    // `mainPageOf` is vararg-only in cloudstream3 — there is no collection overload — so the list
    // is spread once at plugin init. Narrow suppression, not a per-request allocation.
    @Suppress("SpreadOperator")
    override val mainPage = mainPageOf(*Av123Parse.homeRows().toTypedArray())

    /**
     * `app.get` with no extra headers: S1's matrix showed the site's own pages need no cookie,
     * no referer and no age unlock (12/12 probes 200, three rounds).
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val listing = listing(page) { Av123Parse.pageUrl(request.data, it) }
        return newHomePageResponse(
            request,
            listing.cards.map { it.toSearchResponse() },
            hasNext = listing.hasNext,
        )
    }

    /**
     * `/en/search?keyword=<q>` — the site's own GET form, paged by `&page=N` (`hasNext` reads
     * the pager). Search is offset-paged like the rows, so it goes through the same
     * boundary-overlap dedupe: the pipeline duplicate bar is a page-1/page-2 overlap check, and
     * a card served twice across a shifted boundary would fail it.
     */
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val listing = listing(page) { Av123Parse.searchUrl(query, it) }
        return newSearchResponseList(listing.cards.map { it.toSearchResponse() }, hasNext = listing.hasNext)
    }

    /** One fetched listing page and its pager signal. */
    private data class Listing(val cards: List<Av123Card>, val hasNext: Boolean)

    /**
     * One page of a listing, boundary overlap removed. Page N is filtered against page N-1's
     * cards fetched in-flight, never against remembered state: a per-surface seen-set on the
     * provider made a repeated page request return an empty page and let two concurrent requests
     * mutate one set. Nothing is stored, so a repeat returns the same cards.
     */
    private suspend fun listing(page: Int, url: (Int) -> String): Listing {
        val document = app.get(url(page)).document
        val previous = if (page <= 1) emptyList() else Av123Parse.cards(app.get(url(page - 1)).document)
        return Listing(
            cards = Av123Parse.fresh(Av123Parse.cards(document), previous),
            hasNext = Av123Parse.hasNext(document),
        )
    }

    /**
     * Poster headers stay absent: S1 proved the `icdn.123av.me` covers answer 200 with no
     * referer and no UA gate (re-probed this run: 200 `image/jpg`, 37,159 B, bare `curl`). The
     * duration parsed onto the card is not shown here — `SearchResponse` has no such field and
     * it belongs to S4's `load()`.
     */
    private fun Av123Card.toSearchResponse(): SearchResponse =
        newMovieSearchResponse(title, url, TvType.NSFW) {
            posterUrl = poster
        }
}

@CloudstreamPlugin
class Av123Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Av123())
        registerHostExtractors()
    }
}
