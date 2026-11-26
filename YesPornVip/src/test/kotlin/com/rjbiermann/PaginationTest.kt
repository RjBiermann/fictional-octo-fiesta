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

    // --- cross-page seen filter (the behaviour that makes the disjoint-page proof matter) ---

    private fun card(id: String?, slug: String = "slug") = YesPornVipParse.Card(
        videoId = id,
        title = "Entry $id",
        href = "https://yesporn.vip/video/$id/$slug/",
        poster = null,
    )

    private fun taxonomyCard(id: String, slug: String = "slug") = YesPornVipParse.Card(
        videoId = null,
        title = "Entry $id",
        href = "https://yesporn.vip/channels/$id/$slug/",
        poster = null,
    )

    @Test
    fun seenFilterDropsTheRankedBoundaryCardOnTheNextPage() {
        // The observed `most-popular` case: video 67389 is the last card of page 1 and the
        // first of page 2. Page 2 must not re-serve it.
        val seen = HashSet<String>()
        assertEquals(listOf("67389", "67390"), YesPornVipParse.fresh(listOf(card("67389"), card("67390")), seen).map { it.videoId })
        assertEquals(listOf("67391"), YesPornVipParse.fresh(listOf(card("67389"), card("67391")), seen).map { it.videoId })
    }

    @Test
    fun seenFilterKeysOnTheVideoIdNotTheSlug() {
        // Same video, two surface slugs (`-ebk44f` / `-2av635`): one card, not two.
        val seen = HashSet<String>()
        assertEquals(1, YesPornVipParse.fresh(listOf(card("76391", "ebk44f")), seen).size)
        assertTrue(YesPornVipParse.fresh(listOf(card("76391", "2av635")), seen).isEmpty())
    }

    @Test
    fun seenFilterFallsBackToHrefForTaxonomyEntries() {
        // A channel/model/category card has no video id; its href is the identity.
        val seen = HashSet<String>()
        assertEquals(1, YesPornVipParse.fresh(listOf(taxonomyCard("public")), seen).size)
        assertTrue(YesPornVipParse.fresh(listOf(taxonomyCard("public")), seen).isEmpty())
        assertEquals(1, YesPornVipParse.fresh(listOf(taxonomyCard("public", "other")), seen).size)
    }

    @Test
    fun reSeedingPageOneServesItAgain() {
        val seen = HashSet<String>()
        val page1 = listOf(card("67389"), card("67390"))
        YesPornVipParse.fresh(page1, seen)
        // The adapter clears the set before page 1, so a refresh re-serves the row.
        seen.clear()
        assertEquals(2, YesPornVipParse.fresh(page1, seen).size)
    }

    @Test
    fun anAllSeenPageYieldsNothingSoTheRowEnds() {
        val seen = HashSet<String>()
        YesPornVipParse.fresh(listOf(card("67389")), seen)
        assertTrue(YesPornVipParse.fresh(listOf(card("67389")), seen).isEmpty())
    }
}
