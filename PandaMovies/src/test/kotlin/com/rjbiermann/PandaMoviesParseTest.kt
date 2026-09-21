package com.rjbiermann

import com.kraptor.DistinctBar
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures: real pandamovies.pw search/listing markup and a real video page (issue #421). */
class PandaMoviesParseTest {

    private val base = "https://pandamovies.pw/"

    private val searchDoc by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/panda-search.html")!!, "UTF-8", base)
    }
    private val videoDoc by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/panda-video.html")!!, "UTF-8", base)
    }

    @Test fun `listing cards parse title href poster`() {
        val cards = Parse.cards(searchDoc)
        assertEquals(5, cards.size)
        val first = cards.first()
        assertEquals("We Live Together 32", first.title)
        assertEquals("https://pandamovies.pw/watch-we-live-together-32-movie-online-free", first.href)
        assertTrue(first.poster?.contains("i3.wp.com") == true)
    }

    @Test fun `card identities are distinct (Distinct bar, fixture level)`() {
        val cards = Parse.cards(searchDoc)
        DistinctBar.assertDistinctVideos(
            cards.map { DistinctBar.VideoIdentity(it.title, it.poster, it.href) }
        )
    }

    @Test fun `cited #439 query parses 8 cards from live capture (oldtitle→h2 fallback)`() {
        val doc = Jsoup.parse(
            javaClass.getResourceAsStream("/panda-search-roccos-intimacy.html")!!,
            "UTF-8", base
        )
        val cards = Parse.cards(doc)
        assertEquals(8, cards.size)
        assertEquals("Rocco’s Intimacy", cards.first().title)
        assertEquals(
            "https://pandamovies.pw/watch-roccos-intimacy-movie-online-free",
            cards.first().href
        )
        assertTrue(cards.first().poster?.contains("1376910h.jpg") == true)
    }

    @Test fun `queryMismatch is false for healthy results (#444)`() {
        val doc = Jsoup.parse(
            javaClass.getResourceAsStream("/panda-search-roccos-intimacy.html")!!,
            "UTF-8", base
        )
        assertTrue(!Parse.queryMismatch(Parse.cards(doc), "Rocco's intimacy"))
        assertTrue(!Parse.queryMismatch(Parse.cards(doc), "rocco’s intimacy"))
    }

    @Test fun `queryMismatch flags zero-overlap garbage results (#444)`() {
        // titles reproduced live from pandamovies.pw for near-miss query encodings
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

    @Test fun `video page fields parse`() {
        val page = Parse.videoPage(videoDoc)
        assertEquals("We Live Together 31", page.title)
        assertTrue(page.poster?.contains("1685035h.jpg") == true)
        assertTrue(page.plot!!.startsWith("We Live Together Vol. 31"))
        assertEquals(222, page.durationMin)      // 3 hrs. 42 mins.
        assertEquals(2014, page.year)
        assertTrue(page.tags.contains("Cunnilingus"))
        assertTrue(page.actors.contains("Malena Morgan"))
    }

    @Test fun `related cards parsed`() {
        val recs = Parse.cards(videoDoc, fromRelated = true)
        assertTrue(recs.size >= 2)
        assertTrue(recs.none { it.title == "We Live Together 31" })
        DistinctBar.assertDistinctVideos(recs.map { DistinctBar.VideoIdentity(it.title, it.poster, it.href) })
    }

    @Test fun `watch embeds parsed and lulu normalize to registry canonical`() {
        val embeds = Parse.embeds(videoDoc)
        assertEquals(
            listOf(
                "https://lulustream.com/kv92aveomh88",
                "https://playmogo.com/e/cz4kqifd60hh",
                "https://mixdrop.my/e/z1znljvdcgv0mr0",
                "https://voe.sx/4npykjbbbl4p",
            ), embeds
        )
    }

    @Test fun `duration parser`() {
        assertEquals(222, Parse.minutes("3 hrs. 42 mins."))
        assertEquals(9, Parse.minutes("9 mins."))
        assertEquals(null, Parse.minutes("N/A"))
    }
}
