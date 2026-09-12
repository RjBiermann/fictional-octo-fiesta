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
        assertNull(SearchCard.parse(cards[3], "span.name"))
    }

    @Test fun `tag-link-first card - title never binds to a tag link (P0-14)`() {
        // cards[2]: first <a> is a tag link, the span title has no href of its own.
        // Intended behavior: the first-<a> fallback must not bind the title to the tag
        // link — no video link resolvable means the card is rejected (null), exactly
        // like a card with no link at all.
        assertNull(SearchCard.parse(cards[2], "span.name"))
        // Same bar when the title anchor itself exists but carries no href attribute:
        // the href-less-<a> title must not adopt the tag link either.
        assertNull(SearchCard.parse(cards[3], "a.name"))
    }
}
