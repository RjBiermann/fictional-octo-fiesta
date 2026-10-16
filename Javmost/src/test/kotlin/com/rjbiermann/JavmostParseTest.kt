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

    // --- issue #376: the /ri3123o235r/ AJAX response carries embed urls under data[];
    // embedTargets returns EVERY url (the array is not always single-element), so loadLinks
    // can dispatch them all — emturbovid chains server-direct, everything else through the
    // framework loadExtractor (voe.sx / dood family / streamtape registry rows)
    @Test fun `embedTargets extracts every url from the ajax response fixture`() {
        val json = javaClass.classLoader!!.getResource("dooplayer-ajax-response.json")!!.readText()
        assertEquals(
            listOf("https://www.dooplayer.com/embed/e/MTEzMzM1.31a75573f599c352"),
            Javmost.Parse.embedTargets(json)
        )
    }

    @Test fun `embedTargets returns all urls on a multi-element data array`() {
        assertEquals(
            listOf("https://voe.sx/e/abc123", "https://dood.ws/e/xyz789"),
            Javmost.Parse.embedTargets("{\"status\":\"success\",\"data\":[\"https:\\/\\/voe.sx\\/e\\/abc123\",\"https:\\/\\/dood.ws\\/e\\/xyz789\"]}")
        )
    }

    @Test fun `embedTargets unescapes slashed urls`() {
        assertEquals(
            listOf("https://emturbovid.com/t/abc"),
            Javmost.Parse.embedTargets("{\"data\":[\"https:\\/\\/emturbovid.com\\/t\\/abc\"]}")
        )
    }

    @Test fun `embedTargets empty on error response without data`() =
        assertTrue(Javmost.Parse.embedTargets("{\"ok\":false,\"error\":\"bad token\"}").isEmpty())

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

    // --- 2026-10 audit: dooplayer.com / mostplayer.com embed service is BACK (dead at the
    // #332/#376 fixes). Embed pages serve the x-embed contract again: GET (Sec-Fetch-Dest:
    // iframe + Referer) → content="<token>"name=x-embed-token | x-embed-api | x-embed-et |
    // x-embed-sig; POST <api><urlencoded-token> with X-Embed-Auth/ET/SIG + {"ref":<embed>}
    // → {"ok":true,"url":"<direct-stream>"}. Fix restores the branch HOST-AGNOSTICALLY
    // (detect the metas, not the hostname — hardening against future host migrations).
    private fun dooMetas(fixture: String) = Javmost.Parse.dooMetas(
        javaClass.classLoader!!.getResource(fixture)!!.readText()
    )

    @Test fun `dooMetas parses the live dooplayer embed page`() {
        val m = dooMetas("dooplayer-embed.html")!!
        assertEquals("MTEzMjky.5ef6592a1f6cbbc3", m.token)
        assertEquals("https://www.dooplayer.com/api/stream/", m.api)
        assertTrue(m.et.toLong() > 1_700_000_000)
        assertEquals(64, m.sig.length)
    }

    @Test fun `dooMetas parses the mostplayer embed page (same contract, other host)`() {
        val m = dooMetas("mostplayer-embed.html")!!
        assertEquals("MTE1NjA2MA.318bda65e33b3894", m.token)
        assertEquals("https://www.mostplayer.com/api/stream/", m.api)
    }

    @Test fun `dooMetas null on an embed page without the metas`() =
        assertNull(Javmost.Parse.dooMetas("<html><title>nope</title></html>"))

    @Test fun `dooMetas null when metas incomplete (missing sig)`() =
        assertNull(Javmost.Parse.dooMetas(
            "<meta content=\"tok\"name=x-embed-token><meta content=\"https://x/api/stream/\"name=x-embed-api><meta content=\"123\"name=x-embed-et>"
        ))

    @Test fun `dooStream returns the direct stream url`() {
        assertEquals(
            "https://cdn.mostplayer.com/stream?t=abc",
            Javmost.Parse.dooStream("""{"ok":true,"url":"https://cdn.mostplayer.com/stream?t=abc"}""")
        )
    }

    @Test fun `dooStream null on error shape (no bogus substring artifact)`() {
        // regression of the 2026-09-10 reviewer finding: substringAfter with a missing
        // delimiter default used to yield "{" on this body
        assertNull(Javmost.Parse.dooStream("""{"ok":false,"error":"bad token"}"""))
    }

    @Test fun `dooStream null on non-json garbage`() =
        assertNull(Javmost.Parse.dooStream("<html>gateway timeout</html>"))

    @Test fun `dooStream null when ok without url`() =
        assertNull(Javmost.Parse.dooStream("""{"ok":true}"""))

    // --- 2026-10 audit finding 2: video pages now render TWO select_part buttons per group
    // (a 'parent' one and a 'child' one). The old loadLinks regex hardcoded 'parent', so
    // child-sourced embeds were dropped — ABW-286's child rows add a dood.so mirror embed,
    // a second distinct mostplayer embed, and a whole group (34) the parent-only pass never
    // saw. Parse.servers() (pure) matches both variants; the placeholder no-op call
    // select_part('1',YWRzMg,'','no',...) can't match (group is not numeric).
    private fun servers(fixture: String) = Javmost.Parse.servers(
        javaClass.classLoader!!.getResource(fixture)!!.readText()
    )

    @Test fun `servers matches both parent and child buttons on abw-286`() {
        val s = servers("abw-286.html")
        assertEquals(13, s.size)
        assertEquals(setOf("34", "41", "46", "50", "54", "61"), s.map { it.group }.toSet())
    }

    @Test fun `servers matches parent-child pairs on avsa-457`() {
        val s = servers("avsa-457-video.html")
        assertEquals(4, s.size)
        assertEquals(setOf("60", "62"), s.map { it.group }.toSet())
    }

    @Test fun `servers ignores the no-op placeholder call`() =
        assertTrue(Javmost.Parse.servers(
            "select_part('1',YWRzMg,'','no',YWRzNA,YWRzNQ,YWRzNg)"
        ).isEmpty())
}
