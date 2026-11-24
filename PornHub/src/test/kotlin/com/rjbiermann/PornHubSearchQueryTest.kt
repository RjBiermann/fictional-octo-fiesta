package com.rjbiermann

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `Parse` query seam (S1 of #1574): [Parse.normaliseQuery] rewrites a query into the form the
 * site's own 301 uses, and [Parse.searchEchoTarget] turns a canonical echo into the one retry the
 * provider makes. Fixtures are the verbatim head lines + `ul#videoSearchResult` block of the
 * followed responses captured 2026-11-24 from a US (Virginia) egress — FINDINGS §Query shapes.
 */
class PornHubSearchQueryTest {

    private val provider = PornHub()

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.reader().readText()

    @Test fun `normaliseQuery folds case diacritics apostrophes dots and whitespace`() {
        assertEquals("teen", Parse.normaliseQuery("Teen"))
        assertEquals("big tits", Parse.normaliseQuery("BIG TITS"))
        assertEquals("lana s", Parse.normaliseQuery("Lana's"))
        assertEquals("cafe", Parse.normaliseQuery("café"))
        assertEquals("big tits", Parse.normaliseQuery("big.tits"))
        assertEquals("teen", Parse.normaliseQuery("  teen  "))
        // The measured invalid-query class is deliberately left alone: the site answers these
        // with `404 Page Not Found`, so rewriting them would change what the user asked for.
        assertEquals("big-tits", Parse.normaliseQuery("big-tits"))
        assertEquals("m&s", Parse.normaliseQuery("m&s"))
    }

    @Test fun `searchUrl sends the normalised query so the site never redirects it away`() {
        // The site's 301 drops `&page=N`, so page 2+ of an un-normalised query re-serves page 1.
        assertEquals("$BASE/video/search?search=teen&page=2", Parse.searchUrl("Teen", 2))
        assertEquals("$BASE/video/search?search=lana+s&page=2", Parse.searchUrl("Lana's", 2))
        assertEquals("$BASE/video/search?search=big+tits&page=2", Parse.searchUrl("big.tits", 2))
        assertEquals("$BASE/video/search?search=cafe&page=2", Parse.searchUrl("café", 2))
        // Not rewritten: the site's 404 class keeps its own meaning (and encoding).
        assertEquals("$BASE/video/search?search=big-tits&page=2",
            Parse.searchUrl("big-tits", 2))
        assertEquals("$BASE/video/search?search=m%26s&page=2", Parse.searchUrl("m&s", 2))
    }

    @Test fun `an echoed page-2 body retries with the canonical search query`() {
        // Followed `?search=Teen&page=2` → 301 → `?search=teen`: canonical page 1, still rel=next.
        val html = fixture("search-echo-page2.html")
        assertEquals("teen", Parse.searchEchoTarget(html, 2, "Teen"))
        assertFalse("the echo must stop the search, not replay it",
            Parse.searchHasNext(html, 2))
        assertNull("page 1 is never an echo", Parse.searchEchoTarget(html, 1, "Teen"))
    }

    @Test fun `a pornstar canonical echo falls back to the normalised request query`() {
        // Followed `?search=Riley+Reid&page=2` → 301 → canonical `/pornstar/riley-reid`, which
        // carries no `search=` at all; the retry is the normalised request query.
        val html = fixture("search-echo-pornstar-page2.html")
        assertEquals("riley reid", Parse.searchEchoTarget(html, 2, "Riley Reid"))
        assertFalse("the pornstar echo also stops the search",
            Parse.searchHasNext(html, 2))
    }

    @Test fun `a normalised deeper page is not an echo and still pages`() {
        // `?search=riley+reid&page=3` answers 200 with its own canonical page 3.
        val html = fixture("search-normalised-page-3.html")
        assertNull(Parse.searchEchoTarget(html, 3, "riley reid"))
        assertTrue("page 3 announces page 4", Parse.searchHasNext(html, 3))
        assertTrue("page 3 must still parse its cards",
            provider.parseVideoCards(html, search = true).size >= 20)
    }

    @Test fun `an echo on the retry stops the search - no loop is possible`() {
        // First request echoes → retry target; the retry body is itself an echo (canonical page
        // 1), so guard 2 ends the search on the body actually used.
        val first = fixture("search-echo-page2.html")
        val target = Parse.searchEchoTarget(first, 2, "Teen")
        assertEquals("teen", target)
        val retried = first // the site answers the retried page-2 request with the same echo
        assertFalse(Parse.searchHasNext(retried, 2))
        assertEquals("the retry target is stable, but it is never followed again",
            target, Parse.searchEchoTarget(retried, 2, target!!))
    }

    private companion object {
        const val BASE = "https://www.pornhub.com"
    }
}
