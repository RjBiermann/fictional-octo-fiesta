package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1439 (video-source leg of the fleet audit): HQPorner's catalogue is served by
 * THREE player generations, but the provider only resolved one of them end-to-end.
 *
 *  - `mydaddy.cc` — covered by the shared `MyDaddyExtractor` row (prefix dispatch). Healthy.
 *  - `hqwo.cc` — older uploads; the body carries the SAME single-quoted
 *    `<a href='…/pubs/<key>/<res>.mp4'>` quality anchors `MyDaddyExtractor` parses, but no
 *    HostRegistry row answers `hqwo.cc`: `loadExtractor` prefix dispatch misses and the
 *    Levenshtein mirror fallback scores 71 (<80), so every hqwo video emitted ZERO links.
 *  - `www.flyflv.com` — a third embed with a different grammar (`<source src='…'
 *    label='720p'>`); the provider's iframe selector did not even look for it.
 *
 * Red → green at pure Parse seams (ADR-0005) with real player-body captures under
 * `issue1439/` (FINDINGS: docs/archive/findings/FINDINGS-1439.md).
 */
class PlayerSourceParseTest {

    private fun fixture(name: String) = javaClass.classLoader
        ?.getResourceAsStream("issue1439/$name")?.bufferedReader().use { it?.readText() } ?: ""

    // ── mydaddy family (mydaddy.cc + hqwo.cc share the markup) ──

    @Test fun `hqwo player body yields every single-quoted mp4 quality anchor`() {
        val streams = MyDaddyParse.streams(fixture("hqwo_player.html"))
        assertEquals(
            listOf(
                "//hqwo.cc/pubs/6b01ec31d851b/360.mp4",
                "//hqwo.cc/pubs/6b01ec31d851b/720.mp4",
            ),
            streams.map { it.url },
        )
        assertEquals(listOf("360", "720"), streams.map { it.quality })
    }

    @Test fun `mydaddy body keeps the 2160 quality on 4K uploads`() {
        val body = """
            <a href='//cdn.example/pubs/abc/360.mp4' style='color:#ddd'>360p</a>
            <a href='//cdn.example/pubs/abc/720.mp4'>720p</a>
            <a href='//cdn.example/pubs/abc/1080.mp4'>1080p</a>
            <a href='//cdn.example/pubs/abc/2160.mp4'>2160p</a>
        """.trimIndent()
        assertEquals(
            listOf("360", "720", "1080", "2160"),
            MyDaddyParse.streams(body).map { it.quality },
        )
    }

    @Test fun `a body without quality anchors yields nothing`() {
        assertTrue(MyDaddyParse.streams("<html><body><a href=\"#\">no player</a></body></html>").isEmpty())
        assertTrue(MyDaddyParse.streams("").isEmpty())
    }

    @Test fun `a repeated anchor is emitted once`() {
        val body = "<a href='//cdn.example/pubs/abc/720.mp4'>720p</a>" +
            "<a href='//cdn.example/pubs/abc/720.mp4'>720p</a>"
        assertEquals(1, MyDaddyParse.streams(body).size)
    }

    // ── flyflv (third embed generation) ──

    @Test fun `flyflv player body yields both source tags with their labels`() {
        val streams = FlyFlvParse.streams(fixture("flyflv_player.html"))
        assertEquals(
            listOf(
                "//video.flyflv.com/key=VBQVhhLRDtkwaBtf67LA+A,end=1795291677,ip=172.203.239.163" +
                    "/buffer=1600K/hd/5135.mp4",
                "//video.flyflv.com/key=CabCfUQ1Cl0gFTLCw8qlzQ,end=1795291677,ip=172.203.239.163" +
                    "/speed=89600/buffer=875K/sd/5135.mp4",
            ),
            streams.map { it.url },
        )
        assertEquals(listOf("720p", "360p"), streams.map { it.quality })
    }

    @Test fun `flyflv attribute order and html entities are tolerated`() {
        val body = "<source label='1080p' type='video/mp4' src='//video.flyflv.com/a/1080.mp4?x=1&amp;y=2'>"
        val streams = FlyFlvParse.streams(body)
        assertEquals(
            listOf("//video.flyflv.com/a/1080.mp4?x=1&y=2"),
            streams.map { it.url },
        )
        assertEquals(listOf("1080p"), streams.map { it.quality })
    }

    @Test fun `flyflv falls back to the filename when the label is missing`() {
        assertEquals(
            listOf("480"),
            FlyFlvParse.streams("<source src='//video.flyflv.com/b/480.mp4'>").map { it.quality },
        )
    }

    @Test fun `flyflv body without source tags yields nothing`() {
        assertTrue(FlyFlvParse.streams("<html><body>no player</body></html>").isEmpty())
    }

    @Test fun `flyflv player body carries the video's own poster`() {
        // the existing PlayerPosterParse body contract (`poster="…"`) already covers the
        // third embed — only the iframe selector had to learn the host (#1439)
        assertEquals(
            "https://static.flyflv.com/movies/005/315/5135/thumbs/12.jpg",
            PlayerPosterParse.fromBody(fixture("flyflv_player.html")),
        )
    }
}
