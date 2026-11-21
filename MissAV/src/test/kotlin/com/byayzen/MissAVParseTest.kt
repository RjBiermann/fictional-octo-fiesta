package com.byayzen

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture: real missav.live /en/abf-384 meta rows (issue #274). */
class MissAVParseTest {

    private val doc by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/missav-video-meta.html")!!, "UTF-8", "https://missav.live/")
    }

    @Test fun `actress row only - no genre pollution`() {
        assertEquals(listOf("Nonoura Warm"), MissAVParse.parseActors(Jsoup.parse("""
            <div class="text-secondary"><span>Actress:</span><a href="#">Nonoura Warm</a></div>
            <div class="text-secondary"><span>Genre:</span><a href="#">Av Actress</a></div>""", "https://missav.live/")))
    }

    /** Site exposes both Actress: and Actor: rows (issue #328, roe-469) — both must feed addActors. */
    @Test fun `actor row is included alongside actress`() {
        assertEquals(listOf("Nonoura Warm", "Tooru Ozawa"), MissAVParse.parseActors(doc))
    }

    @Test fun `genres include Av Actress link without leaking into actors`() {
        val tags = MissAVParse.parseTags(doc)
        assertEquals(6, tags.size)
        assertEquals("Av Actress", tags.last())
    }

    /** og:video:duration is seconds; CloudStream wants minutes (issue #302, midv-852 = 7256s = 120min). */
    @Test fun `duration seconds to minutes`() {
        assertEquals(120, MissAVParse.parseDuration("7256"))
        assertEquals(2, MissAVParse.parseDuration("120"))
        assertEquals(null, MissAVParse.parseDuration("59")) // sub-minute rounds to 0 → null, like other providers
        assertEquals(null, MissAVParse.parseDuration("abc"))
        assertEquals(null, MissAVParse.parseDuration(null))
    }

    // ==== /en/new pagination fixtures (issue #641) — real /dm539/en/new?page=1|2 captures ====

    /** Card hrefs exactly as the provider derives them: card elements → card link →
     *  absolute URL, deduped by URL (each card's grid cell also nests a matching
     *  thumbnail div, so elements double-match), minus the nav shells the provider
     *  drops via poster-null/blacklist. */
    private fun cardUrls(page: String): List<String> {
        val doc = Jsoup.parse(javaClass.getResourceAsStream("/en-new-$page.html")!!, "UTF-8", "https://missav.live/")
        return MissAVParse.parseCardElements(doc)
            .mapNotNull { it.selectFirst("a[href*='/en/'], a[href*='/dm']")?.attr("abs:href") }
            .filter { it.startsWith("http") }
            .distinctBy { it }
            .filterNot { it.endsWith("/new") || it.endsWith("/contact") } // nav shells
    }

    /** Site cache skew (issue #641): /en/new page 2's head repeats the tail of
     *  page 1 — verified stable across repeated fetches on 2026-10-16. The card
     *  selector must still match the live grammar on both pages. */
    @Test fun `en-new fixtures parse with overlapping boundary`() {
        val p1 = cardUrls("page1")
        val p2 = cardUrls("page2")
        assertTrue("page 1 should have video cards, got ${p1.size}", p1.isNotEmpty())
        assertTrue("page 2 should have video cards, got ${p2.size}", p2.isNotEmpty())
        // page 1's tail must equal page 2's head (the site-side repetition this run hardens against)
        val tail = p1.takeLast(p1.size - 7)   // 7 fresh items on p1 vs live capture
        assertTrue("boundary repetition missing — live grammar changed?", tail.isNotEmpty())
        assertTrue("p1 tail not in p2", p2.containsAll(tail.take(1)))
    }

    /** filterSeen drops URLs served on earlier pages of the same row, in order,
     *  so the boundary repetition never reaches the user's home feed. */
    @Test fun `filterSeen drops already-served urls keeping order`() {
        val served = setOf("https://missav.live/en/mudr-407", "https://missav.live/en/pow-096")
        val page2 = listOf(
            "https://missav.live/en/pow-096",       // repeat of p1 tail
            "https://missav.live/en/mxgs-1448",     // fresh
            "https://missav.live/en/mudr-407",      // repeat of p1 tail
            "https://missav.live/en/mvsh-002"       // fresh
        )
        assertEquals(
            listOf("https://missav.live/en/mxgs-1448", "https://missav.live/en/mvsh-002"),
            MissAVParse.filterSeen(page2, served)
        )
        // first page / empty seen-set: nothing dropped
        assertEquals(page2, MissAVParse.filterSeen(page2, emptySet()))
    }

    // ==== issue #1440 — the stream host lives in the page's own player config ====

    /** getAndUnpack() output from two live video pages (2026-11-21): the pack names its
     *  own bucket host — `c.surrit.com` on most pages, the apex on the rest. */
    private fun unpackedPlayerConfig(name: String): String =
        javaClass.getResourceAsStream("/issue1440/$name")!!.bufferedReader().readText()

    /** A UUID minted in the `c.surrit.com` bucket 403s (Aliyun `BucketPunish`) on the apex
     *  host the provider used to hardcode — the URL must come from the config verbatim. */
    @Test fun `playlist url keeps the c-surrit bucket the config names`() {
        assertEquals(
            "https://c.surrit.com/ab2b36f7-e1df-467e-8589-87d1f991d6ae/playlist.m3u8",
            MissAVParse.parsePlaylistUrl(unpackedPlayerConfig("unpacked-c-surrit.txt"))
        )
    }

    /** The apex-bucket pages keep their apex URL (still a 200 — the c. host is a superset,
     *  but the site's own choice is what ships). */
    @Test fun `playlist url keeps the apex bucket when the config names it`() {
        assertEquals(
            "https://surrit.com/93153cd4-152a-4710-a15b-fb092b1f322a/playlist.m3u8",
            MissAVParse.parsePlaylistUrl(unpackedPlayerConfig("unpacked-apex-surrit.txt"))
        )
    }

    /** Older/other pack shapes carry only the bare id — keep the legacy apex form so a
     *  config change degrades to the pre-#1440 behaviour instead of emitting no link. */
    @Test fun `playlist url falls back to the legacy apex form without an absolute url`() {
        assertEquals(
            "https://surrit.com/0f8b1c2d-3e4f-5a6b-7c8d-9e0f1a2b3c4d/playlist.m3u8",
            MissAVParse.parsePlaylistUrl("recomms='/0f8b1c2d-3e4f-5a6b-7c8d-9e0f1a2b3c4d/items/'")
        )
        // no id and no url at all → nothing to emit (never a fabricated host)
        assertEquals(null, MissAVParse.parsePlaylistUrl("source=''"))
        assertEquals(null, MissAVParse.parsePlaylistUrl(null))
        assertEquals(null, MissAVParse.parsePlaylistUrl("  "))
    }

    /** The real boundary: everything p2 shares with p1 is dropped, the rest flows through. */
    @Test fun `filterSeen clears the en-new p1-p2 boundary overlap`() {
        val p1 = cardUrls("page1")
        val p2 = cardUrls("page2")
        val fresh = MissAVParse.filterSeen(p2, p1.toSet())
        assertTrue("fresh page 2 cards must be non-empty", fresh.isNotEmpty())
        assertTrue("no repeat may survive the filter", p1.none { it in fresh })
        assertEquals("fresh count must equal p2 minus overlap", p2.size - p2.count { it in p1 }, fresh.size)
    }
}
