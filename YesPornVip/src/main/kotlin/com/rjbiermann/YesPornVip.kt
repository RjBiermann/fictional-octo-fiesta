package com.rjbiermann

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kraptor.JsonLdParse
import com.kraptor.KvsFlashvars
import com.kraptor.registerHostExtractors
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
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
 * The video-detail half ([metadata], [flashvars], [downloadLink], [related]) follows the same
 * split: it reads a page into site terms and leaves every request to the adapter.
 *
 * No `app.get` lives here: HTTP stays in the MainAPI adapter and is covered by pipeline
 * Verification, never by unit tests (ADR-0005). Fixtures are live captures (home/listing rows
 * 2026-11-26; search + browse grids 2026-11-27; video 76685 detail + per-format download
 * pages + the login wall 2026-11-27), mobile Chrome UA, under `src/test/resources/`.
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

    /**
     * One video's metadata, in site terms — no CloudStream types (see the seam note above).
     *
     * [year] is the only date shape `LoadResponse` can carry; [uploadDate] keeps the raw
     * JSON-LD token (`2026-11-21T11:44:44-05:00`) so the day-level fact is not thrown away in
     * the seam.
     */
    data class VideoMetadata(
        val title: String,
        val poster: String?,
        val plot: String?,
        val tags: List<String>,
        val categories: List<String>,
        val actors: List<String>,
        val durationMinutes: Int?,
        val uploadDate: String?,
    ) {
        val year: Int? get() = uploadDate?.take(4)?.toIntOrNull()

        /**
         * The single tag row a load screen shows: the site's categories first, then its tags.
         *
         * Both arrays are the *same* taxonomy with different spelling — JSON-LD `genre` is
         * title-cased (`Big tits`) and `keywords` is lower-cased (`big tits`) — so a plain
         * `distinct()` keeps every entry twice (4/4 case twins on the 76685 fixture). The
         * merge is case-insensitive and keeps the leading, title-cased spelling.
         */
        val distinctTags: List<String>
            get() = (categories + tags).distinctBy { it.lowercase() }
    }

    /** One KVS flashvar media candidate: the URL plus its real resolution, when one is stated. */
    data class Source(val url: String, val quality: Int?)

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
    fun cards(document: Document): List<Card> = cards(document.select("div.thumb"))

    /** [cards] over an already-selected thumb list (related strips, A/B fixtures). */
    fun cards(thumbs: Elements): List<Card> =
        thumbs.mapNotNull { videoCard(it) }.distinctBy { it.videoId }

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

    /**
     * Pure Parse function: the video-detail page's metadata, or null when the page carries no
     * video at all.
     *
     * The page's `VideoObject` JSON-LD is the structured source (`name`, `genre` → categories,
     * `keywords` → tags, `actor` → performers, `thumbnailUrl`, `uploadDate`, `duration:
     * PT0H8M00S`). KVS flashvars carry the same values on every sampled page and stay as the
     * fallback for a page whose JSON-LD block is dropped (grammar drift), while the visible
     * `h1.title` is the last title resort.
     *
     * **A page with no video signal at all is a wall, not a video**: the login/premium/geo
     * gate (`/login-required/` answers 200 with just a login modal) carries neither JSON-LD
     * nor `video_id`, so this returns null and the adapter drops the card — no
     * metadata-without-streams row.
     *
     * `video_models` (not the site's uploader/member) is the performer list: JSON-LD `actor`
     * carries it in page order, so the flashvar only backfills a page whose actor array is
     * absent.
     */
    fun metadata(html: String): VideoMetadata? {
        val document = Jsoup.parse(html)
        val video = videoObject(document)
        // Without either structured video block the page is not a video page (wall/404).
        if (video == null && KvsFlashvars.field(html, "video_id") == null) return null
        val title = video?.path("name")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("h1.title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: KvsFlashvars.field(html, "video_title")
            ?: return null
        val categories = textList(video?.path("genre"))
            .ifEmpty { csv(KvsFlashvars.field(html, "video_categories")) }
        val tags = csv(video?.path("keywords")?.asText())
            .ifEmpty { csv(KvsFlashvars.field(html, "video_tags")) }
        val actors = performerNames(video?.path("actor"))
            .ifEmpty { csv(KvsFlashvars.field(html, "video_models")) }
        // Poster: the `390x218/1.jpg` screenshot the listing cards already show, so a video's
        // card art and its load screen agree (the verbatim-distinctness bar — the JSON-LD
        // `thumbnailUrl` is a different file, the high-res `preview.jpg` hero). Falls back to
        // the JSON-LD thumbnail, then `og:image`, when a page has no screenshots tab.
        val poster = document.selectFirst("div.block-screenshots a.screen-img")?.let { poster(it) }
            ?: video?.path("thumbnailUrl")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
                ?.takeIf { it.isNotEmpty() }
        // The page's own description meta is real prose (the JSON-LD `description` is just the
        // title plus the model list), so it is the plot when present.
        val plot = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }
        val uploadDate = video?.path("uploadDate")?.asText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("meta[property=video:release_date]")?.attr("content")
                ?.trim()?.takeIf { it.isNotEmpty() }
        return VideoMetadata(
            title = title,
            poster = poster?.let { absolute(it) },
            plot = plot,
            tags = tags,
            categories = categories,
            actors = actors,
            // Shared ISO-8601 grammar (PT0H8M00S → 8); never a hand-rolled seconds math.
            durationMinutes = JsonLdParse.minutes(video?.path("duration")?.asText()),
            uploadDate = uploadDate,
        )
    }

    /**
     * Pure Parse function: the KVS player's media candidates, in KVS key order (qualities as
     * captioned), for the fallback branch of [YesPornVip.loadLinks].
     *
     * The grammar (legacy `video_url: '…'` / cfg `videoUrl: '…'`, plus the `<key>_text`
     * quality caption) is [KvsFlashvars]' job, not ours. Two site facts are handled here:
     *
     *  - `video_alt_url_redirect: '1'` marks a variant that holds a *page* URL, not a media
     *    file (the embed player serves its 720p that way on some titles), so it is skipped:
     *    a page href would otherwise be probed 200 and shipped as a "stream".
     *  - `video_url` is prefixed `function/0/` in front of an absolute URL
     *    (`function/0/https://yesporn.vip/get_file/…`). Both spellings answer 404 for the
     *    `*_trim.mp4` files this site's flashvars point at (verified 3/3 formats, 2026-11-27),
     *    which is why the download chain below is the effective resolver; the absolute target
     *    is unwrapped anyway so a title that *does* have trim files gets a real URL.
     *
     * A candidate's [Source.quality] comes from the caption first and the filename token
     * second (both inside [KvsFlashvars.quality]); null when neither says anything — never a
     * guessed resolution.
     */
    fun flashvars(html: String): List<Source> =
        KvsFlashvars.URL_KEYS.mapNotNull { key ->
            if (Regex("""${Regex.escape(key)}_redirect\s*:\s*'1'""").containsMatchIn(html)) {
                return@mapNotNull null
            }
            val url = KvsFlashvars.source(html, key)?.let { mediaUrl(it) } ?: return@mapNotNull null
            Source(url = url, quality = resolutionOf(KvsFlashvars.quality(html, key), url))
        }.distinctBy { it.url }

    /**
     * Pure Parse function: the download endpoint's URL for one [format] of [videoId].
     *
     * The endpoint is the site's own download dialog (`/view_video_download.php?id=…&format=…`),
     * verified 200 for all three formats on every sampled title; [downloadLink] reads the
     * signed media URL out of the page it answers.
     */
    fun downloadPageUrl(videoId: String, format: Int): String =
        "$BASE/view_video_download.php?id=$videoId&format=$format"

    /** The formats the download dialog offers, best last — 480p, 720p, 1080p. */
    val FORMATS = listOf(480, 720, 1080)

    /**
     * Pure Parse function: the media URL of the [format] download page, or null when the page
     * does not actually offer that format.
     *
     * The page carries one `a.btn.download` whose href is the signed `get_file/…mp4/` URL with
     * its query tail (`?download_filename=…&download=true[&br=…]`) — the tail is preserved
     * verbatim because it is the signed part (following the 302 to `cdn3.yesporn.vip` is what
     * turns it into bytes). A short format ladder (the site answers with a lower file, or with no
     * download button) yields null rather than a mislabelled link: the emitted quality is
     * [qualityOf] of the URL that came back, not the format that was asked for.
     */
    fun downloadLink(html: String, format: Int): String? =
        Jsoup.parse(html).select("a.btn.download[href]")
            .map { it.attr("href").trim() }
            // Match the format *within* the search, not after it: a page that ever renders
            // more than one download button must not hide the later rung behind the first.
            .firstOrNull { MEDIA_PATH.containsMatchIn(it) && qualityOf(it) == format }

    /**
     * Resolution of a downloaded media URL: the `_720p` filename token when the site put one
     * there, else 480 — the site's own naming, where `format=480` serves the tokenless base
     * file (`76685.mp4`) and only the higher rungs carry a token (`76685_720p.mp4`).
     */
    fun qualityOf(url: String): Int? =
        FILE_QUALITY.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: BASE_QUALITY

    /**
     * Pure Parse function: the detail page's related grid, as [Card]s — the same
     * framework-free seam the listing cards use, so the adapter stamps the `SearchResponse`.
     *
     * The strip is the page's `#list_videos_related_videos_items` container ("Related Videos"),
     * reusing [cards] on it so related rows parse exactly like listing rows. The separate
     * `Recommended Videos` aside is a *different* strip and is not mixed in. [videoId] is the
     * title the page is showing: a related grid may repeat it (and a slug pair is the same
     * video), so it is filtered out.
     */
    fun related(document: Document, videoId: String? = null): List<Card> =
        cards(document.select("$RELATED_SELECTOR div.thumb"))
            .filter { videoId == null || it.videoId != videoId }

    /** `#list_videos_related_videos_items` — the detail page's Related Videos strip. */
    private const val RELATED_SELECTOR = "#list_videos_related_videos_items"

    /** `…/76685_720p.mp4/?…` → `720`; a tokenless base file is the site's 480 rung. */
    private val FILE_QUALITY = Regex("""_(\d{3,4})p\.""")
    private const val BASE_QUALITY = 480

    /** A URL path that is media, not a page: `.mp4`/`.m3u8`/`.webm` followed by `/`, `?`, `#`. */
    private val MEDIA_PATH = Regex("""\.(?:mp4|m3u8|webm)(?:[/?#]|${'$'})""", RegexOption.IGNORE_CASE)

    private val MAPPER = ObjectMapper()

    /** The page's `VideoObject` JSON-LD node, or null when it carries none. */
    private fun videoObject(document: Document): JsonNode? =
        document.select("script[type=application/ld+json]").asSequence()
            .mapNotNull { runCatching { MAPPER.readTree(it.data()) }.getOrNull() }
            .firstOrNull { it.path("@type").asText() == "VideoObject" }

    /** JSON-LD array of plain strings (`genre`). */
    private fun textList(node: JsonNode?): List<String> =
        node?.takeIf { it.isArray }?.mapNotNull { it.asText().trim().takeIf(String::isNotEmpty) }
            .orEmpty()

    /** JSON-LD `actor` array → performer names, in page order. */
    private fun performerNames(node: JsonNode?): List<String> =
        node?.takeIf { it.isArray }
            ?.mapNotNull { it.path("name").asText().trim().takeIf(String::isNotEmpty) }
            .orEmpty()

    /** A comma list (`"big tits, latina"`) → trimmed entries. */
    private fun csv(value: String?): List<String> =
        value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /**
     * A flashvar value → an absolute media URL. The site prefixes `video_url` with
     * `function/0/` in front of an already-absolute URL, so that prefix is unwrapped (see
     * [flashvars]); a relative path is resolved against [BASE].
     */
    private fun mediaUrl(value: String): String {
        val trimmed = value.trim()
        val embedded = Regex("""https?://.*""").find(trimmed)?.value
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            embedded != null -> embedded
            else -> absolute(trimmed)
        }
    }

    /** Resolution stated by [label] (`480p`, `1080p FHD`) or by the URL's filename token. */
    private fun resolutionOf(label: String?, url: String?): Int? =
        label?.let { Regex("""(\d{3,4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?.takeIf { it in 144..4320 }
            ?: url?.let { FILE_QUALITY.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?.takeIf { it in 144..4320 }

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
     * The app's only card-click path is `load(url)`, and it serves two kinds of page:
     *
     *  - a **video** (`/video/<id>/<slug>/`, [loadVideo]) — the canonicalising 301 is the
     *    framework's job (OkHttp follows it), and the id alone is the identity, so both slug
     *    spellings load;
     *  - a **taxonomy** page (`/channels|models|categories/<slug>/`, [loadTaxonomy]) — opens as
     *    a load screen whose `recommendations` row is that page's video grid, parsed by the
     *    same [YesPornVipParse.cards] the rows use, plus a "Next page" card whenever the
     *    site's pager links one, which walks the grid `<path>/<n>/` page by page in-app.
     */
    override suspend fun load(url: String): LoadResponse? {
        val videoId = YesPornVipParse.videoId(url)
        return if (videoId == null) loadTaxonomy(url) else loadVideo(url, videoId)
    }

    private suspend fun loadVideo(url: String, videoId: String): LoadResponse? {
        val response = app.get(url)
        // A walled page (login/premium/geo) carries no video metadata at all: the card is
        // dropped instead of opening a metadata-without-streams row.
        val metadata = YesPornVipParse.metadata(response.text) ?: return null
        return newMovieLoadResponse(metadata.title, url, TvType.NSFW, url) {
            this.posterUrl = metadata.poster
            this.plot = metadata.plot
            // `LoadResponse` has one tag list; the site's categories lead, its tags follow —
            // case-insensitively, because both arrays spell the same taxonomy.
            this.tags = metadata.distinctTags
            this.actors = metadata.actors.map { ActorData(Actor(it)) }
            this.duration = metadata.durationMinutes
            this.year = metadata.year
            this.recommendations =
                YesPornVipParse.related(response.document, videoId).map { it.toSearchResult() }
        }
    }

    private suspend fun loadTaxonomy(url: String): LoadResponse? {
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

    /** The theme's `*_trim.mp4` preview family — never the full scene, whatever its quality tag. */
    private val TRIM_PATH = Regex("""_trim\.mp4""", RegexOption.IGNORE_CASE)

    /**
     * Streams come from two branches, in the spec's order:
     *
     *  1. the **KVS flashvars** candidates, each probed first (`app.head` without following
     *     the redirect, so a live `get_file` answers 302 while a dead one answers 404) — a
     *     candidate that is absent or dead is simply not emitted;
     *  2. the **download endpoint chain** for every format branch 1 did not cover:
     *     `/view_video_download.php?id=<id>&format=<480|720|1080>` → the signed
     *     `get_file/…mp4/?download_filename=…&download=true` URL, which 302s to
     *     `cdn3.yesporn.vip` (`206 video/mp4`, verified on several titles with no cookies).
     *
     * On this site branch 1 is always dead — the flashvars point at `*_trim.mp4` files the
     * origin 404s (FINDINGS) — so branch 2 is the effective resolver. It stays a fallback, not
     * a replacement, because the trim variants are a server-side property that may differ per
     * title. No cookies are sent: verification proved none are required (ADR-0008).
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val videoId = YesPornVipParse.videoId(data) ?: return false
        val html = app.get(data).text
        val covered = mutableSetOf<Int>()
        var emitted = 0
        for (source in YesPornVipParse.flashvars(html)) {
            if (!isLive(source.url)) continue
            callback(mediaLink(source.url, source.quality))
            emitted++
            // A live trim file is a preview, not the scene: emitting it is fine, but it must
            // not suppress the download chain's full file for the same rung.
            source.quality?.takeIf { !TRIM_PATH.containsMatchIn(source.url) }
                ?.let { covered.add(it) }
        }
        for (format in YesPornVipParse.FORMATS) {
            if (format in covered) continue
            // One dead hop must not discard the rungs that already resolved.
            val page = runCatching {
                app.get(YesPornVipParse.downloadPageUrl(videoId, format)).text
            }.getOrNull() ?: continue
            val media = YesPornVipParse.downloadLink(page, format) ?: continue
            callback(mediaLink(media, format))
            emitted++
        }
        return emitted > 0
    }

    /** Liveness probe: `get_file` answers 302 when the file exists, 404 when it does not. */
    private suspend fun isLive(url: String): Boolean =
        runCatching { app.head(url, allowRedirects = false).code < 400 }.getOrDefault(false)

    private suspend fun mediaLink(url: String, quality: Int?): ExtractorLink =
        newExtractorLink(
            source = name,
            name = if (quality == null) name else "$name - ${quality}p",
            url = url,
            type = ExtractorLinkType.VIDEO,
        ) {
            this.referer = "$mainUrl/"
            this.quality = quality ?: Qualities.Unknown.value
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
