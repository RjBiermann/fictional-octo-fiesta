package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fixture is the real JSON-LD block from tube.perverzija.com video pages. */
class ParseTest {

    private fun doc(ld: String) = Jsoup.parse("""<script type="application/ld+json" class="saswp-schema-markup-output">$ld</script>""")

    @Test fun `year parses from datePublished`() {
        val ld = """{"@type":"VideoObject","datePublished":"2022-04-14T22:50:35+02:00","duration":"PT39M59S"}"""
        assertEquals(2022, Parse.year(doc(ld)))
    }

    @Test fun `dead extra-selector source stays null when no datePublished`() {
        assertEquals(null, Parse.year(doc("""{"@type":"VideoObject","duration":"PT39M"}""")))
    }

    @Test fun `no ld-json script returns null`() {
        assertEquals(null, Parse.year(Jsoup.parse("<div class='extra'><span class='C'>2022</span></div>")))
    }
}
