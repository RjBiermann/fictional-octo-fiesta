package com.kraptor

import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.StreamTapeNet
import com.lagradost.cloudstream3.extractors.StreamTapeXyz
import com.lagradost.cloudstream3.utils.ExtractorApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #353 (review #347 P0-10/P0-11): the 120+-row registration table in
 * HostRegistry depends on "loadExtractor matches the LAST registered adapter
 * first" (verified against the framework jar: ExtractorApiKt.loadExtractor
 * iterates last → first and returns the first adapter whose mainUrl is a
 * prefix of the target URL). A reorder that kills a mirror family used to
 * compile and test green. These tests pin the pure-data contract.
 *
 * StreamTape supersession, precisely: loadExtractor dispatches by mainUrl
 * prefix, so the contract that keeps the custom com.kraptor.StreamTAPE in
 * charge of streamtape traffic is (a) the framework StreamTape family is
 * never registered raw, and (b) the custom adapter is the unique row whose
 * mainUrl answers https://streamtape.com. (Mirror rows are anonymous
 * subclasses of the custom adapter — see the streamtapeMirror factory — so
 * "StreamTAPE() after the streamtapeMirror rows" is neither true of the table
 * nor the load-bearing invariant; the table deliberately interleaves them.)
 */
class HostRegistryTest {

    private val rows: List<ExtractorApi> = sharedHostRegistry()

    private fun host(url: String): String {
        val stripped = url.substringAfter("//")
        return stripped.substringBefore('/').substringBefore(':').lowercase()
    }

    /** (a) Every registered mainUrl is unique — a duplicate is a dead row under last-registered-wins. */
    @Test
    fun `every registered mainUrl is unique`() {
        val urls = rows.map { it.mainUrl }
        assertEquals(
            "duplicate mainUrls in sharedHostRegistry — the earlier row is dead (last-registered-wins)",
            urls.size,
            urls.toSet().size
        )
    }

    /** No mainUrl may host-shadow another: a target matching both always resolves to the LAST row. */
    @Test
    fun `no mainUrl host-shadows another`() {
        val shadowed = rows.flatMap { later ->
            rows.filter { earlier ->
                earlier !== later &&
                    earlier.mainUrl != later.mainUrl &&
                    host(later.mainUrl).startsWith(host(earlier.mainUrl))
            }.map { earlier -> earlier.mainUrl to later.mainUrl }
        }
        assertTrue(
            "host-shadowed mainUrls — the earlier row can never win: $shadowed",
            shadowed.isEmpty()
        )
    }

    /**
     * (b) The StreamTape supersession invariant (the real one — see class doc):
     * the framework StreamTape family is never registered raw, and the row
     * answering streamtape.com is the custom com.kraptor.StreamTAPE.
     */
    @Test
    fun `custom StreamTAPE supersedes the framework StreamTape family`() {
        val framework = setOf(
            StreamTape().mainUrl,
            StreamTapeNet().mainUrl,
            StreamTapeXyz().mainUrl,
        )
        // Framework hosts are all served by the custom family in the shared
        // table (streamtape.com by StreamTAPE itself, .net/.xyz by
        // streamtapeMirror rows). A raw framework row at any of those hosts is
        // what last-registered-wins would use to supersede the custom adapter —
        // so a framework host must have NO row outside the custom family.
        val registered = rows.map { it.mainUrl }.toSet()
        val byUrl = rows.associateBy { it.mainUrl }
        val clash = registered
            .filter { reg -> framework.any { host(reg) == host(it) } }
            .filter { byUrl.getValue(it) !is StreamTAPE }
            .toSet()
        assertTrue(
            "framework StreamTape rows registered raw — last-registered-wins would supersede the custom adapter: $clash",
            clash.isEmpty()
        )
        assertTrue(
            "framework StreamTape rows registered raw — last-registered-wins would supersede the custom adapter: $clash",
            clash.isEmpty()
        )
        // Every row handling a streamtape host must be the custom adapter family.
        val streamtapeRows = rows.filter { host(it.mainUrl).startsWith("streamtape") }
        assertTrue("no streamtape rows at all — mirror family would be dead", streamtapeRows.isNotEmpty())
        val foreign = streamtapeRows.filter { it !is StreamTAPE }
        assertTrue(
            "non-custom StreamTAPE rows registered: ${foreign.map { it.mainUrl }}",
            foreign.isEmpty()
        )
        // And the canonical streamtape.com host is answered exactly once, by the custom adapter.
        val canonical = streamtapeRows.filter { host(it.mainUrl) == "streamtape.com" }
        assertEquals("exactly one row answers streamtape.com", 1, canonical.size)
        assertTrue(
            "the streamtape.com row is not the custom com.kraptor.StreamTAPE",
            canonical.single() is StreamTAPE
        )
    }

    /**
     * (c) Naming collisions across code paths (issue #347 P0-11) are
     * intentional — but only the documented ones. Names are user-facing in
     * CloudStream's source list; mirror families legitimately share a display
     * name. Any NEW collision must consciously update INTENTIONAL_NAME_COLLISIONS.
     */
    @Test
    fun `name collisions are exactly the intentional allowlist`() {
        val collisions = rows.groupBy { it.name }
            .filterValues { it.size > 1 }
            .mapValues { (_, v) -> v.size }
        val outside = collisions.filterKeys { it !in INTENTIONAL_NAME_COLLISIONS }
        assertTrue(
            "collisions outside the allowlist (add them to INTENTIONAL_NAME_COLLISIONS or rename): $outside",
            outside.isEmpty()
        )
        assertEquals(
            "a documented collision changed (row added/removed — update the allowlist consciously): " +
                "documented=$INTENTIONAL_NAME_COLLISIONS actual=$collisions",
            INTENTIONAL_NAME_COLLISIONS,
            collisions
        )
    }

    companion object {
        /**
         * Every name shared by >1 row today, with the number of rows (issue #353
         * probe census; supersedes REVIEW.md's stale P0-11 counts of 6×EarnVids /
         * 3×Streamwish). Mirrors share a display name on purpose; note the
         * diagnosability tax REVIEW.md flagged: swhoi.com is served by the
         * Filesim adapter under the "Streamwish" name (2 rows total).
         */
        private val INTENTIONAL_NAME_COLLISIONS = mapOf(
            "Byse" to 16,            // filemoon byse*.com mirror family
            "DoodStream" to 9,       // DoodStream class (myvidplay.com override) + dood factory default
            "EarnVids" to 5,         // vidHidePro mirrors
            "Filemoon" to 7,         // filemoon factory default
            "HlsFree" to 2,          // HlsFree class + HlsFreeWww www. variant
            "LuluStream" to 2,       // lulu(…, "LuluStream")
            "Lulustream" to 6,       // lulu factory default
            "MixDrop" to 3,          // MixDropAg + MixDropMy + mixdrop.is mirror
            "Player4Me" to 8,        // Player4Me class + player4me mirrors
            "Streamtape" to 7,       // custom StreamTAPE + streamtapeMirror rows
            "Streamwish" to 2,       // Streamwish base + Filesim-pathed swhoi row
            "VidHidePro" to 7,       // VidHidePro class + vidHidePro mirrors
            "Voe" to 4,              // Voe base + voeMirror rows
        )
    }
}
