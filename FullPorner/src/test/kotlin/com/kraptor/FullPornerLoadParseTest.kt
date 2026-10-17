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

    /* ── #644 (2026-10-16 probe): the embed is now a fluidPlayer page — no jwplayer
       configs. Sources are statically derivable: var id = "<videoId>" (reversed by site
       JS) and var quality = parseInt("N") masking 1=360/2=480/4=720/8=1080.
       Fixtures: live embeds /video/0739433/14 and /video/e98d5f2ef1da6/2 (FINDINGS-644). */
    @Test fun `embed mask 0b1110 yields 480 720 1080 sources for the reversed id`() {
        val sources = FullPornerParse.parseEmbedSources(doc("embed_page_mask14.html").outerHtml())
        assertEquals(listOf(480, 720, 1080), sources.map { it.quality })
        val videoId = "0739433".reversed() // 3349370 — same reversal the site JS does
        assertEquals(
            listOf(480, 720, 1080).map { "https://xiaoshenke.net/vid/$videoId/$it" },
            sources.map { it.url }
        )
    }

    /** Alphanumeric ids reverse glyph-by-glyph exactly like the numeric ones. */
    @Test fun `embed mask 2 on an alphanumeric id yields only 480`() {
        val sources = FullPornerParse.parseEmbedSources(doc("embed_page_mask2.html").outerHtml())
        assertEquals(listOf(480), sources.map { it.quality })
        assertEquals("https://xiaoshenke.net/vid/6ad1fe2f5d89e/480", sources.single().url)
    }

    @Test fun `embed without id or quality mask parses to no sources`() {
        assertTrue(FullPornerParse.parseEmbedSources("<html><body>gone</body></html>").isEmpty())
    }

    /** #644: div.video-info exposes the duration clock ("36:24") and the span.create
        upload epoch (1791526312 = 2026-10-09 UTC; 1792160596 on watch_page_lacey =
        2026-10-16Z) — both on fixtures already in-tree. */
    @Test fun `video-info duration clock parses to floored minutes`() {
        assertEquals(36, FullPornerParse.durationMinutes(doc("watch_page_busty_blonde.html")))
        assertEquals(50, FullPornerParse.durationMinutes(doc("watch_page_lacey.html")))
    }

    @Test fun `video-info create epoch parses to a UTC year`() {
        assertEquals(2026, FullPornerParse.year(doc("watch_page_busty_blonde.html")))
        assertEquals(2026, FullPornerParse.year(doc("watch_page_lacey.html")))
    }

    @Test fun `absent video-info yields null duration and year`() {
        val d = org.jsoup.Jsoup.parse("<html></html>")
        assertEquals(null, FullPornerParse.durationMinutes(d))
        assertEquals(null, FullPornerParse.year(d))
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
