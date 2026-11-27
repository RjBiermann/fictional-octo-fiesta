package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1541: the theme re-added a per-video synopsis at div.wp-desc > p after #649
 * (which had judged the surface gone). Fixtures are live transcripts 2026-11-23:
 * video-170727 carries a synopsis, video-136954 only the site boilerplate.
 */
class WatchPornPlotParseTest {

    private fun fixture(name: String) =
        Jsoup.parse(javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString())

    @Test fun `plot from div wp-desc paragraph on a synopsis page`() {
        val plot = WatchPornParse.description(fixture("video-170727.html"))
        assertEquals(
            "Who knew ] drop-off could get so wild? I showed up in my tightest leggings and " +
                "sports bra today... and now I'm kneeling at your front door, dying to feel your " +
                "cock throbbing between my lips. Let's skip the small talk",
            plot
        )
    }

    @Test fun `plot never picks up the more button`() {
        val plot = WatchPornParse.description(fixture("video-170727.html"))!!
        assertTrue(plot.endsWith("Let's skip the small talk"))
        assertFalse(plot.contains("more"))
    }

    @Test fun `legacy theme synopsis is kept as a fallback`() {
        // old-theme fixture: no div.wp-desc, a real 3,999-char p.single__content-description
        val plot = WatchPornParse.description(fixture("video-28447.html"))
        assertNotNull(plot)
        assertTrue(plot!!.startsWith("I am so excited for my first Vegas trip with my step-mom!"))
        assertTrue(plot.endsWith("in their mouths"))
        assertEquals(3999, plot.length)
    }

    @Test fun `plot stays null on a page without a synopsis`() {
        // og:description / ld+json carry the 151-char site boilerplate here — never a plot
        assertNull(WatchPornParse.description(fixture("video-136954.html")))
    }

    @Test fun `plot stays null on a page with an empty description container`() {
        assertNull(WatchPornParse.description(Jsoup.parse("<html><body></body></html>")))
        assertNull(
            WatchPornParse.description(
                Jsoup.parse("<html><body><div class=\"wp-desc\"><p>  </p></div></body></html>")
            )
        )
    }

    @Test fun `multiple paragraphs join with a single space`() {
        val doc = Jsoup.parse(
            "<html><body><div class=\"wp-desc\"><p>First part.</p><p>Second part.</p>" +
                "<button class=\"wp-dmore\">more</button></div></body></html>"
        )
        assertEquals("First part. Second part.", WatchPornParse.description(doc))
    }
}
