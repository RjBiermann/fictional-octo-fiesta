package com.rjbiermann

import com.kraptor.DurationParse
import com.kraptor.JsonLdParse
import com.kraptor.registerHostExtractors
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Pure parsing for the /video listing cards (TDD-first, ADR-0005) — fixtures in
 * `src/test/resources/`, every selector transcribed in
 * `docs/archive/providers/PornHub/FINDINGS.md`.
 */
object Parse {
    const val BASE = "https://www.pornhub.com"

    /**
     * `pix-cdn77.phncdn.com` serves the still only when a Referer is present (403 without one,
     * 200 with any Referer); the signature query stays load-bearing either way (FINDINGS
     * §Listing — CDN shard & hotlink matrix).
     */
    val posterHeaders = mapOf("Referer" to "$BASE/")

    /**
     * The listing block. Scoping to `ul#videoCategory` is load-bearing: the header's
     * "Hottest" dropdown renders the same `li.pcVideoListItem` cards on every page, so a
     * bare `li.pcVideoListItem` would return those duplicates on every row and page.
     */
    private const val LISTING = "ul#videoCategory li.pcVideoListItem"

    /**
     * The search-results block. The card markup is shared with the listing, but the container is
     * not: search results render in `ul#videoSearchResult`, while the header dropdown
     * (`ul#hottestMenuSection`) and the bottom promo row (`ul#bottomVideos`) put the same
     * `li.pcVideoListItem` cards on the search page too (FINDINGS §Search).
     */
    private const val SEARCH_LISTING = "ul#videoSearchResult li.pcVideoListItem"

    /** Related-videos block on a watch page; scoped because the page repeats card lists in
     *  the locked-premium carousel too (FINDINGS §Video pages). */
    private const val RELATED_LISTING = "ul#relatedVideosListing li.pcVideoListItem"

    /** The card link; ad slots (`li.sniperModeEngaged`) carry no such anchor and are dropped. */
    private const val LINK = "a[href^=\"/view_video.php?viewkey=\"]"

    private const val SEARCH_PATH = "/video/search"

    /** `var flashvars_<id> = {...};` — the player config blob (one line on a watch page).
     *  DOT_MATCHES_ALL keeps a multi-line refresh of the blob working. */
    private val FLASHVARS =
        Regex("""var\s+flashvars(?:_\w+)?\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)

    private val JSON = com.fasterxml.jackson.databind.ObjectMapper()

    /** One listing card. [durationMinutes] / [views] are the card's own metadata (FINDINGS). */
    data class VideoCard(
        val title: String,
        val url: String,
        val poster: String?,
        val durationMinutes: Int?,
        val views: String?,
    )

    /** Parse function: listing HTML → cards. Broken cards are dropped, never fail the list. */
    fun cards(html: String): List<VideoCard> = cardsIn(html, LISTING)

    /** Parse function: search-results HTML → cards, scoped to the result block (FINDINGS §Search). */
    fun searchCards(html: String): List<VideoCard> = cardsIn(html, SEARCH_LISTING)

    /** Parse function: watch-page HTML → related-video cards (FINDINGS §Video pages). */
    fun relatedCards(html: String): List<VideoCard> = cardsIn(html, RELATED_LISTING)

    private fun cardsIn(html: String, selector: String): List<VideoCard> =
        Jsoup.parse(html, BASE).select(selector).mapNotNull { card(it) }

    /** Row URL for [page]: page 1 is the row's own URL, deeper pages append `page`. */
    fun pageUrl(base: String, page: Int): String =
        if (page <= 1) base else "$base${if (base.contains("?")) "&" else "?"}page=$page"

    /**
     * The form the site's own 301 rewrites a search query to (FINDINGS §Query shapes): trim,
     * lowercase, fold **Latin** diacritics, turn `'` and `.` into spaces, collapse whitespace
     * runs. Sending the normalised form up front keeps `&page=N` alive — the site's 301 **drops**
     * the page argument, so an un-normalised query would silently re-serve page 1 past page 1.
     *
     * Deliberately not rewritten: `-`, `,`, `&`. Those belong to a different site response
     * class (`404 Page Not Found`), so rewriting them would change what the user asked for.
     *
     * Mark folding is restricted to Latin bases: the marks of every other script are lexical,
     * not accents, so an NFD round trip must leave them in place — `हिंदी` must stay `हिंदी`, not
     * collapse to `हद` (the composition back to NFC restores what the decomposition split apart).
     */
    fun normaliseQuery(query: String): String {
        val decomposed = Normalizer.normalize(query.trim().lowercase(), Normalizer.Form.NFD)
        val folded = StringBuilder(decomposed.length)
        var lastBaseIsLatin = false
        var index = 0
        while (index < decomposed.length) {
            val codePoint = decomposed.codePointAt(index)
            index += Character.charCount(codePoint)
            if (isMark(codePoint)) {
                // A mark is an accent only over a Latin base; otherwise it is part of the word.
                if (lastBaseIsLatin) continue
            } else {
                lastBaseIsLatin = isLatinLetter(codePoint)
            }
            folded.appendCodePoint(codePoint)
        }
        return Normalizer.normalize(folded, Normalizer.Form.NFC)
            .replace('\'', ' ')
            .replace('.', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /** True for a combining mark (general category `M*`) — what diacritic folding can drop. */
    private fun isMark(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    /** True when [codePoint] is a Latin letter — the only base whose combining marks get folded. */
    private fun isLatinLetter(codePoint: Int): Boolean =
        Character.isLetter(codePoint) && when (Character.UnicodeBlock.of(codePoint)) {
            Character.UnicodeBlock.BASIC_LATIN,
            Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
            Character.UnicodeBlock.LATIN_EXTENDED_A,
            Character.UnicodeBlock.LATIN_EXTENDED_B,
            Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL,
            -> true
            else -> false
        }

    /**
     * Search URL for [query] on [page]. [normaliseQuery] is applied first, so the request is
     * never the shape the site 301s away; spaces become `+`, the canonical form (FINDINGS
     * §Query shapes, §Search). Page 1 carries no `page` argument, exactly like the listing rows.
     */
    fun searchUrl(query: String, page: Int): String = searchRetryUrl(normaliseQuery(query), page)

    /**
     * URL for a [searchEchoTarget] retry. Unlike [searchUrl] the term is **not** re-normalised:
     * on the canonical branch it is already the site's own folded query, and folding it through
     * our rules a second time can erase exactly the form the site asked for — measured
     * 2026-11-24: the site keeps the combining dot in `İstanbul` (`search=i̇stanbul`), which
     * [normaliseQuery] strips back to `istanbul`, so a re-folded retry would be byte-identical to
     * the request it is meant to replace (FINDINGS §Query shapes).
     */
    fun searchRetryUrl(search: String, page: Int): String =
        pageUrl(BASE + SEARCH_PATH + "?search=" + URLEncoder.encode(search, "UTF-8"), page)

    /**
     * Retry query when [html] is a canonical **echo** — the site ignored `page=N` and re-served an
     * earlier page (FINDINGS §Query shapes). Null on page 1, on a body whose canonical names the
     * requested page, and on a body with no canonical at all.
     *
     * The retry query is the canonical's own `search=` value when it carries one (the `Teen` →
     * `teen` shape); the `/pornstar/<slug>` canonical carries no `search=`, so the normalised
     * request query is used instead (the `Riley+Reid` shape). The caller retries **once**, through
     * [searchRetryUrl] so the canonical's own form survives verbatim, and [searchHasNext] reads the
     * retried body — an echo on the retry still ends the search, so no loop is possible.
     */
    fun searchEchoTarget(html: String, page: Int, query: String): String? {
        if (page <= 1) return null
        val href = canonicalHref(Jsoup.parse(html, BASE)) ?: return null
        if (pageArgument(href) >= page) return null
        return searchArgument(href) ?: normaliseQuery(query)
    }

    /**
     * Pagination signal: the site's own `<link rel="next">`, with the pagination block's enabled
     * Next anchor as the backstop. It is absent on the reachable last page (page 100 for a broad
     * query, where `li.page_next` is `disabled` with an empty href) and on the 404 no-results
     * response — never a constant (FINDINGS §Pagination (search)).
     */
    fun hasNextPage(html: String): Boolean = hasNextPage(Jsoup.parse(html, BASE))

    private fun hasNextPage(doc: Document): Boolean {
        if (doc.selectFirst("link[rel=next]") != null) return true
        val next = doc.selectFirst("li.page_next:not(.disabled) a[href]") ?: return false
        return next.attr("href").isNotBlank()
    }

    /**
     * Home-row stop rule: the `/video` listings publish the very same `<link rel="next">` (with
     * the enabled `li.page_next a[href]` as the backstop) as search, so the stop is derived with
     * [hasNextPage] — never a constant (FINDINGS §Pagination (home)).
     *
     * The old `hasNext = true` survives **only** as the fallback for a body with no mark-up at
     * all, and only on page 1: there the derived rule has no evidence to read, and `false` would
     * end the row on a transport hiccup. Past page 1 the same empty body means "nothing left to
     * show", so the row stops — a site answering blank bodies cannot leave it with an endless
     * "next". It never fires on a real, signal-less body either way: the past-the-end
     * `/video?page=9999` 404 (no `ul#videoCategory`, no `rel=next`) and a gated or degraded
     * listing both stop the row, so the app can neither walk into 404s nor re-serve page 1.
     */
    fun homeHasNext(html: String, page: Int = 1): Boolean =
        if (html.isBlank()) page == 1 else hasNextPage(html)

    /**
     * Search stop rule (S2 of #1524): three independent guards over the same seams [hasNextPage]
     * reads, each derived from the site's own mark-up — never a constant (FINDINGS
     * §Pagination (search)).
     *
     * 1. **Page-scoped blank body** — a blank body carries no evidence. Page 1 keeps the search
     *    open (a transport hiccup must not end it); past page 1 the same blank body means
     *    "nothing left to show" and stops it, exactly like [homeHasNext].
     * 2. **Page echo** — when the body's own `<link rel="canonical">` names an earlier page than
     *    the one requested, the site ignored `page=N` and re-served that page. It stops the
     *    search even though the re-served body still carries `rel=next`; otherwise the app would
     *    replay the same page for ever (the duplicate-loop class this repo hit on EPorner, #537).
     * 3. **Pagination-block backstop** — with the head `rel=next` absent (the M1′ shape S1 of
     *    #1524 measured), a `div.pagination3` block that still advertises a page beyond the one
     *    requested keeps the search open. A block that advertises nothing beyond it — the
     *    `page_next disabled` last reachable page, or no block at all — still stops.
     *
     * It deliberately never fires on a body with no next-page evidence: the M1′ body S1 measured
     * has cards but no `rel=next` and **no** `pagination3` block at all, and its page 2 answers
     * `404 div.noResultsWrapper`, so the search stops there (its guard 3 has nothing to read)
     * rather than re-requesting a page the site does not serve (FINDINGS §Pagination (search),
     * §Verification note (S2 of #1524)). The reachable last page (`rel=next` absent,
     * `page_next disabled`) stops for the same reason; only a block advertising a page **beyond**
     * the requested one keeps it open.
     */
    fun searchHasNext(html: String, page: Int = 1): Boolean {
        if (html.isBlank()) return page == 1
        val doc = Jsoup.parse(html, BASE)
        val canonical = canonicalPage(doc)
        if (page > 1 && canonical != null && canonical < page) return false
        return hasNextPage(doc) || advertisedPage(doc) > page
    }

    /** The body's own `<link rel="canonical">` href, or null when it carries none. */
    private fun canonicalHref(doc: Document): String? =
        doc.selectFirst("link[rel=canonical]")?.attr("href")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The page a response says it is: the `page` argument of its own canonical href (page 1
     * carries none). Null when the body has no canonical — no evidence, never an echo.
     */
    private fun canonicalPage(doc: Document): Int? =
        canonicalHref(doc)?.let { pageArgument(it) }

    /** `page=N` in [url] (1 when absent or unusable, like the site's own page-1 URLs). */
    private fun pageArgument(url: String): Int {
        val raw = Regex("""[?&]page=([^&#]*)""").find(url)?.groupValues?.get(1) ?: return 1
        return raw.toIntOrNull() ?: 1
    }

    /** Decoded `search=` in [url], or null when the URL carries none (the `/pornstar/` shape). */
    private fun searchArgument(url: String): String? {
        val raw = Regex("""[?&]search=([^&#]*)""").find(url)?.groupValues?.get(1) ?: return null
        return try {
            URLDecoder.decode(raw, "UTF-8").trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Highest page the body's own `div.pagination3` block advertises — its numbered page links
     * plus the current-page marker (0 when the body carries no block). The block is **absent** on
     * the M1′ body and **`disabled`/at the current page** on a reachable last page; that asymmetry
     * is what [searchHasNext]'s backstop reads (FINDINGS §Pagination (search)).
     */
    private fun advertisedPage(doc: Document): Int {
        val block = doc.selectFirst("div.pagination3") ?: return 0
        val pages = block.select("a[href]").map { pageArgument(it.attr("href")) } +
            block.select("li.page_current").mapNotNull { it.text().trim().toIntOrNull() }
        return pages.maxOrNull() ?: 0
    }

    /** Watch-page metadata. [related] is empty when the page exposes no related block. */
    data class VideoDetails(
        val title: String,
        val poster: String?,
        val plot: String?,
        val tags: List<String>,
        val actors: List<String>,
        val durationMinutes: Int?,
        val year: Int?,
        val related: List<VideoCard>,
    )

    /** One media leg from `flashvars.mediaDefinitions` ([height] is 0 when absent). */
    data class Media(val format: String, val url: String, val height: Int, val remote: Boolean)

    /** One playable source: [url] plus the height that carries the quality. */
    data class Stream(val url: String, val height: Int)

    /** Parse function: watch-page HTML → the `var flashvars_<id>` config blob, or null. */
    fun flashvars(html: String): String? = FLASHVARS.find(html)?.groupValues?.get(1)

    /**
     * Parse function: watch-page HTML → [VideoDetails], or **null** for a page the app must drop.
     * A page with no flashvars blob is premium-walled or removed (both answer without player
     * config — FINDINGS §Video pages), and the blob's own `video_unavailable` /
     * `video_unavailable_country` flags cover the geo wall. Never fabricate metadata for those.
     */
    fun videoDetails(html: String): VideoDetails? {
        val flashvars = flashvars(html) ?: return null
        val root = try {
            JSON.readTree(flashvars)
        } catch (e: Exception) {
            return null
        }
        if (root.path("video_unavailable").asText("false") == "true") return null
        if (root.path("video_unavailable_country").asText("false") == "true") return null

        val doc = Jsoup.parse(html, BASE)
        val title = doc.selectFirst("h1.title span.inlineFree")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: return null

        val jsonLd = doc.select("script[type=application/ld+json]").map { it.data() }
            .firstOrNull { it.contains("VideoObject") }
        val categories = doc.select("div.categoriesWrapper a[data-label=category]")
            .map { it.text().trim() }.filter { it.isNotEmpty() }
        val keywords = doc.select("div.tagsWrapper a[data-label=tag]")
            .map { it.text().trim() }.filter { it.isNotEmpty() }

        return VideoDetails(
            title = title,
            poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.trim()?.takeIf { it.isNotEmpty() },
            // The site exposes no owner-written synopsis; og/name description is the generated
            // "Watch <title> on Pornhub.com, …" summary — carried verbatim, never invented.
            plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
                    ?.takeIf { it.isNotEmpty() },
            tags = (categories + keywords).distinct(),
            actors = doc.select("div.pornstarsWrapper a.pstar-list-btn")
                .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            durationMinutes = JsonLdParse.minutes(jsonLd)
                ?: DurationParse.fromSeconds(root.path("video_duration").asText()),
            year = JsonLdParse.year(jsonLd),
            related = relatedCards(html),
        )
    }

    /** Parse function: `mediaDefinitions` legs with an absolute URL, in page order. */
    fun mediaDefinitions(flashvarsJson: String): List<Media> = try {
        val defs = JSON.readTree(flashvarsJson).path("mediaDefinitions")
        if (!defs.isArray) emptyList()
        else (0 until defs.size()).mapNotNull { i ->
            val entry = defs.get(i) ?: return@mapNotNull null
            val url = entry.path("videoUrl").asText("").trim()
            if (!url.startsWith("http")) null
            else Media(
                format = entry.path("format").asText(""),
                url = url,
                height = entry.path("height").asInt(0),
                remote = entry.path("remote").asBoolean(false),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** HLS legs only (one per quality, low → high) — the signed master.m3u8 sources. */
    fun hlsStreams(flashvarsJson: String): List<Stream> =
        mediaDefinitions(flashvarsJson)
            .filter { it.format == "hls" }
            .map { Stream(it.url, it.height) }
            .sortedBy { it.height }

    /** The `remote` mp4 leg — `/video/get_media`, the documented fallback (FINDINGS §Stream sources). */
    fun getMediaUrl(flashvarsJson: String): String? =
        mediaDefinitions(flashvarsJson)
            .firstOrNull { it.remote && it.url.contains("/video/get_media") }?.url

    /** Parse function: a `/video/get_media` JSON body → progressive mp4 [Stream]s (low → high). */
    fun getMediaStreams(body: String): List<Stream> = try {
        val root = JSON.readTree(body)
        if (!root.isArray) emptyList()
        else (0 until root.size()).mapNotNull { i ->
            val entry = root.get(i) ?: return@mapNotNull null
            val url = entry.path("videoUrl").asText("").trim()
            if (url.startsWith("http")) Stream(url, entry.path("height").asInt(0)) else null
        }.sortedBy { it.height }
    } catch (e: Exception) {
        emptyList()
    }

    /** Entry height → CloudStream quality: `Qualities` values ARE the pixel heights. */
    fun qualityOf(height: Int): Int =
        if (height in 144..4320) height else Qualities.Unknown.value

    /** Player label for a leg: `PornHub 720p`, or the bare source name when no height is known. */
    fun linkName(source: String, height: Int): String =
        if (height > 0) "$source ${height}p" else source

    /**
     * HLS legs the player can actually open — [isLive] decides per leg, so the caller can probe
     * the master playlist first. An empty result means the page was served the dead signing shape
     * (style B answers 410 — FINDINGS §Stream sources), so `loadLinks` must take the
     * `/video/get_media` fallback instead of handing the player URLs that cannot resolve.
     */
    inline fun playableHls(streams: List<Stream>, isLive: (Stream) -> Boolean): List<Stream> =
        streams.filter(isLive)

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
 * [search] reads the search-results container instead of the row listing — the card shape is
 * shared, only the block differs (FINDINGS §Search).
 */
fun MainAPI.parseVideoCards(html: String, search: Boolean = false): List<SearchResponse> =
    (if (search) Parse.searchCards(html) else Parse.cards(html)).mapNotNull { card ->
        try {
            newMovieSearchResponse(card.title, card.url, TvType.NSFW) {
                this.posterUrl = card.poster
                this.posterHeaders = Parse.posterHeaders
            }
        } catch (e: Exception) {
            null
        }
    }

/**
 * Parse seam: [Parse.VideoDetails] → [LoadResponse], no HTTP — so the poster-header wiring is
 * testable without mocking. The load poster and every recommendation card carry
 * [Parse.posterHeaders] because the app's image loader sends no Referer (FINDINGS §Listing).
 */
suspend fun MainAPI.parseLoadResponse(url: String, details: Parse.VideoDetails): LoadResponse =
    newMovieLoadResponse(details.title, url, TvType.NSFW, url) {
        this.posterUrl = details.poster
        this.posterHeaders = Parse.posterHeaders
        this.plot = details.plot
        this.tags = details.tags
        this.duration = details.durationMinutes
        this.year = details.year
        this.recommendations = details.related.mapNotNull { card ->
            try {
                newMovieSearchResponse(card.title, card.url, TvType.NSFW) {
                    this.posterUrl = card.poster
                    this.posterHeaders = Parse.posterHeaders
                }
            } catch (e: Exception) {
                null
            }
        }
        addActors(details.actors.map { Actor(it) })
    }

class PornHub : MainAPI() {
    override var mainUrl = Parse.BASE
    override var name = "PornHub"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    // FINDINGS §Quick search: /video/search_autocomplete, /api/v1/video/search and /suggest all
    // answer HTTP 404, so there is no distinct suggest endpoint to call — results come from the
    // full search page only.
    override val hasQuickSearch = false

    // FINDINGS §Homepage: all four rows are the /video listing, `o=` selects the sort, and
    // the labels are the site's own nav labels (`o=cm` is Newest — "sort_newest" in the page).
    override val mainPage = mainPageOf(
        "$mainUrl/video" to "Home",
        "$mainUrl/video?o=mv" to "Most Viewed",
        "$mainUrl/video?o=tr" to "Top Rated",
        "$mainUrl/video?o=cm" to "Newest",
    )

    // kotlin:S6518 ("Replace function call with indexed accessor") reads NiceHttp's `app.get(...)`
    // — the HTTP verb, single positional argument — as a collection accessor. The analyzer has no
    // classpath for this repo, so the same false positive sits on every provider page fetch (86
    // open instances on main, 2026-11-22); it is suppressed here because this PR's new code is the
    // only place the new-code gate measures it. Root cause filed as a triage issue with this fix.
    @Suppress("kotlin:S6518")
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = app.get(Parse.pageUrl(request.data, page)).text
        val videos = parseVideoCards(html)
        return newHomePageResponse(
            HomePageList(name = request.name, list = videos, isHorizontalImages = false),
            // Derived from the site's own rel=next on every live page, exactly like search;
            // `Parse.homeHasNext` documents the single fallback (an empty body, page 1 only).
            hasNext = Parse.homeHasNext(html, page),
        )
    }

    // Same `kotlin:S6518` false positive as `getMainPage` above — NiceHttp's `app.get(...)` HTTP
    // verb read as a collection accessor, one instance per page fetch.
    @Suppress("kotlin:S6518")
    override suspend fun search(query: String, page: Int): SearchResponseList {
        var html = app.get(Parse.searchUrl(query, page)).text
        // At most one retry: a canonical echo means the site dropped `page=N` and re-served an
        // earlier page, so re-request the canonical query once (FINDINGS §Query shapes).
        // `searchRetryUrl` keeps the canonical's own form verbatim. Cards and hasNext read the
        // body actually used; an echo on the retry still stops the search at guard 2, so this
        // cannot loop.
        Parse.searchEchoTarget(html, page, query)?.let { target ->
            html = app.get(Parse.searchRetryUrl(target, page)).text
        }
        return newSearchResponseList(
            parseVideoCards(html, search = true),
            // The three guards of FINDINGS §Pagination (search): blank-body scope, page echo
            // (on the body actually used), then the site's own signals with the block backstop.
            hasNext = Parse.searchHasNext(html, page),
        )
    }

    // A walled / removed page returns null, so the app drops the card instead of throwing
    // (FINDINGS §Video pages: premium pages answer 200 with no flashvars blob).
    override suspend fun load(url: String): LoadResponse? {
        val details = Parse.videoDetails(app.get(url).text) ?: return null
        return parseLoadResponse(url, details)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // Resolved fresh on every call: validfrom/validto expire after ~2 h (FINDINGS §Stream sources).
        val response = app.get(data)
        val flashvars = Parse.flashvars(response.text) ?: return false
        val referer = "$mainUrl/"

        // The CDN assigns one of two signing shapes per page fetch: style A answers 200, style B
        // answers 410 (FINDINGS §Stream sources — CDN host lottery). Only a leg that answers
        // reaches the player, and a dead shape falls through to /video/get_media below — an
        // emitted-but-unresolvable HLS URL is worse than no HLS link at all.
        val playable = Parse.playableHls(Parse.hlsStreams(flashvars)) { leg ->
            isPlayable(leg.url, referer)
        }
        if (playable.isNotEmpty()) {
            playable.forEach { stream ->
                callback(newExtractorLink(
                    source = name,
                    name = Parse.linkName(name, stream.height),
                    url = stream.url,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = referer
                    this.quality = Parse.qualityOf(stream.height)
                })
            }
            return true
        }

        // Documented fallback when flashvars carries no usable HLS leg: the `remote` mp4 leg
        // `/video/get_media`. It needs the watch page's session cookies — a cookie-less GET
        // answers `[]` (FINDINGS §Stream sources).
        val fallbackUrl = Parse.getMediaUrl(flashvars) ?: return false
        val mp4 = try {
            Parse.getMediaStreams(
                app.get(fallbackUrl, referer = data, cookies = response.cookies).text
            )
        } catch (e: Exception) {
            emptyList()
        }
        mp4.forEach { stream ->
            callback(newExtractorLink(
                source = name,
                name = Parse.linkName(name, stream.height),
                url = stream.url,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.referer = referer
                this.quality = Parse.qualityOf(stream.height)
            })
        }
        return mp4.isNotEmpty()
    }

    /** A master playlist answers 2xx when its signing shape is alive; a playlist GET is a few KB. */
    private suspend fun isPlayable(url: String, referer: String): Boolean = try {
        app.get(url, referer = referer).code in 200..299
    } catch (e: Exception) {
        false
    }
}

@CloudstreamPlugin
class PornHubPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PornHub())
        registerHostExtractors()
    }
}
