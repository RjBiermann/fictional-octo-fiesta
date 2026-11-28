package com.kraptor

import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.StreamTapeNet
import com.lagradost.cloudstream3.extractors.StreamTapeXyz
import com.lagradost.cloudstream3.extractors.VinovoTo
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
     * (d) Cat3Film backup embed (#613 Story 2, FINDINGS 2026-10-15): the site's sources
     * API backup lives on the player. subdomain (player.abyssplayer.com/<id>). The
     * framework's loadExtractor primary dispatch is startsWith(target, stripped mainUrl);
     * the player subdomain does not prefix-match the apex row, so without an explicit
     * row the backup route only resolves via the fuzzy Levenshtein fallback. Pin that an
     * explicit AbyssPlayer row answers it — and that the apex row is not shadowed.
     */
    @Test
    fun `abyss backup embed player subdomain has an explicit row`() {
        val playerRows = rows.filter { host(it.mainUrl) == "player.abyssplayer.com" }
        assertTrue(
            "no row answers player.abyssplayer.com — the #613 backup embed dispatch " +
                "relies on the fuzzy Levenshtein fallback",
            playerRows.isNotEmpty()
        )
        assertTrue(playerRows.single() is AbyssPlayer)
        // The apex row stays reachable for apex-host embeds (Film1k fixture shape).
        val apexRows = rows.filter { host(it.mainUrl) == "abyssplayer.com" }
        assertTrue("apex abyssplayer row missing", apexRows.isNotEmpty())
        assertTrue(apexRows.single() is AbyssPlayer)
    }

    /**
     * (e) Issue #653 deep pass (2026-10-16): xmoviesforyou.com serves FOUR filehost
     * families on its video pages — streamtape.com, mixdrop.ag, dood.to, bigwarp.io
     * (probe: 4 sampled pages; streamtape on 3, dood.to on 1, bigwarp.io + mixdrop.ag
     * on 1). loadExtractor dispatches by registered-mainUrl prefix, so a served family
     * with no registry row means its anchors are silently dropped — zero links from
     * every page that carries only that family. Run 8 (FINDINGS-535) verified only the
     * streamtape row; the rotating deep pass surfaced the other two.
     */
    @Test
    fun `xmfy-served filehost families have registered rows`() {
        val hosts = rows.map { host(it.mainUrl) }.toSet()
        // #1446: the MIXDROP chip host rotates through the mixdrop alias family; the
        // provider resolves the alias hop, so the canonical mxdrop.click target needs the row.
        val served = listOf("streamtape.com", "mixdrop.ag", "mxdrop.click", "dood.to", "bigwarp.io")
        val missing = served.filter { it !in hosts }
        assertTrue(
            "filehost families xmoviesforyou.com serves have no registry row " +
                "(loadExtractor drops their anchors): $missing",
            missing.isEmpty()
        )
    }

    /**
     * (e2) Issue #1446 (2026-11-22 XMoviesForYou video-source audit): the site's MIXDROP
     * chip rotates hostnames every sweep — mxdrop.click, m1xdrop.bz, mixdrp.click,
     * miiixdrop.net, miiiiixdrop.net, miiixdrop.top were all observed live in one run, each
     * 30x-redirecting to mxdrop.click/f/<id>. Pinning a row per alias is a losing race, so
     * the provider follows the hop and this canonical row must stay in the MixDrop family
     * (the framework adapter rewrites /f/<id> → /e/<id> and decodes MDCore.wurl).
     */
    @Test
    fun `mxdrop click canonical row serves the rotating mixdrop aliases`() {
        val mxdropRows = rows.filter { host(it.mainUrl) == "mxdrop.click" }
        assertEquals("exactly one row answers mxdrop.click", 1, mxdropRows.size)
        assertTrue(
            "the mxdrop.click row is not a MixDrop-family adapter: ${mxdropRows.single()::class.simpleName}",
            mxdropRows.single() is MixDrop
        )
    }

    /**
     * (e) Mangoporn's default data-servers host moved to vidara.so (#657 FINDINGS:
     * live capture 2026-10-17, video-1 data-default="0" → vidara.so/e/…). The
     * shared Vidara adapter answers vidara.to; without an explicit .so row
     * loadExtractor drops the site's primary embed (multi-host fallback rule).
     */
    @Test
    fun `vidara so mirror row serves the vidara family`() {
        val soRows = rows.filter { host(it.mainUrl) == "vidara.so" }
        assertTrue(
            "no row answers vidara.so — Mangoporn's default embed host(dispatch drops it)",
            soRows.isNotEmpty()
        )
        // same adapter family as the .to row — one API contract (POST /api/stream)
        assertTrue(soRows.single() is Vidara)
        // the .to row stays reachable for .to embeds (jav.guru searcho "cd")
        assertTrue(rows.any { host(it.mainUrl) == "vidara.to" })
    }

    /**
     * (f) Issue #670 (2026-10-16 Javmost deep pass): dood.so is a dood-family mirror —
     * dood.so/e/<id> answers 301 → playmogo.com/e/<id> (curl-verified live), and the
     * playmogo row IS registered. But loadExtractor dispatches by registered-mainUrl
     * prefix on the ORIGINAL URL, so a dood.so anchor never reaches the playmogo row
     * and the stream is dropped — in every provider, not just Javmost. Pin an explicit
     * dood.so row in the dood (DoodStream) engine family.
     */
    @Test
    fun `dood so mirror row serves the dood family`() {
        val soRows = rows.filter { host(it.mainUrl) == "dood.so" }
        assertTrue(
            "no row answers dood.so — dood-family embeds dispatched as dood.so URLs " +
                "are dropped (their 301 → playmogo never helps dispatch)",
            soRows.isNotEmpty()
        )
        // same dood engine family as the playmogo row — one API contract (pass_md5 flow)
        assertTrue(soRows.single() is DoodStream)
        // the redirect target row stays reachable for playmogo-host embeds
        assertTrue(rows.any { host(it.mainUrl) == "playmogo.com" })
    }

    /**
     * (g) Issue #1439 (HQPorner video-source audit): the catalogue is served by three
     * player generations. mydaddy.cc was the only one with a registry row — hqwo.cc (the
     * older uploads, same single-quoted mp4 quality anchors as mydaddy) scored 71 on the
     * framework's Levenshtein mirror fallback (<80 threshold) and flyflv had no row at
     * all, so every hqwo/flyflv video emitted zero links. Pin an explicit row for each
     * host the provider forwards, so dispatch never depends on fuzzy matching.
     */
    @Test
    fun `hqporner player generations have explicit registry rows`() {
        val hosts = rows.map { host(it.mainUrl) }.toSet()
        val missing = listOf("mydaddy.cc", "hqwo.cc", "www.flyflv.com").filter { it !in hosts }
        assertTrue(
            "HQPorner player hosts have no registry row (loadExtractor drops those videos): $missing",
            missing.isEmpty()
        )
        // hqwo shares the mydaddy grammar — one adapter family, no duplicated extractor.
        assertTrue(rows.single { host(it.mainUrl) == "hqwo.cc" } is MyDaddyExtractor)
        assertTrue(rows.single { host(it.mainUrl) == "mydaddy.cc" } is MyDaddyExtractor)
        // the third generation has its own source-tag grammar.
        assertTrue(rows.single { host(it.mainUrl) == "www.flyflv.com" } is FlyFlv)
        assertTrue(HubEmbeds.covered("https://hqwo.cc/player/b214fc93241fe8cc9eacac028a8de065?img=x"))
        assertTrue(HubEmbeds.covered("https://www.flyflv.com/movies/player/5135"))
    }

    /**
     * (h) Issue #1442 (JavGuru video-source audit): the searcho mirror rotates its player
     * hosts, and two of them carried real streams with no row — javplaya.com (the packed
     * `links={hls2,hls3,hls4}` family, identical to javclan) and maxstream.org (Playerjs
     * `file:` inside the same packer). loadExtractor dispatches by registered-mainUrl
     * prefix, so both were dropped on every page that offered them (14/16 and 4/16 of the
     * run's samples). Pin an explicit row for each, in the adapter family that parses it.
     */
    @Test
    fun `javguru searcho player hosts have explicit registry rows`() {
        val hosts = rows.map { host(it.mainUrl) }.toSet()
        val missing = listOf("javplaya.com", "maxstream.org").filter { it !in hosts }
        assertTrue(
            "jav.guru searcho player hosts have no registry row (loadExtractor drops them): $missing",
            missing.isEmpty()
        )
        // javplaya serves the same `links` object javclan does — one adapter family.
        assertTrue(rows.single { host(it.mainUrl) == "javplaya.com" } is Javclan)
        // maxstream is a Playerjs player: different grammar, own adapter.
        assertTrue(rows.single { host(it.mainUrl) == "maxstream.org" } is Maxstream)
        // the javclan.com row these mirror off must stay registered.
        assertTrue(rows.any { host(it.mainUrl) == "javclan.com" })
    }

    /**
     * (i) Issue #1512 (S9/#1447 PandaMovies + S23/#1461 Mangoporn video-source audits):
     * both providers enumerate `section.hlm[data-servers]` host-agnostically and dispatch
     * each `u` through loadExtractor, so a served host with no row is a silently dropped
     * mirror. `vinovo.to` was served on 5/15 PandaMovies and 4/12 Mangoporn samples and
     * had no row. The framework ships `VinovoTo` (mainUrl https://vinovo.to) whose parse
     * targets match the live page verbatim — `meta[name=token|file_code|recaptcha]` +
     * `video[data-base]`, POST /api/file/url/ with Origin + X-Requested-With, reCAPTCHA
     * via APIHolder.getCaptchaToken (jar-verified against cloudstream3:pre-release,
     * 2026-11-27). So the row is the fix — and it must be the VinovoTo family, not a
     * dood row: vinovo.to does NOT serve the dood pass_md5 grammar (FINDINGS-1447).
     */
    @Test
    fun `vinovo to embed host has an explicit registry row`() {
        val vinovoRows = rows.filter { host(it.mainUrl) == "vinovo.to" }
        assertEquals("exactly one row answers vinovo.to", 1, vinovoRows.size)
        val row = vinovoRows.single()
        assertTrue(
            "the vinovo.to row is not the framework VinovoTo adapter: ${row::class.simpleName}",
            row is VinovoTo
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
            "AbyssPlayer" to 2,       // AbyssPlayer apex (issue #233) + player. subdomain row (#613)
            "Byse" to 16,            // filemoon byse*.com mirror family
            "DoodStream" to 11,      // DoodStream class (myvidplay.com override) + dood factory default + DoodToExtractor row (#653) + dood.so mirror (#670)
            "EarnVids" to 5,         // vidHidePro mirrors
            "Filemoon" to 7,         // filemoon factory default
            "HlsFree" to 2,          // HlsFree class + HlsFreeWww www. variant
            "LuluStream" to 3,       // lulu(…, "LuluStream") + luluvido.com (mangoporn #564 grammar, FINDINGS-564)
            "Lulustream" to 6,       // lulu factory default
            "MixDrop" to 4,          // MixDropAg + MixDropMy + mixdrop.is + mxdrop.click (#1446 rotating-alias canonical) mirror
            "Player4Me" to 8,        // Player4Me class + player4me mirrors
            "Streamtape" to 7,       // custom StreamTAPE + streamtapeMirror rows
            "Streamwish" to 2,       // Streamwish base + Filesim-pathed swhoi row
            "Txxx" to 3,             // KVS videofile family (issue #1445): videotxxx.com mirror + txxx.com + txxx.me rows
            "Vidara" to 2,           // Vidara (.to, jav.guru "cd") + VidaraSo mirror — Mangoporn default host (issue #657)
            "VidHidePro" to 7,       // VidHidePro class + vidHidePro mirrors
            "Voe" to 4,              // Voe base + voeMirror rows
        )
    }
}
