package com.rjbiermann

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row-proves-paging contract (plan round 2, decision 1): a home row ships only when page 2
 * returns different items. `latest-updates` is the row pinned here — `/latest-updates/2/`
 * was fetched live (2026-11-26, mobile Chrome UA) and its card ids are disjoint from
 * page 1. A row that cannot prove this is dropped, not shipped half-working.
 */
class PaginationTest {

    private fun fixture(name: String): Document =
        Jsoup.parse(javaClass.classLoader.getResourceAsStream(name)!!.readBytes().decodeToString())

    private fun ids(cards: List<YesPornVipParse.Card>) = cards.mapNotNull { it.videoId }

    @Test
    fun pageOneIsAFullListing() {
        val ids = ids(YesPornVipParse.cards(fixture("latest-updates-1.html")))
        assertEquals(20, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("76391", ids.first())
    }

    @Test
    fun pageOneAndPageTwoShareNoVideo() {
        val page1 = ids(YesPornVipParse.cards(fixture("latest-updates-1.html"))).toSet()
        val page2 = ids(YesPornVipParse.cards(fixture("latest-updates-2.html"))).toSet()
        assertEquals(20, page2.size)
        assertTrue(
            "page 1 and page 2 must not share a card: ${page1 intersect page2}",
            (page1 intersect page2).isEmpty(),
        )
    }

    @Test
    fun homeAndListingAgreeOnTheSameVideoDespiteDifferentSlugs() {
        // The site canonicalises the slug per surface (`-ebk44f` on home, `-2av635` on the
        // listing) — the id is the identity both surfaces agree on.
        val home = YesPornVipParse.cards(fixture("home.html")).first { it.videoId == "76391" }
        val listing = YesPornVipParse.cards(fixture("latest-updates-1.html")).first { it.videoId == "76391" }
        assertEquals(home.title, listing.title)
        assertEquals("https://yesporn.vip/video/76391/sweet-as-sugar-episode-3-ebk44f/", home.href)
        assertEquals("https://yesporn.vip/video/76391/sweet-as-sugar-episode-3-2av635/", listing.href)
    }
}
