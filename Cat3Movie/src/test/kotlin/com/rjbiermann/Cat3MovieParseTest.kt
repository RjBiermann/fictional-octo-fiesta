package com.rjbiermann

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #203: the halimmovies theme renders the 10 newest posts twice on the homepage
 * archive (once in a top "Latest" strip, once in the main grid) — fixture
 * home-dup.html reproduces the live shape (61 article.thumb, 58 hrefs, 48 unique).
 * Homepage parsing must dedupe by card href.
 */
class Cat3MovieParseTest {

    private val html = javaClass.getResourceAsStream("/home-dup.html")
        ?.readBytes()?.toString(Charsets.UTF_8) ?: error("fixture home-dup.html missing")

    @Test fun `homepage cards are deduped by href`() {
        val doc = Jsoup.parse(html)
        val all = doc.select("article.thumb")
        assertEquals(61, all.size)
        val hrefs = all.mapNotNull { it.selectFirst("a.halim-thumb")?.attr("href") }
        assertEquals(58, hrefs.size)
        assertEquals(48, hrefs.distinct().size)
        val parsed = Parse.homeCards(doc)
        assertEquals(48, parsed.size)
        assertEquals(hrefs.distinct(), parsed.mapNotNull { it.selectFirst("a.halim-thumb")?.attr("href") })
    }

    @Test fun `search keeps duplicates (dedupe is homepage-only)`() {
        // search pages have no duplicates, but the passthrough must not dedupe or drop the
        // linkless widgets either — all 61 cards are kept, incl. the 10 newest ×2
        val cards = Parse.searchCards(Jsoup.parse(html))
        assertEquals(61, cards.size)
        assertEquals(58, cards.mapNotNull { it.selectFirst("a.halim-thumb")?.attr("href") }.size)
    }
}
