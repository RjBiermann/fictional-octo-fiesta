package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #642 deep pass (2026-10-16): the live video-page grammar changed —
 * the year block (`div.extra span.C a`) and the rating span (`span.dt_rating_vgs`)
 * dropped out of the served body entirely, so `load()`'s year/score selectors
 * can never match again. Red→green at a Parse seam (ADR-0005): the detail-field
 * extraction moves behind `VideoPageParse`, pinned to a real capture
 * (/hdporn/128207-special_request.html, plain-curl tier, provider headers).
 */
class VideoPageParseTest {

    private fun doc() = Jsoup.parse(
        javaClass.classLoader!!.getResource("issue642/128207-special_request.html")!!.readText()
    )

    @Test fun `title from h1`() {
        assertEquals("special request", VideoPageParse.from(doc()).title)
    }

    @Test fun `plot from meta description`() {
        assertEquals(
            "Watch porn video Special Request in high definition for free. " +
                "Video duration is 47min 19sec. Tags related to this video: " +
                "story movies, rich, beautiful sex, art, glamour.",
            VideoPageParse.from(doc()).plot,
        )
    }

    @Test fun `tags from the categories h3+p anchor run`() {
        assertEquals(
            listOf(
                "1080p", "big tits", "bisexual", "blonde", "cumshot", "hairy",
                "latina", "masturbation", "milf", "redhead", "stockings", "threesome",
            ),
            VideoPageParse.from(doc()).tags,
        )
    }

    @Test fun `actors from the featuring row`() {
        assertEquals(
            listOf("Octavia Red", "Cherry Kiss"),
            VideoPageParse.from(doc()).actors,
        )
    }

    @Test fun `duration clock feeds the shared DurationParse seam`() {
        val meta = VideoPageParse.from(doc())
        assertEquals(47, DurationParse.minutes(meta.durationText))
    }

    @Test fun `empty document yields null title, empty lists`() {
        val meta = VideoPageParse.from(Jsoup.parse("<html></html>"))
        assertNull(meta.title)
        assertEquals(emptyList<String>(), meta.tags)
        assertEquals(emptyList<String>(), meta.actors)
        assertNull(meta.durationText)
    }

    /** Grammar pin: the 2026-10 body serves neither selector's target anymore —
     *  the dead selectors removed from load() by this issue cannot resurrect
     *  against a stale grammar without the fixture failing. */
    @Test fun `live grammar serves no year block and no rating span`() {
        val d = doc()
        assertNull(d.selectFirst("div.extra span.C a"))
        assertNull(d.selectFirst("span.dt_rating_vgs"))
    }
}
