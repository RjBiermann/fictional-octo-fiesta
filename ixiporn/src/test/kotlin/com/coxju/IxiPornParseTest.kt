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
 *
 * #708 (provider-side half, 2026-10-20 probe): shape B now resolves through the
 * pure seam `IxiPornParse.hubEmbedUrl` — the player-x.php b64 payload
 * (`post_id=…&type=iframe&tag=<url-encoded html>`) decodes to the hub iframe
 * (xvideos/embedframe or embed.redtube.com — hub varies per video; live probe
 * 27/30 search hits xvideos, redtube fixture captured 2026-10-17). `loadLinks`
 * chains the extracted hub URL through the framework's `loadExtractor` dispatch
 * onto the shared host adapters (registerHostExtractors) — ixiporn never resolves
 * hub streams itself. Undecodable/dead payload -> null -> emits nothing (the
 * #655 dead-target contract).
 */
class IxiPornParseTest {

    private val direct =
        javaClass.getResourceAsStream("/player-direct-newest-eyes.mp4block.html")!!.readBytes().decodeToString()

    private val embed =
        javaClass.getResourceAsStream("/player-embed-redtube.block.html")!!.readBytes().decodeToString()

    private val embedXvideos =
        javaClass.getResourceAsStream("/player-embed-xvideos.block.html")!!.readBytes().decodeToString()

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

    // #708: player-x.php b64 chain — pure Parse seam

    @Test fun `xvideos embed block yields the hub iframe src (live verbatim, 2026-10-20)`() {
        val doc = Jsoup.parse(embedXvideos)
        assertEquals(
            "https://www.xvideos.com/embedframe/14054221",
            IxiPornParse.hubEmbedUrl(doc.selectFirst("div.video-player"))
        )
    }

    @Test fun `redtube embed block yields the hub iframe src (live verbatim, 2026-10-17)`() {
        val doc = Jsoup.parse(embed)
        assertEquals(
            "https://embed.redtube.com/?id=21152821",
            IxiPornParse.hubEmbedUrl(doc.selectFirst("div.video-player"))
        )
    }

    @Test fun `shape-A direct block yields null hub (payload wraps video tag, not iframe)`() {
        val doc = Jsoup.parse(direct)
        assertNull(IxiPornParse.hubEmbedUrl(doc.selectFirst("div.video-player")))
    }

    @Test fun `hubEmbedUrl null block yields null`() = assertNull(IxiPornParse.hubEmbedUrl(null))

    @Test fun `hubEmbedUrl undecodable b64 yields null (dead target emits nothing)`() {
        val doc = Jsoup.parse("""
            <div class="video-player">
              <iframe src="https://ixiporn.live/wp-content/plugins/clean-tube-player/public/player-x.php?q=!!!not-base64!!"></iframe>
            </div>
        """.trimIndent())
        assertNull(IxiPornParse.hubEmbedUrl(doc.selectFirst("div.video-player")))
    }

    @Test fun `hubEmbedUrl payload without tag param yields null`() {
        val b64 = java.util.Base64.getEncoder().encodeToString("post_id=1&type=iframe".toByteArray())
        val doc = Jsoup.parse("""
            <div class="video-player">
              <iframe src="https://ixiporn.live/wp-content/plugins/clean-tube-player/public/player-x.php?q=$b64"></iframe>
            </div>
        """.trimIndent())
        assertNull(IxiPornParse.hubEmbedUrl(doc.selectFirst("div.video-player")))
    }

    @Test fun `hubEmbedUrl decoded tag without iframe yields null`() {
        val tag = java.net.URLEncoder.encode("<video></video>", "UTF-8")
        val b64 = java.util.Base64.getEncoder().encodeToString("post_id=1&type=iframe&tag=$tag".toByteArray())
        val doc = Jsoup.parse("""
            <div class="video-player">
              <iframe src="https://ixiporn.live/wp-content/plugins/clean-tube-player/public/player-x.php?q=$b64"></iframe>
            </div>
        """.trimIndent())
        assertNull(IxiPornParse.hubEmbedUrl(doc.selectFirst("div.video-player")))
    }

    @Test fun `hubEmbedUrl non-absolute hub src yields null (no junk URLs)`() {
        val tag = java.net.URLEncoder.encode("<iframe src=\"/embedframe/1\"></iframe>", "UTF-8")
        val b64 = java.util.Base64.getEncoder().encodeToString("post_id=1&type=iframe&tag=$tag".toByteArray())
        val doc = Jsoup.parse("""
            <div class="video-player">
              <iframe src="https://ixiporn.live/wp-content/plugins/clean-tube-player/public/player-x.php?q=$b64"></iframe>
            </div>
        """.trimIndent())
        assertNull(IxiPornParse.hubEmbedUrl(doc.selectFirst("div.video-player")))
    }

    @Test fun `hubEmbedUrl player-x iframe missing q param yields null`() {
        val doc = Jsoup.parse("""
            <div class="video-player">
              <iframe src="https://ixiporn.live/wp-content/plugins/clean-tube-player/public/player-x.php"></iframe>
            </div>
        """.trimIndent())
        assertNull(IxiPornParse.hubEmbedUrl(doc.selectFirst("div.video-player")))
    }

    @Test fun `html entities in the filename decode (jsoup attr semantics, #217)`() {
        val doc = Jsoup.parse("""
            <div class="video-player">
              <meta itemprop="contentUrl" content="https://ixifile.xyz/a/Brother&#039;s%20Wife.mp4">
            </div>
        """.trimIndent())
        assertEquals("https://ixifile.xyz/a/Brother's%20Wife.mp4", IxiPornParse.contentUrl(doc.selectFirst("div.video-player")))
    }
}
