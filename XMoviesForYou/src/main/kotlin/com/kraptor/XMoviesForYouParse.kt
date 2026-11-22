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

/** The watch page's own player config: `/api/stream/<videoId>?target=<target>` → HLS URL. */
data class XmfyStreamEndpoint(val videoId: String, val target: String)

object XMoviesForYouParse {

    /** Card grammar matching every live listing surface (FINDINGS-535.md Homepage). */
    const val CARD = "a[data-video-card], a.card, a.group.flex.flex-col"

    /** The watch page's download panel heading — the anchor every mirror chip sits after. */
    private val DOWNLOADS_PANEL = "#downloads ~ div a[href]"

    private val VIDEO_ID = Regex("""videoId\s*=\s*["']([^"']+)["']""")
    private val TARGET = Regex("""target\s*=\s*["']([^"']+)["']""")

    /** Dood-family landing path (`/d/<id>`) — the player itself lives at `/e/<id>` (#1446). */
    private val DOOD_LANDING = Regex("""/d/([A-Za-z0-9]+)(?=$|[?#])""")

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

    /**
     * The site-hosted HLS player config (`const videoId = "…"; const target = "…";`) — the
     * watch page's primary source. Only newer posts carry it; older uploads ship download
     * chips alone, so a null here is a normal shape, not a defect (FINDINGS-1446).
     */
    fun streamEndpoint(document: Document): XmfyStreamEndpoint? {
        val js = document.select("script").joinToString("\n") { it.data() }
        val id = VIDEO_ID.find(js)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val target = TARGET.find(js)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return XmfyStreamEndpoint(id, target)
    }

    /**
     * Every mirror chip on a watch page, in page order (FINDINGS-1446). Host-agnostic on
     * purpose: the chip hosts rotate between page loads — mxdrop.click, m1xdrop.bz,
     * mixdrp.click, miiixdrop.net in one sweep, and streamtapeadblockuser.art in FINDINGS-535 —
     * so matching host names (the pre-#1446 selector) always lags one rotation behind. Two
     * structural grammars, unioned: the anchors inside the `#downloads` panel, and any anchor
     * carrying the panel's `download` icon glyph (survives a panel heading whose id is gone).
     */
    fun streamLinks(document: Document): List<String> {
        val anchors = document.select(DOWNLOADS_PANEL) +
            document.select("a[href]").filter { isDownloadChip(it) }
        return anchors.map { it.attr("href").trim() }
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()
    }

    /** A chip anchor carries the download glyph as its icon span (`…outlined">download</span>`). */
    private fun isDownloadChip(anchor: Element): Boolean =
        anchor.selectFirst("span.material-symbols-outlined")?.text()?.trim()
            ?.equals("download", ignoreCase = true) == true

    /**
     * `/d/<id>` → `/e/<id>`: a Dood-family landing page wraps the player (and its pass_md5
     * token) one path segment deeper, so the registered host rows see the player path.
     */
    fun playerPath(url: String): String = url.replace(DOOD_LANDING, "/e/$1")
}
