package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD for issue #907 (S2 of the PerverZija residual-2004 epic #903), driven by the
 * census fixtures shipped in #906 (FINDINGS-906): the same video id has two player
 * generations that are mutually exclusive on the CDN, and the only route into the
 * owner's "one source, no qualities" 2004 is a *gated* page whose boot line is
 * missed — the extractor then falls back to the tokenless `xs1.php` decoy
 * (playlist 200, every segment 404).
 *
 * The fixtures cover the two green families plus the decoy trap:
 *  - gated multi-quality   `perverzija_player_reported_xsftk.html` (the #903 URL)  1080/720/480
 *  - gated single-quality  `perverzija_player_singleq_xsftk.html`                  360 only
 *  - legacy boot-miss      `perverzija_player_legacy_bootmiss.html`                plain-TS `xs1.php`
 *
 * `decodeXsManifest` / `qualities` / `variantPlaylist` are pinned as regressions on
 * these new shapes; boot/gate detection carries the red: a page that still ships the
 * FTK gate but loses the boot call must never be treated as legacy.
 */
class PerverZijaResidualShapesTest {

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource(name)!!.readText()

    private fun manifestBin(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream(name)!!.readBytes()

    private val reportedPage by lazy { fixture("perverzija_player_reported_xsftk.html") }
    private val reportedId = "71eee742e4c6e094e6af364597af3f05"
    private val reportedPlayerUrl = "https://pervl3.xtremestream.xyz/player/index.php?data=$reportedId"

    private val singleQPage by lazy { fixture("perverzija_player_singleq_xsftk.html") }
    private val singleQId = "95177e528f8d6c7c28a5473fd5a471b6"
    private val singleQPlayerUrl = "https://pervl5.xtremestream.xyz/player/index.php?data=$singleQId"

    private val legacyPage by lazy { fixture("perverzija_player_legacy_bootmiss.html") }
    private val legacyId = "60ad83801910ec976590f69f638e0d6d"

    private fun reportedManifest(): String {
        val boot = PerverZijaParse.xsftkBoot(reportedPage)!!
        return PerverZijaParse.decodeXsManifest(manifestBin("perverzija_manifest_reported.bin"), boot)!!
    }

    private fun singleQManifest(): String {
        val boot = PerverZijaParse.xsftkBoot(singleQPage)!!
        return PerverZijaParse.decodeXsManifest(manifestBin("perverzija_manifest_singleq.bin"), boot)!!
    }

    // ---- boot detection on the real player shapes ----------------------------

    @Test fun `boot detected on the reported 903 gated page`() {
        val boot = PerverZijaParse.xsftkBoot(reportedPage)!!
        assertEquals(reportedId, boot.id)
        assertEquals("ba68f7052da908106bfd6b1f.1792789200", boot.token)
        assertTrue(PerverZijaParse.isGatedPlayer(reportedPage))
    }

    @Test fun `boot detected on the single-quality gated page`() {
        val boot = PerverZijaParse.xsftkBoot(singleQPage)!!
        assertEquals(singleQId, boot.id)
        assertEquals("d6b582d81d265d1d2e81e48b.1792789200", boot.token)
        assertTrue(PerverZijaParse.isGatedPlayer(singleQPage))
    }

    @Test fun `legacy boot-miss page is not gated and has no boot call`() {
        assertNull(PerverZijaParse.xsftkBoot(legacyPage))
        // The legacy generation's xs1.php chain is live (FINDINGS-906) — the fallback
        // must stay for it, so the page must classify as NOT gated.
        assertFalse(PerverZijaParse.isGatedPlayer(legacyPage))
    }

    @Test fun `gated page that loses its boot call is still gated`() {
        // The decoy trap: FTK assets still load, but the inline boot call is gone.
        // Falling back to xs1.php here emits a 200 playlist whose segments all 404
        // (the owner's in-app 2004), so the page must stay classified as gated.
        val bootMiss = reportedPage.replace(Regex("""(?m)^.*XSFTK\.boot\(.*$"""), "")
        assertFalse(bootMiss.contains("XSFTK.boot("))
        assertNull(PerverZijaParse.xsftkBoot(bootMiss))
        assertTrue(PerverZijaParse.isGatedPlayer(bootMiss))
    }

    @Test fun `asset markers alone keep a boot-miss page gated without XSFTK`() {
        // Pin the markers that do *not* contain the XSFTK token: with every XSFTK
        // reference removed, ftk_engine and ftk/xsad must decide on their own (a
        // marker with no fixture evidence would be a false-positive risk instead).
        val noXsftk = reportedPage.replace("XSFTK", "")
        assertFalse(noXsftk.contains("XSFTK"))
        assertNull(PerverZijaParse.xsftkBoot(noXsftk))
        assertTrue(PerverZijaParse.isGatedPlayer(noXsftk)) // ftk_engine decides
        assertTrue(PerverZijaParse.isGatedPlayer(noXsftk.replace("ftk_engine", ""))) // ftk/xsad decides
        assertFalse(
            PerverZijaParse.isGatedPlayer(noXsftk.replace("ftk_engine", "").replace("ftk/xsad", ""))
        )
    }

    @Test fun `boot detection tolerates player-side formatting drift`() {
        // Same call, formatted the way minifiers/editors rewrite it: window prefix,
        // bracket access, single quotes, line breaks around the arguments.
        val variants = listOf(
            """window.XSFTK.boot('$reportedId', 'ba68f7052da908106bfd6b1f.1792789200', player)""",
            """XSFTK["boot"]("$reportedId","ba68f7052da908106bfd6b1f.1792789200")""",
            "XSFTK.boot(\n  \"$reportedId\",\n  \"ba68f7052da908106bfd6b1f.1792789200\",\n  player\n)"
        )
        for (html in variants) {
            val boot = PerverZijaParse.xsftkBoot(html)
            assertNotNull("boot not detected in: $html", boot)
            assertEquals(reportedId, boot!!.id)
            assertEquals("ba68f7052da908106bfd6b1f.1792789200", boot.token)
        }
    }

    // ---- manifest decode + quality ordering on the new shapes ----------------

    @Test fun `reported manifest decodes to the multi-quality json`() {
        val json = reportedManifest()
        assertTrue(json.startsWith("{"))
        assertTrue(json.contains("\"cdn\""))
        assertEquals(listOf("1080", "720", "480"), XsftkParse.qualities(json))
    }

    @Test fun `single-quality manifest decodes to exactly one quality`() {
        val json = singleQManifest()
        assertTrue(json.startsWith("{"))
        assertTrue(json.contains("\"cdn\""))
        assertEquals(listOf("360"), XsftkParse.qualities(json))
    }

    @Test fun `manifest with the wrong boot token rejects instead of throwing`() {
        val wrongBoot = XsftkParse.Boot(reportedId, "000000000000000000000000.1")
        assertNull(
            PerverZijaParse.decodeXsManifest(manifestBin("perverzija_manifest_reported.bin"), wrongBoot)
        )
    }

    // ---- synthesized playlists per quality ----------------------------------

    @Test fun `reported multi-quality playlists keep every quality`() {
        val json = reportedManifest()
        val boot = PerverZijaParse.xsftkBoot(reportedPage)!!
        val expected = mapOf("1080" to 80, "720" to 76, "480" to 74)
        val keyUrl = "https://pervl3.xtremestream.xyz/player/proto_api.php" +
            "?data=$reportedId&op=key&vt=ba68f7052da908106bfd6b1f.1792789200"

        for ((q, count) in expected) {
            val pl = XsftkParse.variantPlaylist(json, boot, reportedPlayerUrl, q)!!
            assertTrue(pl.startsWith("#EXTM3U"))
            assertTrue(pl.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"$keyUrl\""))
            assertFalse(pl.contains("IV="))
            // manifest td is 8 but a real segment runs 8.341667s → target must cover it
            assertTrue(pl.contains("#EXT-X-TARGETDURATION:9\n"))
            assertEquals(count, pl.lineSequence().count { it.startsWith("#EXTINF:") })
            // first and last segment of that quality, from its own CDN host
            assertTrue(pl.contains("/cdn/down/$reportedId/${q}_enc/${q}0.html"))
            assertTrue(pl.contains("/cdn/down/$reportedId/${q}_enc/$q${count - 1}.html"))
            assertTrue(pl.endsWith("#EXT-X-ENDLIST"))
        }
    }

    @Test fun `single-quality playlist is emitted for the only quality`() {
        val json = singleQManifest()
        val boot = PerverZijaParse.xsftkBoot(singleQPage)!!
        val pl = XsftkParse.variantPlaylist(json, boot, singleQPlayerUrl, "360")!!

        assertTrue(pl.contains("#EXT-X-TARGETDURATION:11\n")) // max dur 10.427078
        assertEquals(1411, pl.lineSequence().count { it.startsWith("#EXTINF:") })
        assertTrue(pl.contains("https://pervl5.xspcdn01.click/cdn/down/$singleQId/360_enc/3600.html"))
        assertTrue(pl.contains("/360_enc/3601410.html"))
        assertNull(XsftkParse.variantPlaylist(json, boot, singleQPlayerUrl, "1080"))
    }

    // ---- legacy family must keep its plain-TS shape --------------------------

    @Test fun `legacy family playlists stay on the plain cdn layout`() {
        // FINDINGS-906: legacy videos serve ONLY /<q>/<q><i>.html as plain MPEG-TS;
        // the gated /<q>_enc/ shape 404s for them (and vice versa). Rewriting the
        // legacy fallback onto the gated shape is the same 2004 class, so pin both.
        val master = fixture("perverzija_legacy_master.m3u8")
        assertEquals(
            listOf("480", "720", "1080"),
            Regex("""&q=(\d+)""").findAll(master).map { it.groupValues[1] }.toList()
        )
        assertTrue(master.contains("https://perv.xtremestream.xyz/player/xs1.php?data=$legacyId&q=480"))

        val variant = fixture("perverzija_legacy_variant480.m3u8")
        val segments = variant.lineSequence().filter { it.startsWith("https://") }.toList()
        assertEquals(269, segments.size)
        // live CDN host families: xspcdnNN and xscasacdnNN (both plain, per-segment)
        assertTrue(segments.all { it.matches(Regex("""https://perv\.[a-z]*cdn\d+\.click/cdn/down/$legacyId/480/480\d+\.html""")) })
        assertFalse(segments.any { it.contains("_enc/") })
    }
}
