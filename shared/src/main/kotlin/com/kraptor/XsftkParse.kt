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
//     decoy ONLY on gated videos (playlist 200, every segment 404s); on the pre-gate
//     family it is live (plain MPEG-TS) — FINDINGS-840/906. playerPlan() is the one
//     switch between the families (#908).
//
// Kept free of CloudStream framework types (unit-test classpath, ADR-0005) and of
// android.* calls on the hot path. Lives in shared/ because the root cause
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

    /** Boot call skeleton: `XSFTK.boot(...)` or `XSFTK["boot"](...)`, any spacing.
     *  The argument list is matched structurally and the quoted arguments are then
     *  classified by shape (issue #908): a fixed "id, token" byte order is how a
     *  re-minified page degraded to the tokenless `xs1.php` decoy (FINDINGS-906). */
    private val bootCallRe = Regex(
        """XSFTK\s*(?:\.\s*boot|\[\s*["']boot["']\s*\])\s*\(([^)]*)\)""",
        RegexOption.IGNORE_CASE
    )

    private val quotedArgRe = Regex("""["']([^"']+)["']""")
    private val bootIdRe = Regex("""[0-9a-fA-F]{16,}""")
    private val bootTokenRe = Regex("""[0-9a-fA-F]{8,}\.\d+""")

    fun xsftkBoot(playerHtml: String): Boot? = xsftkBoot(playerHtml, null)

    /** Structural boot match. [expectedId] (the player URL's `data=` id) resolves the
     *  arguments when shape alone is ambiguous — a token without the `<hex>.<epoch>`
     *  suffix, an id that is not hex, or args in a different order. Without it the
     *  pair is classified by shape: dotted argument = token, plain hex = id. */
    fun xsftkBoot(playerHtml: String, expectedId: String?): Boot? {
        for (call in bootCallRe.findAll(playerHtml)) {
            val args = quotedArgRe.findAll(call.groupValues[1]).map { it.groupValues[1] }.toList()
            bootFromArgs(args, expectedId)?.let { return it }
        }
        return null
    }

    private fun bootFromArgs(args: List<String>, expectedId: String?): Boot? {
        if (args.size < 2) return null
        if (expectedId != null) {
            val id = args.firstOrNull { it.equals(expectedId, ignoreCase = true) } ?: return null
            val token = args.firstOrNull { it != id } ?: return null
            return Boot(id, token)
        }
        val token = args.firstOrNull { bootTokenRe.matches(it) } ?: return null
        val id = args.firstOrNull { it != token && bootIdRe.matches(it) } ?: return null
        return Boot(id, token)
    }

    /** Structural gate markers of the FTK player generation. A page that still loads
     *  the gate but lost its inline XSFTK.boot(...) call must never be classified as
     *  legacy: its tokenless xs1.php fallback is the decoy (playlist 200, every
     *  segment 404 — FINDINGS-840/906), i.e. the owner-visible 2004 class.
     *  `proto_api` is deliberately absent: no #906 gated fixture carries it, and a
     *  false positive here would suppress the *working* legacy fallback (the mirror
     *  2004 class). Only markers seen in the gated fixtures decide. */
    private val gateMarkers = listOf("XSFTK", "ftk_engine", "ftk/xsad")

    fun isGatedPlayer(playerHtml: String): Boolean =
        xsftkBoot(playerHtml) != null || gateMarkers.any { playerHtml.contains(it) }

    /** How one player page becomes sources (issue #908). The legacy branch keeps the
     *  live tokenless master; a gated page without a boot call has no playable route
     *  (its `xs1.php` is the decoy), so it fails closed instead of emitting 404s. */
    sealed class PlayerPlan {
        data class Xsftk(val boot: Boot) : PlayerPlan()
        data class Legacy(val masterUrl: String) : PlayerPlan()
        object FailClosed : PlayerPlan()
    }

    private val dataIdRe = Regex("""[?&]data=([0-9a-fA-F]{16,})""")

    /** Video id from the player URL — the id the boot call has to bind to. */
    fun playerId(playerUrl: String): String? =
        dataIdRe.find(playerUrl)?.groupValues?.get(1)

    /** Origin (scheme + host) of a player/embed URL, or null when it is not http(s). */
    private val originRe = Regex("""^(https?://[^/?#]+)""", RegexOption.IGNORE_CASE)

    /** Tokenless master for the pre-gate family. Built from the URL origin + `data=` id,
     *  so every player path shape (/player/index.php, /player/, extra query params and
     *  non-/player/ embeds) resolves to the one xs1.php endpoint FINDINGS-906 verified
     *  as live. No `data=` id → null: a master URL cannot be guessed, so the caller
     *  fails closed instead of emitting the player page itself as a playlist. */
    fun legacyMasterUrl(playerUrl: String): String? {
        val origin = originRe.find(playerUrl)?.groupValues?.get(1) ?: return null
        val id = playerId(playerUrl) ?: return null
        return "$origin/player/xs1.php?data=$id"
    }

    /** The one decision point the shared extractor uses (issue #908). Order matters:
     *  boot → XSFTK chain; still gated → FailClosed (never the decoy); otherwise the
     *  legacy family keeps its live master (16/336 videos, FINDINGS-906). */
    fun playerPlan(playerUrl: String, playerHtml: String): PlayerPlan {
        val boot = xsftkBoot(playerHtml, playerId(playerUrl))
        return when {
            boot != null -> PlayerPlan.Xsftk(boot)
            isGatedPlayer(playerHtml) -> PlayerPlan.FailClosed
            else -> legacyMasterUrl(playerUrl)
                ?.let { PlayerPlan.Legacy(it) }
                ?: PlayerPlan.FailClosed
        }
    }

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

    /** One playable quality of a decoded manifest. `label` is the manifest key —
     *  numeric on every #906 fixture, kept as text so an odd label (e.g. "hd")
     *  still reaches the app instead of being dropped. */
    data class QualitySource(val label: String, val playlist: String)

    /** One manifest quality and the outcome of trying to build its playlist (#1328/S1):
     *  `playlist` is null when the quality is skipped, and `reason` then names why
     *  (`missing n`, `missing durs`, `unbuildable`). `cdnHost`/`segmentCount` are the
     *  values that reach the synthesized playlist, for the adapter's log line. */
    data class QualityPlan(
        val label: String,
        val playlist: String?,
        val reason: String?,
        val cdnHost: String?,
        val segmentCount: Int
    )

    /** Every quality with its build outcome, highest first. This is the diagnostics
     *  view of the same decision [variantPlaylist] makes; [playableSources] derives
     *  from it, so there is still one selection path and emitted playlists stay
     *  byte-identical. An unparseable manifest yields none. */
    fun qualityPlans(
        json: String,
        boot: Boot,
        playerUrl: String,
        cdnHost: String? = null
    ): List<QualityPlan> {
        val root = try {
            mapper.readTree(json)
        } catch (e: Exception) {
            return emptyList()
        }
        val cdn = cdnHost ?: root.get("cdn")?.get(0)?.asText()
        return qualities(json).map { q ->
            val node = root.get(q)
            val n = node?.get("n")?.asInt()
            val durs = node?.get("durs")
            when {
                node == null -> QualityPlan(q, null, "unbuildable", cdn, 0)
                n == null -> QualityPlan(q, null, "missing n", cdn, 0)
                durs == null -> QualityPlan(q, null, "missing durs", cdn, 0)
                cdn == null -> QualityPlan(q, null, "unbuildable", null, 0)
                else -> {
                    val segs = minOf(n, durs.size()).coerceAtLeast(0)
                    val playlist = variantPlaylist(json, boot, playerUrl, q, cdn)
                    QualityPlan(q, playlist, if (playlist == null) "unbuildable" else null, cdn, segs)
                }
            }
        }
    }

    /** Every quality the manifest can actually build, highest first. A malformed
     *  quality entry is skipped instead of zeroing the source list (the "only one
     *  source, no qualities" class); an unparseable manifest yields none. */
    fun playableSources(
        json: String,
        boot: Boot,
        playerUrl: String,
        cdnHost: String? = null
    ): List<QualitySource> = qualityPlans(json, boot, playerUrl, cdnHost).mapNotNull { plan ->
        plan.playlist?.let { QualitySource(plan.label, it) }
    }

    // ---- CDN roster selection (#1328/S2) -------------------------------------
    // The manifest's `cdn` list is a redundancy pool: FINDINGS-1328 §1 fetched segment 0
    // of every quality from all 45 hosts (two families, `pervl3.xspcdnNN` /
    // `pervl3.xscasacdnNN`) and every response was byte-identical. Building all segment
    // URLs from `cdn[0]` therefore made one blocked or unresolvable host kill every
    // quality of the video at once — the connect-level 2001 shape of this report, and
    // AGENTS.md's "multi-host fallback" hardening for a third drift recurrence.
    //
    // Selection stays pure (a boolean probe callable, no HTTP), so the bounded-probe
    // contract is unit-tested without mocking transport (ADR-0005).

    /** Primary probe budget. A healthy manifest costs exactly one probe (`cdn[0]` is
     *  candidate #1); six still covers both families and each family's second host. */
    const val MAX_PROBES = 6

    /** Fallback-source probe budget: three siblings of the other family are enough to
     *  survive a single dead host without spending the whole roster. */
    const val MAX_ALT_PROBES = 3

    private val trailingDigitsRe = Regex("""\d+$""")

    /** Every `cdn` entry, trimmed, blanks dropped, deduped, manifest order preserved.
     *  Empty for malformed JSON or a manifest without a roster — the caller then fails
     *  closed instead of guessing a host. */
    fun cdnHosts(json: String): List<String> = try {
        val node = mapper.readTree(json).get("cdn")
        if (node == null || !node.isArray) emptyList()
        else LinkedHashSet<String>().also { seen ->
            for (el in node) {
                val host = el.asText().trim()
                if (host.isNotEmpty()) seen.add(host)
            }
        }.toList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Structural family key = the second-level label with trailing digits stripped:
     *  `pervl6.xspcdn01.click` → `xspcdn`, `j2.xscasacdn03.click` → `xscasacdn`. A host
     *  with no second level (or an all-digit one) falls back to the whole host, so
     *  unrelated CDNs can never merge into one family by accident. */
    fun cdnFamily(host: String): String {
        val trimmed = host.trim().lowercase()
        val labels = trimmed.split('.')
        if (labels.size < 3 || labels.any { it.isEmpty() }) return trimmed
        return labels[labels.size - 2].replace(trailingDigitsRe, "").ifEmpty { trimmed }
    }

    /** Deterministic round-robin across the roster's families: the first host of every
     *  family (manifest order), then the second of every family, and so on. After
     *  `cdn[0]` the head of each *other* family is tried before any family's second
     *  host, so one blocked family and one blocked host are both survived with the
     *  fewest probes — while candidate #1 stays `cdn[0]`. Every host appears exactly
     *  once; a stable input gives a stable output. */
    fun cdnCandidateOrder(hosts: List<String>): List<String> {
        val seen = HashSet<String>()
        val families = LinkedHashMap<String, MutableList<String>>()
        for (raw in hosts) {
            val host = raw.trim()
            if (host.isEmpty() || !seen.add(host)) continue
            families.getOrPut(cdnFamily(host)) { mutableListOf() }.add(host)
        }
        val ordered = ArrayList<String>(seen.size)
        var round = 0
        while (true) {
            var added = false
            for (family in families.values) {
                if (round < family.size) {
                    ordered.add(family[round])
                    added = true
                }
            }
            if (!added) return ordered
            round++
        }
    }

    /** First candidate the probe accepts, bounded to [maxProbes] calls. `null` means no
     *  host is usable — the caller then emits nothing instead of dead segment URLs.
     *  `inline` so an adapter can pass its suspend HTTP probe straight through. */
    inline fun pickCdn(
        ordered: List<String>,
        maxProbes: Int = MAX_PROBES,
        probe: (String) -> Boolean
    ): String? {
        var probes = 0
        for (host in ordered) {
            if (probes >= maxProbes) return null
            probes++
            if (probe(host)) return host
        }
        return null
    }

    /** First candidate from a *different* family the probe accepts — the manual
     *  fallback source. Bounded to [maxProbes] (3): one blocked family is survivable
     *  when any sibling answers. `null` on a single-family roster or when no other
     *  candidate is alive, so a roster of one family never grows a duplicate source. */
    inline fun altCdnHost(
        ordered: List<String>,
        primaryFamily: String,
        primaryHost: String,
        maxProbes: Int = MAX_ALT_PROBES,
        probe: (String) -> Boolean
    ): String? {
        var probes = 0
        for (host in ordered) {
            if (host == primaryHost || cdnFamily(host) == primaryFamily) continue
            if (probes >= maxProbes) return null
            probes++
            if (probe(host)) return host
        }
        return null
    }

    /** One link the adapter emits (#1328/S2): a synthesized playlist for one manifest
     *  quality plus the CDN host it names. `alt = true` marks the manual fallback copy
     *  of the same quality set (the app shows it as a second source of the same video). */
    data class PlannedSource(
        val quality: String,
        val playlist: String,
        val cdnHost: String,
        val alt: Boolean
    )

    /** The whole emission decision, still pure: build the quality set against `cdn[0]`
     *  first (a manifest with no buildable quality must not spend a network call), probe
     *  the round-robin order for a live host, rebuild the qualities against it, then add
     *  the other family's copy once (capped at two families). An empty list means fail
     *  closed — no decoy, no tokenless `xs1.php` on a gated page (#906).
     *
     *  [onSkip] reports each quality the manifest cannot build (the #1336/S1 diagnostics);
     *  [probe] receives a candidate host plus the highest buildable quality, i.e. the
     *  segment the adapter preflights with `Range: bytes=0-1023`. */
    inline fun cdnSources(
        json: String,
        boot: Boot,
        playerUrl: String,
        maxProbes: Int = MAX_PROBES,
        maxAltProbes: Int = MAX_ALT_PROBES,
        noinline onSkip: ((QualityPlan) -> Unit)? = null,
        probe: (host: String, quality: String) -> Boolean
    ): List<PlannedSource> {
        val ordered = cdnCandidateOrder(cdnHosts(json))
        val seedHost = ordered.firstOrNull() ?: return emptyList()
        val seedPlans = qualityPlans(json, boot, playerUrl, seedHost)
        for (plan in seedPlans) if (plan.playlist == null) onSkip?.invoke(plan)
        val probeQuality = seedPlans.firstOrNull { it.playlist != null }?.label ?: return emptyList()

        val chosen = pickCdn(ordered, maxProbes) { host -> probe(host, probeQuality) }
            ?: return emptyList()
        val primary = playableSources(json, boot, playerUrl, chosen).map { source ->
            PlannedSource(source.label, source.playlist, chosen, alt = false)
        }
        if (primary.isEmpty()) return emptyList()

        val altHost = altCdnHost(ordered, cdnFamily(chosen), chosen, maxAltProbes) { host ->
            probe(host, probeQuality)
        } ?: return primary
        return primary + playableSources(json, boot, playerUrl, altHost).map { source ->
            PlannedSource(source.label, source.playlist, altHost, alt = true)
        }
    }

    // Emission is an http URL, never an in-band `data:` URI: the app's player fetches
    // every source through Cronet/OkHttp, which reject unknown schemes with
    // net::ERR_UNKNOWN_URL_SCHEME before the HLS parser sees the playlist — the
    // ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (2001) of #840/#1328/#1353. The adapter
    // hosts the playlist on 127.0.0.1 instead (XsftkPlaylistServer, FINDINGS-1353).
}
