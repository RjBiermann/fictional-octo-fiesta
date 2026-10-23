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
     *  hook are new (2026-10); the video id in arg 1 equals the data= param.
     *  [expectedId] is the player URL's `data=` id, used to resolve the argument
     *  pair structurally when the page's formatting/order drifts (#908). */
    fun xsftkBoot(playerHtml: String) = XsftkParse.xsftkBoot(playerHtml)

    fun xsftkBoot(playerHtml: String, expectedId: String?) =
        XsftkParse.xsftkBoot(playerHtml, expectedId)

    /** Per-variant fallback chain for one player page (#908): booted gated page →
     *  XSFTK chain, gated page without a boot call → fail closed (its tokenless
     *  xs1.php is the 404 decoy), pre-gate page → live legacy master. */
    fun playerPlan(playerUrl: String, playerHtml: String) =
        XsftkParse.playerPlan(playerUrl, playerHtml)

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

    // ---- video-page embeds (issue #908) --------------------------------------

    /** Non-player iframes the WP theme renders on every video page (Discord month
     *  widget, hidden Gravity Forms frame). Structural host match: subdomains count. */
    private val widgetHosts = listOf(
        "discord.com", "discordapp.com", "youtube.com", "youtu.be",
        "facebook.com", "twitter.com", "x.com", "instagram.com",
        "telegram.org", "t.me", "podbean.com", "spotify.com"
    )

    /** The video's player iframe, or null. The xtremestream host varies per video
     *  (pervlN/pervmN/jN) and the #906 census found one video on another embed host
     *  (mixdrop.to), so pick structurally: known player family first, else the first
     *  iframe that is not a known non-player widget. Protocol-relative `//host/...`
     *  srcs are normalized to https for the extractor. */
    fun playerEmbed(doc: Document): String? {
        val srcs = doc.select("iframe[src]").map { it.attr("src").trim() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith("//")) "https:$it" else it }
        return srcs.firstOrNull { it.contains("xtremestream.xyz") }
            ?: srcs.firstOrNull { isPlayerEmbedUrl(it) }
    }

    /** http(s) URL whose host is not one of the theme's known non-player widgets. */
    private fun isPlayerEmbedUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        return host.isNotEmpty() && widgetHosts.none { host == it || host.endsWith(".$it") }
    }
}
