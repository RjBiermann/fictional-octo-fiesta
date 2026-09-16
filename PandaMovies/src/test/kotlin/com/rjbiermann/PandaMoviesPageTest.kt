package com.rjbiermann

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #425 follow-up: hasNext follows the WordPress search semantics observed live.
 * WP serves up to 40 results per page (`posts_per_page=40`, confirmed via the
 * site's own analytics pixel) and pages past the real end return a card-less
 * 200 body (or a 404 for search URLs). The site renders no usable pagination
 * block on /search/, /movies/ or /genre/ pages, so the only reliable signal
 * is the card count: a full 40-card page may have a next page, anything less
 * is the last page.
 */
class PandaMoviesPageTest {
    /** Real search page fixture (5 cards), ADR-0005 convention. */
    private val fixture = org.jsoup.Jsoup.parse(
        javaClass.getResource("/panda-search.html")!!.readText()
    )

    /** One real card from the fixture, with a unique href per copy so `cards`'s
     *  distinctBy-href dedup doesn't collapse the synthetic full page. */
    private val cardHtml = fixture.selectFirst("div.ml-item")!!.outerHtml()

    private fun page(n: Int) = org.jsoup.Jsoup.parse(
        (1..n).joinToString("") { cardHtml.replace("watch-we-live-together-32", "watch-test-fixture-$it") }
    )

    @Test
    fun `real short search page is the last`() {
        assertFalse(Parse.hasNextPage(fixture))
    }

    @Test
    fun `full pages carry next, short pages are the last`() {
        assertTrue(Parse.hasNextPage(page(40)))
        assertFalse(Parse.hasNextPage(page(39)))
        assertFalse(Parse.hasNextPage(page(0)))
    }
}
