package com.rjbiermann

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 related grid (TDD-first, ADR-0005) — the detail page's "Related Videos" strip, reusing the
 * listing [YesPornVipParse.cards] parser. Fixture: live capture of video 76685 (2026-11-27).
 *
 * The page ships 36 `div.thumb` cards in `#list_videos_related_videos_items` and shows the
 * first 12 (`data-show="12"` on its `i.yp-fresh` element) before the strip is expanded. All 36
 * are real, served cards in the same HTML, so all 36 become recommendations — pinning 12 would
 * hide two thirds of what the page offers. The separate "Recommended Videos" aside is not part
 * of this strip.
 */
class RelatedTest {

    private fun fixture(name: String): Document =
        Jsoup.parse(javaClass.classLoader.getResourceAsStream(name)!!.readBytes().decodeToString())

    @Test
    fun theRelatedStripYieldsEveryCard() {
        val related = YesPornVipParse.related(fixture("video-76685.html"))

        assertEquals(36, related.size)
        assertTrue(related.all { it.videoId != null })
        assertTrue(related.all { it.title.isNotBlank() })
        // The identity is the video id: no card is served twice, even with two slug spellings.
        assertEquals(36, related.mapNotNull { it.videoId }.toSet().size)
        assertEquals("74913", related.first().videoId)
        assertEquals("Vanessa Sky Prove It", related.first().title)
    }

    @Test
    fun theTitleBeingWatchedIsNotItsOwnRecommendation() {
        val document = fixture("video-76685.html")

        // 76685 is not in its own grid on this capture, so the exclusion is proven on a card
        // that is (74913) — the same filter that guards the self-recommendation case.
        assertEquals(35, YesPornVipParse.related(document, videoId = "74913").size)
        assertFalse(YesPornVipParse.related(document, videoId = "74913").any { it.videoId == "74913" })
        assertEquals(36, YesPornVipParse.related(document, videoId = "76685").size)
    }

    @Test
    fun relatedCardsAreAbsoluteAndOffsiteFree() {
        val related = YesPornVipParse.related(fixture("video-76685.html"))

        assertTrue(related.all { it.href.startsWith("https://yesporn.vip/video/") })
        assertTrue(related.all { it.poster?.startsWith("https://yesporn.vip/") == true })
    }
}
