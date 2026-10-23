package com.kraptor

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Pure parsing helpers for issue #273 (recommendations) — test-safe, no framework types. */
object PerverZijaParse {

    /** Related-videos cards. The old div.srelacionados block is gone from the site;
     *  the WP theme now serves div.xs-related-item (title link + poster img). */
    fun related(doc: Document): List<Element> = doc.select("div.xs-related-item").toList()

    /** Home/search listing cards. The bare div#sidebar widget column is also a
     *  div.col-md-3 (issue #333) — excluding it by id keeps video-card rows for
     *  both live variants (col-sm-3 on search, col-sm-6 col-xs-6 on home). */
    fun listingCards(doc: Document): List<Element> =
        doc.select("div.col-md-3:not(#sidebar)").toList()

    /** Title from div.xs-related-title a — img alt/title repeat the host page's
     *  title on the live site, so they must never be used. */
    fun titleOf(item: Element): String? =
        item.selectFirst("div.xs-related-title a")?.text()?.trim()?.takeIf { it.isNotEmpty() }

    /** Poster from the item's img (plain src; no data-src on this markup). */
    fun posterOf(item: Element): String? =
        item.selectFirst("a img")?.attr("src")?.takeIf { it.isNotEmpty() }

    // ---- XSFTK signed-token layer (issue #846 probe, #840) -------------------
    // Issue #845: the implementation moved into shared XsftkParse.kt — the root-cause
    // adapter (shared PerverZijaExtractor) builds the synthesized playlists with the
    // same helpers, so the protocol lives in one file spliced into every provider
    // (ADR-0002). These delegates pin the #846 API surface for the probe tests.

    /** Player boot call exposed in plain text on the player (index.php) page:
     *  XSFTK.boot(<"hex" id>, "<hex>.<expiry>", player). The token and the audio
     *  hook are new (2026-10); the video id in arg 1 equals the data= param. */
    fun xsftkBoot(playerHtml: String) = XsftkParse.xsftkBoot(playerHtml)

    /** Structural FTK-gate detection (markers and/or a live boot call). A gated page
     *  whose boot call is missed keeps this true so the legacy xs1.php decoy is never
     *  emitted for it (FINDINGS-906). */
    fun isGatedPlayer(playerHtml: String) = XsftkParse.isGatedPlayer(playerHtml)

    /** Opaque per-video key/tokens from the signed-token API; header X-XS-P: 1 is
     *  required on every op (403 without it); referer is not. */
    fun protoApiUrl(playerUrl: String, boot: XsftkParse.Boot, op: String, extra: String = "") =
        XsftkParse.protoApiUrl(playerUrl, boot, op, extra)

    /** op=manifest answers octet-stream bytes XORed with SHA-256("M|<id>|<token>")
     *  (JS: crypto.subtle.digest… TextDecoder → JSON.parse). Returns the JSON text
     *  or null for anything that does not decode to an object. */
    fun decodeXsManifest(raw: ByteArray, boot: XsftkParse.Boot) =
        XsftkParse.decodeXsManifest(raw, boot)
}
