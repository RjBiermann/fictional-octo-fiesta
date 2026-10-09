package com.kerimmkirac

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture-driven test for issue #536: the site's details block dropped the
 *  Description:/Models:/Duration cells; fields re-sourced from markup/JSON-LD. */
class FreePornVideosParseTest {

    // video-93820265 from latest-updates, video-93404552 from search — probed 2026-10-09
    private val pages = listOf("/video-93820265.html", "/video-93404552.html")
        .map { javaClass.getResourceAsStream(it)!!.readBytes().decodeToString() }
        .map { Jsoup.parse(it) }

    @Test fun `actors from Pornstars cell`() {
        assertEquals(listOf("Jovan Jordan", "Mckenzie Mae"), FreePornVideosParse.actors(pages[0]))
        assertEquals(listOf("Eric", "Victoria Tiffani"), FreePornVideosParse.actors(pages[1]))
    }

    @Test fun `tags from Categories cell`() {
        assertEquals(listOf("Anal", "Blowjob", "Cumshot", "Facial", "Handjob", "Masturbation",
            "Blonde", "Natural Tits", "Interracial", "Black", "Big Cock", "Deepthroat",
            "Ass-To-Mouth", "Gaping", "Pussy to Mouth", "Beautiful Ass"),
            FreePornVideosParse.tags(pages[0]))
        assertEquals(listOf("HD", "Brunette", "Interracial", "POV", "Big Cock"),
            FreePornVideosParse.tags(pages[1]))
    }

    @Test fun `plot from meta description`() {
        assertTrue(FreePornVideosParse.plot(pages[0])!!
            .contains("Starring Jovan Jordan and Mckenzie Mae"))
        assertNull(FreePornVideosParse.plot(Jsoup.parse("<html/>")))
    }

    @Test fun `duration from meta video duration is minutes`() {
        // 1894s → 31m; 2370s → 39m (matches JSON-LD PT0H31M34S / PT0H39M30S floored)
        assertEquals(31, FreePornVideosParse.duration(pages[0]))
        assertEquals(39, FreePornVideosParse.duration(pages[1]))
    }

    @Test fun `absent duration yields null`() {
        assertNull(FreePornVideosParse.duration(Jsoup.parse("<html/>")))
    }

    @Test fun `year from JSON-LD uploadDate`() {
        assertEquals(2026, FreePornVideosParse.year(pages[0]))
        assertEquals(2021, FreePornVideosParse.year(pages[1]))
    }
}
