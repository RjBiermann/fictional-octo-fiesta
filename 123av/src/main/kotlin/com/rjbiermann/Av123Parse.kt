// Pure Parse seam for 123AV (123av.com) — jsoup in, values out; no HTTP, no framework types
// (ADR-0005). Fixtures under src/test/resources/ are live captures from 2026-11-27.
//
// The site is a Laravel + Alpine app. One listing grammar (`div.card`) serves the homepage
// rows, `/en/new`, `/en/hot`, maker pages and search — so this seam is the single card parser
// every surface reuses (S3's `parseSearch` is that same grammar, deliberately not a copy).
// Three traps the fixtures pin:
//  - the homepage renders an Alpine `<template x-for>` prototype card (`:href`, `:src`,
//    `x-text`) next to the real cards. It carries no href/src values, so requiring a real
//    href drops it instead of emitting a null card.
//  - pagination is the pager's own `a[rel=next]`, not "this page looked full": the site pages
//    by offset over a growing list, so a boundary card can repeat (S1 captured 1/12 on
//    `/en/new`) and a full last page is not proof that another page exists.
//  - `/en/makers` is an `a.mcard` grid, not a card listing: it needs its own seam, and its
//    names are the only place the eleven ura row labels come from (never invented).
package com.rjbiermann

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kraptor.DurationParse
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** One listing card: the fields every 123AV surface (home rows, maker, search) exposes. */
data class Av123Card(
    /** The `/en/v/<slug>` slug — the site's video identity, used to dedupe pages. */
    val id: String,
    val title: String,
    /** Absolute watch URL: [Av123Parse.MAIN] plus the card's own `href`. */
    val url: String,
    val poster: String?,
    /** Runtime in seconds from the `.card__dur` clock; null when the card has no badge. */
    val duration: Int?,
)

/** One studio on the `/en/makers` index: the slug, the site's own name, and its listing URL. */
data class Av123Maker(val slug: String, val name: String, val url: String)

/**
 * One ura home row: a studio's paginated `/en/makers/<slug>` listing and the name the site
 * renders for it ([Av123Parse.MAKER_ROWS]). The label is the site's own string — `mcard__name`
 * on the index, or the nav link text for `xxx-av`, which sits beyond index page 1.
 */
data class Av123MakerRow(val slug: String, val label: String)

/** One episode from the inlined S1 payload: `number`, `name`, and the embed-page `url`. */
data class Av123Episode(val number: Int?, val name: String?, val url: String)

/** The `/stream` media response the embed player binds to. */
data class Av123Stream(val url: String, val vtt: String?)

/**
 * One video page's exposed metadata plus the episode payload. Every nullable/empty field is
 * genuinely absent from the page — the parse never invents a title, poster, date or cast member.
 * Japanese titles are not present on the video page (the recommendation API carries
 * `title_ja` for other titles only), so [title] is the page's own English string.
 */
data class Av123Video(
    val id: String,
    val title: String,
    val poster: String?,
    val plot: String?,
    val tags: List<String>,
    val actors: List<String>,
    val maker: String?,
    val series: String?,
    val durationMinutes: Int?,
    val releaseDate: String?,
    val year: Int?,
    val episodes: List<Av123Episode>,
    val recommendations: List<Av123Card>,
)

object Av123Parse {

    const val MAIN = "https://123av.com"

    /** Row 1 — the `New Releases` row on `/en`, i.e. that row's own "See all" target. */
    const val NEW = "$MAIN/en/new"

    /** Row 2 — the site's most-viewed ordering (nav label `Hot`). */
    const val HOT = "$MAIN/en/hot"

    /** The search surface: every page's GET form is `action="/en/search" name="keyword"`. */
    const val SEARCH = "$MAIN/en/search"

    /** The one listing-card root (home rows, `/en/new`, `/en/hot`, makers, search). */
    const val CARD = "div.card"

    /** The makers index grid (`/en/makers`), one `a.mcard` per studio. */
    const val MCARD = "a.mcard"

    /**
     * The eleven genuinely-uncensored (ura) studios — the reason this provider exists (#1423).
     * Order is the issue's own; every slug is a live `/en/makers/<slug>` page (FINDINGS §Maker
     * rows), so no row is a renamed keyword search.
     */
    val MAKER_ROWS = listOf(
        Av123MakerRow("1pondo", "1Pondo"),
        Av123MakerRow("caribbeancom", "Caribbeancom"),
        Av123MakerRow("heyzo", "HEYZO"),
        Av123MakerRow("takara-video", "Takara Video"),
        Av123MakerRow("10musume", "10musume"),
        Av123MakerRow("tokyo-hot", "Tokyo Hot"),
        Av123MakerRow("pacopacomama", "Pacopacomama"),
        Av123MakerRow("h4610", "H4610"),
        Av123MakerRow("c0930", "C0930"),
        Av123MakerRow("h0930", "H0930"),
        Av123MakerRow("xxx-av", "XXX-AV"),
    )

    /**
     * The `HomePage` rows: the two S2 site listings first (their order is unchanged), then one
     * row per ura studio. Kept here rather than in the provider so the composition and its order
     * are data a fixture-free unit test can pin.
     */
    fun homeRows(): List<Pair<String, String>> =
        listOf(NEW to "New Releases", HOT to "Hot") +
            MAKER_ROWS.map { makerUrl(it.slug, 1) to it.label }

    /** The pager's own next-page link; absent on the last page and on the homepage. */
    const val NEXT = "div.pager a[rel=next]"

    /** Live page size on every listing surface: 12 cards (S1 probes, 2026-11-26). */
    const val PAGE_SIZE = 12

    /** `/en/v/<slug>` → the slug. Every card emits two anchors (cover + title), same href. */
    private val SLUG = Regex("""/v/([^/?#]+)""")

    /** `/en/makers/<slug>` → the studio slug on the makers index grid. */
    private val MAKER_SLUG = Regex("""/makers/([^/?#]+)""")

    private fun String?.present(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A captured listing page → its cards; one broken card is dropped, never the whole page.
     * The spec's seam name — parsing from the raw body keeps the function honest to its input.
     */
    fun parseCards(html: String): List<Av123Card> = cards(Jsoup.parse(html, MAIN))

    /** An already-parsed page → its cards (the production path, so a row is parsed once). */
    fun cards(document: Document): List<Av123Card> = document.select(CARD).mapNotNull { card(it) }

    /**
     * A search result page → its cards. Same `div.card` grammar as every listing (asserted in
     * the tests), so a selector fix on `card()` can never drift search away from the rows.
     */
    fun parseSearch(html: String): List<Av123Card> = cards(Jsoup.parse(html, MAIN))

    /**
     * The `/en/makers` index → its studios. Empty rows are dropped rather than emitted half
     * filled: a name or a `/en/makers/` href is what makes a studio addressable as a row.
     */
    fun parseMakers(html: String): List<Av123Maker> = makers(Jsoup.parse(html, MAIN))

    /** An already-parsed makers index → its studios. */
    fun makers(document: Document): List<Av123Maker> = document.select(MCARD).mapNotNull { el ->
        val slug = MAKER_SLUG.find(el.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
        val name = el.selectFirst(".mcard__name")?.text().present() ?: return@mapNotNull null
        Av123Maker(slug = slug, name = name, url = "$MAIN/en/makers/$slug")
    }

    /**
     * The cards of one page with the boundary overlap removed: [previous] is the page that
     * precedes it. Paging is offset-based over a growing list, so a boundary card can be
     * re-served on the next page (S1 measured 1/12 on `/en/new`) — the overlap is that boundary
     * card, so the immediately preceding page is the whole seen-set it needs.
     *
     * Deliberately **stateless**: no set outlives the call. A per-surface set kept on the
     * provider made a repeated page request return an empty page and let two concurrent searches
     * consume each other's cards; here a repeat re-derives the same cards and nothing is shared.
     * Cards duplicated *within* one page are dropped too.
     */
    fun fresh(cards: List<Av123Card>, previous: List<Av123Card>): List<Av123Card> {
        val served = previous.mapTo(HashSet(previous.size)) { it.id }
        return cards.distinctBy { it.id }.filter { it.id !in served }
    }

    /**
     * One `div.card` → [Av123Card]; null when the card has no real `/en/v/` href or no title,
     * which is also what drops the homepage's Alpine `<template>` prototype card.
     *
     * Field chain (probed on the fixtures, S2 findings §Listing):
     *  - href: `a.card__cover[href]`, then `a.card__link[href]` — the same URL on both;
     *  - title: `h3.card__title` text, then the link text. Left verbatim: the code prefix and
     *    the `—` description are the site's own title string (S1: do not "fix" it);
     *  - poster: `img.card__img[src]`, then any `img[src]` inside `.card__poster`; `data:` URIs
     *    skipped, never fabricated;
     *  - duration: the `span.card__dur` clock, null when the badge is absent.
     */
    fun card(el: Element): Av123Card? {
        val href = el.selectFirst("a.card__cover[href]")?.attr("href").present()
            ?: el.selectFirst("a.card__link[href]")?.attr("href").present()
            ?: return null
        val slug = SLUG.find(href)?.groupValues?.get(1) ?: return null
        val title = el.selectFirst("h3.card__title")?.text().present()
            ?: el.selectFirst("a.card__link")?.text().present()
            ?: return null
        return Av123Card(
            id = slug,
            title = title,
            url = if (href.startsWith("http")) href else MAIN + href,
            poster = poster(el),
            duration = duration(el.selectFirst("span.card__dur")?.text()),
        )
    }

    /**
     * `?page=N`, matching the pager's own form (`/en/new?page=2`). Page 1 is the bare surface:
     * the pager renders page 1 without an href, and a stale `page=` is worse than none.
     */
    fun pageUrl(surface: String, page: Int): String =
        if (page <= 1) surface else "$surface?page=$page"

    /**
     * `/en/search?keyword=<urlencoded>` plus `&page=N` — page 1 is the bare query, exactly as
     * the site's pager links it (`/en/search?keyword=1pondo`, then `…&page=2`).
     */
    fun searchUrl(query: String, page: Int): String {
        val base = "$SEARCH?keyword=" + URLEncoder.encode(query, "UTF-8")
        return if (page <= 1) base else "$base&page=$page"
    }

    /** A maker row's listing: `/en/makers/<slug>`, paged by the site's own `?page=` param. */
    fun makerUrl(slug: String, page: Int): String = pageUrl("$MAIN/en/makers/$slug", page)

    /**
     * "another page exists", read from the site's pager rather than from a full row — the list
     * is offset-paged and grows, so a 12-card page is neither necessary nor sufficient.
     */
    fun hasNext(document: Document): Boolean = document.selectFirst(NEXT) != null

    /** `H:MM:SS` / `MM:SS` → seconds; null for blank or unparseable text. */
    fun duration(text: String?): Int? {
        val match = CLOCK.find(text.present().orEmpty()) ?: return null
        val (hours, minutes, seconds) = match.destructured
        return (hours.toIntOrNull() ?: 0) * 3600 + minutes.toInt() * 60 + seconds.toInt()
    }

    private val CLOCK = Regex("""^(?:(\d+):)?(\d+):(\d+)$""")

    private fun poster(el: Element): String? {
        for (selector in listOf("img.card__img", ".card__poster img")) {
            val src = el.selectFirst(selector)?.attr("src").present() ?: continue
            if (src.startsWith("data:")) continue
            return if (src.startsWith("http")) src else MAIN + src
        }
        return null
    }
    // ---- video page + episodes (S4) ---------------------------------------------

    private val JSON = ObjectMapper()

    /** `player(JSON.parse('<escaped episodes>'), titleId, '<slug>', …)` in `x-data`. */
    private val PLAYER_JSON = Regex("""JSON\.parse\(\s*'((?:\\.|[^'\\])*)'\s*\)""", RegexOption.DOT_MATCHES_ALL)

    /** The third argument of the same call is the slug the page itself uses. */
    private val PLAYER_CODE = Regex(
        """JSON\.parse\(\s*'(?:\\.|[^'\\])*'\s*\)\s*,\s*\d+\s*,\s*'([^']+)'""",
    )

    /** `style="background-image:url('…')"` on the player (the cover, not the generic og:image). */
    private val STYLE_URL = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""")

    /** The `/e/<hash>` path the embed script turns into `GET /stream?id=<hash>`. */
    private val EMBED_ID = Regex("""/e/([A-Za-z0-9_]+)""")

    /** The media CDN's own master-playlist resolution, used only for a quality label. */
    private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")

    /** `poster=` query value on the episode URL (percent-encoded cover). */
    private val POSTER_PARAM = Regex("""[?&]poster=([^&]+)""")

    /**
     * A captured video page → its metadata. Null when the page has no `h1.watch__title`, which
     * is how a 404 / wall page (the site's Laravel error page) is rejected before any field is
     * fabricated. The episode list is inline: the player `x-data` is a JS string literal around
     * the JSON array, so it is JS-unescaped first, then read by [parseEpisode] exactly as the
     * committed S1 fixture is.
     */
    fun parseVideo(html: String): Av123Video? = video(Jsoup.parse(html, MAIN))

    /** An already-parsed video page → its metadata. */
    fun video(document: Document): Av123Video? {
        val title = document.selectFirst("h1.watch__title")?.text().present() ?: return null
        val xData = document.select("[x-data]")
            .firstOrNull { it.attr("x-data").contains("JSON.parse(") }
            ?.attr("x-data")
            .orEmpty()
        val episodes = PLAYER_JSON.find(xData)
            ?.groupValues
            ?.get(1)
            ?.let { parseEpisode(unescapeJs(it)) }
            .orEmpty()
        val code = PLAYER_CODE.find(xData)?.groupValues?.get(1)?.present()
        val info = infoRows(document)
        val durationText = info["duration"]?.text().present()
        val releaseDate = info["release date"]?.text().present()

        return Av123Video(
            id = code ?: info["code"]?.text()?.lowercase().orEmpty(),
            title = title,
            poster = playerPoster(document) ?: episodes.firstOrNull()?.url?.let(::posterFromQuery),
            plot = document.selectFirst("p.watch__desc-text")?.text().present(),
            tags = chips(info["genres"]),
            actors = chips(info["cast"]),
            maker = chips(info["maker"]).firstOrNull(),
            series = chips(info["series"]).firstOrNull(),
            durationMinutes = DurationParse.minutes(durationText),
            releaseDate = releaseDate,
            year = releaseDate?.take(4)?.toIntOrNull(),
            episodes = episodes,
            recommendations = related(document),
        )
    }

    /**
     * The S1 episode shape: `[{"number":1,"name":"1","url":"https://<embed>/e/<hash>?poster=…"}]`.
     * Reads the decoded JSON directly (the fixture is exactly that), keeps source order, and drops
     * an entry without a URL instead of emitting an unusable stream reference. Junk is empty, never
     * an exception — a page with no player must not crash `load()`.
     */
    fun parseEpisode(apiJson: String): List<Av123Episode> {
        // The team passes the decoded JSON (fixture), but accepting the raw `JSON.parse('…')`
        // argument too keeps the seam usable by a caller that has only the page attribute.
        val root = runCatching { JSON.readTree(apiJson) }.getOrNull()
            ?: runCatching { JSON.readTree(unescapeJs(apiJson)) }.getOrNull()
            ?: return emptyList()
        val nodes: List<JsonNode> = when {
            root.isArray -> root.toList()
            root.has("episodes") && root.get("episodes").isArray -> root.get("episodes").toList()
            else -> emptyList()
        }
        return nodes.mapNotNull { node ->
            val url = node.path("url").asText("").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            Av123Episode(
                number = node.get("number")?.takeIf { it.isNumber }?.asInt(),
                name = node.path("name").asText("").trim().takeIf { it.isNotEmpty() },
                url = url,
            )
        }
    }

    /** The `/stream` response: `{"status":"ok","media":{"stream":"…","vtt":"…"}}`. */
    fun parseStream(apiJson: String): Av123Stream? {
        val root = runCatching { JSON.readTree(apiJson) }.getOrNull() ?: return null
        if (root.path("status").asText("") != "ok") return null
        val media = root.get("media") ?: return null
        val url = media.path("stream").asText("").trim()
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null
        val vtt = media.path("vtt").asText("").trim().takeIf { it.isNotEmpty() }
        return Av123Stream(url, vtt)
    }

    /**
     * ExtractorLink type from the response Content-Type, never from the URL suffix: the media CDN
     * serves HLS, but its segments are deliberately named `.css`/`.svg` and typed `text/css`, so a
     * suffix (or a "looks like a playlist" guess) would mislabel the stream.
     */
    fun streamType(contentType: String?): ExtractorLinkType? {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return when {
            type.contains("mpegurl") -> ExtractorLinkType.M3U8
            type.startsWith("video/") || type == "application/mp4" -> ExtractorLinkType.VIDEO
            else -> null
        }
    }

    /** Quality from a master playlist's `RESOLUTION=WxH`; unknown when the playlist is absent. */
    fun quality(masterPlaylist: String?): Int {
        val height = masterPlaylist?.let { RESOLUTION.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: return Qualities.Unknown.value
        return when {
            height >= 2160 -> Qualities.P2160.value
            height >= 1440 -> Qualities.P1440.value
            height >= 1080 -> Qualities.P1080.value
            height >= 720 -> Qualities.P720.value
            height >= 480 -> Qualities.P480.value
            height >= 360 -> Qualities.P360.value
            height >= 240 -> Qualities.P240.value
            else -> Qualities.P144.value
        }
    }

    /** The embed origin + `/e/<hash>` the episode URL carries — the host rotates per video. */
    fun embedOrigin(episodeUrl: String): String? = runCatching {
        val uri = URI(episodeUrl)
        val scheme = uri.scheme ?: return@runCatching null
        val authority = uri.authority ?: return@runCatching null
        "$scheme://$authority"
    }.getOrNull()

    /** The `<hash>` the embed script passes to `GET /stream?id=<hash>`. */
    fun embedId(episodeUrl: String): String? =
        runCatching { URI(episodeUrl).path }.getOrNull()?.let { EMBED_ID.find(it)?.groupValues?.get(1) }

    private fun infoRows(document: Document): Map<String, Element> =
        document.select("dl.watch__info div.watch__info-row").mapNotNull { row ->
            val label = row.selectFirst("dt")?.text()?.trim()?.lowercase() ?: return@mapNotNull null
            val value = row.selectFirst("dd") ?: return@mapNotNull null
            label to value
        }.toMap()

    private fun chips(row: Element?): List<String> =
        row?.select("a.chip")?.map { it.text().trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun playerPoster(document: Document): String? {
        val style = document.selectFirst("div.player")?.attr("style") ?: return null
        val url = STYLE_URL.find(style)?.groupValues?.get(1)?.trim() ?: return null
        return absolute(url)
    }

    private fun posterFromQuery(episodeUrl: String): String? =
        POSTER_PARAM.find(episodeUrl)?.groupValues?.get(1)?.let {
            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
        }?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    private fun related(document: Document): List<Av123Card> =
        document.select("aside.watch-side a.vside").mapNotNull { el ->
            val href = el.attr("href").present() ?: return@mapNotNull null
            val slug = SLUG.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = el.selectFirst(".vside__title")?.text().present() ?: return@mapNotNull null
            val style = el.selectFirst(".vside__thumb")?.attr("style")
            val poster = style?.let { STYLE_URL.find(it)?.groupValues?.get(1) }?.let(::absolute)
            Av123Card(
                id = slug,
                title = title,
                url = if (href.startsWith("http")) href else MAIN + href,
                poster = poster,
                duration = duration(el.selectFirst(".vside__dur")?.text()),
            )
        }

    private fun absolute(url: String?): String? {
        val value = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (value.startsWith("http")) value else MAIN + value
    }

    /**
     * JS single-quoted string literal → the JSON text inside it. The page writes `\u0022` for
     * quotes and `\\\/` for slashes; JSON.parse receives the result of this unescape, so dropping
     * either rule makes every episode URL unreadable.
     */
    private fun unescapeJs(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 >= value.length) {
                out.append(c)
                i++
                continue
            }
            when (val escaped = value[i + 1]) {
                'u' -> {
                    val hex = value.substring(i + 2, minOf(i + 6, value.length))
                    val code = hex.toIntOrNull(16)
                    if (hex.length == 4 && code != null) {
                        out.append(code.toChar())
                        i += 6
                    } else {
                        out.append(escaped)
                        i += 2
                    }
                }
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                else -> { out.append(escaped); i += 2 }
            }
        }
        return out.toString()
    }

}
