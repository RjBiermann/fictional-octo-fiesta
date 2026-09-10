package com.rjbiermann

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TDD for the card-block parser on the video page (fixture: live HTML 2026-09-09, AVOP-179). */
class JavmostParseTest {

    private fun info() = Javmost.Parse.cardBlock(
        Jsoup.parse(javaClass.classLoader!!.getResource("avop-179.html")!!.readText())
    )

    @Test fun `year from Release text`() = assertEquals(2015, info().year)

    @Test fun `duration in minutes from Time text`() = assertEquals(130, info().duration)

    @Test fun `actors from star anchors`() =
        assertEquals(listOf("Kana Sekikawa"), info().actors)

    @Test fun `tags from category anchors, trimmed`() = assertEquals(
        listOf("Creampie", "Solowork", "Married Woman", "Kimono", "Mourning", "Cuckold", "Hot Spring", "AV OPEN 2015 Milf Dept."),
        info().tags
    )

    @Test fun `missing fields are null or empty`() {
        val info = Javmost.Parse.cardBlock(Jsoup.parse("<div class=\"card-block\"><p>nothing here</p></div>"))
        assertNull(info.year)
        assertNull(info.duration)
        assertEquals(emptyList<String>(), info.actors)
        assertEquals(emptyList<String>(), info.tags)
    }

    // --- poster fix (issue #239): real URL is on <source data-srcset>; img[data-src] is a white lazyload placeholder
    private fun card() = Jsoup.parse(
        javaClass.classLoader!!.getResource("related-card.html")!!.readText()
    ).selectFirst("div.card")!!

    @Test fun `poster prefers data-srcset over lazyload placeholder`() =
        assertEquals("https://img3.javmost.ws/images/480/OKS-148.webp", Javmost.Parse.parseCardUrl(card()))

    @Test fun `poster null when only preload placeholder present`() =
        assertNull(Javmost.Parse.parseCardUrl(Jsoup.parse(
            "<div class='card'><img data-src='https://x/preload.webp' src='https://x/preload.webp'></div>"
        ).selectFirst("div.card")!!))

    // --- dooplayer fix (issue #239): embed page carries x-embed-* metas driving POST api/stream/<token>
    @Test fun `dooplayer metas parse and stream url extracts`() {
        val doc = Jsoup.parse(javaClass.classLoader!!.getResource("dooplayer-embed.html")!!.readText())
        val info = Javmost.Parse.dooPlayer(doc)
        assertEquals("MTEyNzY1.78f62c066bdc5291", info.token)
        assertEquals("https://www.dooplayer.com/api/stream/", info.api)
        assertEquals("1789005230", info.et)
        assertEquals("c660231500a7405addf730af6f0de5c40ba2ae9fdf5b0398d4d6d862df0002a2", info.sig)
        assertEquals(
            "https://cdn.mostplayer.com/stream?t=abc",
            Javmost.Parse.dooStream("{\"ok\":true,\"url\":\"https:\\/\\/cdn.mostplayer.com\\/stream?t=abc\"}")
        )
    }
}
