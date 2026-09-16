package com.rjbiermann

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #425 follow-up: has-Next follows the WordPress search semantics observed live.
 * WP serves up to 40 results per page (`posts_per_page=40`, confirmed via the
 * site's own analytics pixel) and pages past the real end return a card-less
 * 200 body (or a 404 for search URLs). The site renders no usable pagination
 * block on /search/, /movies/ or /genre/ pages, so the only reliable signal
 * is the card count: a full 40-card page may have a next page, anything less
 * is the last page.
 */
class PandaMoviesPageTest {
    // one real card lifted from panda-search.html, trimmed to its markup shell
    private val card =
        """<div class="ml-item"><a href="/watch-roccos-intimacy-movie-online-free/" title="Rocco&#8217;s Intimacy" class="ml-mask jt-info" data-url="/watch-roccos-intimacy-movie-online-free/" data-movie-id="1376910" oldtitle="Rocco&#8217;s Intimacy"><img data-lazy-src="https://i0.wp.com/pandanetwork.club/adult/wp-content/uploads/2024/06/1376910h.jpg" data-src="https://i0.wp.com/pandanetwork.club/adult/wp-content/uploads/2024/06/1376910h.jpg" class="lazyload"></a></div>"""

    private fun page(n: Int) = org.jsoup.Jsoup.parse(card.repeat(n))

    @Test
    fun `wp full pages carry next, short pages are the last`() {
        assertTrue(Parse.hasFeatures(page(40)))
        assertFalse(Parse.hasFeatures(page(39)))
        assertFalse(Parse.hasFeatures(page(8)))
        assertFalse(Parse.hasFeatures(page(0)))
    }
}
