package com.rjbiermann

import com.kraptor.DistinctBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are the verbatim `<link rel="canonical">` / `<link rel="next|prev">` head lines of
 * https://www.pornhub.com/video (page 1) and https://www.pornhub.com/video?page=2 (page 2)
 * followed by the verbatim `<ul id="videoCategory">` listing block of the same responses, plus
 * the verbatim `<title>` + error block of the past-the-end `?page=9999` response (HTTP 404) —
 * the markup the card and pagination Parse seams read. See FINDINGS §§Homepage,
 * Pagination (home).
 */
class PornHubParseTest {

    private val provider = PornHub()

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.reader().readText()

    private fun page1() = provider.parseVideoCards(fixture("home-page-1.html"))
    private fun page2() = provider.parseVideoCards(fixture("home-page-2.html"))

    @Test fun `home page 1 parses a non-empty card list`() {
        val videos = page1()
        // The fixture holds 31 listing cards (FINDINGS §Homepage); a threshold, not an exact
        // count, so a fixture refresh does not need a test edit.
        assertTrue("page 1 should parse the listing cards, got ${videos.size}", videos.size >= 20)
    }

    @Test fun `home page 2 parses a non-empty card list`() {
        val videos = page2()
        assertTrue("page 2 should parse the listing cards, got ${videos.size}", videos.size >= 20)
    }

    @Test fun `page 2 does not repeat page 1`() {
        val overlap = page1().map { it.url }.intersect(page2().map { it.url }.toSet())
        assertTrue("page 2 must be fresh items, but ${overlap.size} repeat", overlap.isEmpty())
    }

    @Test fun `distinct bar holds on both home pages`() {
        for ((label, videos) in listOf("page 1" to page1(), "page 2" to page2())) {
            DistinctBar.assertDistinctVideos(
                videos.map { DistinctBar.VideoIdentity(it.name, it.posterUrl, it.url) }
            )
            assertTrue("$label: missing poster", videos.all { it.posterUrl != null })
            assertTrue("$label: blank title", videos.all { it.name.isNotBlank() })
            assertTrue("$label: url points at a video page",
                videos.all { it.url.startsWith("https://www.pornhub.com/view_video.php?viewkey=") })
        }
    }

    @Test fun `every home poster carries the site Referer`() {
        // CloudStream's image loader sends no Referer; pix-cdn77 answers 403 without one
        // (FINDINGS §Listing — CDN shard & hotlink matrix).
        val videos = page1()
        assertTrue("home rows must carry the poster header",
            videos.all { it.posterHeaders == mapOf("Referer" to "$BASE/") })
        assertEquals(mapOf("Referer" to "$BASE/"), Parse.posterHeaders)
    }

    @Test fun `the pix-cdn77 home still keeps its signature verbatim`() {
        // The refreshed home fixture is a pix-cdn77 fetch. Stripping `hash=`/`validto=`
        // answers 403 even with a Referer, so the emitted URL must be the markup value.
        val raw = Regex("""https://pix-cdn77\.phncdn\.com/[^"]+\?hash=[^"&]+&validto=\d+""")
            .find(fixture("home-page-1.html"))!!.value
        assertTrue("fixture still exposes the signature shape", raw.contains("hash="))
        val emitted = page1().firstOrNull { it.posterUrl == raw }
        assertNotNull("the pix-cdn77 card must survive parse", emitted)
        assertEquals("the site Referer rides along", mapOf("Referer" to "$BASE/"),
            emitted!!.posterHeaders)
    }

    @Test fun `card metadata is read where the card exposes it`() {
        val cards = Parse.cards(fixture("home-page-1.html"))
        assertTrue(cards.isNotEmpty())
        assertTrue("every card exposes var.duration", cards.all { it.durationMinutes != null })
        assertTrue("every card exposes span.views", cards.all { it.views != null })
    }

    @Test fun `clock formats convert to minutes`() {
        assertEquals(9, Parse.clockMinutes("9:25"))
        assertEquals(62, Parse.clockMinutes("1:02:03"))
        assertEquals(null, Parse.clockMinutes(""))
        assertEquals(null, Parse.clockMinutes(null))
    }

    @Test fun `row urls paginate with the query param`() {
        assertEquals("$BASE/video", Parse.pageUrl("$BASE/video", 1))
        assertEquals("$BASE/video?page=2", Parse.pageUrl("$BASE/video", 2))
        assertEquals("$BASE/video?o=mv&page=2", Parse.pageUrl("$BASE/video?o=mv", 2))
    }

    @Test fun `home page 1 announces a next page`() {
        // The row stop is derived from the site's own signal, exactly like search — never a
        // constant (FINDINGS §Pagination (home)).
        assertTrue("page 1 must carry the site's own rel=next",
            Parse.hasNextPage(fixture("home-page-1.html")))
    }

    @Test fun `home page 2 announces a next page`() {
        assertTrue("page 2 must carry the site's own rel=next",
            Parse.hasNextPage(fixture("home-page-2.html")))
    }

    @Test fun `a past-the-end home page stops the row`() {
        // ?page=9999 answers HTTP 404 with no listing block, no rel=next and no page_next:
        // that body is what makes the derived stop safe (FINDINGS §Pagination (home)).
        val html = fixture("home-past-end.html")
        assertEquals("the 404 body carries no listing cards",
            emptyList<Any>(), provider.parseVideoCards(html))
        assertFalse("the 404 body must not claim a next page", Parse.hasNextPage(html))
        assertFalse("the row stop rule must stop there too", Parse.homeHasNext(html))
    }

    @Test fun `a degraded listing with no next signal stops the row`() {
        // The listing block is present but the head link and the enabled Next anchor are both
        // gone (a gated or degraded body): the stop rule still says "no next page" — a constant
        // here is exactly the defect this story removes.
        val html = """<html><head><title>Pornhub</title></head><body>
            <ul id="videoCategory"><li class="pcVideoListItem">
                <a href="/view_video.php?viewkey=degradedcard1" title="Degraded Card">
                    <img data-image="https://pix-fl.phncdn.com/degraded.jpg"></a>
                <span class="title"><a href="/view_video.php?viewkey=degradedcard1">Degraded Card</a></span>
                <var class="duration">9:25</var><span class="views"><var>12K</var> views</span>
            </li></ul>
            <div class="pagination3"><ul><li class="page_next disabled">
                <a href="" class="orangeButton">Next</a></li></ul></div>
        </body></html>"""
        assertTrue("the listing block must still parse",
            provider.parseVideoCards(html).isNotEmpty())
        assertFalse("no head link and no enabled Next anchor means no next page",
            Parse.hasNextPage(html))
        assertFalse("the row stop rule follows it", Parse.homeHasNext(html))
    }

    @Test fun `the home fallback survives only for a body with no markup`() {
        // Documented fallback (FINDINGS §Pagination (home)): an empty body carries no evidence,
        // so the constant keeps the row open; any real body is derived, never a constant.
        assertTrue("an empty body keeps the row open", Parse.homeHasNext(""))
        assertTrue("a whitespace-only body is the same case", Parse.homeHasNext(" \n\t "))
        assertFalse("the derived rule alone would stop there", Parse.hasNextPage(""))
        assertTrue("a live page keeps paging", Parse.homeHasNext(fixture("home-page-1.html")))
        assertFalse("a signal-less real body stops the row",
            Parse.homeHasNext(fixture("home-past-end.html")))
    }

    @Test fun `ad slots without a video link are dropped`() {
        val html = """<ul id="videoCategory">
            <li class="pcVideoListItem"><div class="phimage">ad slot</div></li>
            <li class="pcVideoListItem">
                <a href="/view_video.php?viewkey=6afaf0df7bd9a" title="Real Card">
                    <img data-image="https://pix-fl.phncdn.com/still.jpg">
                </a>
                <span class="title"><a href="/view_video.php?viewkey=6afaf0df7bd9a"
                    title="Real Card">Real Card</a></span>
                <span class="views"><var>12K</var> views</span>
                <var class="duration">9:25</var>
            </li>
        </ul>"""
        val videos = provider.parseVideoCards(html)
        assertEquals(1, videos.size)
        assertEquals("Real Card", videos.first().name)
        assertNotNull(videos.first().posterUrl)
    }

    @Test fun `cards outside the listing block are ignored`() {
        // The header's Hottest dropdown repeats li.pcVideoListItem on every page.
        val html = """<ul id="hottestMenuSection"><li class="pcVideoListItem">
            <a href="/view_video.php?viewkey=dropdown" title="Dropdown Card">
                <img data-image="https://pix-fl.phncdn.com/dropdown.jpg"></a>
            <span class="title"><a href="/view_video.php?viewkey=dropdown">Dropdown Card</a></span>
        </li></ul>"""
        assertEquals(emptyList<Any>(), provider.parseVideoCards(html))
    }

    private companion object {
        const val BASE = "https://www.pornhub.com"
    }
}
