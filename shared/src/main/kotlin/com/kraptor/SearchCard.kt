// Deep parse module for the repo's card → SearchResponse shape (glossary: Parse function).
// One home for the null-guards, lazyload poster fallback and `data:`-placeholder skip that
// were duplicated in ~15 providers' private Element.toSearchResult copies. Callers pass
// FINDINGS selectors; site quirks (attribute-title fallbacks, gated cards) stay local as
// documented exceptions around this module. The Parse function is pure and fixture-tested;
// the MainAPI adapter applies fixUrl + emission (MainAPI flows stay with Verification).
package com.kraptor

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newMovieSearchResponse
import org.jsoup.nodes.Element

/** The per-video identity fields resolved from one listing card. */
data class CardFields(val title: String, val href: String, val poster: String?)

object SearchCard {

    /**
     * Pure Parse function: resolves one listing card into [CardFields].
     *
     * @param card      the card element from the listing
     * @param titleSel  selector for the title anchor — if it is an `<a>`, its href is also
     *                  the card link (the common shape); otherwise pass [hrefSel]
     * @param hrefSel   explicit link selector for cards whose title and href come from
     *                  different elements (defaults to the title anchor's href, then the
     *                  card's first `<a>` that carries the card link — the
     *                  card-root-wraps-the-link theme; anchor-only links (tags, actors,
     *                  categories) and their wrappers are skipped so a title is never
     *                  bound to an unrelated link)
     * @param posterSel poster `<img>` selector (defaults to the first `<img>` in the card)
     *
     * @return null when the card lacks a real title or link — one broken card must not
     *         kill the list. `poster` is null when absent; `data:` URIs and placeholder
     *         pixels are skipped in favor of the lazyload `data-src` value, never fabricated.
     */
    fun parse(
        card: Element,
        titleSel: String,
        hrefSel: String? = null,
        posterSel: String? = null,
    ): CardFields? {
        val anchor = card.selectFirst(titleSel) ?: return null
        val title = anchor.text().trim().takeIf { it.isNotEmpty() } ?: return null
        val href = (hrefSel?.let { card.selectFirst(it)?.attr("href") }
            ?: anchor.attr("href").takeIf { it.isNotEmpty() }
            ?: firstCardLink(card, title))
            ?.takeIf { it.isNotBlank() } ?: return null
        val poster = card.selectFirst(posterSel ?: "img")?.let { img ->
            img.attr("data-src").takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                ?: img.attr("src").takeIf { it.isNotEmpty() && !it.startsWith("data:") }
        }
        return CardFields(title, href, poster)
    }

    /**
     * Fallback href: the card's first `<a>` that actually carries the card link.
     *
     * P0-14: a bare first-`<a>` grab binds the title to the first anchor, which in many
     * card shapes is a tag/actor/category link. Such anchor-only links are skipped: an
     * `<a>` counts as the card link only when it carries evidence of being the video
     * link — it wraps an image or a title-ish element (the card-root-wraps-the-link
     * theme), or its text matches the card title (the bare-sibling video-link shape).
     * Review round on P0-14: evidence alone is not enough — a tag/category/actor
     * wrapper around a name-ish child would still admit the binding, so taxonomy
     * paths are excluded outright. Sites with video links under taxonomy-shaped
     * paths must pass an explicit [hrefSel] instead.
     * When no such link exists the card has no resolvable video link, so the fallback
     * yields null and the card is rejected — the title is never bound to an unrelated
     * tag/actor link. Cards whose title and video link are structurally unrelated pass
     * an explicit [hrefSel] instead of relying on this heuristic.
     */
    private fun firstCardLink(card: Element, title: String): String? =
        card.select("a").firstOrNull { a ->
            val href = a.attr("href")
            href.isNotBlank() && !TAXONOMY_HREF.containsMatchIn(href) && (
                a.select("img").isNotEmpty() ||
                    a.children().any {
                        it.tagName() != "a" && (
                            it.classNames().any { c -> c.contains("title", true) || c.contains("name", true) } ||
                                it.id().contains("title", true) || it.id().contains("name", true)
                            )
                    } ||
                    a.text().trim().equals(title, ignoreCase = true)
                )
        }?.attr("href")

    private val TAXONOMY_HREF = Regex(
        "/(tags?|categor(?:y|ies)|actors?|models?|pornstars?|labels?|studios?|channels?)/",
        RegexOption.IGNORE_CASE)
}

/** Adapter: SearchCard.parse + the repo's NSFW emission shape. */
fun MainAPI.searchCard(
    card: Element,
    titleSel: String,
    hrefSel: String? = null,
    posterSel: String? = null,
): SearchResponse? = SearchCard.parse(card, titleSel, hrefSel, posterSel)?.let { f ->
    newMovieSearchResponse(f.title, fixUrl(f.href), TvType.NSFW) {
        this.posterUrl = fixUrlNull(f.poster)
    }
}
