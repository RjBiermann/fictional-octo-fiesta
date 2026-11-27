// TDD parse tests for the 123AV listing-card seam (ADR-0005, S2 of #1423 / #1654).
// Fixtures are live captures — plain curl from the runner (egress colo MSP), 2026-11-27:
//
//   home-page-1.html = https://123av.com/en             homepage: hero swiper + `New Releases`
//                                                       + `Recently Added` = 24 real cards plus
//                                                       one Alpine <template x-for> prototype
//                                                       card that must never be parsed
//   home-page-2.html = https://123av.com/en/new?page=2  row 1 (New Releases), page 2
//   new-page-1.html  = https://123av.com/en/new?page=1  row 1, page 1
//   hot-page-1.html  = https://123av.com/en/hot?page=1  row 2 (Hot), page 1
//   hot-page-2.html  = https://123av.com/en/hot?page=2  row 2, page 2
//   search-page-1.html = https://123av.com/en/search?keyword=1pondo          (S3)
//   search-page-2.html = https://123av.com/en/search?keyword=1pondo&page=2   (S3)
//   makers-index.html  = https://123av.com/en/makers  60 studios on index page 1 (S3)
//   maker-1pondo-page-1.html = https://123av.com/en/makers/1pondo           (S3)
//   maker-1pondo-page-2.html = https://123av.com/en/makers/1pondo?page=2    (S3)
//
// Titles are never asserted as literals: the expected value is always the fixture attribute the
// parse function is contracted to read, so the tests document the selector chain. Page-1 ∩ page-2
// overlap is asserted for both rows because the site pages by offset over a growing list, so a
// boundary card can legitimately repeat — the disjointness seen on capture is the evidence, and
// the assertion is what fails if the row URL stops honouring `?page=`.
package com.rjbiermann

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Av123ParseTest {

    private fun html(res: String): String =
        javaClass.getResourceAsStream(res)!!.reader().readText()

    private fun doc(res: String) = Jsoup.parse(html(res), Av123Parse.MAIN)

    private val listings = listOf(
        "home page" to "/home-page-1.html",
        "new releases page 1" to "/new-page-1.html",
        "new releases page 2" to "/home-page-2.html",
        "hot page 1" to "/hot-page-1.html",
        "hot page 2" to "/hot-page-2.html",
    )

    // ---- parseCards() -------------------------------------------------------------

    @Test
    fun `home page drops the alpine template prototype card`() {
        val document = doc("/home-page-1.html")
        // 25 div.card in the served HTML: 24 real cards + the `<template x-for>` prototype,
        // whose href/src/duration are Alpine bindings (`:href`, `:src`, `x-text`) with no value.
        assertEquals(25, document.select(Av123Parse.CARD).size)
        assertEquals(24, document.select("a.card__cover[href]").size)

        val cards = Av123Parse.parseCards(html("/home-page-1.html"))
        assertEquals(24, cards.size)
        assertTrue("prototype card leaked", cards.none { it.url.endsWith("/v/null") })
    }

    @Test
    fun `no card lacks a title or an absolute video url`() {
        for ((label, res) in listings) {
            val cards = Av123Parse.parseCards(html(res))
            assertTrue("$label: no cards parsed", cards.isNotEmpty())
            for (card in cards) {
                assertTrue("$label: blank title", card.title.isNotBlank())
                assertTrue("$label: relative url ${card.url}", card.url.startsWith("https://"))
                assertTrue("$label: blank id", card.id.isNotBlank())
            }
        }
    }

    @Test
    fun `card fields come from the card selectors`() {
        val document = doc("/new-page-1.html")
        val el = document.select(Av123Parse.CARD).first()!!
        val card = Av123Parse.parseCards(html("/new-page-1.html")).first()

        val href = el.selectFirst("a.card__cover[href]")!!.attr("href")
        assertEquals(href.substringAfterLast('/'), card.id)
        assertEquals(Av123Parse.MAIN + href, card.url)
        assertEquals(el.selectFirst("h3.card__title")!!.text(), card.title)
        assertEquals(el.selectFirst("img.card__img")!!.attr("src"), card.poster)
        assertNotNull("card exposes .card__dur", el.selectFirst("span.card__dur"))
    }

    @Test
    fun `poster is absolute on the image CDN`() {
        val card = Av123Parse.parseCards(html("/new-page-1.html")).first()
        val poster = card.poster!!
        assertTrue(poster, poster.startsWith("https://icdn.123av.me/"))
    }

    @Test
    fun `listing cards are distinct`() {
        for ((label, res) in listOf(
            "new releases page 1" to "/new-page-1.html",
            "hot page 1" to "/hot-page-1.html",
            "hot page 2" to "/hot-page-2.html",
        )) {
            val ids = Av123Parse.parseCards(html(res)).map { it.id }
            assertEquals("$label: duplicate ids", ids.size, ids.toSet().size)
        }
    }

    // ---- cross-page dedupe -------------------------------------------------------

    @Test
    fun `fresh drops a boundary card re-served on the next page`() {
        val seen = HashSet<String>()
        val page1 = Av123Parse.parseCards(html("/new-page-1.html"))
        assertEquals(Av123Parse.PAGE_SIZE, Av123Parse.fresh(page1, seen).size)

        // Offset paging over a growing list: hero is the last card of page 1 and the first of
        // page 2. Page 2 must not re-serve it.
        val boundary = page1.last()
        val page2 = Av123Parse.parseCards(html("/home-page-2.html"))
        val fresh = Av123Parse.fresh(listOf(boundary) + page2, seen)
        assertEquals(page2.size, fresh.size)
        assertFalse("boundary card re-served", fresh.any { it.id == boundary.id })
    }

    @Test
    fun `fresh re-seeds the row when page one is cleared`() {
        val seen = HashSet<String>()
        val page1 = Av123Parse.parseCards(html("/new-page-1.html"))
        Av123Parse.fresh(page1, seen)
        assertTrue("page re-served without a clear", Av123Parse.fresh(page1, seen).isEmpty())

        // The adapter clears the set before page 1, so a refresh serves the row again.
        seen.clear()
        assertEquals(page1.size, Av123Parse.fresh(page1, seen).size)
    }

    // ---- duration -----------------------------------------------------------------

    @Test
    fun `duration parses the card clock and stays null when absent`() {
        val el = doc("/new-page-1.html").select(Av123Parse.CARD).first()!!
        val clock = el.selectFirst("span.card__dur")!!.text()
        assertEquals(Av123Parse.duration(clock), Av123Parse.parseCards(html("/new-page-1.html")).first().duration)
        assertEquals(2 * 3600 + 20 * 60 + 31, Av123Parse.duration("2:20:31"))
        assertEquals(12 * 60 + 44, Av123Parse.duration("12:44"))
        assertNull(Av123Parse.duration(""))
        assertNull(Av123Parse.duration(null))
    }

    // ---- pagination ---------------------------------------------------------------

    @Test
    fun `page 1 and page 2 of each row are disjoint`() {
        for ((label, p1, p2) in listOf(
            Triple("new releases", "/new-page-1.html", "/home-page-2.html"),
            Triple("hot", "/hot-page-1.html", "/hot-page-2.html"),
        )) {
            val one = Av123Parse.parseCards(html(p1)).map { it.id }
            val two = Av123Parse.parseCards(html(p2)).map { it.id }
            assertEquals("$label: page 1 size", Av123Parse.PAGE_SIZE, one.size)
            assertEquals("$label: page 2 size", Av123Parse.PAGE_SIZE, two.size)
            assertEquals("$label: pages overlap", emptySet<String>(), one.toSet() intersect two.toSet())
        }
    }

    @Test
    fun `hasNext follows the site pager not the card count`() {
        // The pager is the site's own signal: `a[rel=next]` present means another page exists.
        assertTrue(Av123Parse.hasNext(doc("/new-page-1.html")))
        assertTrue(Av123Parse.hasNext(doc("/home-page-2.html")))
        assertTrue(Av123Parse.hasNext(doc("/hot-page-1.html")))
        assertTrue(Av123Parse.hasNext(doc("/hot-page-2.html")))
        // The homepage itself does not paginate — its rows link out to the listings above.
        assertFalse(Av123Parse.hasNext(doc("/home-page-1.html")))
    }

    @Test
    fun `listing url uses the site page parameter`() {
        assertEquals("https://123av.com/en/new", Av123Parse.pageUrl(Av123Parse.NEW, 1))
        assertEquals("https://123av.com/en/new?page=2", Av123Parse.pageUrl(Av123Parse.NEW, 2))
        assertEquals("https://123av.com/en/hot?page=3", Av123Parse.pageUrl(Av123Parse.HOT, 3))
    }

    // ---- search (S3) ---------------------------------------------------------------

    @Test
    fun `search page one and page two are disjoint`() {
        val one = Av123Parse.parseSearch(html("/search-page-1.html"))
        val two = Av123Parse.parseSearch(html("/search-page-2.html"))
        assertEquals(Av123Parse.PAGE_SIZE, one.size)
        assertEquals(Av123Parse.PAGE_SIZE, two.size)
        // The pipeline duplicate bar: a card served on both pages fails Verification.
        assertEquals(emptySet<String>(), one.map { it.id }.toSet() intersect two.map { it.id }.toSet())
        // Search is not a separate grammar: it is the listing card, pins the ura studio code.
        assertTrue("keyword=1pondo returned a non-1pondo card", one.all { it.id.startsWith("1pondo-") })
        assertTrue("keyword=1pondo page 2 returned a non-1pondo card", two.all { it.id.startsWith("1pondo-") })
    }

    @Test
    fun `search parses through the shared card grammar`() {
        for (res in listOf("/search-page-1.html", "/search-page-2.html")) {
            // parseSearch() is a seam over cards(), not a second selector chain: if someone
            // forks the selectors for search, this equality breaks before the drift ships.
            assertEquals(res, Av123Parse.cards(doc(res)), Av123Parse.parseSearch(html(res)))
        }
    }

    @Test
    fun `a search row and a maker row produce the same card shape`() {
        val search = Av123Parse.parseSearch(html("/search-page-1.html")).first()
        val maker = Av123Parse.parseCards(html("/maker-1pondo-page-1.html")).first()
        for (card in listOf(search, maker)) {
            assertTrue("blank id", card.id.isNotBlank())
            assertTrue("blank title", card.title.isNotBlank())
            assertEquals("https://123av.com/en/v/${card.id}", card.url)
            assertTrue("poster ${card.poster}", card.poster!!.startsWith("https://icdn.123av.me/"))
            assertTrue("missing duration", (card.duration ?: 0) > 0)
        }
    }

    @Test
    fun `search url is the site GET form with page`() {
        assertEquals("https://123av.com/en/search?keyword=1pondo", Av123Parse.searchUrl("1pondo", 1))
        assertEquals(
            "https://123av.com/en/search?keyword=1pondo&page=2",
            Av123Parse.searchUrl("1pondo", 2),
        )
        // The keyword is urlencoded, never interpolated raw (spaces, non-ASCII queries).
        assertEquals("https://123av.com/en/search?keyword=1+pondo", Av123Parse.searchUrl("1 pondo", 1))
        assertTrue(Av123Parse.searchUrl("2 人", 1).contains("%E4%BA%BA"))
        assertEquals("/en/search", doc("/search-page-1.html").selectFirst("form.search__field")!!.attr("action"))
    }

    @Test
    fun `the search page exposes no suggest endpoint to quick-search against`() {
        // hasQuickSearch = false is a decision from evidence, so pin the evidence. S1 grepped the
        // client bundle for a suggest/autocomplete route (0 matches); the served page adds none.
        val page = html("/search-page-1.html")
        assertFalse("a suggest route appeared on the search page", page.contains("suggest", ignoreCase = true))
        val input = doc("/search-page-1.html").selectFirst("input[name=keyword]")!!
        assertFalse("keyword field gained an input handler", input.hasAttr("@input") || input.hasAttr("x-on:input"))
    }

    // ---- makers (S3) ---------------------------------------------------------------

    @Test
    fun `makers index parses the studio grid`() {
        val document = doc("/makers-index.html")
        val makers = Av123Parse.parseMakers(html("/makers-index.html"))
        assertEquals(60, document.select(Av123Parse.MCARD).size)
        assertEquals(document.select(Av123Parse.MCARD).size, makers.size)
        val bySlug = document.select(Av123Parse.MCARD).associateBy { el -> el.attr("href").substringAfterLast('/') }
        for (maker in makers) {
            val el = bySlug.getValue(maker.slug)
            assertEquals(el.selectFirst(".mcard__name")!!.text(), maker.name)
            assertEquals(Av123Parse.MAIN + "/en/makers/${maker.slug}", maker.url)
        }
        assertEquals(makers.size, makers.map { it.slug }.toSet().size)
    }

    @Test
    fun `the eleven ura maker rows are the issue's studio set`() {
        assertEquals(11, Av123Parse.MAKER_ROWS.size)
        assertEquals(
            listOf(
                "1pondo", "caribbeancom", "heyzo", "takara-video", "10musume", "tokyo-hot",
                "pacopacomama", "h4610", "c0930", "h0930", "xxx-av",
            ),
            Av123Parse.MAKER_ROWS.map { it.slug },
        )
        for (row in Av123Parse.MAKER_ROWS) {
            assertEquals("https://123av.com/en/makers/${row.slug}", Av123Parse.makerUrl(row.slug, 1))
            assertEquals("https://123av.com/en/makers/${row.slug}?page=2", Av123Parse.makerUrl(row.slug, 2))
        }
    }

    @Test
    fun `every maker row label is the site's own name`() {
        val document = doc("/makers-index.html")
        val names = Av123Parse.parseMakers(html("/makers-index.html")).associate { it.slug to it.name }
        for (row in Av123Parse.MAKER_ROWS) {
            // Ten of the eleven sit on index page 1; `xxx-av` is beyond it, so its label comes
            // from the header nav link the site renders for the same href.
            val expected = names[row.slug]
                ?: document.select("a[href]").firstOrNull { it.attr("href") == "/en/makers/${row.slug}" }?.text()
            assertEquals("${row.slug} label", row.label, expected)
        }
    }

    @Test
    fun `maker page one and page two are disjoint`() {
        val one = Av123Parse.parseCards(html("/maker-1pondo-page-1.html"))
        val two = Av123Parse.parseCards(html("/maker-1pondo-page-2.html"))
        assertEquals(Av123Parse.PAGE_SIZE, one.size)
        assertEquals(Av123Parse.PAGE_SIZE, two.size)
        assertEquals(emptySet<String>(), one.map { it.id }.toSet() intersect two.map { it.id }.toSet())
        assertTrue(Av123Parse.hasNext(doc("/maker-1pondo-page-2.html")))
    }

    @Test
    fun `maker rows carry ura studio codes not leaks or decensored titles`() {
        for (res in listOf("/maker-1pondo-page-1.html", "/maker-1pondo-page-2.html")) {
            val ids = Av123Parse.parseCards(html(res)).map { it.id }
            assertTrue("$res: not a 1pondo row", ids.all { it.startsWith("1pondo-") })
            assertTrue(
                "$res: leak/decensored card in a ura maker row",
                ids.none { it.contains("leak") || it.contains("decensored") },
            )
        }
        // The site does serve leaks elsewhere (the home grid) — the ura rows must not inherit them.
        val home = Av123Parse.parseCards(html("/home-page-1.html")).map { it.id }
        assertTrue("home grid stopped carrying leak titles", home.any { it.contains("leaked") })
    }
}
