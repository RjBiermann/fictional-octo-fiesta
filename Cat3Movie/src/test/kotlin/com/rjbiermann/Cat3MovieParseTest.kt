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

/**
 * Issue #250: loadLinks silently returned no sources ("video doesn't play") whenever the
 * watch page lacked body[data-nonce] (CF-cached/older page shapes) or the embed iframe used
 * single quotes. StreamConfig must extract post_id + nonce with the halim_cfg fallback and
 * parse embed iframes regardless of quote style.
 */
class Cat3MovieStreamConfigTest {

    private fun res(name: String) =
        javaClass.getResourceAsStream("/$name")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("fixture $name missing")

    @Test fun `body nonce primary path`() {
        val cfg = Parse.streamConfig(Jsoup.parse(res("player-page.html")))
        assertEquals(34514, cfg?.postId)
        assertEquals("e7a09473dd", cfg?.nonce)
    }

    @Test fun `halim_cfg nonce fallback when body nonce missing`() {
        val cfg = Parse.streamConfig(Jsoup.parse(res("player-page-no-body-nonce.html")))
        assertEquals(34514, cfg?.postId)
        assertEquals("276322d4c6", cfg?.nonce)
    }

    @Test fun `player referer built from watch-page slug, no doubled watch`() {
        // data is either the base watch URL or an episode page; both must yield the same referer
        val base = "https://cat3movie.org/watch-women-at-play-1985"
        assertEquals("https://cat3movie.org/watch-women-at-play-1985/full-sv1.html",
            Parse.playerReferer("$base/full-sv1.html", 1))
        assertEquals("https://cat3movie.org/watch-women-at-play-1985/full-sv2.html",
            Parse.playerReferer(base, 2))
    }
}

/** Issue #250 re-probe: watch pages dropped the raw p.released markup — year comes from the
 *  title's "Movie (1985)" suffix (every halimmovies watch-page h1 carries it). */
class Cat3MovieYearTest {
    @Test fun `year falls back to the title suffix`() {
        assertEquals(1985, Parse.yearFromTitle("Watch Women at Play (1985)  "))
        assertEquals(1983, Parse.yearFromTitle("Joy (1983)"))
    }

    @Test fun `no year in title yields null`() {
        assertEquals(null, Parse.yearFromTitle("Watch Some Untitled Movie"))
    }
}
