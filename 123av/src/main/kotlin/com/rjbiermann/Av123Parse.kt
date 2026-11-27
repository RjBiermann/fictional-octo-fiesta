// Pure Parse seam for 123AV (123av.com) — jsoup in, values out; no HTTP, no framework types
// (ADR-0005). Fixtures under src/test/resources/ are live captures from 2026-11-27.
//
// The site is a Laravel + Alpine app. One listing grammar (`div.card`) serves the homepage
// rows, `/en/new`, `/en/hot`, maker pages and search — so this seam is the single card parser
// the later stories reuse. Two traps the fixtures pin:
//  - the homepage renders an Alpine `<template x-for>` prototype card (`:href`, `:src`,
//    `x-text`) next to the real cards. It carries no href/src values, so requiring a real
//    href drops it instead of emitting a null card.
//  - pagination is the pager's own `a[rel=next]`, not "this page looked full": the site pages
//    by offset over a growing list, so a boundary card can repeat (S1 captured 1/12 on
//    `/en/new`) and a full last page is not proof that another page exists.
package com.rjbiermann

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

object Av123Parse {

    const val MAIN = "https://123av.com"

    /** Row 1 — the `New Releases` row on `/en`, i.e. that row's own "See all" target. */
    const val NEW = "$MAIN/en/new"

    /** Row 2 — the site's most-viewed ordering (nav label `Hot`). */
    const val HOT = "$MAIN/en/hot"

    /** The one listing-card root (home rows, `/en/new`, `/en/hot`, makers, search). */
    const val CARD = "div.card"

    /** The pager's own next-page link; absent on the last page and on the homepage. */
    const val NEXT = "div.pager a[rel=next]"

    /** Live page size on every listing surface: 12 cards (S1 probes, 2026-11-26). */
    const val PAGE_SIZE = 12

    /** `/en/v/<slug>` → the slug. Every card emits two anchors (cover + title), same href. */
    private val SLUG = Regex("""/v/([^/?#]+)""")

    private fun String?.present(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A captured listing page → its cards; one broken card is dropped, never the whole page.
     * The spec's seam name — parsing from the raw body keeps the function honest to its input.
     */
    fun parseCards(html: String): List<Av123Card> = cards(Jsoup.parse(html, MAIN))

    /** An already-parsed page → its cards (the production path, so a row is parsed once). */
    fun cards(document: Document): List<Av123Card> = document.select(CARD).mapNotNull { card(it) }

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
