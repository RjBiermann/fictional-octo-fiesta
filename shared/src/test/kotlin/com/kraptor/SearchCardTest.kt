package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixture: real listing-card shapes — KVS lazyload card, card-root link theme, broken card. */
class SearchCardTest {

    private val cards by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/search_card_fixture.html")!!.reader().readText())
            .select("div.card-list > div")
    }

    @Test fun `kvs card - lazyload poster wins, data placeholder skipped`() {
        val f = SearchCard.parse(cards[0], "p.mbtit a", posterSel = "div.mbimg img")
        assertEquals("Hot Scene", f!!.title)
        assertEquals("/videos/1234/hot-scene/", f.href)
        assertEquals("/contents/videos_screenshots/1000/1234/preview.jpg", f.poster)
    }

    @Test fun `card-root theme - href from inner anchor, entities decoded`() {
        val f = SearchCard.parse(cards[1], "span.name")
        assertEquals("Other Scene & Friends", f!!.title)
        assertEquals("/videos/5678/other-scene/", f.href)
        assertEquals("/thumbs/5678.jpg", f.poster)
    }

    @Test fun `broken card - missing title yields null`() {
        assertNull(SearchCard.parse(cards[2], "span.name"))
    }
}
