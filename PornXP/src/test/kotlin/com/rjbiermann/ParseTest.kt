package com.rjbiermann

import com.kraptor.SearchCard
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures: real /tags/ search pages (tag page 1 + single-result tag page). */
class ParseTest {

    private fun doc(res: String) =
        Jsoup.parse(javaClass.getResourceAsStream(res)!!.reader().readText(), "https://pxp.news/")

    // cards on tag pages use the same shape as the homepage grid; the shared Parse holds
    @Test fun `tag page card parses with existing card selectors`() {
        val card = doc("/tag-atk-p1.html").select(".item_cont").first()!!
        val f = SearchCard.parse(card, ".item_title", hrefSel = "a[href*=videos]", posterSel = ".item_thumb img")
        assertEquals("Virtual Vacation Hawaii 8/16", f!!.title)
        assertTrue(f.href.contains("/videos/55337788126"))
        assertEquals("/5533778864126.jpg", f.poster)
    }

    // /tags/Peggy%20DeVille has one result and an empty #pages block → no more pages.
    // Page 2 of such a tag falls back to the generic grid, so hasNext must come from the
    // site's own next control, not from the mere presence of page links.
    @Test fun `single-result tag page has no pagination`() {
        assertFalse(Parse.searchHasNext(doc("/tag-peggydeville-p1.html")))
    }

    // multi-page tag page 1 carries the site's ">" next control
    @Test fun `multi-page tag page paginates`() {
        assertTrue(Parse.searchHasNext(doc("/tag-atk-p1.html")))
    }

    // /tags/ATKGirlfriends p119 is the real last page (5 cards): #pages still links back
    // to earlier pages but has no ">" next control. p120+ serve the unfiltered fallback.
    @Test fun `last tag page with only back-links has no next`() {
        assertFalse(Parse.searchHasNext(doc("/tag-atk-p119-last.html")))
    }

    // issue #331: card .item_dur (site clock H:MM:SS / MM:SS / M) → CloudStream minutes.
    @Test fun `item dur clock converts to minutes`() {
        assertEquals(61, Parse.clockMinutes("1:01:12"))   // tag-peggydeville fixture card
        assertEquals(34, Parse.clockMinutes("34:11"))
        assertEquals(9, Parse.clockMinutes("09:57"))
        assertEquals(null, Parse.clockMinutes(""))
        assertEquals(null, Parse.clockMinutes("HD"))
    }

    // the card's own .item_dur round-trips through the shared card Parse
    @Test fun `card item dur parses to minutes`() {
        val card = doc("/tag-peggydeville-p1.html").select(".item_cont").first()!!
        assertEquals(61, Parse.clockMinutes(card.selectFirst(".item_dur")?.text() ?: ""))
    }

    @Test fun `tag url encodes query and appends page param past page 1`() {
        assertEquals("https://pxp.news/tags/Peggy%20DeVille?page=2", Parse.searchUrl("https://pxp.news", "Peggy DeVille", 2))
        assertEquals("https://pxp.news/tags/Peggy", Parse.searchUrl("https://pxp.news", "Peggy", 1))
    }
}
