package com.kraptor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD for issue #1338 (S3 of the PerverZija playback-hardening epic #1328).
 *
 * Every gated source ships as a synthesized HLS playlist inside a
 * `data:application/vnd.apple.mpegurl;base64,…` URI, because that is how the
 * `X-XS-P: 1` key-header session stays in one request context (FINDINGS-840).
 * Whether the in-app player accepts a multi-tens-of-kB `data:` URI is **unverifiable
 * from CI** (H5 of the S1 hypothesis table), so this story does not replace the
 * mechanism — it *bounds* it: one documented budget constant, one over-budget log line
 * on the real emission path, and the in-band playlist contract itself pinned per
 * quality.
 *
 * Measured shape (FINDINGS-1328 §1, largest sampled quality of the reported video):
 * 1080 / 394 segments → 44 052 B raw → 58 736 B in the `data:` URI. The budget is
 * 96 KiB (~2× headroom). The shipped fixtures show why the budget may only *log*:
 * `perverzija_proto_manifest.bin` (720 × 777) is 113 216 B and
 * `perverzija_manifest_singleq.bin` (360 × 1411) is 207 484 B — both over budget on
 * real long videos, so dropping over-budget sources would lose working sources.
 */
class PerverZijaPlaylistBudgetTest {

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource(name)!!.readText()

    private fun manifestBin(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream(name)!!.readBytes()

    // ---- fixtures (the same #906/#845 captures the other suites use) ----------

    private val reportedPage by lazy { fixture("perverzija_player_reported_xsftk.html") }
    private val reportedId = "71eee742e4c6e094e6af364597af3f05"
    private val reportedPlayerUrl = "https://pervl3.xtremestream.xyz/player/index.php?data=$reportedId"
    private val reportedBoot by lazy { PerverZijaParse.xsftkBoot(reportedPage)!! }

    private val singleQPage by lazy { fixture("perverzija_player_singleq_xsftk.html") }
    private val singleQId = "95177e528f8d6c7c28a5473fd5a471b6"
    private val singleQPlayerUrl = "https://pervl5.xtremestream.xyz/player/index.php?data=$singleQId"
    private val singleQBoot by lazy { PerverZijaParse.xsftkBoot(singleQPage)!! }

    private val protoPage by lazy { fixture("perverzija_player_xsftk.html") }
    private val protoId = "4e316bf7cad703ffd97bd72c174b72c6"
    private val protoPlayerUrl = "https://pervl3.xtremestream.xyz/player/index.php?data=$protoId"
    private val protoBoot by lazy { PerverZijaParse.xsftkBoot(protoPage)!! }

    private fun reportedManifest(): String =
        PerverZijaParse.decodeXsManifest(manifestBin("perverzija_manifest_reported.bin"), reportedBoot)!!

    private fun singleQManifest(): String =
        PerverZijaParse.decodeXsManifest(manifestBin("perverzija_manifest_singleq.bin"), singleQBoot)!!

    private fun protoManifest(): String =
        PerverZijaParse.decodeXsManifest(manifestBin("perverzija_proto_manifest.bin"), protoBoot)!!

    /** One manifest plus the player context needed to synthesize its playlists. */
    private class Fixture(
        val name: String,
        val json: String,
        val boot: XsftkParse.Boot,
        val playerUrl: String
    ) {
        /** The player host the key leg talks to (H2), never the CDN that serves segments. */
        val playerHost: String get() = playerUrl.substringAfter("://").substringBefore('/')
    }

    private fun fixtures(): List<Fixture> = listOf(
        Fixture("reported", reportedManifest(), reportedBoot, reportedPlayerUrl),
        Fixture("singleq", singleQManifest(), singleQBoot, singleQPlayerUrl),
        Fixture("proto", protoManifest(), protoBoot, protoPlayerUrl)
    )

    private fun reportedSources(): List<XsftkParse.QualitySource> =
        XsftkParse.playableSources(reportedManifest(), reportedBoot, reportedPlayerUrl)

    /** Every buildable quality of every fixture, with the fixture it came from. */
    private fun allSources(): List<Pair<Fixture, XsftkParse.QualitySource>> =
        fixtures().flatMap { f ->
            XsftkParse.playableSources(f.json, f.boot, f.playerUrl).map { f to it }
        }

    private fun uriBytes(playlist: String): Int =
        XsftkParse.dataUri(playlist).toByteArray(Charsets.UTF_8).size

    private fun extinfDurations(playlist: String): List<Double> =
        playlist.lineSequence()
            .filter { it.startsWith("#EXTINF:") }
            .map { it.removePrefix("#EXTINF:").substringBefore(',').toDouble() }
            .toList()

    private fun targetDuration(playlist: String): Long =
        playlist.lineSequence()
            .first { it.startsWith("#EXT-X-TARGETDURATION:") }
            .removePrefix("#EXT-X-TARGETDURATION:")
            .trim()
            .toLong()

    private fun keyUri(playlist: String): String {
        val keyLines = playlist.lineSequence().filter { it.startsWith("#EXT-X-KEY:") }.toList()
        assertEquals("exactly one #EXT-X-KEY line", 1, keyLines.size)
        return Regex("""URI="([^"]+)"""")
            .find(keyLines[0])!!.groupValues[1]
    }

    // ---- 1. the documented budget, pinned by test ----------------------------

    @Test fun `playlist budget is 96 KiB, above the measured maximum`() {
        // 58 736 B measured maximum (1080 / 394 segments, FINDINGS-1328 §1) with ~2×
        // headroom, so a normal video can never trip the diagnostic.
        assertEquals(96 * 1024, XsftkParse.PLAYLIST_BUDGET_BYTES)
        assertTrue(XsftkParse.PLAYLIST_BUDGET_BYTES > 58_736)
    }

    @Test fun `every buildable quality of the reported video fits the budget`() {
        val sources = reportedSources()
        assertEquals(listOf("1080", "720", "480"), sources.map { it.label })
        for (source in sources) {
            val uri = XsftkParse.dataUri(source.playlist)
            assertTrue(
                "q=${source.label} data: URI is ${uri.length} B " +
                    "(budget ${XsftkParse.PLAYLIST_BUDGET_BYTES})",
                uri.length <= XsftkParse.PLAYLIST_BUDGET_BYTES
            )
        }
        // the highest quality carries the biggest in-band payload of the real fixture
        assertEquals("1080", sources.maxByOrNull { uriBytes(it.playlist) }!!.label)
    }

    /**
     * The measured live maximum (FINDINGS-1328 §1) is a 394-segment 1080 quality. The
     * shipped #906 fixture was pruned to 80 segments, so the live shape is rebuilt here
     * by cycling its own durations up to 394 entries — same durations, same id/token, so
     * the resulting playlist reproduces the recorded size: 44 046 B raw vs 44 052 live,
     * 58 728 B base64 vs 58 736 live (+ the 42 B `data:` prefix → 58 770 B carried).
     * Asserting the number (not just the budget) is the point: it proves the test really
     * exercises a large payload.
     */
    @Test fun `the measured 394-segment maximum is the largest payload and stays under budget`() {
        val json = liveMaximumManifest()
        val playlist = XsftkParse.variantPlaylist(json, reportedBoot, reportedPlayerUrl, "1080")!!
        assertEquals(394, playlist.lineSequence().count { it.startsWith("#EXTINF:") })

        val uri = XsftkParse.dataUri(playlist)
        assertEquals("measured maximum carried in the data: URI", 58_770, uri.length)
        assertTrue("must exercise a genuinely large payload (> 40 kB)", uri.length > 40 * 1024)
        assertTrue("and stay under the budget", uri.length < XsftkParse.PLAYLIST_BUDGET_BYTES)
        assertTrue(
            "larger than every shipped-fixture quality",
            uri.length > reportedSources().maxOf { uriBytes(it.playlist) }
        )
    }

    /** A quality list that blows past the budget is still emitted (#1338: log, don't drop). */
    @Test fun `over-budget qualities are still emitted - the budget only logs`() {
        val single = XsftkParse.playableSources(singleQManifest(), singleQBoot, singleQPlayerUrl)
        assertEquals(listOf("360"), single.map { it.label })
        assertTrue(
            "singleq fixture (360 x 1411) is over budget",
            XsftkParse.dataUri(single[0].playlist).length > XsftkParse.PLAYLIST_BUDGET_BYTES
        )

        val proto = XsftkParse.playableSources(protoManifest(), protoBoot, protoPlayerUrl)
        assertEquals(listOf("720", "480"), proto.map { it.label })
        assertTrue(
            "proto fixture (720 x 777) is over budget too",
            proto.all { XsftkParse.dataUri(it.playlist).length > XsftkParse.PLAYLIST_BUDGET_BYTES }
        )
    }

    // ---- 2. TARGETDURATION >= the largest EXTINF -----------------------------

    @Test fun `target duration covers the longest extinf for every quality`() {
        val all = allSources()
        assertTrue("fixtures produced no sources", all.isNotEmpty())
        for ((fixture, source) in all) {
            val playlist = source.playlist
            val durations = extinfDurations(playlist)
            val longest = durations.maxOrNull()!!
            val target = targetDuration(playlist)
            assertTrue(
                "${fixture.name} q=${source.label}: td=$target < max EXTINF=$longest",
                target >= longest
            )
            // RFC 8216: TARGETDURATION is the rounded max, so an integer target covers it
            assertTrue(
                "${fixture.name} q=${source.label}: td=$target < ceil($longest)",
                target >= Math.ceil(longest).toLong()
            )
        }
        // the undershoot case the constant's rationale records: manifest td=8 while a
        // real 1080 segment runs 8.341667 s, so the synthesized target must be 9
        for (source in reportedSources()) {
            assertEquals("q=${source.label}", 9L, targetDuration(source.playlist))
        }
    }

    // ---- 3. MEDIA-SEQUENCE:0 (the IV contract) -------------------------------

    @Test fun `media sequence is zero for every quality`() {
        for ((fixture, source) in allSources()) {
            val playlist = source.playlist
            assertEquals(
                "${fixture.name} q=${source.label} media sequence line count",
                1,
                playlist.lineSequence().count { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
            )
            assertTrue(
                "${fixture.name} q=${source.label} media sequence must be 0",
                playlist.contains("#EXT-X-MEDIA-SEQUENCE:0\n")
            )
            // segments carry no IV=, so the media sequence number IS the IV (BE index)
            assertFalse("${fixture.name} q=${source.label}", playlist.contains("IV="))
        }
    }

    // ---- 4. the key line contract -------------------------------------------

    @Test fun `key line is single, IV-free and lives on the player host`() {
        for ((fixture, source) in allSources()) {
            val playlist = source.playlist
            val key = keyUri(playlist)
            assertTrue(
                "${fixture.name} q=${source.label}: key line must precede segments",
                playlist.indexOf("#EXT-X-KEY:") < playlist.indexOf("#EXTINF:")
            )
            assertTrue("METHOD=AES-128", playlist.contains("#EXT-X-KEY:METHOD=AES-128,URI=\""))
            // the key leg is a separate connection to the player host (H2 in the S1
            // hypothesis table) — never the CDN host that serves the segments
            val keyHost = key.substringAfter("://").substringBefore('/')
            assertEquals("${fixture.name} key host", fixture.playerHost, keyHost)
            assertFalse("key URI must not be a CDN host: $key", keyHost.endsWith(".click"))
            assertTrue("key URI shape", key.contains("/player/proto_api.php?"))
            assertTrue("key URI carries op=key", key.contains("op=key"))
            assertTrue("key URI binds the video id", key.contains("data=${fixture.boot.id}"))
            // and the segment host is the CDN, i.e. the two legs are separate hosts
            val segmentHost = playlist.lineSequence()
                .first { it.startsWith("https://") }
                .substringAfter("://").substringBefore('/')
            assertNotEquals(keyHost, segmentHost)
        }
    }

    // ---- 5. VOD, not live ---------------------------------------------------

    @Test fun `endlist is the last line so the playlist is VOD`() {
        for ((fixture, source) in allSources()) {
            val playlist = source.playlist
            assertEquals(
                "${fixture.name} q=${source.label}",
                "#EXT-X-ENDLIST",
                playlist.trimEnd().lines().last()
            )
            assertEquals(
                "${fixture.name} q=${source.label} ENDLIST count",
                1,
                playlist.lineSequence().count { it.trimEnd() == "#EXT-X-ENDLIST" }
            )
            assertTrue(playlist.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        }
    }

    // ---- 6. segment order: 0..n-1, no gaps, no duplicates --------------------

    @Test fun `segment urls are in index order with no gaps and no duplicates`() {
        for ((fixture, source) in allSources()) {
            val q = source.label
            val indexRe = Regex("""/cdn/down/[0-9a-fA-F]+/${Regex.escape(q)}_enc/${Regex.escape(q)}(\d+)\.html$""")
            val urls = source.playlist.lineSequence().filter { it.startsWith("https://") }.toList()
            assertEquals(
                "${fixture.name} q=$q: one segment per EXTINF",
                extinfDurations(source.playlist).size,
                urls.size
            )
            val indexes = urls.map { url ->
                val match = indexRe.find(url)
                assertNotNull("${fixture.name} q=$q unexpected segment url: $url", match)
                match!!.groupValues[1].toInt()
            }
            assertEquals("${fixture.name} q=$q duplicates", urls.size, urls.toSet().size)
            assertEquals("${fixture.name} q=$q indexes 0..n-1", (0 until urls.size).toList(), indexes)
        }
    }

    // ---- 7. one broken quality must not drop the others ---------------------

    @Test fun `a quality with missing or empty durs still yields the other qualities`() {
        // "only one source" class: a quality entry that lost its duration list used to
        // take the whole source list with it. The other qualities must survive it.
        data class Case(val label: String, val json: String, val reason: String)

        val cases = listOf(
            Case("missing durs", manifestWithout720Durs(empty = false), "missing durs"),
            Case("empty durs", manifestWithout720Durs(empty = true), "unbuildable")
        )
        for (case in cases) {
            val plans = XsftkParse.qualityPlans(case.json, reportedBoot, reportedPlayerUrl)
            assertEquals(case.label, listOf("1080", "720", "480"), plans.map { it.label })
            assertEquals(case.label, case.reason, plans.first { it.label == "720" }.reason)

            val sources = XsftkParse.playableSources(case.json, reportedBoot, reportedPlayerUrl)
            assertEquals(case.label, listOf("1080", "480"), sources.map { it.label })
            for (source in sources) {
                assertTrue(case.label, uriBytes(source.playlist) <= XsftkParse.PLAYLIST_BUDGET_BYTES)
            }
        }

        // and the single-quality side of the same class is a supported shape
        assertEquals(
            listOf("360"),
            XsftkParse.playableSources(singleQManifest(), singleQBoot, singleQPlayerUrl).map { it.label }
        )
    }

    // ---- manifest surgery ---------------------------------------------------

    /** The reported 1080 entry, cycled up to [segments] durations (the live 394 shape). */
    private fun liveMaximumManifest(segments: Int = 394): String {
        val mapper = ObjectMapper()
        val root = mapper.readTree(reportedManifest()) as ObjectNode
        val quality = root.get("1080") as ObjectNode
        val durs = quality.get("durs")
        val expanded = mapper.createArrayNode()
        for (i in 0 until segments) expanded.add(durs.get(i % durs.size()).asDouble())
        quality.set<JsonNode>("durs", expanded)
        quality.put("n", segments)
        return mapper.writeValueAsString(root)
    }

    /** The reported manifest with the 720 quality's `durs` removed or emptied. */
    private fun manifestWithout720Durs(empty: Boolean): String {
        val mapper = ObjectMapper()
        val root = mapper.readTree(reportedManifest()) as ObjectNode
        val quality = root.get("720") as ObjectNode
        if (empty) quality.set<JsonNode>("durs", mapper.createArrayNode()) else quality.remove("durs")
        return mapper.writeValueAsString(root)
    }
}
