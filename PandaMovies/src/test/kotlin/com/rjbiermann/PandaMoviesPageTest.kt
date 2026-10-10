package com.rjbiermann

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * hasNext follows the WordPress listing semantics observed live (#555): listings
 * serve 35 cards per page and pages past the real end return a card-less 200
 * (or a 404). No pagination block is rendered, so the only reliable signal is
 * the card count. Fixtures: fresh card__-grammar search page.
 */
class PandaMoviesPageTest {
    private val fixture = org.jsoup.Jsoup.parse(
        javaClass.getResource("/panda-search.html")!!.readText()
    )

    /** One real card from the fixture, with a unique href per copy so `cards`'s
     *  distinctBy-href dedup doesn't collapse the synthetic full page. */
    private val cardHtml = fixture.selectFirst("article.card")!!.outerHtml()

    private fun page(n: Int) = org.jsoup.Jsoup.parse(
        (1..n).joinToString("") { cardHtml.replace("watch-sex-romance-4", "watch-test-fixture-$it") }
    )

    @Test
    fun `full pages carry next, short pages are the last`() {
        assertTrue(Parse.hasNextPage(page(35)))
        assertTrue(!Parse.hasNextPage(page(34)))
        assertTrue(!Parse.hasNextPage(page(0)))
    }
}
