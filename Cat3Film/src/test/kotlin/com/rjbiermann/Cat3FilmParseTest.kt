package com.rjbiermann

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #292: data-completeness. ratingValue sits in the page's single ld+json script's
 * @graph[0].aggregateRating; watch pages carry one .wserver per season (names "Season 1/2",
 * episode numbers in data-no, ids in data-ep) all present on a single ?sv=1&part=1
 * response. Single-server pages (movies) label the server "Server 1".
 */
class Cat3FilmParseTest {

    private fun res(name: String) =
        javaClass.getResourceAsStream("/$name")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("fixture $name missing")

    // --- score ---------------------------------------------------------------

    @Test fun `ratingValue is extracted from ld+json`() {
        assertEquals(8.1, Parse.rating(res("detail-handmaiden.html"))!!, 0.001)
    }

    @Test fun `ratingValue absent returns null`() {
        assertNull(Parse.rating("<html><body>no ld here</body></html>"))
    }

    // --- episodes ------------------------------------------------------------

    @Test fun `multi-season watch page splits into seasons`() {
        val eps = Parse.episodes(Jsoup.parse(res("watch-hache.html")))
        assertEquals(14, eps.size)
        assertEquals(listOf(1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2), eps.map { it.season })
        assertEquals((1..8).toList() + (1..6).toList(), eps.map { it.number })
        assertEquals("449", eps[0].data)
        assertEquals("462", eps.last().data)
    }

    @Test fun `movie single server keeps episode 1 without season`() {
        val eps = Parse.episodes(Jsoup.parse(res("watch-handmaiden.html")))
        assertEquals(1, eps.size)
        assertEquals(1, eps[0].number)
        assertNull(eps[0].season)
        assertEquals("405", eps[0].data)
    }
}
