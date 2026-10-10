package com.rjbiermann

import com.kraptor.DistinctBar
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures: live pandamovies.org listing (?s=sex) and video page, post migration to the card/vid grammar (issue #555). */
class PandaMoviesParseTest {

    private val base = "https://pandamovies.org/"

    private val searchDoc by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/panda-search.html")!!, "UTF-8", base)
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
        assertTrue(first.poster!!.endsWith("?resize=360,540"))
    }

    @Test fun `card identities are distinct (Distinct bar, fixture level)`() {
        val cards = Parse.cards(searchDoc)
        DistinctBar.assertDistinctVideos(
            cards.map { DistinctBar.VideoIdentity(it.title, it.poster, it.href) }
        )
    }

    @Test fun `hasNextPage threshold follows 35-cards-per-page (#555)`() {
        assertEquals(true, Parse.hasNextPage(searchDoc))          // full page = 35
        val empty = Jsoup.parse("<div class=\"grid\"><article class=\"card\"></article></div>")
        assertEquals(false, Parse.hasNextPage(empty))
    }

    @Test fun `queryMismatch is false for healthy results (#444)`() {
        assertTrue(!Parse.queryMismatch(Parse.cards(searchDoc), "Sex romance"))
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
        assertEquals("Sex & Romance 4", page.title)   // breadcrumb bc__c, agrees with card title
        assertTrue(page.poster!!.contains("2639538.jpg") && page.poster.endsWith("?resize=360,540"))
        assertTrue(page.plot!!.contains("spark to build"))
        assertEquals(305, page.durationMin)          // 5:05:00
        assertEquals(2019, page.year)
        assertTrue(page.tags.contains("Romance"))
        assertTrue(page.actors.contains("Abigail Mac"))
    }

    @Test fun `related cards parsed from grid--blk similar-titles strip`() {
        val recs = Parse.cards(videoDoc, fromRelated = true)
        println("DBG recs=" + recs.size + " sel=" + videoDoc.select("div.grid--blk article.card").size + " blk=" + videoDoc.select("div.grid--blk").size)
        assertTrue(recs.size >= 2)
        assertTrue(recs.none { it.title == "Sex & Romance 4" })
        DistinctBar.assertDistinctVideos(recs.map { DistinctBar.VideoIdentity(it.title, it.poster, it.href) })
    }

    @Test fun `watch embeds parsed from data-servers attribute and hosts normalized`() {
        val embeds = Parse.embeds(videoDoc)
        assertEquals(
            listOf(
                "https://lulustream.com/6tah55m5clnh",
                "https://lulustream.com/ncbi3j86c73o",
                "https://doply.net/e/lkzjgwmdd52u",
                "https://doply.net/e/0wlslsuddh5r",
                "https://doply.net/e/xu6fxle6be5p",
                "https://voe.sx/saoflwz2jkjh",
                "https://mixdrop.ag/e/nl0z8qzdiqe74q",
                "https://mixdrop.ag/e/r6nek91rfpzzk9",
            ), embeds
        )
    }

    @Test fun `duration parser (H-MM-SS and M-SS, #555)`() {
        assertEquals(305, Parse.minutes("5:05:00"))
        assertEquals(25, Parse.minutes("25:00"))
        assertEquals(null, Parse.minutes("N/A"))
    }
}
