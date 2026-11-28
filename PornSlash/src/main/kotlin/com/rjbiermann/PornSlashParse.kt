// Pure Parse functions for PornSlash (pornslash.com) — jsoup in, values out; no HTTP, no
// framework types (ADR-0005: MainAPI/HTTP flows stay with pipeline Verification). Fixtures
// under src/test/resources/ are live captures from 2026-11-26/27.
//
// Why a provider-local seam and not the shared KVS modules: this site is not flashvars KVS.
// The served HTML carries no `video_url` and no `kt_player`, so KvsFlashvars/KvsVideofile
// have nothing to read. What it does serve is the mp4 path every watch page's `og:image`
// mirrors (`/t/<encid>.jpeg` → `/p/<encid>.mp4`), and a JSON shorts feed whose items are
// plain `/watch/<item_id>` links (S4 #1646).
//
// S5 (#1647) adds the model browse surface without a scrape: `/suggest/<orientation>/<query>`
// is the site's own model directory (JSON, one request), and its `model_id` is the
// `/pornstar/<slug>` route. A search query that names a model exactly browses that model's
// filmography; every other query stays the ordinary keyword search.
//
// The structural rule the drift history demands (findings 2026-11-26): the CDN host is a
// per-video `*.pages.dev` project (wolfcipher56-1343.pages.dev/13426,
// ba0ze4asqz-1338.pages.dev/13375, ...), so it is read from the page on every load and
// never templated.
//
// S6 (#1648) closes the loop on the play path with the close-out probe: the `og:image`
// mirror (`/t/<encid>.jpeg` → `/p/<encid>.mp4`) is the *hover-preview* clip the cards load
// (`div.thumbnail[data-p]`), a 10-second teaser — never the movie. The full-length source is
// the page's own player: the rotating `*.trycloudflare.com` tunnel, `<base>/master/<encid>`,
// fetched as `application/x-mpegurl` with the site's real 144p…1080p ladder. The base is
// decoded per load and never remembered (the same video answered two different tunnels
// minutes apart); the preview mp4 stays only as the last-resort fallback.
package com.rjbiermann

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.kraptor.DurationParse
import com.kraptor.JsonLdParse
import com.kraptor.decodeBase64
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** One listing card: the fields the surfaces (home, search, category, related) all carry. */
data class SlashCard(
    val id: String,
    val title: String,
    val poster: String?,
    val quality: String?,
    /** Runtime in seconds — `data-duration` is exact; the `.duration` clock is the fallback. */
    val duration: Int?,
)

/**
 * One `/watch/<id>` page: the metadata the detail surface renders, plus the stream candidate.
 * Long-form and shorts items share the route and this grammar.
 */
data class SlashDetail(
    val title: String,
    val poster: String?,
    val description: String?,
    val tags: List<String>,
    /** CloudStream convention: minutes, never seconds. */
    val duration: Int?,
    val quality: String?,
    /** The per-video encode id — the `<encid>` the thumbnail path, the mp4 and the master share. */
    val encid: String?,
    /** The hover-preview clip the thumbnail mirrors. A 10-second teaser — never the movie. */
    val mp4: String?,
    /** The full-length HLS master, `https://<tunnel>/master/<encid>` — the play path. */
    val hls: String?,
    val recommendations: List<SlashCard>,
)

/**
 * One `/suggest/` model row — the site's own directory entry. `id` is the `model_id`, i.e. the
 * `/pornstar/<id>/` route; the display `name` is what an exact-name query is matched against.
 */
data class SlashModel(val id: String, val name: String)

/**
 * The search surface a query browses, remembered across pages. CloudStream's `search(query, page)`
 * carries nothing between pages, so a decision re-made per page can move a page sequence onto the
 * other surface: when `/suggest/` stops answering on page 2, a model filmography would silently
 * become page 2 of the keyword result set — skipped and unrelated cards. The first page's decision
 * is therefore remembered, negative (`null` = keyword) answers included, which also costs one
 * `/suggest/` request per query instead of one per page.
 *
 * [limit] bounds it — this is a session convenience, not a query history.
 */
internal class SearchSurface(private val limit: Int = 16) {
    // `""` is the remembered "no model" answer: a nullable map cannot tell a cached null apart
    // from a query that was never decided.
    private val decided = object : LinkedHashMap<String, String>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > limit
    }

    /**
     * The model slug [query] browses, or null for the ordinary keyword surface. [resolve] runs only
     * while the query is undecided, so a page never re-decides a surface an earlier page chose.
     */
    suspend fun surface(query: String, resolve: suspend () -> String?): String? {
        val remembered = synchronized(decided) { decided[query] }
        if (remembered != null) return remembered.ifEmpty { null }
        val slug = resolve() ?: ""
        synchronized(decided) { decided[query] = slug }
        return slug.ifEmpty { null }
    }
}

object PornSlashParse {

    /** The listing card root — one grammar on every surface (home, search, /cat/, related). */
    const val CARD = "div.video-item"

    /** The watch page's dataset holder — `<div class="share-video" data-id data-encid data-quality …>`. */
    const val SHARE = "div.share-video"

    /** The detail title — `<h1 class="video-page-title">`. */
    const val DETAIL_TITLE = "h1.video-page-title"

    /** The related rail under the player — the recommendation surface. */
    const val RELATED = "div.related div.video-item"

    /** The spec's related cap. */
    const val RECOMMENDATION_LIMIT = 50

    /** A category tile on `/categories/` — `<a class="cat-item" href="/cat/<slug>">`. */
    const val CATEGORY_ITEM = "a.cat-item"

    const val MAIN = "https://www.pornslash.com"

    /** The `Latest` surface. The bare `/` serves the same 50-card grid. */
    const val VIDEOS = "$MAIN/videos/"

    const val SEARCH = "$MAIN/search/"

    /**
     * The site's model directory lookup — JSON, not HTML. The orientation segment is the one the
     * plain `/search/` surface uses; the [VIDEOS]/[SHORTS] feeds and `/cat/<slug>/` browse live
     * under the same default.
     */
    const val SUGGEST = "$MAIN/suggest/straight/"

    /**
     * The shorts feed — JSON, not HTML, and 10 items per page (S4 #1646). Its items are
     * ordinary catalogue entries: each `item_id` resolves through the same `/watch/<id>`
     * detail route as a long-form movie, paged with the same `?p=N` contract (page 1 is the
     * bare feed).
     */
    const val SHORTS = "$MAIN/get/shorts"

    /**
     * Cards per page, live 2026-11-26/27: `/videos/` serves 50, `/search/<q>/` and `/cat/<slug>/`
     * serve 25, the shorts feed serves 10. The sizes feed [hasNext]'s stop rule, so a site-side
     * change surfaces as a short row rather than a 500 on `?p=last+1`.
     */
    const val PAGE_SIZE_VIDEOS = 50
    const val PAGE_SIZE_SEARCH = 25
    const val PAGE_SIZE_SHORTS = 10

    /** The detail route both surfaces share: `/watch/<id>` serves movies and shorts items. */
    fun watchUrl(id: String): String = "$MAIN/watch/$id"

    private val jsonMapper = ObjectMapper()

    /**
     * The eight curated home rows, pinned from the 92 tiles on `/categories/`.
     *
     * The pin is the site's own listing, not a guess: every slug below served a full
     * 25-card page 1 *and* page 2 on 2026-11-26/27 (the spec's `>= 25 cards` bar). The
     * `/categories/` fixture test keeps the pairing honest — a retired slug or a relabelled
     * tile fails there before it reaches the home page.
     */
    val CURATED_CATEGORIES = listOf(
        "milf" to "MILF",
        "anal" to "Anal",
        "teen" to "Teen",
        "asian" to "Asian",
        "bbw" to "BBW",
        "amateur" to "Amateur",
        "ebony" to "Ebony",
        "lesbian" to "Lesbian",
    )

    fun categoryUrl(slug: String): String = "$MAIN/cat/$slug/"

    private val VIDEO_HREF = Regex("""/watch/([A-Za-z0-9_-]+)""")

    /** `/t/<encid>.<ext>` — the trailing thumbnail path segment the mp4 mirrors. */
    private val THUMB = Regex("""/t/([^/?#]+)\.[A-Za-z0-9]+$""")

    /** The player's own master URL, already rendered into the page's inline script. */
    private val HLS_MASTER = Regex("""https://[A-Za-z0-9.-]+/master/([A-Za-z0-9_-]{4,})""")

    /**
     * The player's own base token (`Pt="<b64>"`, which the player `atob`s at runtime).
     *
     * Matched by its variable, never as "any quoted base64": the page carries encrypted ids,
     * content hashes and an ad base in that same shape, so an unscoped scan lets any bare-origin
     * string on the page become this video's stream host. Only `Pt` is the player's.
     */
    private val PLAYER_BASE = Regex("""\bPt\s*=\s*["']([A-Za-z0-9+/]{24,256}={0,2})["']""")

    /** A decoded token is a *base* only as a bare https origin — the tunnel shape. */
    private val HTTPS_ORIGIN = Regex("""^https://[A-Za-z0-9.-]+$""")

    /** H:MM:SS / MM:SS card clock (`54:35` on the live home page). */
    private val CLOCK = Regex("""^(?:(\d+):)?(\d+):(\d+)$""")

    private val DIGITS = Regex("""(\d{3,4})""")

    /** A listing page → its cards; one broken card is dropped, never the whole page. */
    fun cards(document: Document): List<SlashCard> = document.select(CARD).mapNotNull { card(it) }

    /**
     * `/search/<q>/?p=N`. The query is percent-encoded (`%20` for spaces) and otherwise left
     * alone: the site resolves both `%20` and `+` for the same query, and the Porntrex-style
     * `-` normalization is an assumption this site never asked for.
     */
    fun searchUrl(query: String, page: Int): String =
        pageUrl(SEARCH + encodeQuery(query) + "/", page)

    /**
     * `/suggest/straight/<q>` — the model-directory lookup, or null for a blank query (the
     * bare route answers an HTML page, so it is never requested).
     */
    fun suggestUrl(query: String): String? {
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) null else SUGGEST + encodeQuery(trimmed)
    }

    /**
     * `/pornstar/<slug>/` — a model's own filmography, 25 cards/page on the same listing
     * grammar and the same `?p=N` contract as `search()` (see [pageUrl]).
     */
    fun pornstarUrl(slug: String): String = "$MAIN/pornstar/$slug/"

    /**
     * A `/suggest/` payload → its model rows. The shape is `[terms[], models[]]` and either half
     * may be an explicit JSON null — the live no-hit answer is `[null,null]`. A malformed payload
     * yields no rows, never a throw, and a row missing `model_id` or `name` is dropped rather
     * than half-filled.
     */
    fun modelSuggestions(json: String?): List<SlashModel> {
        val root = json?.takeIf { it.isNotBlank() }
            ?.let { runCatching { jsonMapper.readTree(it) }.getOrNull() }
            ?: return emptyList()
        val models = root.get(1) as? ArrayNode ?: return emptyList()
        val rows = ArrayList<SlashModel>(models.size())
        for (i in 0 until models.size()) {
            val node = models.get(i)
            val id = node.textOrNull("model_id") ?: continue
            val name = node.textOrNull("name") ?: continue
            rows.add(SlashModel(id, name))
        }
        return rows
    }

    /**
     * The `/pornstar/<slug>` route a search query names, or null when it names no model.
     *
     * Only an *exact* name resolves, ignoring case, padding and the hyphen/space separator: a
     * prefix like `riley` must keep the ordinary keyword search, where the site's term
     * suggestions (`riley reid anal`, …) live. Exactness is also what makes the surface useful —
     * the model page is that model's filmography, while a keyword page for the same name shares
     * only a handful of its videos.
     */
    fun modelSlug(query: String, json: String?): String? {
        val wanted = normalizeName(query) ?: return null
        return modelSuggestions(json).firstOrNull { normalizeName(it.name) == wanted }?.id
    }

    /** Case-, padding- and separator-insensitive model name, or null when nothing is left. */
    private fun normalizeName(raw: String?): String? =
        raw?.replace('-', ' ')?.replace('_', ' ')?.lowercase()
            ?.split(' ')?.filter { it.isNotEmpty() }?.joinToString(" ")
            ?.takeIf { it.isNotEmpty() }

    /**
     * One `div.video-item` → [SlashCard]; null when the card lacks a real id or title so a
     * single broken card never kills a listing.
     *
     * Field sources, in order: id from `div.video-info[data-id]` then the `/watch/<id>`
     * anchor; title from `data-title` then the title anchor's text/title attr; poster from
     * the thumbnail `<img>` (placeholder `data:` URIs skipped, never fabricated); quality
     * from the `.quality` badge then `data-quality`; duration from `data-duration` (seconds)
     * then the `.duration` clock.
     */
    fun card(el: Element): SlashCard? {
        val meta = el.selectFirst("div.video-info")
        val id = meta?.attr("data-id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: VIDEO_HREF.find(el.selectFirst("a[href*=/watch/]")?.attr("href").orEmpty())
                ?.groupValues?.get(1)
            ?: return null
        val title = meta?.attr("data-title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: el.selectFirst("h2.video-title a[title]")?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: el.selectFirst("h2.video-title a")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val poster = el.selectFirst("div.thumbnail img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
                .takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }
        val quality = quality(
            el.selectFirst(".quality")?.text()?.takeIf { it.isNotBlank() } ?: meta?.attr("data-quality")
        )
        val duration = meta?.attr("data-duration")?.trim()?.toIntOrNull()
            ?: clockSeconds(el.selectFirst(".duration")?.text())
        return SlashCard(id, title, poster, quality, duration)
    }

    /**
     * One `/watch/<id>` page → [SlashDetail], or null when the page carries no title at all.
     *
     * Nothing here is cached or templated — the per-video `*.pages.dev` CDN host comes from the
     * page's own `og:image`, so the mp4 is derived per load.
     */
    fun detail(document: Document): SlashDetail? {
        val share = document.selectFirst(SHARE)
        val title = document.selectFirst(DETAIL_TITLE)?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.trim()?.takeIf { it.isNotEmpty() }
            ?: share?.attr("data-title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.trim()?.takeIf { it.isNotEmpty() }
        val id = share?.attr("data-id")?.trim()?.takeIf { it.isNotEmpty() }
        val encid = share?.attr("data-encid")?.trim()?.takeIf { it.isNotEmpty() }
        return SlashDetail(
            title = title,
            poster = poster,
            description = description(document),
            tags = tags(document),
            duration = durationMinutes(
                document.selectFirst("meta[property=og:duration]")?.attr("content"),
                document.selectFirst("script[type=application/ld+json]")?.data(),
            ),
            quality = quality(share?.attr("data-quality")),
            encid = encid,
            mp4 = mp4FromThumb(poster),
            hls = hlsMaster(document.html(), encid),
            recommendations = recommendations(document, id),
        )
    }

    /**
     * The full-length stream: `https://<tunnel>/master/<encid>`.
     *
     * Two page shapes, both handled structurally — the live page renders the player's own
     * master URL into the inline script, and a page that ships only the base (`Pt="<b64>"`,
     * decoded by the player at runtime) is completed from [encid]. Nothing is templated: the
     * host comes from the page itself, and the base is decoded **per load, never cached** —
     * the `*.trycloudflare.com` tunnel rotates between loads (the same video answered two
     * different tunnels minutes apart), so a remembered host is a dead source on the next
     * play.
     *
     * A decoded token counts as a base only when it is a bare https origin: the page also
     * carries an ad token (`atob("aHR0cHM6…")`) that decodes to a URL *with a path*, which
     * appending `/master/<encid>` to would invent a nonexistent source. And it counts only when
     * the player assigned it to its own variable — see [PLAYER_BASE].
     *
     * No [encid] means no candidate at all: the encid is the page's only statement of which
     * stream is *this* video's, and a page can carry several master URLs (a related rail ships
     * its own player). Guessing would play another video; returning null leaves the preview
     * fallback, which is this video's own clip.
     */
    fun hlsMaster(html: String?, encid: String?): String? {
        val page = html?.takeIf { it.isNotBlank() } ?: return null
        val id = encid?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // The rendered URL must belong to *this* video: a page can carry several master URLs
        // (a rail that ships its own player), and one of those is never this stream.
        HLS_MASTER.findAll(page)
            .firstOrNull { it.groupValues[1] == id }
            ?.let { return it.value }
        for (token in PLAYER_BASE.findAll(page)) {
            val base = decodeBase64(token.groupValues[1])?.trim() ?: continue
            if (HTTPS_ORIGIN.matches(base)) return "$base/master/$id"
        }
        return null
    }

    /**
     * One `/get/shorts` page: the cards parsed from its `videos[]` JSON, plus the raw feed item
     * count. Pagination reads [rawCount], never `cards.size` — a malformed item is dropped from
     * the row but it still occupied a feed slot, so a full page carrying one broken item must
     * not look partial and hide the pages after it.
     */
    data class ShortsPage(val cards: List<SlashCard>, val rawCount: Int)

    /**
     * `/get/shorts?p=N` → its feed page, read from the feed's own `videos[]` JSON.
     *
     * Each item carries everything the card needs: `item_id` (the `/watch/<item_id>` route),
     * `title`, `poster` (the grid image; `image` backs it up when the feed ships none),
     * `duration` in seconds and `quality` as a bare number. The feed also carries an `url`
     * field — the ephemeral playback base — which is deliberately unread: the item plays from
     * the mp4 its `/watch/<id>` page derives, so no tunnel host enters the play path and no
     * extra round-trip is needed at playback time. A malformed payload yields no cards, never
     * a throw, and an item without an id or a title is dropped rather than half-filled. An
     * explicit JSON `null` is treated as absent, never as the text `"null"`.
     */
    fun shortsPage(json: String?): ShortsPage {
        val root = json?.takeIf { it.isNotBlank() }
            ?.let { runCatching { jsonMapper.readTree(it) }.getOrNull() }
            ?: return ShortsPage(emptyList(), 0)
        val videos = root.get("videos") as? ArrayNode ?: return ShortsPage(emptyList(), 0)
        val cards = ArrayList<SlashCard>(videos.size())
        for (i in 0 until videos.size()) {
            val node = videos.get(i)
            val id = node.textOrNull("item_id") ?: continue
            val title = node.textOrNull("title") ?: continue
            cards.add(
                SlashCard(
                    id = id,
                    title = title,
                    poster = image(node.textOrNull("poster")) ?: image(node.textOrNull("image")),
                    quality = quality(node.textOrNull("quality")),
                    duration = node.textOrNull("duration")?.toIntOrNull(),
                )
            )
        }
        return ShortsPage(cards, videos.size())
    }

    /** The cards of one feed page — [shortsPage] when only the rows are needed. */
    fun shortsCards(json: String?): List<SlashCard> = shortsPage(json).cards

    /**
     * A field's text, or null when the key is absent **or an explicit JSON null**. Jackson's
     * `NullNode.asText()` is the literal `"null"`: read as a value it would route to
     * `/watch/null` and shadow the `image` poster fallback.
     */
    private fun JsonNode?.textOrNull(key: String): String? =
        this?.get(key)?.takeUnless { it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun image(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }

    /**
     * The plot: `og:description` when the site ships it, else the plain meta description. The
     * live pages carry only the latter, prefixed with the site name — kept verbatim rather than
     * edited.
     */
    fun description(document: Document): String? =
        (document.selectFirst("meta[property=og:description]")
            ?: document.selectFirst("meta[name=description]"))
            ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

    /** `og:video:tag` in page order: blanks dropped, duplicates collapsed. */
    fun tags(document: Document): List<String> =
        document.select("meta[property=og:video:tag]")
            .map { it.attr("content").trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * Runtime in minutes (CloudStream convention) from `og:duration` — the live pages ship bare
     * seconds, KVS variants ship ISO-8601 — falling back to the ld+json `VideoObject` duration.
     * A sub-minute value becomes null: a floored 0 is not a runtime.
     */
    fun durationMinutes(ogDuration: String?, jsonLd: String?): Int? {
        val raw = ogDuration?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            val minutes =
                if (raw.startsWith("P", ignoreCase = true)) JsonLdParse.minutes(raw)
                else DurationParse.fromSeconds(raw) ?: DurationParse.minutes(raw)
            if (minutes != null) return minutes
        }
        return JsonLdParse.minutes(jsonLd)
    }

    /**
     * The related rail: up to [limit] cards, the video itself excluded — the site's rail omits it
     * today, but a self-link is never a recommendation.
     */
    fun recommendations(
        document: Document,
        currentId: String? = null,
        limit: Int = RECOMMENDATION_LIMIT,
    ): List<SlashCard> =
        document.select(RELATED)
            .mapNotNull { card(it) }
            .filter { currentId == null || it.id != currentId }
            .take(limit)

    /**
     * Page-N URL for a listing surface: `?p=N` and nothing else.
     *
     * Path paging is dead on this site (`/search/<q>/2/` and `/page/2/` answer 404), and the
     * pager is JS-built, so the query param is the entire contract. Page 1 is the bare
     * surface — a stale `p=` is dropped rather than forwarded.
     *
     * The query is rebuilt pair by pair rather than regex-replaced: a surface that carries
     * another param (`...?p=1&sort=new`, S5's filters) keeps both its `?` and that param.
     */
    fun pageUrl(surface: String, page: Int): String {
        val q = surface.indexOf('?')
        val path = if (q >= 0) surface.substring(0, q) else surface
        val params = (if (q >= 0) surface.substring(q + 1) else "")
            .split('&')
            .filter { it.isNotBlank() && !it.startsWith("p=") }
            .toMutableList()
        if (page > 1) params.add("p=$page")
        return if (params.isEmpty()) path else path + "?" + params.joinToString("&")
    }

    /**
     * Thumbnail URL → its hover-preview clip: `/t/<encid>.<ext>` becomes `/p/<encid>.mp4`,
     * host (the per-video `*.pages.dev` project) and `<ptid>` path segment preserved. Null when
     * the URL is not that shape — a clip is never derived from something that is not a
     * thumbnail.
     *
     * The same mapping is the card's hover preview (`div.thumbnail[data-p]`), which is why the
     * result is a **10-second teaser** (`mvhd` duration 10.01s on a 54-minute page) and not a
     * play source — see [hlsMaster].
     */
    fun mp4FromThumb(ogImage: String?): String? {
        val url = ogImage?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val mp4 = THUMB.replace(url) { m -> "/p/${m.groupValues[1]}.mp4" }
        return mp4.takeIf { it != url }
    }

    /**
     * `data-quality` is the truth: `"480"` → `"480p"`, `"1080p"` → `"1080p"`. No ladder is
     * invented when the source has no number (absent, blank or `"HD"` → null): a fabricated
     * quality label misleads the player.
     */
    fun quality(raw: String?): String? =
        raw?.let { DIGITS.find(it) }?.groupValues?.get(1)?.let { "${it}p" }

    /**
     * Pagination stop rule: a full page means another page may exist, a partial page is an
     * unambiguous stop. Never probe `last + 1` — `/videos/?p=9999` and `/cat/<slug>/?p=9999`
     * answer 500 and `/search/<q>/?p=9999` answers 404 on this site.
     */
    fun hasNext(count: Int, pageSize: Int): Boolean = pageSize > 0 && count == pageSize

    private fun encodeQuery(query: String): String =
        java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")

    private fun clockSeconds(text: String?): Int? {
        val m = CLOCK.find(text?.trim().orEmpty()) ?: return null
        val (h, min, s) = m.destructured
        return (h.toIntOrNull() ?: 0) * 3600 + min.toInt() * 60 + s.toInt()
    }
}
