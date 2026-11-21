package com.rjbiermann

import com.kraptor.DistinctBar
import com.lagradost.cloudstream3.utils.Qualities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Watch-page fixtures are the verbatim blocks the load/loadLinks Parse seams read (FINDINGS
 * §Video pages):
 *  - `video-page-1.html` — `https://www.pornhub.com/view_video.php?viewkey=6a333a768c26c`:
 *    head `og:*`/`name=description` metas, the JSON-LD `VideoObject`, `div.title-container`,
 *    `div.pornstarsWrapper` (2 models), `div.categoriesWrapper`, `div.tagsWrapper`, the
 *    `<ul id="relatedVideosListing">` opening + its first three cards, and the full
 *    `var flashvars_<id>` blob (3 HLS legs + the remote `/video/get_media` leg).
 *  - `video-page-2.html` — `...?viewkey=6ae50ab253d9f`: same blocks, **no** pornstarsWrapper
 *    (the uploader credits no models) and 3 HLS legs.
 *  - `video-page-walled.html` — `...?viewkey=64894d8f6af1f`, a premium video: 200 with
 *    `og:*` metas and the premium lock marker, but **no** flashvars blob (the paywall wall).
 *  - `get-media-response.json` — the verbatim body of that video's `/video/get_media?s=…`
 *    fallback (4 progressive mp4 legs), captured with the watch page's session cookies.
 */
class PornHubLoadParseTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.reader().readText()

    private fun details1() = Parse.videoDetails(fixture("video-page-1.html"))
    private fun details2() = Parse.videoDetails(fixture("video-page-2.html"))

    @Test fun `watch page 1 exposes the full metadata inventory`() {
        val details = details1()
        assertNotNull("video-page-1 must parse", details)
        details!!
        assertTrue("title comes from h1.title span.inlineFree",
            details.title.startsWith("Picking Up My Step Sister From Starbucks"))
        assertNotNull("poster (og:image)", details.poster)
        assertTrue(details.poster!!.startsWith("https://pix-egi.phncdn.com/"))
        assertNotNull("plot (og:description)", details.plot)
        assertTrue("the site's description text", details.plot!!.contains("Pornhub.com"))
        assertEquals("JSON-LD PT00H16M50S floors to 16 minutes (repo convention)",
            16, details.durationMinutes)
        assertEquals("JSON-LD uploadDate year", 2026, details.year)
        assertTrue("categories feed the tag list: 18-25", details.tags.contains("18-25"))
        assertTrue("tags feed the tag list: how to start sex",
            details.tags.contains("how to start sex"))
        assertEquals("actors from div.pornstarsWrapper a.pstar-list-btn",
            listOf("Indica Marie", "Teddy Tarantino"), details.actors)
        assertTrue("related videos from ul#relatedVideosListing", details.related.size >= 3)
    }

    @Test fun `watch page 2 parses without performer credits`() {
        val details = details2()
        assertNotNull("video-page-2 must parse", details)
        details!!
        assertEquals("Holly Garner: Hands-on Oiling", details.title)
        assertEquals(9, details.durationMinutes)
        assertEquals(2026, details.year)
        assertEquals("a page without pornstarsWrapper yields no actors",
            emptyList<String>(), details.actors)
        assertTrue("tags/categories still populate", details.tags.isNotEmpty())
        assertTrue("related videos still populate", details.related.size >= 3)
    }

    @Test fun `two different videos never share identity fields`() {
        val pages = listOf(details1()!!, details2()!!)
        DistinctBar.assertDistinctVideos(
            pages.map { DistinctBar.VideoIdentity(it.title, it.poster, it.related.firstOrNull()?.url) }
        )
        assertNotNull("page 1 plot", pages[0].plot)
        assertNotNull("page 2 plot", pages[1].plot)
        assertFalse("plots are per-video, not a constant",
            pages[0].plot == pages[1].plot)
    }

    @Test fun `related cards point at video pages and carry distinct ids`() {
        val related = details1()!!.related
        assertTrue(related.all { it.url.startsWith("$BASE/view_video.php?viewkey=") })
        assertTrue(related.all { it.title.isNotBlank() })
        assertTrue(related.all { it.poster != null })
        assertEquals("related cards are read from the scoped listing only",
            related.size, related.map { it.url }.distinct().size)
    }

    @Test fun `a premium-walled page returns null`() {
        val walled = fixture("video-page-walled.html")
        assertNull("the app must drop a walled card", Parse.videoDetails(walled))
        assertNull("a walled page carries no flashvars blob", Parse.flashvars(walled))
        assertTrue("the fixture is the premium lock shape",
            walled.contains("premiumLockedPartners"))
    }

    @Test fun `hls streams come from the flashvars mediaDefinitions`() {
        val streams = Parse.hlsStreams(Parse.flashvars(fixture("video-page-1.html"))!!)
        assertEquals("one link per HLS quality", listOf(240, 480, 720), streams.map { it.height })
        assertTrue("every HLS master", streams.all { it.url.contains("/master.m3u8") })
        assertTrue("absolute http(s) urls", streams.all { it.url.startsWith("https://") })
        assertEquals("no duplicate sources", streams.size, streams.map { it.url }.distinct().size)
    }

    @Test fun `page 2 hls streams differ from page 1`() {
        val first = Parse.hlsStreams(Parse.flashvars(fixture("video-page-1.html"))!!)
        val second = Parse.hlsStreams(Parse.flashvars(fixture("video-page-2.html"))!!)
        assertTrue("sources never repeat across videos",
            first.map { it.url }.intersect(second.map { it.url }.toSet()).isEmpty())
        assertEquals(listOf(240, 480, 720), second.map { it.height })
    }

    @Test fun `get_media fallback url is carried by the remote leg`() {
        val fallback = Parse.getMediaUrl(Parse.flashvars(fixture("video-page-1.html"))!!)
        assertNotNull("the remote mp4 leg is the documented fallback", fallback)
        assertTrue(fallback!!.startsWith("$BASE/video/get_media?s="))
    }

    @Test fun `a flashvars blob without a remote leg has no fallback`() {
        val noRemote = """{"mediaDefinitions":[
            {"format":"hls","height":720,"videoUrl":"https://ev.phncdn.com/x/master.m3u8"}
        ]}"""
        assertNull(Parse.getMediaUrl(noRemote))
        assertEquals(1, Parse.hlsStreams(noRemote).size)
    }

    @Test fun `get_media response parses to progressive mp4 streams`() {
        val streams = Parse.getMediaStreams(fixture("get-media-response.json"))
        assertEquals("four mp4 legs", listOf(240, 480, 720, 1080), streams.map { it.height })
        assertTrue("progressively served mp4", streams.all { it.url.contains(".mp4?") })
        assertEquals("no duplicate sources", streams.size, streams.map { it.url }.distinct().size)
    }

    @Test fun `an empty or junk get_media body degrades to no links`() {
        assertEquals(emptyList<Parse.Stream>(), Parse.getMediaStreams("[]"))
        assertEquals(emptyList<Parse.Stream>(), Parse.getMediaStreams("<html>nope</html>"))
        assertEquals(emptyList<Parse.Stream>(), Parse.getMediaStreams(""))
    }

    @Test fun `missing flashvars degrades to no streams`() {
        assertEquals(emptyList<Parse.Stream>(), Parse.hlsStreams(""))
        assertEquals(emptyList<Parse.Stream>(), Parse.hlsStreams("{}"))
        assertNull(Parse.getMediaUrl("{}"))
    }

    @Test fun `entry heights map to cloudstream qualities`() {
        assertEquals(720, Parse.qualityOf(720))
        assertEquals(1080, Parse.qualityOf(1080))
        assertEquals(2160, Parse.qualityOf(2160))
        assertEquals(Qualities.Unknown.value, Parse.qualityOf(0))
    }

    @Test fun `a dead cdn signing shape yields no hls legs so the fallback runs`() {
        val streams = Parse.hlsStreams(Parse.flashvars(fixture("video-page-1.html"))!!)
        assertEquals(
            "style-B (410) answers nothing → /video/get_media is the only path",
            emptyList<Parse.Stream>(),
            Parse.playableHls(streams) { false },
        )
        assertEquals(
            "only answered legs reach the player",
            listOf(720),
            Parse.playableHls(streams) { it.height == 720 }.map { it.height },
        )
        assertEquals(
            "style-A keeps every leg",
            listOf(240, 480, 720),
            Parse.playableHls(streams) { true }.map { it.height },
        )
    }

    @Test fun `link labels carry the height and degrade without one`() {
        assertEquals("PornHub 720p", Parse.linkName("PornHub", 720))
        assertEquals("a height-less leg keeps the bare source name", "PornHub", Parse.linkName("PornHub", 0))
    }

    private companion object {
        const val BASE = "https://www.pornhub.com"
    }
}
