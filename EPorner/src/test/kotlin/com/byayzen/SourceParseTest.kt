package com.byayzen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD seam for the /xhr/video source config (issues #1449, video-sources audit S11).
 *
 * Fixtures are verbatim xhr bodies captured 2026-11-23 from the live player:
 *  - `xhr-video-18965595-fallback.json` — hls carries both `src` (AV1 ladder) and
 *    `srcFallback` (H.264 ladder); mp4 ladder 240p..1080p.
 *  - `xhr-video-19055511-src-only.json` — hls carries `src` only, and that ladder is
 *    the plain H.264 one; mp4 ladder 240p..2160p.
 *
 * The pre-#1449 code text-regexed `"labelShort" … "src"` and hardcoded the HLS link to
 * 1080p: it dropped the whole HLS leg on the second fixture and mislabelled the first
 * (a 720p-only video advertised 1080p). These tests pin the replacement.
 */
class SourceParseTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString()

    private val fallbackFixture = fixture("xhr-video-18965595-fallback.json")
    private val srcOnlyFixture = fixture("xhr-video-19055511-src-only.json")

    // --- mp4 ladder ---

    @Test fun `mp4 ladder labels come from labelShort`() {
        val sources = EPornerParse.mp4Sources(fallbackFixture)
        assertEquals(listOf("1080p", "720p", "480p", "360p", "240p"), sources.map { it.label })
        assertTrue(sources.all { it.url.startsWith("https://") })
        assertTrue(sources.first().url.endsWith("-1080p.mp4"))
    }

    @Test fun `mp4 ladder keeps 4K and 2K rungs`() {
        val labels = EPornerParse.mp4Sources(srcOnlyFixture).map { it.label }
        assertEquals(listOf("2160p", "1440p", "1080p", "720p", "480p", "360p", "240p"), labels)
    }

    @Test fun `dload endpoints are not playable sources`() {
        val config = """{"sources":{"mp4":{
            "1080p":{"labelShort":"1080p","src":"https://cdn.example.com/dload/1-1080p.mp4"},
            "720p":{"labelShort":"720p","src":"https://cdn.example.com/v4/1-720p.mp4"}}}}"""
        assertEquals(listOf("720p"), EPornerParse.mp4Sources(config).map { it.label })
    }

    @Test fun `malformed or empty config yields no sources`() {
        assertTrue(EPornerParse.mp4Sources("<html>not json</html>").isEmpty())
        assertTrue(EPornerParse.mp4Sources("""{"available":false}""").isEmpty())
        assertNull(EPornerParse.hlsSource("<html>not json</html>"))
    }

    // --- HLS ladder selection ---

    @Test fun `hls prefers the H264 srcFallback over the AV1 src`() {
        val hls = EPornerParse.hlsSource(fallbackFixture)!!
        assertFalse(hls.url.contains("-av1"))
        assertTrue(hls.url.contains(",240p,360p,480p,720p,1080p,.mp4.urlset"))
        assertEquals("1080p", hls.label)
    }

    @Test fun `hls falls back to a plain src when srcFallback is absent`() {
        // Live shape (2026-11-23): 3 of 8 sampled videos expose `src` only — the old
        // srcFallback-only regex emitted no HLS link for them at all.
        val hls = EPornerParse.hlsSource(srcOnlyFixture)!!
        assertTrue(hls.url.contains(",2160p,.mp4.urlset"))
        assertEquals("2160p", hls.label)
    }

    @Test fun `hls ignores an av1-only ladder`() {
        val config = """{"sources":{"hls":{"auto":{
            "src":"https://dash.example.com/hls/1-,240p-av1,720p-av1,.mp4.urlset/master.m3u8",
            "type":"application/x-mpegURL"}}}}"""
        assertNull(EPornerParse.hlsSource(config))
    }

    @Test fun `hls quality is the top rung of the encoded ladder`() {
        assertEquals(2160, EPornerParse.hlsQuality(
            "https://dash-s12-s50-fr-cdn.eporner.com/hls/19055511-,240p,360p,480p,720p,1080p,1440p,2160p,.mp4.urlset/master.m3u8?hash=x"
        ))
        assertEquals(720, EPornerParse.hlsQuality(
            "https://dash-s4-c50-ca-cdn.eporner.com/hls/v4/12670583-,240p,360p,480p,720p,.mp4.urlset/master.m3u8"
        ))
        // An AV1 token is not a quality rung of its own.
        assertEquals(1080, EPornerParse.hlsQuality(
            "https://dash.example.com/hls/v4/18965595-,240p-av1,1080p-av1,.mp4.urlset/master.m3u8"
        ))
        assertNull(EPornerParse.hlsQuality("https://dash.example.com/live/master.m3u8"))
    }
}
