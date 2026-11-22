package com.rjbiermann

import com.kraptor.DistinctBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search fixtures are the verbatim `<link rel="canonical">` / `<link rel="next|prev">` head lines
 * of the response followed by the verbatim `<ul id="videoSearchResult">` result block (or, for the
 * last page, the verbatim `<div class="pagination3">` block) — the only markup the search Parse
 * seams read. `search-page-1.html` / `search-page-2.html` are the **reporter's own query**
 * (`?search=george+uhl`, #1494), so the pagination seam is asserted on the request that failed
 * for them; `search-last-page.html` / `search-no-results.html` stay on the broad `teen` probe
 * (last reachable page + 404 body). See FINDINGS §§Search, Pagination (search).
 */
class PornHubSearchParseTest {

    private val provider = PornHub()

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.reader().readText()

    private fun search1() = provider.parseVideoCards(fixture("search-page-1.html"), search = true)
    private fun search2() = provider.parseVideoCards(fixture("search-page-2.html"), search = true)

    @Test fun `search page 1 parses a non-empty card list`() {
        val videos = search1()
        // The fixture holds 31 result cards (FINDINGS §Search); a threshold, not an exact count,
        // so a fixture refresh does not need a test edit.
        assertTrue("page 1 should parse the result cards, got ${videos.size}", videos.size >= 20)
    }

    @Test fun `search page 2 parses a non-empty card list`() {
        val videos = search2()
        assertTrue("page 2 should parse the result cards, got ${videos.size}", videos.size >= 20)
    }

    @Test fun `search page 2 does not repeat page 1`() {
        val overlap = search1().map { it.url }.intersect(search2().map { it.url }.toSet())
        assertTrue("page 2 must be fresh results, but ${overlap.size} repeat", overlap.isEmpty())
    }

    @Test fun `the fixtures are the reporter's own query`() {
        // #1494: the fixtures used to carry the `teen` probe; they now carry the query the
        // reporter searched, so every pagination assertion below runs on their request.
        for (name in listOf("search-page-1.html", "search-page-2.html")) {
            val html = fixture(name)
            assertTrue("$name must be the reporter's query",
                html.contains("search?search=george+uhl"))
            assertTrue("$name must carry the site's own rel=next", html.contains("<link rel=\"next\""))
            assertTrue("$name must not hold the old teen probe",
                !html.contains("search=teen"))
        }
    }

    @Test fun `the reporter query paginates - both pages announce a next page and share no card`() {
        // Acceptance evidence for #1494: the seam DOES paginate the reporter's query. Page 2 is
        // new results (0 shared viewkeys) and both pages carry the site's own rel=next.
        assertTrue("page 1 announces page 2", Parse.hasNextPage(fixture("search-page-1.html")))
        assertTrue("page 2 announces page 3", Parse.hasNextPage(fixture("search-page-2.html")))
        val page2 = search2().map { it.url }.toSet()
        val shared = search1().map { it.url }.filter { it in page2 }
        assertEquals("page 2 must share no card with page 1", emptyList<String>(), shared)
    }

    @Test fun `every reporter-query card carries duration and views`() {
        // The card metadata the load seam depends on is present on the reporter-query fixtures
        // too (not just the old probe), so a page-2 card is as complete as a page-1 card.
        for ((label, cards) in listOf("page 1" to Parse.searchCards(fixture("search-page-1.html")),
            "page 2" to Parse.searchCards(fixture("search-page-2.html")))) {
            assertTrue("$label: no cards parsed", cards.isNotEmpty())
            val noDuration = cards.filter { it.durationMinutes == null }
            val noViews = cards.filter { it.views == null }
            assertTrue("$label: ${noDuration.size} card(s) without duration",
                noDuration.isEmpty())
            assertTrue("$label: ${noViews.size} card(s) without views", noViews.isEmpty())
        }
    }

    @Test fun `distinct bar holds on both search pages`() {
        for ((label, videos) in listOf("page 1" to search1(), "page 2" to search2())) {
            DistinctBar.assertDistinctVideos(
                videos.map { DistinctBar.VideoIdentity(it.name, it.posterUrl, it.url) }
            )
            assertTrue("$label: missing poster", videos.all { it.posterUrl != null })
            assertTrue("$label: blank title", videos.all { it.name.isNotBlank() })
        }
    }

    @Test fun `search cards use the same card shape as the load page`() {
        // search ↔ load agreement: a result links to the very `view_video.php?viewkey=` URL the
        // load story (S3) receives, so one URL form is parsed here and loaded there.
        val card = Parse.searchCards(fixture("search-page-1.html")).firstOrNull()
        assertTrue("the search fixture must carry a card", card != null)
        val url = card!!.url
        assertTrue("unexpected result url: $url",
            url.startsWith("$BASE/view_video.php?viewkey="))
        assertEquals("the emitted result must carry the card's own url",
            url, search1().first().url)
        assertTrue("search card metadata: duration", card.durationMinutes != null)
        assertTrue("search card metadata: views", card.views != null)
    }

    @Test fun `every search poster carries the site Referer`() {
        // Same hotlink matrix as the listing: a pix-cdn77 still answers 403 without a
        // Referer (FINDINGS §Listing), and search came back 100% that shard for the reporter.
        val videos = search1()
        assertTrue("search results must carry the poster header",
            videos.all { it.posterHeaders == mapOf("Referer" to "$BASE/") })
        assertEquals(mapOf("Referer" to "$BASE/"), Parse.posterHeaders)
    }

    @Test fun `the pix-cdn77 search still keeps its signature verbatim`() {
        val raw = Regex("""https://pix-cdn77\.phncdn\.com/[^"]+\?hash=[^"&]+&validto=\d+""")
            .find(fixture("search-page-1.html"))!!.value
        val emitted = search1().firstOrNull { it.posterUrl == raw }
        assertNotNull("the pix-cdn77 result must survive parse", emitted)
        assertEquals("the site Referer rides along", mapOf("Referer" to "$BASE/"),
            emitted!!.posterHeaders)
    }

    @Test fun `hasNext follows the site's own rel=next link`() {
        assertTrue("page 1 announces page 2", Parse.hasNextPage(fixture("search-page-1.html")))
        assertTrue("page 2 announces page 3", Parse.hasNextPage(fixture("search-page-2.html")))
    }

    @Test fun `hasNext is false on the last reachable page`() {
        // Page 100 is the reachable last page for "teen": no `<link rel="next">`, and the
        // pagination block renders `li.page_next disabled` with an empty href (FINDINGS).
        val html = fixture("search-last-page.html")
        assertFalse("last page must not claim a next page", Parse.hasNextPage(html))
        assertTrue("the fixture is the disabled pagination shape",
            html.contains("page_next disabled"))
    }

    @Test fun `hasNext falls back to the enabled Next anchor`() {
        // The `<link rel="next">` is the primary signal; the pagination anchor is the backstop
        // for responses that omit the head link.
        val anchor = """<div class="pagination3"><ul><li class="page_next">
            <a href="/video/search?search=teen&amp;page=2" class="orangeButton">Next</a>
            </li></ul></div>"""
        assertTrue(Parse.hasNextPage(anchor))
        val disabled = """<div class="pagination3"><ul><li class="page_next disabled">
            <a href="" class="orangeButton">Next</a></li></ul></div>"""
        assertFalse(Parse.hasNextPage(disabled))
    }

    @Test fun `a no-result query yields no cards and no next page`() {
        // The site answers a query with no matches with HTTP 404 + `div.noResultsWrapper`
        // (and still renders the header dropdown cards, which the search scope drops).
        val html = fixture("search-no-results.html")
        assertEquals(emptyList<Any>(), provider.parseVideoCards(html, search = true))
        assertFalse(Parse.hasNextPage(html))
    }

    @Test fun `search cards outside the result block are ignored`() {
        val html = """<ul id="hottestMenuSection"><li class="pcVideoListItem">
                <a href="/view_video.php?viewkey=dropdown" title="Dropdown Card">
                    <img data-image="https://pix-fl.phncdn.com/dropdown.jpg"></a>
            </li></ul>
            <ul id="bottomVideos"><li class="pcVideoListItem">
                <a href="/view_video.php?viewkey=promo" title="Promo Card">
                    <img data-image="https://pix-fl.phncdn.com/promo.jpg"></a>
            </li></ul>"""
        assertEquals(emptyList<Any>(), provider.parseVideoCards(html, search = true))
    }

    @Test fun `search urls encode the query the way the site canonicalises it`() {
        assertEquals("$BASE/video/search?search=teen", Parse.searchUrl("teen", 1))
        assertEquals("$BASE/video/search?search=teen&page=2", Parse.searchUrl("teen", 2))
        // A space is `+` (the canonical form); `%20` 301-redirects to it (FINDINGS §Search).
        assertEquals("$BASE/video/search?search=big+tits", Parse.searchUrl("  big tits ", 1))
        assertEquals("$BASE/video/search?search=big+tits&page=3",
            Parse.searchUrl("big tits", 3))
        assertEquals("$BASE/video/search?search=a%26b", Parse.searchUrl("a&b", 1))
        // The reporter's own query (#1494): a space is `+` on page 1 and page 2 alike.
        assertEquals("$BASE/video/search?search=george+uhl", Parse.searchUrl("george uhl", 1))
        assertEquals("$BASE/video/search?search=george+uhl&page=2",
            Parse.searchUrl("george uhl", 2))
        assertTrue("the reporter query must never go out as %20",
            !Parse.searchUrl("george uhl", 2).contains("%20"))
    }

    private companion object {
        const val BASE = "https://www.pornhub.com"
    }
}
