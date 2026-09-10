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
     *                  card's first `<a>` — the card-root-wraps-the-link theme)
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
            ?: card.selectFirst("a")?.attr("href"))
            ?.takeIf { it.isNotBlank() } ?: return null
        val poster = card.selectFirst(posterSel ?: "img")?.let { img ->
            img.attr("data-src").takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                ?: img.attr("src").takeIf { it.isNotEmpty() && !it.startsWith("data:") }
        }
        return CardFields(title, href, poster)
    }
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
