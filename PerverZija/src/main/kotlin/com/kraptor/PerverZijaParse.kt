package com.kraptor

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.security.MessageDigest

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

    /** Player boot call exposed in plain text on the player (index.php) page:
     *  XSFTK.boot(<"hex" id>, "<hex>.<expiry>", player). The token and the audio
     *  hook are new (2026-10); the video id in arg 1 equals the data= param. */
    data class XsftkBoot(val id: String, val token: String)

    private val xsftkBootRe =
        Regex("""XSFTK\.boot\("([0-9a-f]+)",\s*"([0-9a-f.]+)"""")

    fun xsftkBoot(playerHtml: String): XsftkBoot? =
        xsftkBootRe.find(playerHtml)?.let { XsftkBoot(it.groupValues[1], it.groupValues[2]) }

    /** Opaque per-video key/tokens from the signed-token API. All ops share this
     *  endpoint shape (verified live 2026-10-21, run of issue #846):
     *  <player-host>/player/proto_api.php?data=<id>&op=<op>&vt=<token>[extra]
     *  header X-XS-P: 1 is required on every op (403 without it); referer is not. */
    fun protoApiUrl(playerUrl: String, boot: XsftkBoot, op: String, extra: String = ""): String {
        val playerPath = playerUrl.substringBefore("/player/")
        val vt = URLEncoder.encode(boot.token, "UTF-8")
        return "$playerPath/player/proto_api.php?data=${boot.id}&op=$op&vt=$vt$extra"
    }

    /** op=manifest answers octet-stream bytes XORed with SHA-256("M|<id>|<token>")
     *  (JS: crypto.subtle.digest… TextDecoder → JSON.parse). Returns the JSON text
     *  or null for anything that does not decode to an object. */
    fun decodeXsManifest(raw: ByteArray, boot: XsftkBoot): String? {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("M|${boot.id}|${boot.token}".toByteArray(Charsets.UTF_8))
        val text = buildString(raw.size) {
            for (i in raw.indices) append((raw[i].toInt() xor digest[i % digest.size].toInt()).toChar())
        }
        return text.trimStart().let {
            if (it.startsWith("{") && it.contains("\"cdn\"")) it.trim() else null
        }
    }
}
