package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD for issue #908 (S3 of the PerverZija residual-2004 epic #903): wire the #907
 * Parse seam into one structural fallback chain so the shared extractor can never
 * land on the tokenless `xs1.php` decoy while a gate exists on the player page.
 *
 * The #906 census showed two mutually exclusive player generations (FINDINGS-906):
 *  - gated  (94.9%) — `XSFTK.boot(...)` → XOR manifest → `_enc` AES segments; the
 *    tokenless `xs1.php` master is a decoy (playlist 200, every segment 404s);
 *  - legacy (4.8%)  — no boot line, `xs1.php` master → plain `/<q>/` MPEG-TS, live.
 * The owner's "one source, no qualities" 2004 is a gated page whose boot call the
 * matcher misses: the old adapter then emitted the decoy. `playerPlan` is the single
 * decision point that fails closed there instead, and keeps the live legacy path.
 *
 * Everything here is fixture-driven or a synthetic manifest string — no HTTP
 * mocking (ADR-0005); the live chain is covered by pipeline Verification (#909).
 */
class PerverZijaFallbackChainTest {

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource(name)!!.readText()

    private fun manifestBin(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream(name)!!.readBytes()

    private val reportedPage by lazy { fixture("perverzija_player_reported_xsftk.html") }
    private val reportedId = "71eee742e4c6e094e6af364597af3f05"
    private val reportedToken = "ba68f7052da908106bfd6b1f.1792789200"
    private val reportedPlayerUrl = "https://pervl3.xtremestream.xyz/player/index.php?data=$reportedId"

    private val singleQPage by lazy { fixture("perverzija_player_singleq_xsftk.html") }
    private val singleQId = "95177e528f8d6c7c28a5473fd5a471b6"
    private val singleQPlayerUrl = "https://pervl5.xtremestream.xyz/player/index.php?data=$singleQId"

    private val legacyPage by lazy { fixture("perverzija_player_legacy_bootmiss.html") }
    private val legacyId = "60ad83801910ec976590f69f638e0d6d"
    private val legacyPlayerUrl = "https://perv.xtremestream.xyz/player/index.php?data=$legacyId"

    private fun reportedManifest(): String {
        val boot = PerverZijaParse.xsftkBoot(reportedPage)!!
        return PerverZijaParse.decodeXsManifest(manifestBin("perverzija_manifest_reported.bin"), boot)!!
    }

    private fun singleQManifest(): String {
        val boot = PerverZijaParse.xsftkBoot(singleQPage)!!
        return PerverZijaParse.decodeXsManifest(manifestBin("perverzija_manifest_singleq.bin"), boot)!!
    }

    // ---- the fallback chain (decoy guard is the invariant) -------------------

    @Test fun `gated page plans the booted XSFTK chain`() {
        val plan = PerverZijaParse.playerPlan(reportedPlayerUrl, reportedPage)
        assertTrue("gated page must plan the XSFTK chain, got $plan", plan is XsftkParse.PlayerPlan.Xsftk)
        val boot = (plan as XsftkParse.PlayerPlan.Xsftk).boot
        assertEquals(reportedId, boot.id)
        assertEquals(reportedToken, boot.token)
    }

    @Test fun `gated page that lost its boot call fails closed`() {
        // Strip the inline call the way a re-minified/partial page would: the FTK
        // assets still load, the boot arguments are gone. The legacy branch would
        // emit xs1.php here — a 200 playlist whose segments all 404 (2004).
        val bootMiss = reportedPage.replace(Regex("""(?m)^.*XSFTK\.boot\(.*$"""), "")
        val plan = PerverZijaParse.playerPlan(reportedPlayerUrl, bootMiss)
        assertEquals(XsftkParse.PlayerPlan.FailClosed, plan)
        assertFalse(plan is XsftkParse.PlayerPlan.Legacy)
        assertFalse(plan.toString().contains("xs1.php"))
    }

    @Test fun `legacy page keeps the live tokenless master`() {
        val plan = PerverZijaParse.playerPlan(legacyPlayerUrl, legacyPage)
        assertTrue("legacy page must keep the xs1.php path, got $plan", plan is XsftkParse.PlayerPlan.Legacy)
        assertEquals(
            "https://perv.xtremestream.xyz/player/xs1.php?data=$legacyId",
            (plan as XsftkParse.PlayerPlan.Legacy).masterUrl
        )
    }

    @Test fun `player url variants all land on the same plan`() {
        // The player paths are not one byte shape: keep the id-based builder so a
        // trailing slash, extra query params or a path change cannot drop the source
        // (or, on a gated page, sneak the decoy in).
        fun variants(id: String) = listOf(
            "https://pervl3.xtremestream.xyz/player/index.php?data=$id",
            "https://pervl3.xtremestream.xyz/player/index.php?data=$id&autoplay=1",
            "https://pervl3.xtremestream.xyz/player/?data=$id",
            "https://pervl3.xtremestream.xyz/player/index.php?data=$id&data=$id"
        )
        for (url in variants(reportedId)) {
            assertEquals("gated plan for $url", reportedId, planId(PerverZijaParse.playerPlan(url, reportedPage)))
        }
        for (url in variants(legacyId)) {
            assertEquals("legacy plan for $url", legacyId, planId(PerverZijaParse.playerPlan(url, legacyPage)))
        }
    }

    /** boot id of an Xsftk plan, or the data id of a Legacy master — so one assertion
     *  covers both families and a wrong-family plan fails loudly. */
    private fun planId(plan: XsftkParse.PlayerPlan): String? = when (plan) {
        is XsftkParse.PlayerPlan.Xsftk -> plan.boot.id
        is XsftkParse.PlayerPlan.Legacy -> XsftkParse.playerId(plan.masterUrl)
        XsftkParse.PlayerPlan.FailClosed -> null
    }

    @Test fun `boot call binds by argument shape not position`() {
        // Token first, id second — a reordering an obfuscator can produce. Both are
        // still recognised: the dotted argument is the token, the plain hex one the
        // id, regardless of order.
        val swapped = """XSFTK.boot("$reportedToken", "$reportedId", player)"""
        val boot = PerverZijaParse.xsftkBoot(swapped)!!
        assertEquals(reportedId, boot.id)
        assertEquals(reportedToken, boot.token)
    }

    @Test fun `boot call tolerates a token without the expiry suffix`() {
        // Structural, not literal: the URL supplies the id, so any second argument is
        // the token even if the site drops the "<hex>.<epoch>" suffix.
        val html = """window.XSFTK["boot"](
            '$reportedId',
            'newtoken-format-2026',
            player
        )"""
        val boot = PerverZijaParse.xsftkBoot(html, reportedId)!!
        assertEquals(reportedId, boot.id)
        assertEquals("newtoken-format-2026", boot.token)
    }

    @Test fun `boot call with unrelated id does not bind to the page`() {
        // A stale/decoy boot line for another video must not be used for this id.
        val html = """XSFTK.boot("00000000000000000000000000000000", "$reportedToken", player)"""
        assertNull(PerverZijaParse.xsftkBoot(html, reportedId))
        assertTrue(PerverZijaParse.isGatedPlayer(html))
    }

    // ---- single / odd-quality manifests --------------------------------------

    @Test fun `multi-quality manifest emits every quality in order`() {
        val json = reportedManifest()
        val boot = PerverZijaParse.xsftkBoot(reportedPage)!!
        val sources = XsftkParse.playableSources(json, boot, reportedPlayerUrl)

        assertEquals(listOf("1080", "720", "480"), sources.map { it.label })
        for (source in sources) {
            assertTrue(source.playlist.startsWith("#EXTM3U"))
            assertTrue(source.playlist.contains("/${source.label}_enc/${source.label}0.html"))
        }
    }

    @Test fun `single-quality manifest still emits its one source`() {
        // 34/319 gated videos expose exactly one quality ("only one source, no
        // qualities" is the correct shape for them, not a failure).
        val json = singleQManifest()
        val boot = PerverZijaParse.xsftkBoot(singleQPage)!!
        val sources = XsftkParse.playableSources(json, boot, singleQPlayerUrl)

        assertEquals(1, sources.size)
        assertEquals("360", sources[0].label)
        assertTrue(sources[0].playlist.contains("/360_enc/3600.html"))
        assertEquals(1411, sources[0].playlist.lineSequence().count { it.startsWith("#EXTINF:") })
    }

    @Test fun `one broken quality does not drop the playable ones`() {
        // A manifest entry that lost its duration list used to make the quality list
        // emit nothing for that entry — with a single-quality manifest that is zero
        // sources overall. The chain must keep every quality it can actually build.
        val json = """
            {"1080":{"td":4,"n":2,"durs":[4.0,4.0],"bw":300,"res":"1920x1080"},
             "720":{"td":4},
             "360":{"td":4,"n":2,"durs":[4.0,4.0],"bw":100,"res":"640x360"},
             "cdn":["cdn.example"]}
        """.trimIndent()
        val boot = XsftkParse.Boot(reportedId, reportedToken)
        val sources = XsftkParse.playableSources(json, boot, reportedPlayerUrl)

        assertEquals(listOf("1080", "360"), sources.map { it.label })
    }

    @Test fun `non-numeric quality labels survive as labels`() {
        val json = """{"hd":{"td":4,"n":1,"durs":[4.0]},"sd":{"td":4,"n":1,"durs":[4.0]},"cdn":["cdn.example"]}"""
        val boot = XsftkParse.Boot(reportedId, reportedToken)
        val sources = XsftkParse.playableSources(json, boot, reportedPlayerUrl)

        assertEquals(listOf("hd", "sd"), sources.map { it.label })
        assertNull("non-numeric labels map to Unknown at the adapter", sources[0].label.toIntOrNull())
    }

    @Test fun `manifest without qualities or cdn emits nothing instead of throwing`() {
        val boot = XsftkParse.Boot(reportedId, reportedToken)
        assertTrue(XsftkParse.playableSources("""{"cdn":["cdn.example"]}""", boot, reportedPlayerUrl).isEmpty())
        assertTrue(XsftkParse.playableSources("""{"360":{"td":4,"n":2,"durs":[4.0,4.0]}}""", boot, reportedPlayerUrl).isEmpty())
        assertTrue(XsftkParse.playableSources("not json", boot, reportedPlayerUrl).isEmpty())
    }

    // ---- loadLinks iframe tolerance -----------------------------------------

    @Test fun `player iframe is picked over the theme widgets`() {
        val doc = Jsoup.parse(fixture("perverzija_video_embeds_xtremestream.html"))
        assertEquals(
            "https://pervl3.xtremestream.xyz/player/index.php?data=$reportedId",
            PerverZijaParse.playerEmbed(doc)
        )
    }

    @Test fun `alternate embed host is not dropped`() {
        // #906 census: 1/336 video pages embed mixdrop.to instead of xtremestream
        // (its upstream file is dead, but dropping the iframe is a separate defect).
        val doc = Jsoup.parse(fixture("perverzija_video_embeds_mixdrop.html"))
        assertEquals("https://mixdrop.to/e/enl4jzdjt76r6q", PerverZijaParse.playerEmbed(doc))
    }

    @Test fun `widget-only page yields no embed`() {
        val doc = Jsoup.parse(
            """<iframe src="https://discord.com/widget?id=1"></iframe>""" +
                """<iframe src="about:blank"></iframe>""" +
                """<iframe src="javascript:void(0)"></iframe>"""
        )
        assertNull(PerverZijaParse.playerEmbed(doc))
        assertNull(PerverZijaParse.playerEmbed(Jsoup.parse("<div>no iframe</div>")))
    }

    @Test fun `protocol-relative player src is normalized`() {
        val doc = Jsoup.parse("""<iframe src="//pervl2.xtremestream.xyz/player/index.php?data=$legacyId"></iframe>""")
        assertEquals(
            "https://pervl2.xtremestream.xyz/player/index.php?data=$legacyId",
            PerverZijaParse.playerEmbed(doc)
        )
    }
}
