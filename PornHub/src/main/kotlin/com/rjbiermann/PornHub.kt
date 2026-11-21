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
import org.jsoup.nodes.Element
import java.net.URLEncoder

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
     * Search URL for [query] on [page]. Spaces become `+`, which is the form the site
     * canonicalises to — a `%20` query answers 301 and costs a redirect round trip
     * (FINDINGS §Search). Page 1 carries no `page` argument, exactly like the listing rows.
     */
    fun searchUrl(query: String, page: Int): String =
        pageUrl(BASE + SEARCH_PATH + "?search=" + URLEncoder.encode(query.trim(), "UTF-8"), page)

    /**
     * Pagination signal: the site's own `<link rel="next">`, with the pagination block's enabled
     * Next anchor as the backstop. It is absent on the reachable last page (page 100 for a broad
     * query, where `li.page_next` is `disabled` with an empty href) and on the 404 no-results
     * response — never a constant (FINDINGS §Pagination (search)).
     */
    fun hasNextPage(html: String): Boolean {
        val doc = Jsoup.parse(html, BASE)
        if (doc.selectFirst("link[rel=next]") != null) return true
        val next = doc.selectFirst("li.page_next:not(.disabled) a[href]") ?: return false
        return next.attr("href").isNotBlank()
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val videos = parseVideoCards(app.get(Parse.pageUrl(request.data, page)).text)
        return newHomePageResponse(
            HomePageList(name = request.name, list = videos, isHorizontalImages = false),
            hasNext = true, // FINDINGS §Pagination (home): every page through the last one has a next
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val html = app.get(Parse.searchUrl(query, page)).text
        return newSearchResponseList(
            parseVideoCards(html, search = true),
            hasNext = Parse.hasNextPage(html), // the site's own rel=next, never a constant
        )
    }

    // A walled / removed page returns null, so the app drops the card instead of throwing
    // (FINDINGS §Video pages: premium pages answer 200 with no flashvars blob).
    override suspend fun load(url: String): LoadResponse? {
        val details = Parse.videoDetails(app.get(url).text) ?: return null
        return newMovieLoadResponse(details.title, url, TvType.NSFW, url) {
            this.posterUrl = details.poster
            this.plot = details.plot
            this.tags = details.tags
            this.duration = details.durationMinutes
            this.year = details.year
            this.recommendations = details.related.mapNotNull { card ->
                try {
                    newMovieSearchResponse(card.title, card.url, TvType.NSFW) {
                        this.posterUrl = card.poster
                    }
                } catch (e: Exception) {
                    null
                }
            }
            addActors(details.actors.map { Actor(it) })
        }
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
