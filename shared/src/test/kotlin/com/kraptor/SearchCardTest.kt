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
        assertNull(SearchCard.parse(cards[6], "span.name"))
    }

    @Test fun `green-side fallback - img-wrapped anchor with separate span title resolves (P0-14 review)`() {
        // cards[4]: title in a bare span, first <a> wraps only an img — the
        // legitimate fallback leg must still fire post-fix.
        val f = SearchCard.parse(cards[4], "span.name")
        assertEquals("Fallback Green Scene", f!!.title)
        assertEquals("/videos/8123/fallback-green/", f.href)
    }

    @Test fun `near-miss - taxonomy-path wrapper around name-ish child binds nothing (P0-14 review)`() {
        // cards[5]: a /tags/ link wrapping a poster-name span would pass the old
        // evidence heuristic; taxonomy paths are excluded outright.
        assertNull(SearchCard.parse(cards[5], "span.name"))
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

    @Test fun `attr-title card - title from the anchor title attribute (Cat3Movie ixiporn shape)`() {
        // cards[7]: title in the anchor's title attribute; anchor text is empty (img-only)
        val f = SearchCard.parse(cards[7], "a.thumb", titleAttr = "title")
        assertEquals("Attr Title Scene", f!!.title)
        assertEquals("/videos/9012/attr-scene/", f.href)
    }

    @Test fun `attr-title blank - falls back to anchor text, else null`() {
        val card = Jsoup.parse("""<div class="c"><a class="thumb" title="" href="/videos/1/a/"></a></div>""")
            .selectFirst("div.c")!!
        assertNull(SearchCard.parse(card, "a.thumb", titleAttr = "title"))

        val textCard = Jsoup.parse("""<div class="c"><a class="thumb" title="" href="/videos/1/a/">Text Title</a></div>""")
            .selectFirst("div.c")!!
        assertEquals("Text Title", SearchCard.parse(textCard, "a.thumb", titleAttr = "title")!!.title)
    }

    @Test fun `homeCards - dedupes by href, drops broken cards, keeps order`() {
        val doc = Jsoup.parse(javaClass.getResourceAsStream("/search_card_fixture.html")!!.reader().readText())
        val list = SearchCard.homeCards(
            doc, "div.card-list > div",
            titleSel = "p.mbtit a, span.name, a.thumb",
            titleAttr = "title",
        )
        // cards 0,1,4 valid (2,3,5 yield null by design), card 6 broken, card 7 attr-title
        // (kept), card 8 dupe of 0 (deduped) → 4 results
        assertEquals(4, list.size)
        assertEquals("Hot Scene", list.first().title)
        assertEquals("Attr Title Scene", list.last().title)
        assertEquals(1, list.count { it.href == "/videos/1234/hot-scene/" })
    }
}
