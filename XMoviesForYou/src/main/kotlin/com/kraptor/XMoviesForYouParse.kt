// Parse function module for XMoviesForYou (issue #535): the 2026-10 site markup serves
// three root-anchor card shapes — home hero carousel `a[data-video-card]`, the still-live
// home/category grid `a.group.flex.flex-col`, and rebuilt search `/new-search` `a.card`.
// All three share the extraction: title in the card's `h3`, poster in the first `img`,
// link in the root anchor's own href. Pure logic only — HTTP flows stay covered by
// pipeline Verification (FINDINGS-535.md is the ground truth).
package com.kraptor

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class XmfyCard(val title: String, val href: String, val poster: String?)

object XMoviesForYouParse {

    /** Card grammar matching every live listing surface (FINDINGS-535.md Homepage). */
    const val CARD = "a[data-video-card], a.card, a.group.flex.flex-col"

    /** Studio bracket prefix («[FakeTaxi] Mackenzie Page (…)») — keep it scraped/present. */
    private val STUDIO_BRACKET = Regex("""\[.*?]""")

    /** One root-anchor card → title/href/poster; null when the card lacks title or link. */
    fun parse(card: Element): XmfyCard? {
        val title = card.selectFirst("h3")?.text()?.trim()
            ?.replace(STUDIO_BRACKET, "")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val href = card.attr("href").takeIf { it.isNotBlank() } ?: return null
        val poster = card.selectFirst("img")?.attr("src")
            ?.takeUnless { it.startsWith("data:") || it.isBlank() }
        return XmfyCard(title, href, poster)
    }

    /** All listing cards on one page, deduped by href — the home carousel repeats items. */
    fun cards(document: Document): List<XmfyCard> =
        document.select(CARD).mapNotNull { parse(it) }.distinctBy { it.href }
}
