// Unit tests for the XMoviesForYou card Parse functions (issue #535), TDD-first per ADR-0005.
// All fixtures are live transcripts from 2026-10-09 (TLS-impersonated probe, FINDINGS-535.md):
// home = hero carousel `a[data-video-card]` cards + the still-live `a.group.flex.flex-col` grid,
// search = rebuilt /new-search `a.card` cards. HTTP flows stay covered by pipeline Verification.
package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XMoviesForYouParseTest {

    private fun homeDoc() = Jsoup.parse(
        javaClass.classLoader.getResource("xmoviesforyou-home.html")!!.readText()
    )
    private fun searchDoc() = Jsoup.parse(
        javaClass.classLoader.getResource("xmoviesforyou-search.html")!!.readText()
    )

    // ---- CARD matches every live listing surface (FINDINGS-535 Homepage table) ----

    @Test
    fun cardSelectorMatchesCarouselGridAndSearchShapes() {
        // home page 1: 3 unique carousel cards + duplicated carousel entry + 2 grid cards
        assertEquals(6, homeDoc().select(XMoviesForYouParse.CARD).size)
        // search (new-search page): a.card
        assertEquals(3, searchDoc().select(XMoviesForYouParse.CARD).size)
    }

    // ---- parse(): one root-anchor card -> title/href/poster ----

    @Test
    fun parseCarouselCard() {
        val card = homeDoc().selectFirst("a[data-video-card]")!!
        val c = XMoviesForYouParse.parse(card)!!
        assertEquals("Kali Roses (PAWG Kali Roses / 09.25.2026)", c.title) // studio bracket stripped
        assertEquals("/pawged-kali-roses-pawg-kali-roses", c.href)
        assertEquals(
            "https://xmoviescdn.online/2026/09/pawged-kali-roses-pawg-kali-roses-xmoviesforyou-6ab675967b2cb.webp",
            c.poster
        )
    }

    @Test
    fun parseSearchCard() {
        val card = searchDoc().selectFirst("a.card")!!
        val c = XMoviesForYouParse.parse(card)!!
        assertEquals("Mackenzie Page (Do You Know Who I Am? / 01.29.2026)", c.title)
        assertEquals("/faketaxi-mackenzie-page-do-you-know-who-i-am", c.href)
        assertTrue(c.poster!!.endsWith("FakeTaxi-Mackenzie-Page-Do-You-Know-Who-I-Am-xmoviesforyou.jpg"))
    }

    @Test
    fun parseGridCard() {
        val card = homeDoc().selectFirst("a.group.flex.flex-col")!!
        val c = XMoviesForYouParse.parse(card)!!
        assertEquals("Serena Gomes (Anal In Public With Serena Gomes / 10.09.2026)", c.title)
    }

    // ---- cards(): full page -> deduped list (home carousel repeats items on page 1) ----

    @Test
    fun cardsDedupeCarouselRepeats() {
        val out = XMoviesForYouParse.cards(homeDoc())
        assertEquals(5, out.size) // 6 anchors, pawged repeats -> 5 unique
        assertEquals(5, out.map { it.href }.toSet().size)
        assertTrue(out.any { it.href == "/pawged-kali-roses-pawg-kali-roses" })
    }

    @Test
    fun cardsSearchPage() {
        val out = XMoviesForYouParse.cards(searchDoc())
        assertEquals(3, out.size)
        assertEquals("/faketaxi-mackenzie-page-do-you-know-who-i-am", out[0].href)
        assertEquals("Mackenzie Page (Do You Know Who I Am? / 01.29.2026)", out[0].title)
    }

    // ---- broken card rejected, not failed ----

    @Test
    fun parseRejectsCardWithoutTitleOrLink() {
        assertNull(XMoviesForYouParse.parse(Jsoup.parse("""<a href="/x-y">no h3 here</a>""").selectFirst("a")!!))
        assertNull(XMoviesForYouParse.parse(Jsoup.parse("""<a class="card"><h3>Bare Title</h3></a>""").selectFirst("a")!!))
    }

    @Test
    fun cardsSkipDataUriPosters() {
        val doc = Jsoup.parse(
            """<a href="/k-y" data-video-card><img src="data:image/gif;base64,R0lGOD"><h3>Kai Yonder (2026)</h3></a>"""
        )
        val c = XMoviesForYouParse.parse(doc.selectFirst("a")!!)!!
        assertNull(c.poster)
        assertEquals("Kai Yonder (2026)", c.title) // no studio bracket -> pass-through, regex strips nothing
    }
}
