package com.byayzen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0005: issue #1463 — the 2026-11-25 stream drift.
 *
 * `script#watch-config` stopped carrying inline `playerSources` (the shape the provider
 * shipped in v13). It now carries a one-shot `playback{endpoint,ticket}` handshake; the
 * sources arrive from `POST <endpoint>` with the form body `ticket=…`, page-relative:
 * `/media/video/<id>/720p?expires=…&signature=…` → 302 → R2 → 206 `video/mp4`.
 *
 * Fixtures are live saves from this run: `javtiful-watch-20261125.html` (the watch page)
 * and `javtiful-playback-116259.json` (its POST response). `javtiful-watch.html` (the
 * 2026-10-09 save) stays the pre-drift negative case — it carries inline `playerSources`
 * and no handshake.
 */
class JavtifulPlaybackTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString()

    private val liveWatch by lazy { fixture("javtiful-watch-20261125.html") }
    private val livePlayback by lazy { fixture("javtiful-playback-116259.json") }
    private val oldWatch by lazy { fixture("javtiful-watch.html") }

    @Test
    fun `handshake sources are absolute and carry the site quality`() {
        val sources = playbackSources(liveWatch, livePlayback)
        assertEquals(1, sources.size)
        val source = sources.single()
        assertTrue(source.src.startsWith("https://javtiful.com/media/video/116259/720p?"))
        assertEquals("video/mp4", source.type)
        assertEquals(720, source.size)
    }

    @Test
    fun `pre-drift save with no handshake yields nothing`() {
        assertTrue(oldWatch.contains("playerSources"))
        assertFalse(liveWatch.contains("playerSources"))
        assertEquals(emptyList<Any>(), playbackSources(oldWatch, livePlayback))
    }

    @Test
    fun `failed or junk playback response yields nothing`() {
        assertEquals(emptyList<Any>(), playbackSources(liveWatch, null))
        assertEquals(emptyList<Any>(), playbackSources(liveWatch, ""))
        assertEquals(emptyList<Any>(), playbackSources(liveWatch, """{"success":false}"""))
        assertEquals(emptyList<Any>(), playbackSources(liveWatch, "not json"))
        assertEquals(emptyList<Any>(), playbackSources(null, livePlayback))
    }

    @Test
    fun `stream path absolutisation keeps absolute urls and drops blanks`() {
        assertEquals(
            "https://javtiful.com/media/video/1/720p?x=1",
            absoluteStream("/media/video/1/720p?x=1")
        )
        assertEquals(
            "https://fast-stream.jav.si/p/abc",
            absoluteStream("https://fast-stream.jav.si/p/abc")
        )
        assertNull(absoluteStream(null))
        assertNull(absoluteStream("  "))
    }

    /** The handshake's `/media/video/<id>/720p?…` path is extensionless: the MIME decides. */
    @Test
    fun `handshake mp4 is not mistaken for HLS`() {
        val src = playbackSources(liveWatch, livePlayback).single().src
        assertFalse(isHls(src, "video/mp4"))
        assertTrue(isHls(src, "application/x-mpegurl"))
    }
}
