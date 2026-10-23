// XSFTK signed-token chain for the xtremestream player family
// (PerverZija videoplayer pervlN/pervmN/jN/perv.xtremestream.xyz).
//
// Protocol live-verified 2026-10-21 without JS execution (FINDINGS-840 probe,
// issue #846; fix wiring issue #845):
//   - player page exposes        XSFTK.boot("<id>", "<hex>.<expiry>", player)
//   - /player/proto_api.php?data=<id>&op=<op>&vt=<token>
//       op=key      → 16 raw AES-128-CBC key bytes (header X-XS-P: 1 REQUIRED, 403
//                     without; referer NOT required)
//       op=manifest → octet-stream bytes XORed with SHA-256("M|<id>|<token>") → JSON
//                     (qualities td/n/durs/bw/res + cdn host list)
//   - AES-128-CBC segment files live OPEN on the CDN (no token, no referer) at
//         https://<cdn-host>/cdn/down/<video_id>/<q>_enc/<q><i>.html
//     encrypted with the op=key bytes and IV = 16-byte big-endian segment index —
//     byte-for-byte the HLS "no IV attribute → IV = media sequence number" default,
//     so a synthesized VOD playlist (#EXT-X-KEY URI=op=key, no IV=, MEDIA-SEQUENCE:0,
//     in-order segments) replays natively. The legacy tokenless xs1.php master is a
//     decoy: playlist 200 but every segment 404s (FINDINGS-840).
//
// Kept free of CloudStream framework types (unit-test classpath, ADR-0005) and of
// android.* calls on the hot path except the Base64 shim (mirror of decodeBase64,
// java.util.Base64 fallback for JVM tests). Lives in shared/ because the root cause
// of the #840 symptom is the shared PerverZijaExtractor adapter (HostRegistry row)
// emitting the decoy — never copy-paste extractor code into a provider (ADR-0002).
package com.kraptor

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.security.MessageDigest

object XsftkParse {

    /** Boot pair from the plain-text player call. Token is <hex-random>.<epoch>. */
    data class Boot(val id: String, val token: String)

    private val mapper = ObjectMapper()

    /** Player boot call exposed in plain text on the player (index.php) page.
     *  Format-tolerant by design (issue #907 hardening): the call may be reached as
     *  XSFTK.boot(...) or XSFTK["boot"](...), optionally behind `window.`, with
     *  single/double quotes and arbitrary whitespace/newlines between the arguments.
     *  A strict one-shape matcher is how a gated page silently degrades to the
     *  tokenless xs1.php decoy (FINDINGS-906). */
    private val bootRe = Regex(
        """(?:window\s*\.\s*)?XSFTK\s*(?:\.\s*boot|\[\s*["']boot["']\s*\])\s*\(\s*""" +
            """["']([0-9a-fA-F]+)["']\s*,\s*["']([0-9a-fA-F.]+)["']"""
    )

    fun xsftkBoot(playerHtml: String): Boot? =
        bootRe.find(playerHtml)?.let { Boot(it.groupValues[1], it.groupValues[2]) }

    /** Structural gate markers of the FTK player generation. A page that still loads
     *  the gate but lost its inline XSFTK.boot(...) call must never be classified as
     *  legacy: its tokenless xs1.php fallback is the decoy (playlist 200, every
     *  segment 404 — FINDINGS-840/906), i.e. the owner-visible 2004 class. */
    private val gateMarkers = listOf("XSFTK", "ftk_engine", "ftk/xsad", "proto_api")

    fun isGatedPlayer(playerHtml: String): Boolean =
        xsftkBoot(playerHtml) != null || gateMarkers.any { playerHtml.contains(it) }

    /** Canonical proto_api endpoint. All ops share the shape
     *  <player-host>/player/proto_api.php?data=<id>&op=<op>&vt=<token>[extra]. */
    fun protoApiUrl(playerUrl: String, boot: Boot, op: String, extra: String = ""): String {
        val playerHost = playerUrl.substringBefore("/player/")
        val vt = URLEncoder.encode(boot.token, "UTF-8")
        return "$playerHost/player/proto_api.php?data=${boot.id}&op=$op&vt=$vt$extra"
    }

    /** op=manifest bytes → XOR SHA-256("M|<id>|<token>") → JSON text, or null when the
     *  payload does not decode into the expected manifest shape. */
    fun decodeXsManifest(raw: ByteArray, boot: Boot): String? {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("M|${boot.id}|${boot.token}".toByteArray(Charsets.UTF_8))
        val text = buildString(raw.size) {
            for (i in raw.indices) append((raw[i].toInt() xor digest[i % digest.size].toInt()).toChar())
        }
        return text.trimStart().let {
            if (it.startsWith("{") && it.contains("\"cdn\"")) it.trim() else null
        }
    }

    /** Quality keys (highest resolution first) from the decoded manifest JSON.
     *  Parsing failures degrade to an empty list — never throw on hostile input. */
    fun qualities(json: String): List<String> = try {
        val root = mapper.readTree(json)
        root.fieldNames().asSequence()
            .filter { it != "cdn" && root[it]?.isObject == true }
            .sortedByDescending { it.toIntOrNull() ?: Int.MIN_VALUE }
            .toList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Segment file URL as served by the CDN. Shape live-verified 2026-10-21:
     *  https://<cdn-host>/cdn/down/<video_id>/<q>_enc/<q><i>.html (200, no token,
     *  no referer — same bytes from every cdn host in the manifest list). */
    fun xsSegmentUrl(cdnHost: String, id: String, q: String, i: Int): String =
        "https://$cdnHost/cdn/down/$id/${q}_enc/$q$i.html"

    /** Synthesized native-HLS VOD playlist for one quality, or null when the quality
     *  is missing or the JSON is malformed. Segment IVs default to the media sequence
     *  number (no IV= attribute) which equals the site's BE(index) scheme only if
     *  MEDIA-SEQUENCE stays 0 — that is a contract of this builder. TARGETDURATION
     *  covers the longest EXTINF per RFC 8216 (the manifest `td` field can undershoot
     *  its own durs, e.g. td=8 with a first segment of 8.333s on the probe fixture). */
    fun variantPlaylist(
        json: String,
        boot: Boot,
        playerUrl: String,
        q: String,
        cdnHost: String? = null
    ): String? = try {
        val root = mapper.readTree(json)
        val node = root.get(q) ?: return null
        val n = node.get("n")?.asInt() ?: return null
        val durs = node.get("durs") ?: return null
        val cdn = cdnHost ?: root.get("cdn")?.get(0)?.asText() ?: return null
        val keyUrl = protoApiUrl(playerUrl, boot, "key")
        val target = buildList {
            for (i in 0 until minOf(n, durs.size())) add(durs.get(i).asDouble())
        }.maxOrNull()
        if (target == null || target <= 0.0) return null
        buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:")
            append(Math.ceil(target).toLong())
            append("\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n")
            append("#EXT-X-KEY:METHOD=AES-128,URI=\"").append(keyUrl).append("\"\n")
            for (i in 0 until minOf(n, durs.size())) {
                append("#EXTINF:").append(durs.get(i).asText()).append(",\n")
                append(xsSegmentUrl(cdn, boot.id, q, i)).append('\n')
            }
            append("#EXT-X-ENDLIST")
        }
    } catch (e: Exception) {
        null
    }

    /** Base64 (RFC 4648, padded, no line breaks). android.util.Base64 first (API 1+),
     *  java.util.Base64 only as the JVM-test fallback (API 26+, mirror of
     *  decodeBase64 in HostRegistry.kt). */
    fun encodeBase64NoWrap(data: ByteArray): String =
        try {
            android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        } catch (e: RuntimeException) {
            java.util.Base64.getEncoder().encodeToString(data)
        } catch (e: LinkageError) {
            java.util.Base64.getEncoder().encodeToString(data)
        }

    /** Playlist text as a data: URI. ExoPlayer opens data: URIs natively
     *  (DataSchemeDataSource), so the synthesized playlist travels in-band and
     *  keeps the X-XS-P header session in one request context (FINDINGS-840 note). */
    fun dataUri(playlist: String): String =
        "data:application/vnd.apple.mpegurl;base64," + encodeBase64NoWrap(playlist.toByteArray(Charsets.UTF_8))
}
