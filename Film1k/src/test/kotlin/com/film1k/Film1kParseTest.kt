package com.film1k

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TDD for issue #233: related videos, year mapping. Fixtures from live film1k.com pages. */
class Film1kParseTest {

    private val relatedDoc by lazy {
        Jsoup.parse(javaClass.classLoader.getResource("film1k_video_related.html")!!.readText())
    }

    @Test fun `related cards parse from main-scoped section`() {
        val related = Film1kParse.relatedOf(relatedDoc)
        assertEquals(11, related.size)
        assertEquals(
            "https://www.film1k.com/coffin-full-of-dollars-1971.html",
            related.first().selectFirst("a")?.attr("href")
        )
        assertEquals("Coffin Full of Dollars (1971)", related.first().selectFirst("h2.entry-title")?.text())
    }

    @Test fun `related cards carry same card markup as listings`() {
        // first card has the figure img data-src the listing parser uses
        assertEquals(
            "https://i.imgur.com/JOwDFtx.jpg",
            relatedDoc.let { Film1kParse.relatedOf(it) }.first()
                .selectFirst("figure img")?.attr("data-src")
        )
    }

    @Test fun `empty related section yields empty list`() {
        assertEquals(0, Film1kParse.relatedOf(Jsoup.parse("<html></html>")).size)
        assertEquals(0, Film1kParse.relatedOf(Jsoup.parse("<main><section><h3>Other</h3></section></main>")).size)
    }

    @Test fun `year parses from og title`() {
        assertEquals(2000, Film1kParse.yearOf("Tick Tock (2000) - Watch Free Online | Film1k"))
        assertEquals(1980, Film1kParse.yearOf("Taboo (1980) - Watch Free Online | Film1k"))
    }

    @Test fun `no year yields null`() {
        assertNull(Film1kParse.yearOf("Some Movie - Watch Free Online | Film1k"))
        assertNull(Film1kParse.yearOf(null))
    }
}
