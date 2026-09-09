package com.sexfilm

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fixtures are real page fragments (block parameters-info + div.flist + meta tags). */
class ParseTest {

    private fun doc() =
        Jsoup.parse(javaClass.getResourceAsStream("/classy-page-fragment.html")!!.reader().readText(), "https://en.sex-film.biz/")

    @Test fun `year parses from parameters-info`() {
        assertEquals(2024, Parse.year(doc()))
    }

    @Test fun `actors parse from Casting row`() {
        assertEquals(listOf("Amateur"), Parse.actors(doc()))
    }

    @Test fun `tags come from the Genre anchor row, not the 1-tag meta`() {
        assertEquals(
            listOf("All Sex", "Teens", "Couples", "Cumshots", "International", "Lingerie", "Stockings", "Tattoos"),
            Parse.tags(doc())
        )
    }

    @Test fun `meta-tag fallback when no Genre row`() {
        val d = Jsoup.parse("""<meta itemprop="genre" content="A&#160;,&#160;B">""")
        assertEquals(listOf("A", "B"), Parse.tags(d))
    }

    @Test fun `missing rows yield empty or null`() {
        val d = Jsoup.parse("<div></div>")
        assertEquals(null, Parse.year(d))
        assertEquals(emptyList<String>(), Parse.actors(d))
        assertEquals(emptyList<String>(), Parse.tags(d))
    }
}
