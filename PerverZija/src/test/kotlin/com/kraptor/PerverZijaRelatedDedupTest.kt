package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TDD for issue #909 (S4 live verification of the #903 epic): the live related grid
 *  renders the same card twice on some pages — 4 of 24 sampled video pages on
 *  2026-10-23 (e.g. `pornworld-tigerr-benson-ready-for-her-gushing`), so `load()`
 *  emitted a duplicated recommendation. Fixture is the page's 18 related cards,
 *  verbatim (two of them share one href). */
class PerverZijaRelatedDedupTest {

    private val doc by lazy {
        Jsoup.parse(
            javaClass.classLoader.getResource("perverzija_video_related_dup.html")!!.readText()
        )
    }

    private fun hrefs() = PerverZijaParse.related(doc).map { it.selectFirst("a")!!.attr("href") }

    @Test fun `fixture really carries the live duplicate`() {
        // guard: the raw markup has 18 cards and one repeated href
        assertEquals(18, doc.select("div.xs-related-item").size)
        assertEquals(17, hrefs().toSet().size)
    }

    @Test fun `related cards are href-distinct`() {
        val cards = hrefs()
        assertEquals(cards.size, cards.toSet().size)
        assertEquals(17, cards.size)
    }

    @Test fun `dedupe keeps the first occurrence and its order`() {
        val cards = hrefs()
        assertEquals("https://tube.perverzija.com/blacked-cherry-candle-college-girl-begs-for-creampie/", cards.first())
        // the duplicated card keeps its first position (4th card), not the later one
        assertEquals(
            "https://tube.perverzija.com/pornworld-nikky-dream-and-loren-minardi-sexy-time-in-the-sauna-lesbians-probe-at-each-others-pinks/",
            cards[3]
        )
    }

    @Test fun `link-less cards are not collapsed into one`() {
        val synthetic = Jsoup.parse(
            """<div class="xs-related-item"><span>no link</span></div>
               <div class="xs-related-item"><span>no link either</span></div>"""
        )
        assertEquals(2, PerverZijaParse.related(synthetic).size)
    }

    @Test fun `empty page yields empty list`() {
        assertEquals(0, PerverZijaParse.related(Jsoup.parse("<html></html>")).size)
    }

    @Test fun `single-card page still parses`() {
        val one = Jsoup.parse(
            """<div class="xs-related-item"><a href="/x/"><img src="https://i/x.jpg"></a>
               <div class="xs-related-title"><a href="/x/">X</a></div></div>"""
        )
        val cards = PerverZijaParse.related(one)
        assertEquals(1, cards.size)
        assertTrue(PerverZijaParse.titleOf(cards.first()) == "X")
    }
}
