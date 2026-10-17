package com.coxju

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TDD seam for `ixiporn.loadLinks` (issue #655 deep pass, 2026-10-17):
 * the site serves two video-page shapes. Shape A (self-hosted, newest
 * uploads) carries `meta[itemprop=contentUrl]` inside `div.video-player`.
 * Shape B (2021–2023 import-era hub embeds — #709) carries a base64
 * `clean-tube-player/player-x.php` iframe wrapping an external hub
 * (xvideos/redtube — hub varies per video) and NO contentUrl meta.
 *
 * The old `loadLinks` did
 * `selectFirst("meta[itemprop=contentUrl]")?.attr("content")?.trim().toString()`
 * — for shape B that emitted the literal string "null" as a stream URL
 * (fixUrl("null") -> a garbage dead link the app cannot play), instead of
 * emitting nothing. The Parse function keeps the shape-A extraction
 * verbatim and documents shape B as "no stream from this provider"
 * (the embed-extractor work belongs to #709).
 */
class IxiPornParseTest {

    private val direct =
        javaClass.getResourceAsStream("/player-direct-newest-eyes.mp4block.html")!!.readBytes().decodeToString()

    private val embed =
        javaClass.getResourceAsStream("/player-embed-redtube.block.html")!!.readBytes().decodeToString()

    // fixtures are live verbatim blocks (newest2 / video2, 2026-10-17 probe)
    @Test fun `direct block fixtures are shape A`() {
        assertEquals(true, direct.contains("itemprop=\"contentUrl\""))
        assertEquals(true, embed.contains("player-x.php"))
        assertEquals(true, embed.contains("redtube"))
        assertEquals(false, embed.contains("contentUrl"))
    }

    @Test fun `direct block yields the contentUrl`() {
        val doc = Jsoup.parse(direct)
        val block = doc.selectFirst("div.video-player")
        assertEquals(
            "https://cdn2.ixifile.xyz/5/Eyes.S01EP01T03.mp4",
            IxiPornParse.contentUrl(block)
        )
    }

    @Test fun `embed block yields null (no contentUrl meta)`() {
        val doc = Jsoup.parse(embed)
        assertNull(IxiPornParse.contentUrl(doc.selectFirst("div.video-player")))
    }

    @Test fun `blank or whitespace content attribute yields null`() {
        val doc = Jsoup.parse("<div class=\"video-player\"><meta itemprop=\"contentUrl\" content=\"   \"></div>")
        assertNull(IxiPornParse.contentUrl(doc.selectFirst("div.video-player")))
    }

    @Test fun `null block yields null`() = assertNull(IxiPornParse.contentUrl(null))

    @Test fun `html entities in the filename decode (jsoup attr semantics, #217)`() {
        val doc = Jsoup.parse("""
            <div class="video-player">
              <meta itemprop="contentUrl" content="https://ixifile.xyz/a/Brother&#039;s%20Wife.mp4">
            </div>
        """.trimIndent())
        assertEquals("https://ixifile.xyz/a/Brother's%20Wife.mp4", IxiPornParse.contentUrl(doc.selectFirst("div.video-player")))
    }
}
