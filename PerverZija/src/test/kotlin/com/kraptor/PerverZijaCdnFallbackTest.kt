package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD for issue #1337 (S2 of the PerverZija playback-hardening epic #1328, recurrence #3).
 *
 * The XSFTK manifest of the reported video lists **45 CDN hosts in two families**
 * (`pervl3.xspcdnNN` / `pervl3.xscasacdnNN`, all `*.click`), and the round-2 probe showed
 * byte-identical segment bytes from every one of them (FINDINGS-1328 §1). `variantPlaylist`
 * nevertheless built every segment URL from `cdn[0]` alone, so one blocked/unresolvable
 * host took down *every* quality of the video at once — the connect-level 2001 shape of
 * #1328, and exactly AGENTS.md's "multi-host fallback" hardening for a provider on its
 * third drift recurrence.
 *
 * All selection logic is pure (`XsftkParse`): a bounded probe over a deterministic
 * round-robin order that tries a *different family* as early as possible, plus one manual
 * fallback source from the second family so the app's source sheet can survive a whole
 * family being filtered (device DNS / VPN / `*.click` blockers are out of scope, S1 keeps
 * them diagnosable). Fixture-driven, no HTTP mocking (ADR-0005).
 */
class PerverZijaCdnFallbackTest {

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource(name)!!.readText()

    private fun manifestBin(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream(name)!!.readBytes()

    private val reportedPage by lazy { fixture("perverzija_player_reported_xsftk.html") }
    private val reportedId = "71eee742e4c6e094e6af364597af3f05"
    private val reportedPlayerUrl = "https://pervl3.xtremestream.xyz/player/index.php?data=$reportedId"

    private val reportedBoot by lazy { PerverZijaParse.xsftkBoot(reportedPage)!! }

    private fun reportedManifest(): String =
        PerverZijaParse.decodeXsManifest(manifestBin("perverzija_manifest_reported.bin"), reportedBoot)!!

    private fun reportedHosts(): List<String> = PerverZijaParse.cdnHosts(reportedManifest())

    // ---- 1. the whole roster, not just cdn[0] --------------------------------

    @Test fun `cdn hosts reads the whole roster in manifest order`() {
        val hosts = reportedHosts()
        assertEquals(45, hosts.size)
        assertEquals("45 distinct hosts", 45, hosts.toSet().size)
        assertEquals("pervl3.xspcdn01.click", hosts.first())
        assertEquals("pervl3.xspcdn35.click", hosts.last())
        // manifest order preserved — cdn[0] stays candidate #1 (one probe on a healthy manifest)
        assertEquals(listOf("pervl3.xspcdn01.click", "pervl3.xspcdn02.click"), hosts.take(2))
        // the roster interleaves families in the middle of the list (11..20 are xscasacdn)
        assertEquals("pervl3.xscasacdn01.click", hosts[10])
        assertEquals("pervl3.xspcdn11.click", hosts[20])
    }

    @Test fun `cdn hosts drops blanks and duplicates and survives malformed json`() {
        val json = """{"360":{"td":4,"n":1,"durs":[4.0]},"cdn":["a.xspcdn01.click"," ","a.xspcdn01.click","  b.xspcdn02.click  "]}"""
        assertEquals(listOf("a.xspcdn01.click", "b.xspcdn02.click"), PerverZijaParse.cdnHosts(json))
        assertTrue(PerverZijaParse.cdnHosts("not json").isEmpty())
        assertTrue(PerverZijaParse.cdnHosts("""{"360":{}}""").isEmpty())
        assertTrue(PerverZijaParse.cdnHosts("""{"cdn":[]}""").isEmpty())
    }

    // ---- 2. families are structural, not literal ------------------------------

    @Test fun `cdn family is the second-level label with trailing digits stripped`() {
        val families = reportedHosts().map { PerverZijaParse.cdnFamily(it) }.toSet()
        assertEquals("two families in the reported roster", setOf("xspcdn", "xscasacdn"), families)
        assertEquals("xspcdn", PerverZijaParse.cdnFamily("pervl6.xspcdn01.click"))
        assertEquals("xscasacdn", PerverZijaParse.cdnFamily("j2.xscasacdn03.click"))
        // sub-domain nesting does not change the family key
        assertEquals("xspcdn", PerverZijaParse.cdnFamily("a.b.xspcdn07.click"))
        // unknown shapes fall back to the whole host so they can never merge accidentally
        assertEquals("weird-host", PerverZijaParse.cdnFamily("weird-host"))
        assertEquals("cdn.example", PerverZijaParse.cdnFamily("cdn.example"))
        assertEquals("pervl3.12345.click", PerverZijaParse.cdnFamily("pervl3.12345.click"))
    }

    // ---- 3. deterministic round-robin across families ------------------------

    @Test fun `cdn candidate order is a stable permutation that alternates families first`() {
        val hosts = reportedHosts()
        val ordered = PerverZijaParse.cdnCandidateOrder(hosts)

        assertEquals(hosts.size, ordered.size)
        assertEquals("every host exactly once", hosts.toSet(), ordered.toSet())
        assertEquals("cdn[0] is still candidate #1", hosts.first(), ordered.first())
        assertNotEquals(
            "the second probe must already cover the other family",
            PerverZijaParse.cdnFamily(ordered[0]), PerverZijaParse.cdnFamily(ordered[1])
        )
        assertEquals(
            "the first six candidates cover both families",
            2, ordered.take(6).map { PerverZijaParse.cdnFamily(it) }.toSet().size
        )
        assertEquals("stable across calls", ordered, PerverZijaParse.cdnCandidateOrder(hosts))
        assertTrue(PerverZijaParse.cdnCandidateOrder(emptyList()).isEmpty())
    }

    // ---- 4./5./6. bounded probe over the ordered candidates ------------------

    @Test fun `pick cdn takes the first candidate with exactly one probe`() {
        val ordered = PerverZijaParse.cdnCandidateOrder(reportedHosts())
        var probes = 0
        assertEquals(ordered[0], PerverZijaParse.pickCdn(ordered) { probes++; true })
        assertEquals(1, probes)
    }

    @Test fun `pick cdn skips dead candidates and probes each once in order`() {
        val ordered = PerverZijaParse.cdnCandidateOrder(reportedHosts())
        val probed = mutableListOf<String>()
        val alive = ordered[3]
        assertEquals(alive, PerverZijaParse.pickCdn(ordered) { probed.add(it); it == alive })
        assertEquals("probe count equals the winner's index + 1", ordered.take(4), probed)
    }

    @Test fun `pick cdn over an all dead roster stops at maxProbes`() {
        val ordered = PerverZijaParse.cdnCandidateOrder(reportedHosts())
        var probes = 0
        assertNull(PerverZijaParse.pickCdn(ordered) { probes++; false })
        assertEquals(XsftkParse.MAX_PROBES, probes)
        assertEquals(6, probes)

        var two = 0
        assertNull(PerverZijaParse.pickCdn(ordered, 2) { two++; false })
        assertEquals("maxProbes is honoured", 2, two)
        var zero = 0
        assertNull(PerverZijaParse.pickCdn(ordered, 0) { zero++; false })
        assertEquals("no probes at all", 0, zero)
    }

    // ---- 7. the chosen host reaches every emitted segment --------------------

    @Test fun `playlist and sources use the chosen host for every segment`() {
        val json = reportedManifest()
        val chosen = "pervl3.xscasacdn07.click"

        val playlist = PerverZijaParse.variantPlaylist(json, reportedBoot, reportedPlayerUrl, "720", chosen)!!
        val segments = playlist.lineSequence().filter { it.contains("/cdn/down/") }.toList()
        assertEquals(76, segments.size)
        assertTrue(
            "every segment must name the chosen host, not cdn[0]",
            segments.all { it.startsWith("https://$chosen/cdn/down/$reportedId/720_enc/720") }
        )
        assertFalse(playlist.contains("xspcdn"))
        assertEquals(777, playlist.lineSequence().count { it.startsWith("#EXTINF:") })

        val sources = PerverZijaParse.playableSources(json, reportedBoot, reportedPlayerUrl, chosen)
        assertEquals(listOf("1080", "720", "480"), sources.map { it.label })
        assertTrue(sources.all { it.playlist.contains("https://$chosen/cdn/down/") })
    }

    // ---- 8. manual fallback from the other family ---------------------------

    @Test fun `alt cdn host comes from a different family`() {
        val ordered = PerverZijaParse.cdnCandidateOrder(reportedHosts())
        val primary = ordered.first()
        val alt = PerverZijaParse.altCdnHost(ordered, PerverZijaParse.cdnFamily(primary), primary) { true }
        assertNotNull(alt)
        assertNotEquals(PerverZijaParse.cdnFamily(primary), PerverZijaParse.cdnFamily(alt!!))
        assertNotEquals(primary, alt)
    }

    @Test fun `alt cdn host is null for a single family roster or an all dead alternate`() {
        val singleFamily = listOf("pervl3.xspcdn01.click", "pervl3.xspcdn02.click")
        assertNull(
            "one family gives no fallback to offer",
            PerverZijaParse.altCdnHost(singleFamily, "xspcdn", "pervl3.xspcdn01.click") { true }
        )

        val ordered = PerverZijaParse.cdnCandidateOrder(reportedHosts())
        val primary = ordered.first()
        var probes = 0
        assertNull(PerverZijaParse.altCdnHost(ordered, PerverZijaParse.cdnFamily(primary), primary) {
            probes++
            false
        })
        assertEquals("alt probing is bounded", XsftkParse.MAX_ALT_PROBES, probes)
        assertEquals(3, probes)
    }

    // ---- 9. fail closed: no live host, no source ----------------------------

    @Test fun `all dead roster plans zero sources`() {
        val json = reportedManifest()
        assertNull(PerverZijaParse.pickCdn(PerverZijaParse.cdnCandidateOrder(reportedHosts())) { false })
        val planned = PerverZijaParse.cdnSources(json, reportedBoot, reportedPlayerUrl) { _, _ -> false }
        assertTrue("a dead roster must emit nothing (never a decoy)", planned.isEmpty())
    }

    @Test fun `healthy roster plans every quality plus one alt source`() {
        val json = reportedManifest()
        val probed = mutableListOf<Pair<String, String>>()
        val planned = PerverZijaParse.cdnSources(json, reportedBoot, reportedPlayerUrl) { host, q ->
            probed.add(host to q)
            true
        }

        assertEquals("3 qualities + 3 alt sources", 6, planned.size)
        assertEquals(listOf("1080", "720", "480"), planned.filter { !it.alt }.map { it.quality })
        assertEquals(listOf("1080", "720", "480"), planned.filter { it.alt }.map { it.quality })
        // one probe for the primary pick (highest buildable quality) + one for the alt
        assertEquals(listOf("pervl3.xspcdn01.click" to "1080", "pervl3.xscasacdn01.click" to "1080"), probed)
        // alt playlist is the same quality set from the other family
        val altHosts = planned.filter { it.alt }.map { it.cdnHost }.toSet()
        assertEquals(1, altHosts.size)
        assertEquals(setOf("pervl3.xscasacdn01.click"), altHosts)
        assertTrue(planned.filter { it.alt }.all { it.playlist.contains("https://${it.cdnHost}/cdn/down/") })
        assertTrue(planned.filter { !it.alt }.all { it.cdnHost == "pervl3.xspcdn01.click" })
    }

    @Test fun `single family roster plans no alt source`() {
        val json = """{"720":{"td":4,"n":2,"durs":[4.0,4.0]},
            "cdn":["pervl3.xspcdn01.click","pervl3.xspcdn02.click"]}"""
        val planned = PerverZijaParse.cdnSources(json, reportedBoot, reportedPlayerUrl) { _, _ -> true }
        assertEquals(1, planned.size)
        assertEquals("pervl3.xspcdn01.click", planned[0].cdnHost)
        assertFalse(planned[0].alt)
    }

    // ---- 10. the #908 decision point must not move -------------------------

    @Test fun `player plan regression - gated, gated-bootless and pre-gate stay put`() {
        assertTrue(
            "gated page still plans the XSFTK chain",
            PerverZijaParse.playerPlan(reportedPlayerUrl, reportedPage) is XsftkParse.PlayerPlan.Xsftk
        )
        // A gated page whose boot call was lost must still fail closed, never xs1.php.
        val bootMiss = reportedPage.replace(Regex("""(?m)^.*XSFTK\.boot\(.*$"""), "")
        assertEquals(XsftkParse.PlayerPlan.FailClosed, PerverZijaParse.playerPlan(reportedPlayerUrl, bootMiss))

        // A pre-gate page keeps its live tokenless master.
        val preGate = fixture("perverzija_player_legacy_bootmiss.html")
        assertTrue(
            "pre-gate page keeps the legacy master",
            PerverZijaParse.playerPlan(
                "https://perv.xtremestream.xyz/player/index.php?data=60ad83801910ec976590f69f638e0d6d",
                preGate
            ) is XsftkParse.PlayerPlan.Legacy
        )
    }
}
