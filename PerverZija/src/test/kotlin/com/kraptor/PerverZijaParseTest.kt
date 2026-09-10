package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TDD for issue #273: recommendations moved from div.srelacionados to div.xs-related-item.
 *  Fixture cut from a live scene page (2026-09-10). */
class PerverZijaParseTest {

    private val doc by lazy {
        Jsoup.parse(javaClass.classLoader.getResource("perverzija_video_related.html")!!.readText())
    }

    @Test fun `related items found with new selector`() {
        // old selector finds nothing — that was the bug
        assertEquals(0, doc.select("div.srelacionados article").size)
        assertEquals(18, PerverZijaParse.related(doc).size)
    }

    @Test fun `item title comes from xs-related-title a not img alt`() {
        val item = PerverZijaParse.related(doc).first()
        // img alt/title are WRONG on the live site (repeat the host page's title)
        assertTrue(
            item.selectFirst("div.xs-related-title a")!!.text()
                != item.selectFirst("a img")!!.attr("alt")
        )
        assertEquals(
            "BangBus – Lo Lacey – Lo Said Fuck Me",
            PerverZijaParse.titleOf(item)
        )
    }

    @Test fun `item href and poster parse`() {
        val item = PerverZijaParse.related(doc).first()
        assertEquals(
            "https://tube.perverzija.com/bangbus-lo-lacey-lo-said-fuck-me/",
            item.selectFirst("a")?.attr("href")
        )
        assertTrue(
            PerverZijaParse.posterOf(item)!!.startsWith("https://tube.perverzija.com/wp-content/uploads/")
        )
    }

    @Test fun `empty page yields empty list`() {
        assertEquals(0, PerverZijaParse.related(Jsoup.parse("<html></html>")).size)
    }
}
