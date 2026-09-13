package com.film1k

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TDD for issue #233: related videos, year mapping. Fixtures from live film1k.com pages. */
class Film1kParseTest {

    private val byseNoSlashDoc by lazy {
        javaClass.classLoader.getResource("film1k_video_byse_noslash.html")!!.readText()
    }
    private val turbovidDoc by lazy {
        javaClass.classLoader.getResource("film1k_video_turbovid.html")!!.readText()
    }
    private val turbovidEmbed by lazy {
        javaClass.classLoader.getResource("film1k_turbovid_embed.html")!!.readText()
    }

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
    // ---- issue #408: embed-markup drift (Byse no trailing slash + new turbovidhls host) ----

    @Test fun `byse code from slugless iframe markup`() {
        assertEquals("m2pahe0ccyjt", Film1kParse.byseCode(byseNoSlashDoc))
        // path form still parses (slug variant)
        assertEquals(
            "dipzme6fc8um",
            Film1kParse.byseCode("""<source src="https://film1k.xyz/e/dipzme6fc8um/bitter-honey.mp4">""")
        )
    }

    @Test fun `byse code takes the first match when several sources exist`() {
        // restored first-match semantics (as pre-#408 `Regex.find`) — a page embedding
        // multiple /e/{code} sources (e.g. trailer + feature) keeps picking the first one
        assertEquals(
            "aaa111222333",
            Film1kParse.byseCode(
                """<iframe src="https://film1k.xyz/e/aaa111222333"></iframe>
                   |""".trimMargin().plus("<source src=\"https://film1k.xyz/e/zzz999888777\">")
            )
        )
    }

    @Test fun `no byse embed yields null`() {
        assertNull(Film1kParse.byseCode("<html>nothing here</html>"))
    }

    @Test fun `turbovid code from page markup`() {
        assertEquals("696f9b3d701a3", Film1kParse.turbovidCode(turbovidDoc))
        assertNull(Film1kParse.turbovidCode("<html></html>"))
    }

    @Test fun `turbovid master m3u8 extracted from embed page`() {
        val url = Film1kParse.turbovidStreamUrl(turbovidEmbed)
        assertEquals("https://cdn3.turboviplay.com/data3/696f9b3d701a3/696f9b3d701a3.m3u8", url)
    }

    @Test fun `turbovid master m3u8 rejects look-alike host`() {
        // page-controlled look-alike (evilturboviplay.com) must not be handed to the player
        assertNull(
            Film1kParse.turbovidStreamUrl(
                """var urlPlay = 'https://evilturboviplay.com/data3/696f9b3d701a3/696f9b3d701a3.m3u8';"""
            )
        )
    }
}
