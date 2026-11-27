// TDD parse tests for the 123AV video-page seam (ADR-0005, S4 of #1423 / #1656).
// Fixtures are live captures from 2026-11-27, the same three pages S1 reversed:
//
//   video-page-1.html = https://123av.com/en/v/1pondo-110326_001
//   video-page-2.html = https://123av.com/en/v/heyzo-3968
//   video-page-3.html = https://123av.com/en/v/tokyo-hot-ka055
//
// The episode array is inlined as the `player(JSON.parse('…'))` Alpine payload, not a
// separate endpoint. `parseVideo` must decode that JS string (the HTML attribute carries
// `\u0022` / `\\\/` escapes) and `parseEpisode` must read the decoded S1 shape directly —
// the tests assert the two paths agree, so a future HTML-escape change cannot silently
// empty `loadLinks`.
package com.rjbiermann

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Av123VideoTest {

    private fun html(res: String): String =
        javaClass.getResourceAsStream(res)!!.reader().readText()

    private fun video(n: Int): Av123Video =
        Av123Parse.parseVideo(html("/video-page-$n.html"))!!

    // ---- identity distinctness (the pipeline's Distinct bar) ----------------------

    @Test
    fun `three video fixtures are three different identities`() {
        val videos = (1..3).map { video(it) }
        assertEquals("titles repeat", 3, videos.map { it.title }.toSet().size)
        assertEquals("posters repeat", 3, videos.map { it.poster }.toSet().size)
        assertEquals(
            "stream references repeat",
            3,
            videos.flatMap { it.episodes }.map { it.url }.toSet().size,
        )
        assertEquals("ids repeat", 3, videos.map { it.id }.toSet().size)
    }

    // ---- metadata (populated where the page exposes it) --------------------------

    @Test
    fun `first video populates the exposed metadata`() {
        val video = video(1)
        assertEquals(
            "1Pondo-110326_001 — elcome To Luxury Spa: Rinka Natsume",
            video.title,
        )
        assertEquals(
            "https://icdn.123av.me/img2/s500/c4/1pondo-110326_001/cover.jpg?6b083139",
            video.poster,
        )
        assertTrue(video.plot!!.contains("A bushy jungle of pubic hair"))
        assertEquals(listOf("Natsume Rinka"), video.actors)
        assertEquals("1Pondo", video.maker)
        assertEquals("Luxury soap", video.series)
        assertEquals("2026-11-03", video.releaseDate)
        assertEquals(2026, video.year)
        // CloudStream duration is minutes, floored from the page's 1:01:52 clock.
        assertEquals(61, video.durationMinutes)
        assertTrue("genres missing", video.tags.contains("Creampie"))
        assertTrue("genres missing", video.tags.contains("Slut"))
        assertEquals(12, video.recommendations.size)
        assertTrue(video.recommendations.all { it.url.startsWith("https://123av.com/en/v/") })
        assertEquals(12, video.recommendations.map { it.id }.toSet().size)
    }

    @Test
    fun `second video exposes its own cast maker and no fabricated series`() {
        val video = video(2)
        assertTrue(video.title.startsWith("HEYZO-3968"))
        assertTrue(video.poster!!.contains("/heyzo-3968/cover.jpg"))
        assertEquals(listOf("Sakurada Momoha"), video.actors)
        assertEquals("HEYZO", video.maker)
        assertNull("the page carries no Series row", video.series)
        assertEquals("2026-10-24", video.releaseDate)
        assertEquals(61, video.durationMinutes)
        assertTrue(video.tags.contains("Big Boobs"))
    }

    @Test
    fun `third video has no cast row and stays honestly empty`() {
        val video = video(3)
        assertTrue(video.title.startsWith("Tokyo-Hot-ka055"))
        assertTrue(video.poster!!.contains("/tokyo-hot-ka055/cover.jpg"))
        assertEquals("the page carries no Cast row", emptyList<String>(), video.actors)
        assertEquals("Tokyo Hot", video.maker)
        assertEquals("Tokyo-Hot", video.series)
        assertEquals("2020-08-08", video.releaseDate)
        assertEquals(54, video.durationMinutes)
        assertEquals(12, video.recommendations.size)
    }

    @Test
    fun `a page without a title is not a video page`() {
        assertNull(Av123Parse.parseVideo("<html><body><h1>404</h1></body></html>"))
    }

    @Test
    fun `episode fixtures match the inline player payload on all three pages`() {
        val fixtures = listOf(
            1 to "/episode-1pondo.json",
            2 to "/episode-heyzo.json",
            3 to "/episode-tokyo-hot.json",
        )
        for ((n, res) in fixtures) {
            val expected = Av123Parse.parseEpisode(html(res))
            assertTrue("$res: fixture did not parse", expected.isNotEmpty())
            assertEquals("video-page-$n episodes", expected, video(n).episodes)
        }
    }

    // ---- episode JSON (the S1 shape) ---------------------------------------------

    @Test
    fun `episode parser keeps the S1 number name and url fields`() {
        val episodes = Av123Parse.parseEpisode(html("/episode-1pondo.json"))
        assertEquals(1, episodes.size)
        assertEquals(Av123Episode(1, "1", episodes.first().url), episodes.first())
        assertTrue(episodes.first().url.startsWith("https://sadie-shop.site/e/"))
        assertTrue(episodes.first().url.contains("1pondo-110326_001"))
        assertTrue(episodes.first().url.contains("poster="))
    }

    @Test
    fun `embed origin and id are derived from the episode url not hardcoded`() {
        val url = video(1).episodes.first().url
        assertEquals("https://sadie-shop.site", Av123Parse.embedOrigin(url))
        assertEquals("D0E7W3", Av123Parse.embedId(url))
        assertNull(Av123Parse.embedOrigin("not a url"))
        assertNull(Av123Parse.embedId("https://embed.test/no-id"))
    }

    @Test
    fun `episode parser accepts the raw JS literal as well as the decoded fixture`() {
        val raw = """[{\u0022number\u0022:1,\u0022name\u0022:\u00221\u0022,\u0022url\u0022:\u0022https:\\\/\\\/embed.test\\\/e\\\/abc\u0022}]"""
        assertEquals(listOf("https://embed.test/e/abc"), Av123Parse.parseEpisode(raw).map { it.url })
    }

    @Test
    fun `episode parser preserves order and drops entries without a url`() {
        val episodes = Av123Parse.parseEpisode(
            """[{"number":2,"name":"B","url":"https://embed.test/e/b"},{"number":1,"name":"A","url":"https://embed.test/e/a"},{"number":3,"name":"","url":""}]""",
        )
        assertEquals(listOf("https://embed.test/e/b", "https://embed.test/e/a"), episodes.map { it.url })
        assertEquals(listOf(2, 1), episodes.map { it.number })
    }

    @Test
    fun `episode parser returns empty for junk instead of throwing`() {
        assertEquals(emptyList<Av123Episode>(), Av123Parse.parseEpisode(""))
        assertEquals(emptyList<Av123Episode>(), Av123Parse.parseEpisode("not json"))
        assertEquals(emptyList<Av123Episode>(), Av123Parse.parseEpisode("{}"))
    }

    // ---- /stream JSON + content-type dispatch -----------------------------------

    @Test
    fun `stream parser reads media stream and preview track`() {
        val stream = Av123Parse.parseStream(html("/stream-api-1pondo.json"))!!
        assertTrue(stream.url.endsWith("/video.m3u8"))
        assertTrue(stream.vtt!!.endsWith("/preview.vtt"))
    }

    @Test
    fun `stream parser rejects a non-ok status or a missing stream`() {
        assertNull(Av123Parse.parseStream("""{"status":"error","media":{}}"""))
        assertNull(Av123Parse.parseStream("""{"status":"ok","media":{}}"""))
        assertNull(Av123Parse.parseStream("not json"))
    }

    @Test
    fun `link type is decided by response content-type never by url suffix`() {
        // HLS aliases the media CDN serves; the URL suffix is deliberately irrelevant here.
        assertEquals(ExtractorLinkType.M3U8, Av123Parse.streamType("application/vnd.apple.mpegurl"))
        assertEquals(ExtractorLinkType.M3U8, Av123Parse.streamType("application/x-mpegURL; charset=utf-8"))
        assertEquals(ExtractorLinkType.M3U8, Av123Parse.streamType("audio/mpegurl"))
        assertEquals(ExtractorLinkType.VIDEO, Av123Parse.streamType("video/mp4"))
        assertEquals(ExtractorLinkType.VIDEO, Av123Parse.streamType("application/mp4"))
        // `text/css` is the obfuscated segment content-type S1 measured, never a stream.
        assertNull(Av123Parse.streamType("text/css"))
        assertNull(Av123Parse.streamType("text/html"))
        assertNull(Av123Parse.streamType(null))
    }

    @Test
    fun `quality comes from the master playlist resolution when present`() {
        assertEquals(720, Av123Parse.quality("""#EXT-X-STREAM-INF:BANDWIDTH=2816000,RESOLUTION=1280x720\nqc/v.m3u8"""))
        assertEquals(1080, Av123Parse.quality("#EXT-X-STREAM-INF:RESOLUTION=1920x1080"))
        assertEquals(
            com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
            Av123Parse.quality("not a playlist"),
        )
        assertNull(Av123Parse.parseStream("""{"status":"ok","media":{"stream":"x"}}"""))
    }

    // ---- field chains are the fixture's own markup -------------------------------

    @Test
    fun `info rows come from the dt label not from chip counts`() {
        val document = Jsoup.parse(html("/video-page-1.html"), Av123Parse.MAIN)
        val rows = document.select("dl.watch__info div.watch__info-row").associate {
            it.selectFirst("dt")!!.text() to it.selectFirst("dd")!!.text()
        }
        assertEquals(rows.getValue("Maker"), video(1).maker)
        assertEquals(rows.getValue("Release date"), video(1).releaseDate)
        assertTrue(rows.getValue("Genres").contains(video(1).tags.first()))
        assertFalse(video(1).tags.isEmpty())
    }
}
