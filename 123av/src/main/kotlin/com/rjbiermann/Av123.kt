// 123AV (123av.com) — a Laravel + Alpine JAV tube.
//
// S1 (#1653) reversed the episode API: the player `x-data` carries an inline episode array
// whose `url` is an embed page, and `<embed-origin>/stream?id=<hash>` returns the HLS URL.
// S2 (#1654) landed the scaffold and card seam; S3 (#1655) search + the eleven ura maker rows.
// This story (S4, #1656) adds `load()` metadata and `loadLinks()` streams; S5 verifies live.
package com.rjbiermann

import com.kraptor.registerHostExtractors
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

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
     * The video page's exposed metadata. Null on a page with no `h1.watch__title` (the site's
     * Laravel 404 / wall page), so a dead card drops instead of showing an empty entry.
     *
     * Fields the page does not expose stay absent: it has no Japanese title (only the English
     * `h1`), and `LoadResponse` in this framework has no maker/studio field, so the parsed
     * `maker` is not forced into tags or actors. FINDINGS records both facts.
     */
    override suspend fun load(url: String): LoadResponse? {
        val video = Av123Parse.parseVideo(app.get(url).text) ?: return null
        return newMovieLoadResponse(video.title, url, TvType.NSFW, url) {
            posterUrl = video.poster
            plot = video.plot
            tags = video.tags
            duration = video.durationMinutes
            year = video.year
            recommendations = video.recommendations.map { it.toSearchResponse() }
            addActors(video.actors)
        }
    }

    /**
     * Resolve every episode fresh on each call. The S1 shape carries the embed page in the
     * episode `url`; the stream is behind `<embed-origin>/stream?id=<hash>`, and the media host
     * rotates per request, so nothing is cached across calls.
     *
     * Dispatch order: `loadExtractor` first (the shared HostRegistry adapters live there), then
     * the provider-local `/stream` resolver. The embed host is not a registered host as of S1,
     * so a shared adapter would be a new `shared/` row — deliberately not added here (ADR-0002).
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val video = runCatching { Av123Parse.parseVideo(app.get(data).text) }.getOrNull()
            ?: return false
        var emitted = 0
        for (episode in video.episodes) {
            if (addEpisode(episode.url, subtitleCallback, callback)) emitted++
        }
        return emitted > 0
    }

    /** Shared dispatch ladder → provider-local embed resolution; true only on a real emission. */
    private suspend fun addEpisode(
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var dispatched = 0
        val matched = runCatching {
            loadExtractor(episodeUrl, "$mainUrl/", subtitleCallback) { link ->
                dispatched++
                callback(link)
            }
        }.getOrDefault(false)
        if (matched && dispatched > 0) return true
        return runCatching { resolveEmbed(episodeUrl, callback) }
            .onFailure { Log.w(name, "123AV stream failed: $episodeUrl (${it.message})") }
            .getOrDefault(false)
    }

    /**
     * The S1 provider-local extractor: derive the embed origin from the episode URL (never a
     * hardcoded host — it changes), call `/stream?id=<hash>`, then classify the media URL by the
     * response Content-Type. `Referer`/`Origin` are the embed origin, the only pair S1 proved
     * the media CDN accepts; a `123av.com` referer is actively rejected.
     */
    private suspend fun resolveEmbed(
        episodeUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val origin = Av123Parse.embedOrigin(episodeUrl) ?: return false
        val id = Av123Parse.embedId(episodeUrl) ?: return false
        val stream = Av123Parse.parseStream(app.get("$origin/stream?id=$id").text) ?: return false
        val headers = mapOf("Referer" to "$origin/", "Origin" to origin)

        val head = runCatching {
            app.head(stream.url, headers = headers, referer = "$origin/")
        }.getOrNull()
        var type = Av123Parse.streamType(head?.headers?.get("content-type"))
        var master: String? = null
        if (type == ExtractorLinkType.M3U8 || type == null) {
            val probe = runCatching {
                app.get(stream.url, headers = headers, referer = "$origin/")
            }.getOrNull()
            master = probe?.text
            type = Av123Parse.streamType(probe?.headers?.get("content-type")) ?: type
        }
        type ?: return false

        callback(
            newExtractorLink(name, name, stream.url, type) {
                this.referer = "$origin/"
                this.headers = headers
                this.quality = Av123Parse.quality(master)
            },
        )
        return true
    }

    /**
     * Poster headers stay absent: S1 proved the `icdn.123av.me` covers answer 200 with no
     * referer and no UA gate (re-probed this run: 200 `image/jpg`, 37,159 B, bare `curl`).
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
