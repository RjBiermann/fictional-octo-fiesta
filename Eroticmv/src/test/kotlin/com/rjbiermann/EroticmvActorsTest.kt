package com.rjbiermann

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** Actors must be parsed from the Stars block (fixture: pretty-peaches-2-1987, 2026-09-09). */
class EroticmvActorsTest {

    @Test fun `parses actors from Stars block`() {
        val html = javaClass.getResourceAsStream("/actor-block.html")!!.readBytes().decodeToString()
        val doc = Jsoup.parse(html)
        val actors = Eroticmv.parseActors(doc)
        assertTrue(actors.contains("Buck Adams"))
        assertTrue(actors.contains("Ashley Welles"))
        assertTrue(actors.none { it.isBlank() })
    }

    @Test fun `no actor block yields empty list`() {
        assertEquals(0, Eroticmv.parseActors(Jsoup.parse("<html><body><p>hi</p></body></html>")).size)
    }
}
