package com.rjbiermann

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search surface (TDD-first, ADR-0005): the URL shape and the bounded-paging predicate.
 *
 * The two fixtures are live captures (2026-11-27, mobile Chrome UA) of
 * `https://yesporn.vip/search/brazzers/` and `https://yesporn.vip/search/brazzers/?from_videos=21`.
 * `brazzers` is the query pinned here because it is the one that proves paging: a narrow query
 * such as `test` has a single result page (`?from_videos=21` answers 404), so it cannot show a
 * distinct page 2. Path paging (`/search/brazzers/2/`) answers 404 — search pages only through
 * the `from_videos` offset.
 */
class SearchPagingTest {

    private fun fixture(name: String): Document =
        Jsoup.parse(javaClass.classLoader.getResourceAsStream(name)!!.readBytes().decodeToString())

    private fun ids(name: String): Set<String> =
        YesPornVipParse.cards(fixture(name)).mapNotNull { it.videoId }.toSet()

    // --- URL shape: page 1 has no offset, later pages carry the KVS offset ---

    @Test
    fun pageOneHasNoOffsetParameter() {
        assertEquals("https://yesporn.vip/search/brazzers/", YesPornVipParse.searchUrl("brazzers", 1))
        // page 0/negative is treated as page 1 — the app always starts at 1.
        assertEquals("https://yesporn.vip/search/brazzers/", YesPornVipParse.searchUrl("brazzers", 0))
    }

    @Test
    fun laterPagesCarryTheFromVideosOffset() {
        // 20 cards per page, so page 2 starts at card 21 and page 3 at card 41 — both offsets
        // were fetched live and served disjoint result sets.
        assertEquals(
            "https://yesporn.vip/search/brazzers/?from_videos=21",
            YesPornVipParse.searchUrl("brazzers", 2),
        )
        assertEquals(
            "https://yesporn.vip/search/brazzers/?from_videos=41",
            YesPornVipParse.searchUrl("brazzers", 3),
        )
    }

    @Test
    fun theQueryIsEscapedAsAPathSegment() {
        // The query lives in the path, so a space must be %20, never the form-encoded `+`
        // (`/search/big%20tits/` — verified 200).
        assertEquals("https://yesporn.vip/search/big%20tits/", YesPornVipParse.searchUrl("big tits", 1))
        assertEquals("https://yesporn.vip/search/big%20tits/", YesPornVipParse.searchUrl("  big tits ", 1))
    }

    // --- the page-2-proves-paging contract ---

    @Test
    fun pageOneIsAFullResultPage() {
        val cards = YesPornVipParse.cards(fixture("search-test-1.html"))
        assertEquals(YesPornVipParse.PAGE_SIZE, cards.size)
        assertEquals(cards.size, cards.mapNotNull { it.videoId }.toSet().size)
        assertTrue(cards.all { it.videoId != null && it.title.isNotBlank() && it.poster != null })
    }

    @Test
    fun theTwoSearchPagesShareNoVideo() {
        val page1 = ids("search-test-1.html")
        val page2 = ids("search-test-2.html")
        assertEquals(YesPornVipParse.PAGE_SIZE, page1.size)
        assertEquals(YesPornVipParse.PAGE_SIZE, page2.size)
        assertTrue(
            "page 1 and page 2 must not share a card: ${page1 intersect page2}",
            (page1 intersect page2).isEmpty(),
        )
    }

    // --- bounded paging: the predicate that stops a repeating result set from looping ---

    @Test
    fun aNewPageContinuesTheSearch() {
        assertTrue(YesPornVipParse.hasNext(fixture("search-test-2.html"), ids("search-test-1.html")))
        // A fresh caller with nothing served yet always continues on a non-empty page.
        assertTrue(YesPornVipParse.hasNext(fixture("search-test-1.html"), emptySet()))
    }

    @Test
    fun aPageThatRepeatsThePreviousIdsEndsTheSearch() {
        // The site re-serving an earlier page must not loop: every id on the page is already
        // known, so there is nothing new to append.
        assertFalse(YesPornVipParse.hasNext(fixture("search-test-1.html"), ids("search-test-1.html")))
        assertFalse(YesPornVipParse.hasNext(fixture("search-test-2.html"), ids("search-test-2.html")))
        // A boundary card that shifted onto the next page is not a repeat of the *whole* page:
        // one unseen id is enough to continue.
        val almostAll = ids("search-test-2.html").drop(1).toSet()
        assertTrue(YesPornVipParse.hasNext(fixture("search-test-2.html"), almostAll))
    }

    @Test
    fun anEmptyParseEndsTheSearch() {
        // Past the last page the site answers 404 with an error body: no `div.thumb` card at
        // all, which is the same shape as a query with no hits.
        val empty = Jsoup.parse("""<div class="thumb"></div><p>Nothing found</p>""")
        assertFalse(YesPornVipParse.hasNext(empty, emptySet()))
        assertTrue(YesPornVipParse.cards(empty).isEmpty())
    }
}
