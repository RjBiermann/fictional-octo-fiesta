package com.rjbiermann

import com.kraptor.DistinctBar
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures: real pandamovies.org listing markup captured 2026-10-13 (#555 BEM "card" grammar). */
class PandaMoviesParseTest {

    private val base = "https://pandamovies.org/"

    private val searchDoc by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/panda-search.html")!!, "UTF-8", base)
    }
    private val homeDoc by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/panda-home.html")!!, "UTF-8", base)
    }
    private val videoDoc by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/panda-video.html")!!, "UTF-8", base)
    }

    @Test fun `listing cards parse title href poster`() {
        val cards = Parse.cards(searchDoc)
        assertEquals(35, cards.size)
        val first = cards.first()
        assertEquals("Sex & Romance 4", first.title)
        assertEquals("https://pandamovies.org/watch-sex-romance-4-movie-online-free", first.href)
        assertTrue(first.poster?.contains("2639538.jpg") == true)
    }

    @Test fun `home cards parse — Featured strip excluded, Latest grid kept (#555)`() {
        val cards = Parse.cards(homeDoc)
        assertEquals(35, cards.size)   // 49 raw − 14 Featured `section.sec` cards
        assertEquals("Lust Pur – Conny Costa Brava", cards.first().title)
        assertTrue(cards.first().href.endsWith("-movie-online-free"))
    }

    @Test fun `card identities are distinct (Distinct bar, fixture level)`() {
        val cards = Parse.cards(searchDoc)
        DistinctBar.assertDistinctVideos(
            cards.map { DistinctBar.VideoIdentity(it.title, it.poster, it.href) }
        )
    }

    @Test fun `queryMismatch is false for healthy results (#444)`() {
        assertTrue(!Parse.queryMismatch(Parse.cards(homeDoc), "bangbros"))
        assertTrue(!Parse.queryMismatch(Parse.cards(homeDoc), "world of bangbros"))
    }

    @Test fun `queryMismatch flags zero-overlap garbage results (#444)`() {
        val garbage = listOf(
            Parse.Card("The Best of Taylor Sands", "x", null),
            Parse.Card("AT-22", "x", null),
            Parse.Card("Picking Up Teen", "x", null),
            Parse.Card("By Appointment Only", "x", null),
        )
        assertTrue(Parse.queryMismatch(garbage, "Rocco's intimacy"))
        // empty results are never a mismatch (legit no-hit search)
        assertTrue(!Parse.queryMismatch(emptyList(), "Rocco's intimacy"))
    }

    @Test fun `normalizeQuery maps curly apostrophe (#444)`() {
        assertEquals("Rocco's intimacy", Parse.normalizeQuery("Rocco\u2019s intimacy"))
        assertEquals("Rocco's intimacy", Parse.normalizeQuery("Rocco’s intimacy"))
        assertEquals("Rocco's intimacy", Parse.normalizeQuery("Rocco's intimacy"))
    }

    @Test fun `video page fields parse (#555 grammar)`() {
        val page = Parse.videoPage(videoDoc)
        assertEquals("Sex At First Sight", page.title)
        assertTrue(page.poster?.contains("1543778.jpg") == true)
        assertTrue(page.plot!!.startsWith("Sex At First Sight."))
        assertEquals(240, page.durationMin)      // st--duration "4:00:00"
        assertEquals(2010, page.year)
        assertTrue(page.tags.contains("Compilation"))
        assertTrue(page.actors.contains("Angelina Crow"))
    }

    @Test fun `related cards parsed from Similar titles section (#555)`() {
        val recs = Parse.cards(videoDoc, fromRelated = true)
        assertTrue(recs.size >= 2)
        assertTrue(recs.none { it.title == "Sex At First Sight" })
        DistinctBar.assertDistinctVideos(recs.map { DistinctBar.VideoIdentity(it.title, it.poster, it.href) })
    }

    @Test fun `watch embeds parsed from hlm data-servers (#555)`() {
        assertEquals(
            listOf(
                "https://doply.net/e/jmffsu8tekc1",
                "https://doply.net/e/3ljwfk2ex0db",
                "https://mixdrop.ag/e/8ljw8z16c664erp",
            ), Parse.embeds(videoDoc)
        )
    }

    @Test fun `hasNextPage follows the pager anchor (#555)`() {
        assertTrue(Parse.hasNextPage(homeDoc))     // a.next present (page 1)
        assertTrue(!Parse.hasNextPage(videoDoc))   // video pages have no pager
    }

    @Test fun `duration parsers`() {
        assertEquals(222, Parse.minutes("3 hrs. 42 mins."))   // legacy prose (old fixture videos)
        assertEquals(9, Parse.minutes("9 mins."))
        assertEquals(null, Parse.minutes("N/A"))
        assertEquals(240, Parse.clockMinutes("4:00:00"))
        assertEquals(1, Parse.clockMinutes("1:00"))
        assertEquals(null, Parse.clockMinutes("N/A"))
    }
}
