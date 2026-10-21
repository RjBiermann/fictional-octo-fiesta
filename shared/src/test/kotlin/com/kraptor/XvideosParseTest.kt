package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD seam for the shared xvideos embed resolver (issue #778, spec round of
 * #708): ixiporn's shape-B pages (2021–2023 import era) base64-wrap an
 * external hub iframe — for the xvideos hub that is
 * `https://www.xvideos.com/embedframe/<id>`, whose SSR HTML carries the full
 * player config as `html5player.setVideo*('…')` calls (live capture
 * 61306335, 2026-10-21 probe: 2 × mp4 qualities + 1 × HLS). Targets deleted
 * upstream answer a 1049-byte "This video has been deleted" shell with NO
 * setVideo* calls — the contract is: emit nothing, never throw, never emit
 * best-effort dead URLs.
 */
class XvideosParseTest {

    private val liveHtml by lazy {
        javaClass.getResourceAsStream("/xvideos_embed_live.html")!!.readBytes().decodeToString()
    }

    private val deletedHtml by lazy {
        javaClass.getResourceAsStream("/xvideos_embed_deleted.html")!!.readBytes().decodeToString()
    }

    @Test fun `live embed page yields high, low and HLS`() {
        val streams = XvideosParse.streams(liveHtml)
        assertEquals(3, streams.size)
        // order: high mp4, low mp4, HLS (pinned — callers may rely on first = best)
        assertEquals("https://mp4-cdn77.xvideos-cdn.com/12ba560c-6f7c-4261-9170-d83ee2feda78/0/video_360p.mp4?secure=hiCYv79kzdeuJxKAaU6laA==,1792564231", streams[0].url)
        assertEquals("https://mp4-cdn77.xvideos-cdn.com/12ba560c-6f7c-4261-9170-d83ee2feda78/0/video_240p.mp4?secure=rqyX3HZN6bf2sDvwUPzztQ==,1792564231", streams[1].url)
        assertEquals("https://hls-cdn77.xvideos-cdn.com/GOplBkO1rW6TwDEW3TQF9A==,1792564231/12ba560c-6f7c-4261-9170-d83ee2feda78/0/hls.m3u8", streams[2].url)
        assertFalse(streams[0].isHls)
        assertFalse(streams[1].isHls)
        assertTrue(streams[2].isHls)
    }

    @Test fun `mp4 qualities parse from the stream filename`() {
        val streams = XvideosParse.streams(liveHtml)
        assertEquals(360, streams[0].quality)
        assertEquals(240, streams[1].quality)
        assertEquals(0, streams[2].quality) // HLS is adaptive — no fixed quality
    }

    @Test fun `deleted shell emits no links (no throw)`() {
        assertTrue(XvideosParse.isDeletedShell(deletedHtml))
        assertEquals(0, XvideosParse.streams(deletedHtml).size) // empty, never throw
    }

    @Test fun `live page is not a deleted shell`() {
        assertFalse(XvideosParse.isDeletedShell(liveHtml))
    }

    @Test fun `garbage or empty page yields empty list`() {
        assertEquals(0, XvideosParse.streams("").size)
        assertEquals(0, XvideosParse.streams("<html>not a player</html>").size)
    }

    @Test fun `title parses from setVideoTitle`() {
        assertEquals(
            "EXTREME CLOSE UP POV Pussy Eating until CUM on TONGUE - Real Amateurs MrPussyLicking",
            XvideosParse.title(liveHtml)
        )
        assertNull(XvideosParse.title(deletedHtml))
        assertNull(XvideosParse.title(""))
    }

    @Test fun `json-escaped urls are unescaped`() {
        val escaped = """html5player.setVideoHLS('https:\/\/hls-cdn77.xvideos-cdn.com\/hls.m3u8');"""
        val streams = XvideosParse.streams(escaped)
        assertEquals(1, streams.size)
        assertEquals("https://hls-cdn77.xvideos-cdn.com/hls.m3u8", streams[0].url)
        assertTrue(streams[0].isHls)
    }

    @Test fun `hls-only and mp4-only pages still resolve`() {
        val hlsOnly = """html5player.setVideoHLS('https://hls.example/x.m3u8');"""
        assertEquals(1, XvideosParse.streams(hlsOnly).size)
        val mp4Only = """html5player.setVideoUrlHigh('https://mp4.example/video_720p.mp4');"""
        val s = XvideosParse.streams(mp4Only)
        assertEquals(1, s.size)
        assertEquals(720, s[0].quality)
        assertFalse(s[0].isHls)
    }
}
