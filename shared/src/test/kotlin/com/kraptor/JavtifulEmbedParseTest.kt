package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Red→green for issue #1444 (FINDINGS-1444): Javseen's popular far-end videos carry a
 * single `javtiful.com/embed/<id>` source and no HostRegistry row answered it, so
 * loadExtractor dropped the video entirely. The embed page is a watch-config JSON block
 * whose `playback` endpoint returns the signed mp4 sources. Fixtures are live captures
 * (2026-11-22, /290353/ → embed/115265 → POST playback).
 */
class JavtifulEmbedParseTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString()

    @Test
    fun `watch config yields the playback endpoint and ticket`() {
        val playback = JavtifulEmbedParse.playback(
            fixture("javtiful_embed_watch_config.html")
        )
        assertEquals("/video/115265/playback", playback?.endpoint)
        assertTrue(playback!!.ticket.startsWith("p1.115265."))
    }

    @Test
    fun `playback response yields the 720p mp4 source`() {
        val sources = JavtifulEmbedParse.sources(fixture("javtiful_playback.json"))
        assertEquals(1, sources.size)
        assertEquals("video/mp4", sources.single().type)
        assertEquals(720, sources.single().size)
        assertTrue(sources.single().src.startsWith("/media/video/115265/720p?"))
    }

    @Test
    fun `page without a watch config yields null`() {
        assertNull(JavtifulEmbedParse.playback("<html><body>nothing</body></html>"))
        assertNull(JavtifulEmbedParse.playback(null))
    }

    @Test
    fun `playback without sources yields an empty list`() {
        assertEquals(emptyList<JavtifulEmbedParse.Source>(),
            JavtifulEmbedParse.sources("""{"success":false,"error":"expired"}"""))
        assertEquals(emptyList<JavtifulEmbedParse.Source>(), JavtifulEmbedParse.sources(null))
        assertEquals(emptyList<JavtifulEmbedParse.Source>(), JavtifulEmbedParse.sources("not json"))
    }
}

/** The condition this run fixed: the embed must reach the shared table at all. */
class JavtifulEmbedRegistryTest {
    @Test
    fun `exactly one registry row answers the javtiful embed prefix`() {
        val target = "https://javtiful.com/embed/115265"
        val matches = sharedHostRegistry().filter { target.startsWith(it.mainUrl) }
        org.junit.Assert.assertEquals(1, matches.size)
        org.junit.Assert.assertTrue(matches.single() is JavtifulEmbed)
    }
}
