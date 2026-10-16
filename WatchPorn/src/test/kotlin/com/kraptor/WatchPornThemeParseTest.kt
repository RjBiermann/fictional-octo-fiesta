package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #649: the 2026-10 KVS theme redesign replaced the listing-card and video-page
 * grammar with "wp-" classes (old span.thumb__title / h1.single__content-title /
 * single__info-row grammar is gone). Fixture-driven and literal (ADR-0005): fixtures
 * are live transcripts 2026-10-16 (search p1 + video 136954).
 */
class WatchPornThemeParseTest {

    private val listingPage = javaClass.getResourceAsStream("/search-milf-1.html")!!.readBytes().decodeToString()
    private val videoPage = javaClass.getResourceAsStream("/video-136954.html")!!.readBytes().decodeToString()

    private val cards = Jsoup.parse(listingPage).select("div.thumb.item")

    // --- listing cards (search / homepage rows / related) ---

    @Test fun `cards parse from wp-ctitle`() {
        val titles = cards.mapNotNull { WatchPornParse.cardTitle(it) }
        // 39 real cards + 1 ad card (grid-ad, no wp-ctitle) on live p1
        assertEquals(39, titles.size)
        assertTrue(titles.contains("Both MILFs Want To Fuck"))
    }

    @Test fun `ad cards yield no title`() {
        val adCard = cards.first { it.className().contains("grid-ad") }
        assertNull(WatchPornParse.cardTitle(adCard))
    }

    @Test fun `card href and poster from the known card`() {
        val card = cards.first { WatchPornParse.cardTitle(it) == "Both MILFs Want To Fuck" }
        assertEquals(
            "https://watchporn.to/video/58740/ryan-keely-shay-sights-both-milfs-want-to-fuck-milfed/",
            WatchPornParse.cardHref(card)
        )
        assertEquals(
            "https://watchporn.to/contents/videos_screenshots/58000/58740/336x189/1.jpg",
            WatchPornParse.cardPoster(card)
        )
    }

    @Test fun `posters never resolve to a placeholder src`() {
        val posters = cards.mapNotNull { WatchPornParse.cardPoster(it) }
        assertTrue(posters.size >= 35)
        assertTrue(posters.none { it.startsWith("data:") })
    }

    @Test fun `card rating from span wp-r`() {
        val card = cards.first { WatchPornParse.cardTitle(it) == "Both MILFs Want To Fuck" }
        assertEquals("90%", WatchPornParse.cardRating(card))
    }

    // --- search pagination ---

    @Test fun `hasNext from wp-pag__next`() {
        assertTrue(WatchPornParse.hasNext(Jsoup.parse(listingPage)))
    }

    @Test fun `no next on a page without pagination`() {
        assertFalse(WatchPornParse.hasNext(Jsoup.parse("<html><body></body></html>")))
    }

    // --- video page ---

    private val doc = Jsoup.parse(videoPage)

    @Test fun `video title from wp-vi__title`() {
        assertEquals("Willow Ryder - Episode 11 Ted Was Right - HookupTherapy", WatchPornParse.pageTitle(doc))
    }

    @Test fun `tags from wp-vchip`() {
        val tags = WatchPornParse.tags(doc)
        assertTrue(tags.containsAll(listOf("Blowjob", "POV")))
    }

    @Test fun `actors from wp-cast`() {
        assertTrue(WatchPornParse.actors(doc).contains("Willow Ryder"))
    }

    @Test fun `ldJson detects VideoObject and feeds JsonLdParse`() {
        val ld = WatchPornParse.ldJson(doc)
        assertTrue(ld!!.contains("\"VideoObject\""))
        assertEquals(72, JsonLdParse.minutes(ld))
        assertEquals(2025, JsonLdParse.year(ld))
    }

    @Test fun `legacy-theme fixture still parses via the fallback selectors (partial-rollout guard)`() {
        val legacy = Jsoup.parse(
            javaClass.getResourceAsStream("/video-28447.html")!!.readBytes().decodeToString()
        )
        assertEquals(
            "Coco Vandi - Private Vacation with Busty Step Mom - TabooHeat",
            WatchPornParse.pageTitle(legacy)
        )
        assertTrue(WatchPornParse.actors(legacy).isNotEmpty())
        assertTrue(WatchPornParse.tags(legacy).isNotEmpty())
    }
}
