package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD for issue #779: shared Redtube embed resolver (the second hub of the ixiporn
 * shape-B tier — hub varies per video, xvideos is #778). Fixtures captured live
 * 2026-10-17:
 *  - redtube_embed_live.html — embed.redtube.com/?id=103165491, HTTP 200
 *  - redtube_embed_deleted.html — ?id=21152821 (the exact ixiporn FINDINGS-655
 *    sample), HTTP 404 "This video has been removed" shell
 *  - redtube_media_hls.json / redtube_media_mp4.json — the /media/{hls,mp4}?s=<token>
 *    responses for the live sample
 * Signed tokens in the fixtures are expired one-shot media URLs, not credentials
 * (Sonar S6418).
 */
class RedtubeParseTest {

    private val liveHtml by lazy { javaClass.classLoader.getResource("redtube_embed_live.html")!!.readText() }
    private val deletedHtml by lazy { javaClass.classLoader.getResource("redtube_embed_deleted.html")!!.readText() }
    private val hlsJson by lazy { javaClass.classLoader.getResource("redtube_media_hls.json")!!.readText() }
    private val mp4Json by lazy { javaClass.classLoader.getResource("redtube_media_mp4.json")!!.readText() }

    // ---- flashvars blob extraction (step 1 of the chain) ----

    @Test fun `flashvars parses from live embed page`() {
        val json = RedtubeParse.flashvarsJson(liveHtml)
        assertTrue("no flashvars blob on the live embed page", json != null)
        assertTrue(json!!.contains("\"mediaDefinitions\""))
        // the blob must be valid JSON — the lazy `});` match must not truncate it
        assertTrue(RedtubeParse.mediaFromFlashvars(json).size == 2)
    }

    /** Deleted-shell contract (#779): the removed-video shell has no flashvars — decline. */
    @Test fun `deleted shell has no flashvars`() {
        assertNull(RedtubeParse.flashvarsJson(deletedHtml))
    }

    @Test fun `flashvars absent on plain html`() {
        assertNull(RedtubeParse.flashvarsJson("<html><body>no player here</body></html>"))
    }

    // ---- mediaDefinitions (step 2: the two legs, relative paths) ----

    @Test fun `media definitions resolve hls and mp4 legs`() {
        val flash = RedtubeParse.flashvarsJson(liveHtml)!!
        val media = RedtubeParse.mediaFromFlashvars(flash)
        assertEquals(2, media.size)
        assertEquals(setOf("hls", "mp4"), media.map { it.format }.toSet())
        // both legs are RELATIVE media paths in the flashvars blob (remote: true)
        assertTrue(media.all { it.videoUrl.startsWith("/") })
        assertTrue(media.all { it.videoUrl.startsWith("/media/") })
        // the hls leg carries a height (404 in the fixture) for quality fallback
        assertEquals(404, media.first { it.format == "hls" }.height)
    }

    @Test fun `non-stream media legs are dropped`() {
        val blob = """{"mediaDefinitions":[
            {"format":"hls","videoUrl":"/media/hls?s=abc"},
            {"format":"","videoUrl":"/media/x?s=abc"},
            {"format":"mp4","videoUrl":""},
            {"format":"mp4"}
        ]}"""
        assertEquals(1, RedtubeParse.mediaFromFlashvars(blob).size)
    }

    @Test fun `garbage flashvars yields no media`() {
        assertTrue(RedtubeParse.mediaFromFlashvars("not json").isEmpty())
        assertTrue(RedtubeParse.mediaFromFlashvars("""{"other":1}""").isEmpty())
    }

    // ---- /media responses (step 3: absolute CDN variants) ----

    @Test fun `hls media response yields absolute masters`() {
        val variants = RedtubeParse.variantsFromMediaJson(hlsJson)
        assertEquals(2, variants.size)
        assertTrue(variants.all { it.url.startsWith("https://") && it.url.endsWith("master.m3u8") })
        val default = variants.first { it.isDefault }
        assertEquals(480, default.quality)
        assertTrue(default.url.contains("480P_2000K"))
    }

    @Test fun `mp4 media response yields direct links`() {
        val variants = RedtubeParse.variantsFromMediaJson(mp4Json)
        assertEquals(2, variants.size)
        assertTrue(variants.all { it.url.startsWith("https://") && it.url.endsWith(".mp4") || it.url.contains(".mp4?") })
        assertEquals(480, variants.first { it.isDefault }.quality)
        assertEquals(240, variants.first { !it.isDefault }.quality)
    }

    @Test fun `garbage media response yields no variants`() {
        assertTrue(RedtubeParse.variantsFromMediaJson("not json").isEmpty())
        assertTrue(RedtubeParse.variantsFromMediaJson("""{"error":"gone"}""").isEmpty())
    }

    /** Relative-entry safety: a non-absolute videoUrl in a media response is never emitted. */
    @Test fun `relative videoUrl in media response is dropped`() {
        val json = """[{"defaultQuality":true,"format":"hls","quality":"480","videoUrl":"/media/hls?s=x"}]"""
        assertTrue(RedtubeParse.variantsFromMediaJson(json).isEmpty())
    }

    // ---- unknown-hub decline (#779 approved Q1) — Parse-level seam of the
    // ---- normal extractor match: no adapter row → covered() false → caller
    // ---- emits nothing (no crash, no dead link).

    /** The redtube hub embed the fixture tier frames must have a registry row. */
    @Test fun `redtube hub is covered by the shared registry`() {
        assertTrue(HubEmbeds.covered("https://embed.redtube.com/?id=21152821"))
    }

    @Test fun `unknown hub declines`() {
        // a hub we ship no adapter for: the decline must be a plain false —
        // this is the seam the provider-side b64 chain (#780) leans on
        assertFalse(HubEmbeds.covered("https://someunknownhub.example/e/12345"))
        assertFalse(HubEmbeds.covered("https://embed.someunknownhub.example/?id=99"))
    }

    @Test fun `coverage mirrors loadExtractor normalization`() {
        // the framework strips ^(https:|)//(www\.|) and lowercases before startsWith —
        // casing/www variants of a covered URL must stay covered, same dispatch
        assertTrue(HubEmbeds.covered("https://www.embed.redtube.com/?id=21152821"))
        assertTrue(HubEmbeds.covered("HTTPS://EMBED.REDTUBE.COM/?id=21152821"))
        // and a fixture registry must follow the same rule as the real one
        val fake = listOf(FakeRow("https://myhub.example"))
        assertTrue(HubEmbeds.covered("https://myhub.example/e/1", fake))
        assertTrue(HubEmbeds.covered("https://www.myhub.example/e/1", fake))
        assertFalse(HubEmbeds.covered("https://other.example/e/1", fake))
    }

    private class FakeRow(override val mainUrl: String) : com.lagradost.cloudstream3.utils.ExtractorApi() {
        override val name = "fake"
        override val requiresReferer = false
        override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
            callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
        ) = Unit
    }
}
