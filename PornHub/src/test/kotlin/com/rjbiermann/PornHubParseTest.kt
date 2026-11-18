package com.rjbiermann

import com.kraptor.DistinctBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are the verbatim `<ul id="videoCategory">` listing block of
 * https://www.pornhub.com/video (page 1) and https://www.pornhub.com/video?page=2 (page 2) —
 * the only part of the page the card Parse seam reads. See FINDINGS §Homepage.
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
