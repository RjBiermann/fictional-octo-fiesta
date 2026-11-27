// 123AV (123av.com) — a Laravel + Alpine JAV tube.
//
// S1 (#1653) was evidence only: both gate studios (1pondo, heyzo) resolve to plain HLS behind
// an embed-host referer, so the site is buildable. This story (S2, #1654) lands the scaffold,
// the listing-card Parse seam (Av123Parse.kt) and the two non-maker home rows. S3 owns maker
// rows + search, S4 the video page, S5 the streams — so `search()` is deliberately not
// implemented here (the base `MainAPI.search` yields nothing) and no row is stubbed.
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

    // S1 evidence: the only search surface is the GET form `/en/search?keyword=`; the client
    // bundle carries no suggest/autocomplete route, so there is nothing to quick-search against.
    override val hasQuickSearch = false

    /**
     * The two non-maker rows, each the site's own paginated listing behind its home row's
     * "See all": `New Releases` (the `/en` row, listed at `/en/new`) and `Hot` (most viewed).
     * The homepage `/en` itself does not paginate, so its rows are not addressable as home rows.
     */
    override val mainPage = mainPageOf(
        Av123Parse.NEW to "New Releases",
        Av123Parse.HOT to "Hot",
    )

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
