package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TDD for issue #333: the div#sidebar widget column was swept into every
 *  home/search list (bogus Gigi-Dior-poster card linking to /advanced-search/,
 *  cross-page duplicate). Fixture cut from a live search page (2026-09-11). */
class PerverZijaListingTest {

    private val doc by lazy {
        Jsoup.parse(javaClass.classLoader.getResource("perverzija_listing_cards.html")!!.readText())
    }

    @Test fun `sidebar excluded from listing cards`() {
        // old selector picks up the sidebar widget column (3 cards + sidebar = 4)
        assertEquals(4, doc.select("div.col-md-3").size)
        assertEquals(3, PerverZijaParse.listingCards(doc).size)
    }

    @Test fun `no advanced-search href among listing cards`() {
        assertFalse(PerverZijaParse.listingCards(doc).any { it.selectFirst("a")?.attr("href") == "/advanced-search/" })
    }

    @Test fun `cards map title to own img and link`() {
        val card = PerverZijaParse.listingCards(doc).first()
        val title = card.selectFirst("img")?.attr("title")
        assertEquals("Private Xtreme 22: A Is For Anal (2005)", title)
        assertTrue(card.selectFirst("a")!!.attr("href").endsWith("-a-is-for-anal-2005/"))
    }

    @Test fun `first card title is not the sidebar widget title`() {
        val sidebarImgTitle = doc.selectFirst("div#sidebar img")!!.attr("title")
        assertFalse(PerverZijaParse.listingCards(doc).any {
            it.selectFirst("img")?.attr("title") == sidebarImgTitle && it.selectFirst("a")?.attr("href") == "/advanced-search/"
        })
    }
}
