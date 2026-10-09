package com.rjbiermann

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/**
 * Tags regression (issue #538, FINDINGS-538.md 2026-10-09): the standalone VideoObject
 * JSON-LD no longer carries articleSection — tags are parsed from the static
 * `.categories-elm .categories-wrap a.category-item` anchors instead (decades/country/genre
 * display labels, mirroring the old articleSection values, fixture: tokyo-nights 2026-10-09).
 */
class EroticmvTagsTest {

    @Test fun `parses tags from categories-elm block`() {
        val html = javaClass.getResourceAsStream("/watch-tokyo-nights.html")!!.readBytes().decodeToString()
        val tags = Eroticmv.parseTags(Jsoup.parse(html))
        assertEquals(listOf("2020s", "Asian Erotica", "Philippines", "Swinging", "Threesome"), tags)
    }

    @Test fun `no categories block yields null`() {
        assertNull(Eroticmv.parseTags(Jsoup.parse("<html><body><p>hi</p></body></html>")))
    }

    @Test fun `nav palette is not scraped as tags`() {
        val doc = Jsoup.parse(
            """<html><body>
                 <li class="menu-item menu-item-object-category"><a href="/category/genre/milf/" class="x">MILF</a></li>
               </body></html>""")
        assertNull(Eroticmv.parseTags(doc))
    }
}
