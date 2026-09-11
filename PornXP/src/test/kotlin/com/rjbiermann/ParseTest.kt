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
    // Page 2 of such a tag falls back to the generic grid, so hasNext must come from #pages.
    @Test fun `single-result tag page has no pagination`() {
        assertFalse(Parse.searchHasNext(doc("/tag-peggydeville-p1.html")))
    }

    // multi-page tag page 1 lists page links inside #pages
    @Test fun `multi-page tag page paginates`() {
        assertTrue(Parse.searchHasNext(doc("/tag-atk-p1.html")))
    }

    @Test fun `tag url encodes query and appends page param past page 1`() {
        assertEquals("https://pxp.news/tags/Peggy%20DeVille?page=2", Parse.searchUrl("Peggy DeVille", 2))
        assertEquals("https://pxp.news/tags/Peggy", Parse.searchUrl("Peggy", 1))
    }
}
