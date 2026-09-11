package com.rjbiermann

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // --- plot fix (issue #270): the video card-block's second a[alt] (href == video url, alt != code) carries the synopsis
    @Test fun `synopsis from title anchor in own card-block`() {
        val doc = Jsoup.parse(javaClass.classLoader!!.getResource("avop-179.html")!!.readText())
        assertEquals(
            "Limited Husband Unofficial One Night The 2nd \"raw\" Take Yoshijuku Woman Affair Hot Spring Trip Of One MurasakiMinoru Forty Years Old",
            Javmost.Parse.plot(doc, "https://www.javmost.ws/AVOP-179/")
        )
    }

    @Test fun `plot falls back to null when no synopsis anchor`() =
        assertNull(Javmost.Parse.plot(Jsoup.parse("<div class=\"card-block\"><p>nothing</p></div>"), "https://x/foo/"))

    @Test fun `plot null on bare without fixtures`() {
        // no card-block at all
        assertNull(Javmost.Parse.plot(Jsoup.parse("<html></html>"), "https://x/foo/"))
    }

    // --- issue #332 finding 1: all/1/category "pending" bucket is all-null metadata (every URL 404s);
    // showlist must drop these entries — but ONLY in the all group (uncensor null-meta items are live)
    private fun allPage1() = com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(javaClass.classLoader!!.getResource("showlist2-all-page1.json")!!.readText())
        .get("result")

    @Test fun `every all-group page-1 entry is the dead pending bucket`() {
        val items = allPage1()
        assertEquals(24, items.size())
        assertTrue(items.all { Javmost.Parse.pendingReason(it) != null })
    }

    @Test fun `non-pending entry is not flagged`() = assertNull(
        Javmost.Parse.pendingReason(
            com.fasterxml.jackson.databind.ObjectMapper().readTree(
                """{"url":"https://x/CARIBBEANCOM-091026-001/","name":"CARIBBEANCOM","release":null,"star":[{"name":"x"}]}"""
            )
        )
    )

    // --- issue #332 finding 2: the /ri3123o235r/ AJAX response carries the embed url under data[];
    // dooplayer/mostplayer embeds from it are dead, so loadLinks keeps only emturbovid ones
    @Test fun `ajaxEmbed extracts url from the ajax response fixture`() {
        val json = javaClass.classLoader!!.getResource("dooplayer-ajax-response.json")!!.readText()
        assertEquals("https://www.dooplayer.com/embed/e/MTEzMzM1.31a75573f599c352", Javmost.Parse.ajaxEmbed(json))
    }

    @Test fun `ajaxEmbed null on error response without url key`() =
        assertNull(Javmost.Parse.ajaxEmbed("{\"ok\":false,\"error\":\"bad token\"}"))

    // --- issue #300 finding 1: recs are anchor-parent cards; self-link filtered downstream
    private fun recs() = Javmost.Parse.recs(
        Jsoup.parse(javaClass.classLoader!!.getResource("avsa-457-recs.html")!!.readText()),
        "https://www.javmost.ws"
    )

    @Test fun `recs extract all wrapper-anchored cards`() {
        // 2 related cards + 2 self card-block anchors (filtered by it.url != url downstream)
        assertEquals(4, recs().size)
        assertEquals("https://www.javmost.ws/VOD-036-UNCENSORED-EDIT/", recs()[0].url)
        assertEquals("VOD-036-UNCENSORED-EDIT", recs()[0].title)
    }

    @Test fun `recs poster from child card data-srcset, not preload`() {
        assertEquals("https://img3.javmost.ws/images/480/VOD-036-UNCENSORED-EDIT.webp", recs()[0].poster)
        // card-block anchors have no div.card child → null poster, null-safe
        assertNull(recs()[2].poster)
        assertNull(recs()[3].poster)
    }

    // --- issue #300 finding 3: showlist2 full_name entities decoded
    @Test fun `titles decode html entities`() {
        assertEquals("\"Raw\" — It's Cute", Javmost.Parse.title("\"Raw\" &#8212; It&#39;s Cute", null))
        assertEquals("Some \"quoted\" here", Javmost.Parse.title("", " Some &quot;quoted&quot; here "))
        assertEquals("", Javmost.Parse.title("", ""))
    }
}
