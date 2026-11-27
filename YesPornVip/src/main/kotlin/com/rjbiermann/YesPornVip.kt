package com.rjbiermann

import com.kraptor.registerHostExtractors
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure Parse functions for yesporn.vip (glossary: Parse function, ADR-0005).
 *
 * The site is a KVS ("Kernel Video Sharing") tube: every listing card — video, channel,
 * model or category — is the same `div.thumb` container, so the *link path* is what
 * decides what a card is, not the container. [cards] keeps `/video/<id>/<slug>/` cards
 * and [browseCards] keeps the `/channels|models|categories/<slug>/` ones; the home page
 * renders both kinds side by side, which is why the split has to be by href.
 *
 * The functions return [Card] rather than `SearchResponse`: the framework's only
 * non-deprecated way to build a search response is `MainAPI.newMovieSearchResponse`
 * (the `MovieSearchResponse` constructors are `@Deprecated(ERROR)`), so emission stays in
 * the MainAPI adapter — the same seam `shared/SearchCard` uses.
 *
 * No `app.get` lives here: HTTP stays in the MainAPI adapter and is covered by pipeline
 * Verification, never by unit tests (ADR-0005). Fixtures are live captures (home/listing rows
 * 2026-11-26; search + browse grids 2026-11-27), mobile Chrome UA, under
 * `src/test/resources/`.
 */
object YesPornVipParse {

    const val BASE = "https://yesporn.vip"

    /** One listing/browse card, in site terms — no CloudStream types. */
    data class Card(
        /** `/video/<id>/` identity; null for a taxonomy entry (channel/model/category). */
        val videoId: String?,
        val title: String,
        val href: String,
        val poster: String?,
    )

    /** `/video/<id>/` — the stable video identity; the slug after the id is canonicalised
     *  server-side (a 301 rewrites `…-ebk44f/` to `…-2av635/`, and two surfaces can serve
     *  two different slugs for the same id), so the id alone is the card identity. */
    private val VIDEO_PATH = Regex("""/video/(\d+)/""")

    /** Poster attributes, best first. KVS rewrites `src` to a 1px `data:` gif on
     *  lazy-loaded cards and parks the real URL in `data-original` (channels index) or
     *  `data-src`; `data-webp` is an alternate-format variant, so it only fills in when no
     *  canonical image survived. */
    private val POSTER_ATTRS = listOf("data-original", "data-src", "src", "data-webp")

    /** A taxonomy detail page — `/channels|models|categories/<slug>/[<page>/]` — which is a
     *  video grid, not a video. [page] is 1 when the URL carries no page segment. */
    data class Taxonomy(val section: String, val slug: String, val page: Int)

    /** Cards per listing page on every surface here (search, browse grids, home rows). The
     *  fixtures pin it: 20 cards on both captured search pages and both browse-grid pages. */
    const val PAGE_SIZE = 20

    /** `…/<section>/<slug>/` with an optional numeric page segment; anchored so a video URL
     *  (`/video/<id>/…`) or a bare index (`/channels/`) never parses as a taxonomy page. */
    private val TAXONOMY_PATH =
        Regex("""(?:^|://[^/]+)/(channels|models|categories)/([^/?#]+)(?:/(\d+))?/?(?:[?#].*)?${'$'}""")

    /** Video id of [href], or null when the href is not a `/video/<id>/…` link. */
    fun videoId(href: String): String? = VIDEO_PATH.find(href)?.groupValues?.get(1)

    /** Relative → absolute against [BASE]. The live pages serve absolute hrefs, but a theme
     *  switch to root-relative ones must not silently empty every row. */
    fun absolute(href: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> BASE + href
        else -> "$BASE/$href"
    }

    /** Poster URL of a card: first non-`data:` value in [POSTER_ATTRS], else null. */
    fun poster(card: Element): String? = card.selectFirst("img")?.let { img ->
        POSTER_ATTRS.firstNotNullOfOrNull { attr ->
            img.attr(attr).trim().takeIf { it.isNotEmpty() && !it.startsWith("data:") }
        }
    }?.let { absolute(it) }

    /**
     * Pure Parse function: a listing page → its video cards.
     *
     * Only `/video/<id>/…` links become cards, so the home page's taxonomy strips and its
     * ad thumb (which links off-site) never leak into a video row. Deduped by
     * [videoId] — a card served twice (KVS repeats the newest post in a strip, and the
     * `-ebk44f` / `-2av635` slug pair is the same video) yields one entry.
     */
    fun cards(document: Document): List<Card> =
        document.select("div.thumb")
            .mapNotNull { videoCard(it) }
            .distinctBy { it.videoId }

    /**
     * Pure Parse function: a taxonomy index (`/categories/`, `/channels/`, `/models/`) →
     * one card per entry, pointing at the entry's own page. [section] is the path prefix
     * an entry href must live under (`/categories/<slug>/`), which is what separates an
     * entry from the index link itself (`/categories/`) and from any ad/off-site thumb.
     */
    fun browseCards(document: Document, section: String): List<Card> {
        // `…/<section>/<slug>/` — the slug segment is required, so the bare index href
        // (`/categories/`, `/categories/`, …) is never emitted as an entry.
        val entry = Regex("""${Regex.escape(section)}[^/?#]+/?(?:[?#].*)?${'$'}""")
        return document.select("div.thumb")
            .mapNotNull { card ->
                val anchor = card.select("a[href]").firstOrNull { entry.containsMatchIn(it.attr("href")) }
                    ?: return@mapNotNull null
                val href = absolute(anchor.attr("href").trim())
                val title = cardTitle(card, anchor) ?: return@mapNotNull null
                Card(videoId = null, title = title, href = href, poster = poster(card))
            }
            .distinctBy { it.href }
    }

    /**
     * Pure Parse function: the cards of one page that have not been served before, seeding
     * [seen] with what this page contributes. Keyed by [Card.videoId] where there is one
     * (slug-canonicalisation safe), else [Card.href] (taxonomy entries have no id).
     *
     * The caller clears [seen] on page 1, so a refresh re-serves the row; an empty result
     * means the row is exhausted (`hasNext = false`). Pulled out of the HTTP adapter so the
     * cross-page filter is fixture-testable without a request (ADR-0005).
     */
    fun fresh(cards: List<Card>, seen: MutableSet<String>): List<Card> =
        cards.filter { seen.add(it.videoId ?: it.href) }

    /**
     * Pure Parse function: the search URL of [query] on [page].
     *
     * Search paging is a **query parameter** — path paging (`/search/<q>/2/`) answers 404
     * (verified 2026-11-27). Page 1 carries no parameter; later pages add the KVS offset
     * `?from_videos=<n>`, which starts one past the last card of the previous page (page 2 →
     * `21`, page 3 → `41` with [PAGE_SIZE] cards per page — both verified disjoint live).
     */
    fun searchUrl(query: String, page: Int): String {
        // The query is a path segment, so spaces must be escaped (`/search/big%20tits/` —
        // verified 200); `URLEncoder` form-encodes spaces as `+`, which is wrong in a path.
        val slug = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return if (page <= 1) {
            "$BASE/search/$slug/"
        } else {
            "$BASE/search/$slug/?from_videos=${(page - 1) * PAGE_SIZE + 1}"
        }
    }

    /**
     * Pure Parse function: the URL of browse-grid [page] — **path** paging, unlike search.
     * `<section>/<slug>/` for page 1, `<section>/<slug>/<page>/` afterwards; both verified
     * 200 on every section (channels/models/categories), `/2/` serving a different grid.
     */
    fun browseUrl(section: String, slug: String, page: Int): String {
        val path = "$BASE/${section.trim('/')}/${slug.trim('/')}/"
        return if (page <= 1) path else "$path$page/"
    }

    /** [url] as a [Taxonomy] page, or null when it is a video / index / off-site URL. */
    fun taxonomy(url: String): Taxonomy? =
        TAXONOMY_PATH.find(url.trim())?.let { match ->
            Taxonomy(
                section = match.groupValues[1],
                slug = match.groupValues[2],
                page = match.groupValues[3].toIntOrNull() ?: 1,
            )
        }

    /**
     * Pure Parse function: the bounded-paging predicate.
     *
     * A page continues the listing only while it brings at least one card not served before,
     * so an empty page (past the last page the site answers 404 with an empty body) or a page
     * that repeats an earlier result set ends the listing instead of looping forever.
     */
    fun hasNext(document: Document, previousIds: Set<String>): Boolean =
        hasNext(cards(document), previousIds)

    /** [hasNext] over an already-parsed page, so a caller does not re-parse the document. */
    fun hasNext(cards: List<Card>, previousIds: Set<String>): Boolean =
        cards.any { (it.videoId ?: it.href) !in previousIds }

    /**
     * Pure Parse function: the next browse-grid page, straight from the site's own pager.
     *
     * Browse grids carry real page links (`a.next` → `<path>/<n+1>/`), so the next page is a
     * structural fact rather than a guess: the last page renders no `a.next` and no dead
     * "Next page" card is offered. Search cannot use this — its pager is AJAX-only
     * (`href="#search"` + `data-parameters`), which is why search bounds by ids instead.
     */
    fun browseNextPage(document: Document): String? =
        document.selectFirst("div.pagination a.next[href]")
            ?.attr("href")?.trim()
            ?.takeIf { it.isNotEmpty() && it != "#" }
            ?.let { absolute(it) }

    /** Grid title: the page's own heading (`New Videos from Brazzers`), null when absent. */
    fun browseTitle(document: Document): String? =
        document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }

    /** A video thumb → its [Card]; null for taxonomy/ad thumbs and broken cards. */
    private fun videoCard(card: Element): Card? {
        val anchor = card.select("a[href]").firstOrNull { videoId(it.attr("href")) != null }
            ?: return null
        val href = absolute(anchor.attr("href").trim())
        val title = cardTitle(card, anchor) ?: return null
        return Card(videoId = videoId(href), title = title, href = href, poster = poster(card))
    }

    /** Card title: the anchor's `title` attribute (the site's canonical spelling), then the
     *  anchor text, then the poster's `alt` — null when the card carries no title at all. */
    private fun cardTitle(card: Element, anchor: Element): String? =
        anchor.attr("title").trim()
            .ifEmpty { anchor.text().trim() }
            .ifEmpty { card.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            .takeIf { it.isNotEmpty() }
}

class YesPornVip : MainAPI() {
    override var mainUrl = YesPornVipParse.BASE
    override var name = "YesPornVip"
    override val hasMainPage = true
    override var lang = "en"
    // Decision 3 (plan round 2): no suggest endpoint was probed, so quick search stays off
    // rather than inventing one.
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    // Six rows driven by the site's own section indexes (the home page only renders four of
    // them). Title → section URL is literal, so no `homeRows` parse seam is invented.
    override val mainPage = mainPageOf(
        "$mainUrl/latest-updates/" to "New Videos",
        "$mainUrl/most-popular/" to "Most Popular",
        "$mainUrl/top-rated/" to "Top Rated",
        "$mainUrl/categories/" to "Categories",
        "$mainUrl/channels/" to "Channels",
        "$mainUrl/models/" to "Models",
    )

    /** Rows that read a taxonomy index instead of a video listing: row URL → href prefix
     *  its entries live under. Everything else is a video row. */
    private val browseSections = mapOf(
        "$mainUrl/categories/" to "/categories/",
        "$mainUrl/channels/" to "/channels/",
        "$mainUrl/models/" to "/models/",
    )

    /**
     * Cross-page seen-set, per row. `/most-popular/` (and the taxonomy indexes) are ranked
     * by a live counter, so an entry on the page-1/page-2 boundary shifts across pages
     * (observed 2026-11-26: video 67389 was the last card of `most-popular` page 1 *and*
     * the first of page 2; the `channels` index pages shared 4 entries). Per-page dedupe
     * alone re-serves them; page 1 re-seeds the set, exactly like Cat3Movie #645.
     */
    private val seen = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Cross-page search seen-set, per query — the same guard as [seen], for the same reason:
     * results are ranked, so a card on a page boundary can be served twice. Page 1 re-seeds
     * the set. Its other job is loop-proofing: a page that repeats an earlier result set ends
     * the search ([YesPornVipParse.hasNext]) instead of feeding the app a duplicate forever.
     */
    private val searchSeen = ConcurrentHashMap<String, MutableSet<String>>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Path pagination on every surface here: `/latest-updates/2/`, `/categories/2/`.
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/$page/"
        val document = app.get(url).document
        val section = browseSections[request.data]
        val cards = if (section == null) {
            YesPornVipParse.cards(document)
        } else {
            YesPornVipParse.browseCards(document, section)
        }
        val seenKeys = seen.getOrPut(request.data) { HashSet() }
        if (page <= 1) seenKeys.clear()
        // Keyed by the video id where there is one (slug-canonicalisation safe), else href.
        val fresh = YesPornVipParse.fresh(cards, seenKeys)
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = fresh.map { it.toSearchResult() },
                isHorizontalImages = false,
            ),
            // An empty page — or a page that only repeated already-seen cards — ends the row.
            hasNext = fresh.isNotEmpty(),
        )
    }

    /**
     * The app's only card-click path is `load(url)`, so a taxonomy detail page
     * (`/channels|models|categories/<slug>/`) opens as a load screen whose `recommendations`
     * row is that page's video grid — parsed by the same [YesPornVipParse.cards] the rows
     * use — plus a "Next page" card whenever the site's pager links one, which walks the
     * grid `<path>/<n>/` page by page inside the app.
     *
     * Video detail pages are S3 (#1652): until that story lands, a `/video/<id>/` URL returns
     * null here (the card is dropped, nothing throws).
     */
    override suspend fun load(url: String): LoadResponse? {
        val taxonomy = YesPornVipParse.taxonomy(url) ?: return null
        val pageUrl = YesPornVipParse.browseUrl(taxonomy.section, taxonomy.slug, taxonomy.page)
        val document = app.get(pageUrl).document
        val cards = YesPornVipParse.cards(document)
        // An empty grid is not worth a screen: the card is dropped rather than opened empty.
        if (cards.isEmpty()) return null
        val nextPage = YesPornVipParse.browseNextPage(document)
        return newMovieLoadResponse(
            YesPornVipParse.browseTitle(document) ?: taxonomy.slug.replace('-', ' '),
            pageUrl,
            TvType.NSFW,
            pageUrl,
        ) {
            this.plot = "${cards.size} videos — ${taxonomy.section} / ${taxonomy.slug}, page ${taxonomy.page}"
            this.tags = listOf(taxonomy.section, taxonomy.slug)
            this.recommendations = cards.map { it.toSearchResult() } + listOfNotNull(
                nextPage?.let { href ->
                    newMovieSearchResponse("Next page", href, TvType.NSFW) {}
                },
            )
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.get(YesPornVipParse.searchUrl(query, page)).document
        val cards = YesPornVipParse.cards(document)
        val seenKeys = searchSeen.getOrPut(query) { HashSet() }
        if (page <= 1) seenKeys.clear()
        // Read the served set *before* this page is folded into it: a page that only repeats
        // earlier ids ends the search (an empty page — the site's 404 past the end — too).
        val hasNext = YesPornVipParse.hasNext(cards, seenKeys)
        return newSearchResponseList(
            YesPornVipParse.fresh(cards, seenKeys).map { it.toSearchResult() },
            hasNext,
        )
    }

    /** Emission adapter: [YesPornVipParse] stays framework-free, the API stamps the rest. */
    private fun YesPornVipParse.Card.toSearchResult(): SearchResponse =
        newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
}

@CloudstreamPlugin
class YesPornVipPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(YesPornVip())
        registerHostExtractors()
    }
}
