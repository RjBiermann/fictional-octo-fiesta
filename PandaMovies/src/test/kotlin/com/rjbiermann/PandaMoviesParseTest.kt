package com.rjbiermann

import com.kraptor.DistinctBar
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures: real pandamovies.org listing markup captured 2026-10-13 (#555 BEM "card" grammar). */
class PandaMoviesParseTest {

    private val base = "https://pandamovies.org/"

    private val searchDoc by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/panda-search.html")!!, "UTF-8", base)
    }
    private val homeDoc by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/panda-home.html")!!, "UTF-8", base)
    }
    private val videoDoc by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/panda-video.html")!!, "UTF-8", base)
    }
    /** Live `/genres/anal` capture 2026-11-22 (#1447): the term-route plural migration. */
    private val genreDoc by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/panda-genres-anal.html")!!, "UTF-8", base)
    }

    /** Live watch-page player block 2026-11-22 (#1447): the rotated embed-host set. */
    private val watchHostsDoc by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/panda-watch-hosts.html")!!, "UTF-8", base)
    }

    @Test fun `listing cards parse title href poster`() {
        val cards = Parse.cards(searchDoc)
        assertEquals(35, cards.size)
        val first = cards.first()
        assertEquals("Sex & Romance 4", first.title)
        assertEquals("https://pandamovies.org/watch-sex-romance-4-movie-online-free", first.href)
        assertTrue(first.poster?.contains("2639538.jpg") == true)
    }

    @Test fun `home cards parse — Featured strip excluded, Latest grid kept (#555)`() {
        val cards = Parse.cards(homeDoc)
        assertEquals(35, cards.size)   // 49 raw − 14 Featured `section.sec` cards
        assertEquals("Lust Pur – Conny Costa Brava", cards.first().title)
        assertTrue(cards.first().href.endsWith("-movie-online-free"))
    }

    @Test fun `card identities are distinct (Distinct bar, fixture level)`() {
        val cards = Parse.cards(searchDoc)
        DistinctBar.assertDistinctVideos(
            cards.map { DistinctBar.VideoIdentity(it.title, it.poster, it.href) }
        )
    }

    @Test fun `queryMismatch is false for healthy results (#444)`() {
        assertTrue(!Parse.queryMismatch(Parse.cards(homeDoc), "bangbros"))
        assertTrue(!Parse.queryMismatch(Parse.cards(homeDoc), "world of bangbros"))
    }

    @Test fun `queryMismatch flags zero-overlap garbage results (#444)`() {
        val garbage = listOf(
            Parse.Card("The Best of Taylor Sands", "x", null),
            Parse.Card("AT-22", "x", null),
            Parse.Card("Picking Up Teen", "x", null),
            Parse.Card("By Appointment Only", "x", null),
        )
        assertTrue(Parse.queryMismatch(garbage, "Rocco's intimacy"))
        // empty results are never a mismatch (legit no-hit search)
        assertTrue(!Parse.queryMismatch(emptyList(), "Rocco's intimacy"))
    }

    @Test fun `normalizeQuery maps curly apostrophe (#444)`() {
        assertEquals("Rocco's intimacy", Parse.normalizeQuery("Rocco\u2019s intimacy"))
        assertEquals("Rocco's intimacy", Parse.normalizeQuery("Rocco’s intimacy"))
        assertEquals("Rocco's intimacy", Parse.normalizeQuery("Rocco's intimacy"))
    }

    @Test fun `video page fields parse (#555 grammar)`() {
        val page = Parse.videoPage(videoDoc)
        assertEquals("Sex At First Sight", page.title)
        assertTrue(page.poster?.contains("1543778.jpg") == true)
        assertTrue(page.plot!!.startsWith("Sex At First Sight."))
        assertEquals(240, page.durationMin)      // st--duration "4:00:00"
        assertEquals(2010, page.year)
        assertTrue(page.tags.contains("Compilation"))
        assertTrue(page.actors.contains("Angelina Crow"))
    }

    @Test fun `related cards parsed from Similar titles section (#555)`() {
        val recs = Parse.cards(videoDoc, fromRelated = true)
        assertTrue(recs.size >= 2)
        assertTrue(recs.none { it.title == "Sex At First Sight" })
        DistinctBar.assertDistinctVideos(recs.map { DistinctBar.VideoIdentity(it.title, it.poster, it.href) })
    }

    @Test fun `watch embeds parsed from hlm data-servers (#555)`() {
        assertEquals(
            listOf(
                "https://doply.net/e/jmffsu8tekc1",
                "https://doply.net/e/3ljwfk2ex0db",
                "https://mixdrop.ag/e/8ljw8z16c664erp",
            ), Parse.embeds(videoDoc)
        )
    }

    @Test fun `hasNextPage follows the pager anchor (#555)`() {
        assertTrue(Parse.hasNextPage(homeDoc))     // a.next present (page 1)
        assertTrue(!Parse.hasNextPage(videoDoc))   // video pages have no pager
    }

    @Test fun `hasNextPage false on last page — cards present, no next anchor (WP end of list)`() {
        // last page at every surface is a plain card listing with the pager anchor stripped
        val last = Jsoup.parse(homeDoc.outerHtml().replace(Regex("<a[^>]*class=\"next[^\"]*\"[^>]*>[\\s\\S]*?</a>"), ""))
        assertTrue(Parse.cards(last).isNotEmpty())
        assertTrue(!Parse.hasNextPage(last))
    }

    @Test fun `term-route cards parse on the plural genres surface (#1447)`() {
        val cards = Parse.cards(genreDoc)
        assertEquals(27, cards.size)
        assertEquals("Audrey Hollander & Otto Bauer & Rick Masters Bound Gagged DP Facial Collector Scene", cards.first().title)
        assertTrue(cards.first().href.endsWith("-movie-online-free"))
        assertTrue(cards.first().poster?.contains("3338755.jpg") == true)
        assertTrue(Parse.hasNextPage(genreDoc))
    }

    @Test fun `rowCandidates derives the taxonomy twin in both directions (#1447)`() {
        val plural = "https://pandamovies.org/genres/anal"
        val singular = "https://pandamovies.org/genre/anal"
        assertEquals(listOf(plural, singular), Parse.rowCandidates(plural))
        assertEquals(listOf(singular, plural), Parse.rowCandidates(singular))
        assertEquals(
            listOf("https://pandamovies.org/studios/brazzers", "https://pandamovies.org/studio/brazzers"),
            Parse.rowCandidates("https://pandamovies.org/studios/brazzers")
        )
        assertEquals(
            listOf("https://pandamovies.org/years/1971", "https://pandamovies.org/release-year/1971"),
            Parse.rowCandidates("https://pandamovies.org/years/1971")
        )
    }

    @Test fun `rowCandidates never invents a twin for slug-free rows (#1447)`() {
        // A bare route (/genres index, /movies, the homepage) is NOT a term page: flipping its
        // last segment would request a page that does not exist, so the single URL stands.
        assertEquals(listOf("https://pandamovies.org"), Parse.rowCandidates("https://pandamovies.org"))
        assertEquals(listOf("https://pandamovies.org/movies"), Parse.rowCandidates("https://pandamovies.org/movies"))
        assertEquals(listOf("https://pandamovies.org/genres"), Parse.rowCandidates("https://pandamovies.org/genres"))
        assertEquals(
            listOf("https://pandamovies.org/most-viewed"),
            Parse.rowCandidates("https://pandamovies.org/most-viewed")
        )
    }

    @Test fun `watch embeds enumerate every rotated host, not an allowlist (#1447)`() {
        assertEquals(
            listOf(
                "https://playmogo.com/e/5q3x3l9ty1gx",
                "https://lulustream.com/e/s7t887tw1aey",
                "https://mixdrop.ag/e/oww46pwraljnqwg",
                // the provider normalizes the voe player path to `voe.sx/<id>` (both shapes
                // serve the same redirect shell — measured 2026-11-22, kept as-is)
                "https://voe.sx/kkco3ozvwnch",
                "https://vinovo.to/e/eeexdq3dtpv87p",
                "https://vidara.so/e/SDen3CBe4iEcz",
                "https://peeplayer.com/e/iDgbI3Cda4Cc",
            ),
            Parse.embeds(watchHostsDoc)
        )
    }

    @Test fun `duration parsers`() {
        assertEquals(240, Parse.clockMinutes("4:00:00"))
        assertEquals(1, Parse.clockMinutes("1:00"))       // assumed H:MM — see clockMinutes KDoc
        assertEquals(null, Parse.clockMinutes("N/A"))
    }
}
