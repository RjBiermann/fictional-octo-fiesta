package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture: live watch pages probed for issue #530 (2026-10-09, FINDINGS-530.md). */
class FullPornerLoadParseTest {

    private fun doc(name: String) =
        Jsoup.parse(javaClass.getResourceAsStream("/$name")!!.reader().readText())

    /** Defect 1: plot must come from meta[name=description], not duplicate the h2 title. */
    @Test fun `plot parses meta description, suffix stripped, empty pornstar page`() {
        val d = doc("watch_page_empty_pornstar.html")
        assertEquals(
            "Wild Lesbian Orgy: A Sensational Sex Party Extravaganza",
            FullPorner.parsePlot(d)
        )
    }

    @Test fun `plot parses meta description, vintage-category page`() {
        val d = doc("watch_page_busty_blonde.html")
        assertEquals(
            "Busty Blonde Vintage Vixen Rides BBC Hard and Takes Massive Cumshots",
            FullPorner.parsePlot(d)
        )
    }

    /** Plot must come from meta[name=description] — the h2 is the title (old bug duped it). */
    @Test fun `plot comes from meta, not the h2 title`() {
        val html = """
            <meta name="description" content="META SUMMARY on fullporner.com, the best full length porn site.">
            <div class="video-block"><div class="single-video-left"><div class="single-video-title"><h2>H2 TITLE</h2></div></div></div>
        """.trimIndent()
        assertEquals("META SUMMARY", FullPorner.parsePlot(Jsoup.parse(html)))
    }

    /** Defect 2: present-but-empty "Pornstar:" cell must not yield an empty-string actor. */
    @Test fun `empty pornstar cell yields no empty-string actors`() {
        assertTrue(FullPorner.parseActors(doc("watch_page_empty_pornstar.html")).isEmpty())
        assertTrue(FullPorner.parseActors(doc("watch_page_busty_blonde.html")).isEmpty())
    }

    @Test fun `populated pornstar list parses, whitespace rows filtered`() {
        // 6abf9d0d probe shape: pornstar cell anchors carry the names; a blank
        // row (whitespace-only anchor) present exactly like the live cell.
        val html = """
            <div class="video-block"><div class="single-video-left">
            <div class="single-video-info-content">
                <p>Pornstar: <a>federica zarri</a> <a> </a></p>
            </div></div></div>
        """.trimIndent()
        assertEquals(listOf("federica zarri"), FullPorner.parseActors(Jsoup.parse(html)))
    }
}
