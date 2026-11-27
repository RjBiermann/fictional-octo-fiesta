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
import java.util.concurrent.ConcurrentHashMap

class Av123 : MainAPI() {
    override var mainUrl = Av123Parse.MAIN
    override var name = "123AV"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true

    /**
     * Cross-page seen-set, per row. The listings page by offset over a growing list, so a
     * boundary card can be served on two pages (S1 measured 1/12 on `/en/new`); page 1
     * re-seeds the set so a refresh re-serves the row.
     */
    private val seen = ConcurrentHashMap<String, MutableSet<String>>()

    // S1 evidence: the only search surface is the GET form `/en/search?keyword=`; the served
    // pages and the client bundle carry no suggest/autocomplete route, so there is nothing to
    // quick-search against (FINDINGS §Quick search).
    override val hasQuickSearch = false

    /**
     * Two site listings (see below) then one row per ura studio: the eleven `/en/makers/<slug>`
     * pages from #1423, each carrying genuine uncensored releases rather than leaks. Every row
     * is the site's own paginated listing, so `getMainPage` needs no row-specific path logic.
     */
    private val rows: List<Pair<String, String>> =
        listOf(
            Av123Parse.NEW to "New Releases",
            Av123Parse.HOT to "Hot",
        ) + Av123Parse.MAKER_ROWS.map { Av123Parse.makerUrl(it.slug, 1) to it.label }

    /**
     * `New Releases` (the `/en` row, listed at `/en/new`), `Hot` (most viewed), and the eleven
     * ura maker rows. The homepage `/en` itself does not paginate, so its rows are not
     * addressable as home rows; each maker row pages over `/en/makers/<slug>?page=N`.
     */
    override val mainPage = mainPageOf(*rows.toTypedArray())

    /**
     * `app.get` with no extra headers: S1's matrix showed the site's own pages need no cookie,
     * no referer and no age unlock (12/12 probes 200, three rounds).
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(Av123Parse.pageUrl(request.data, page)).document
        val seenKeys = seen.getOrPut(request.data) { HashSet() }
        if (page <= 1) seenKeys.clear()
        val cards = Av123Parse.fresh(Av123Parse.cards(document), seenKeys)
        return newHomePageResponse(
            request,
            cards.map { it.toSearchResponse() },
            hasNext = Av123Parse.hasNext(document),
        )
    }

    /**
     * `/en/search?keyword=<q>` — the site's own GET form, paged by `&page=N` (`hasNext` reads
     * the pager). Search is offset-paged like the rows, so it shares the same cross-page dedupe:
     * the pipeline duplicate bar is a page-1/page-2 overlap check, and a card served twice
     * across a shifted boundary would fail it.
     */
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val seenKeys = seen.getOrPut("search:$query") { HashSet() }
        if (page <= 1) seenKeys.clear()
        val document = app.get(Av123Parse.searchUrl(query, page)).document
        val cards = Av123Parse.fresh(Av123Parse.search(document), seenKeys)
        return newSearchResponseList(cards.map { it.toSearchResponse() }, hasNext = Av123Parse.hasNext(document))
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
