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
    private fun watchDoc() = Jsoup.parse(
        javaClass.classLoader.getResource("xmoviesforyou-watch.html")!!.readText()
    )
    private fun watchAliasesDoc() = Jsoup.parse(
        javaClass.classLoader.getResource("xmoviesforyou-watch-aliases.html")!!.readText()
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

    // ---- streamSources(): the site-hosted HLS player config (FINDINGS-1446 live) ----

    @Test
    fun streamEndpointReadsInlinePlayerConfig() {
        val ep = XMoviesForYouParse.streamEndpoint(watchDoc())!!
        assertEquals("179297189623482", ep.videoId)
        assertEquals("dedi_1", ep.target)
    }

    @Test
    fun streamEndpointAbsentOnPostsWithoutPlayer() {
        // older uploads carry download chips only — the HLS leg must be skipped, not fail
        assertNull(XMoviesForYouParse.streamEndpoint(watchAliasesDoc()))
    }

    // ---- streamLinks(): every mirror chip, host-agnostic (FINDINGS-1446 drift) ----

    @Test
    fun streamLinksReturnsEveryMirrorChip() {
        // 3/3 chips, including mxdrop.click — the host the pre-#1446 selector dropped
        assertEquals(
            listOf(
                "https://streamtape.com/v/VBrk4mZMLpSxjJ",
                "https://mxdrop.click/f/033rp7xqhm63er",
                "https://playmogo.com/d/mr7w99gthuut"
            ),
            XMoviesForYouParse.streamLinks(watchDoc())
        )
    }

    @Test
    fun streamLinksSurvivesRotatedMirrorHostsAndLostPanelId() {
        // rotated mixdrop alias + dood.to landing, and no #downloads id on the panel
        assertEquals(
            listOf(
                "https://streamtape.com/v/8JbXRa1G3rS4RB",
                "https://m1xdrop.bz/f/wlmooopra7n07o",
                "https://dood.to/d/zlsj31eis2a6"
            ),
            XMoviesForYouParse.streamLinks(watchAliasesDoc())
        )
    }

    @Test
    fun streamLinksIgnoresJumpLinkAdsAndSiteRail() {
        // the hero "Download" button is an in-page jump (#downloads), the rail links are not sources
        val links = XMoviesForYouParse.streamLinks(watchDoc())
        assertTrue(links.none { it.startsWith("#") })
        assertTrue(links.none { it.contains("adtng.com") || it.contains("greentrafsa.com") })
        assertTrue(links.none { it.contains("xmoviesforyou.video") || it.contains("t.me") })
        assertEquals(links.size, links.distinct().size)
    }

    // ---- playerPath(): Dood-family landing pages wrap the player at /e/<id> ----

    @Test
    fun playerPathRewritesDoodLandingPages() {
        assertEquals(
            "https://playmogo.com/e/mr7w99gthuut",
            XMoviesForYouParse.playerPath("https://playmogo.com/d/mr7w99gthuut")
        )
        // other host grammars are untouched: mixdrop /f/, streamtape /v/, already-/e/ players
        assertEquals(
            "https://mxdrop.click/f/033rp7xqhm63er",
            XMoviesForYouParse.playerPath("https://mxdrop.click/f/033rp7xqhm63er")
        )
        assertEquals(
            "https://streamtape.com/v/VBrk4mZMLpSxjJ",
            XMoviesForYouParse.playerPath("https://streamtape.com/v/VBrk4mZMLpSxjJ")
        )
        assertEquals(
            "https://playmogo.com/e/mr7w99gthuut",
            XMoviesForYouParse.playerPath("https://playmogo.com/e/mr7w99gthuut")
        )
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
