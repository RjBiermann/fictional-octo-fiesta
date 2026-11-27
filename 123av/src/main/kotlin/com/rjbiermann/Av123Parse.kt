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

    /** An already-parsed search page → its cards (the production path). */
    fun search(document: Document): List<Av123Card> = cards(document)

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
}
