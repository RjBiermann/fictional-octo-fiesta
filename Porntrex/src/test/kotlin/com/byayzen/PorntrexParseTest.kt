package com.byayzen

import com.kraptor.DurationParse
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** TDD for issue #215: duration must come from the details stats row, in seconds. */
class PorntrexParseTest {

    private val doc by lazy {
        Jsoup.parse(File("src/test/resources/porntrex_video_page.html").readText())
    }

    /** Raw flashvar block of the recorded /embed/{id}/ fixture (live site capture). */
    private val embedFlashvars by lazy {
        Regex("var flashvars = \\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(
            File("src/test/resources/porntrex_embed_page.html").readText())?.value ?: ""
    }

    /** TDD for issue #309: the clock badge lives in the .video-info stats row on live DOM.
     *  Repo convention: CloudStream `duration` is minutes, floored (shared DurationParse). */
    @Test fun `duration parsed from video-info stats row`() {
        assertEquals(50, PorntrexParse.durationOf(doc))
    }

    @Test fun `duration parses H MM SS clock text`() {
        val doc2 = Jsoup.parse("""<div class="block-details"><div class="item">
            <span><i class="fa fa-clock-o"></i> <em class="badge">1:06:09</em></span></div></div>""")
        assertEquals(66, PorntrexParse.durationOf(doc2))
    }

    @Test fun `no details row yields null not navbar garbage`() {
        val doc3 = Jsoup.parse("""<a><i class="fa fa-clock-o"></i>Latest</a>""")
        assertNull(PorntrexParse.durationOf(doc3))
    }

    @Test fun `duration clock grammar covered by shared DurationParse`() {
        assertEquals(6, DurationParse.minutes("6min 09sec"))
        assertEquals(66, DurationParse.minutes("1:06:09"))
        assertEquals(10, DurationParse.minutes("10min"))
        assertNull(DurationParse.minutes("Latest"))
        assertNull(DurationParse.minutes(null))
    }

    /** Issue #256: all KVS quality variants, in flashvar order. */
    @Test fun `qualityLinks extracts base plus all alt urls with labels`() {
        val fv = """video_url: 'https://x/get_file/a/2491818.mp4/',
            video_alt_url: 'https://x/get_file/b/2491818_720p.mp4/',
            video_alt_url_text: '720p HD',
            video_alt_url_hd: '1',
            video_alt_url2: 'https://x/get_file/c/2491818_1080p.mp4/',
            video_alt_url2_text: '1080p FHD'"""
        val links = PorntrexParse.qualityLinks(fv)
        assertEquals(3, links.size)
        assertEquals("https://x/get_file/a/2491818.mp4/", links[0].second)
        assertEquals("720p HD" to "https://x/get_file/b/2491818_720p.mp4/", links[1])
        assertEquals(null, links[0].first)
        assertEquals("1080p FHD" to "https://x/get_file/c/2491818_1080p.mp4/", links[2])
    }

    /**
     * Issue #275: guest-shell pages yield no tags; the /embed/{id}/ flashvars carry them as
     * `video_tags: 'a, b, c'` and `video_categories: '...'`. Parse into a merged tag list.
     */
    @Test fun `embedTags parses tags plus categories from embed flashvars`() {
        val doc = Jsoup.parse(File("src/test/resources/porntrex_embed_page.html").readText())
        val tags = PorntrexParse.embedTags(doc)
        assertTrue(tags.contains("Milf"))
        assertTrue(tags.contains("Red Head"))
        assertTrue(tags.contains("Busty Redhead"))
        assertTrue(tags.contains("Reverse Cowgirl"))
        // categories come first, then tags, no dupes
        assertEquals("Milf", tags.first())
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test fun `embedTags returns empty when no flashvars`() {
        val doc = Jsoup.parse("<html><body>shell</body></html>")
        assertTrue(PorntrexParse.embedTags(doc).isEmpty())
    }

    /**
     * Issue #256: live /embed/ flashvars mark alt variants with `<n>_redirect: '1'` and the URL
     * is the video page (text/html), not a stream. Those must not become ExtractorLinks.
     */
    @Test fun `qualityLinks skips redirect variants and keeps the direct base`() {
        val fv = """video_url: 'https://x/get_file/a/2491818.mp4/?embed=true',
            video_url_text: '480p',
            video_alt_url: 'https://x/video/2491818/octavia-red-horny-cheerleader',
            video_alt_url_redirect: '1',
            video_alt_url_text: '720p HD',
            video_alt_url2: 'https://x/video/2491818/octavia-red-horny-cheerleader',
            video_alt_url2_redirect: '1',
            video_alt_url2_text: '1080p FHD'"""
        val links = PorntrexParse.qualityLinks(fv)
        assertEquals(1, links.size)
        assertEquals("480p" to "https://x/get_file/a/2491818.mp4/?embed=true", links[0])
    }

    /**
     * Issue #308: guest video pages now serve only an ad shell, so loadLinks resolves via
     * /embed/{id}/ whose flashvars carry ONLY video_url (?embed=true, 480p) — every HD alt is
     * the video page URL flagged `<n>_redirect: '1'`. Simulate the provider on the raw live
     * embed fixture: one working stream, redirect page URLs must never leak.
     */
    @Test fun `qualityLinks on raw embed fixture yields only the embed base stream`() {
        assertTrue(embedFlashvars.contains("video_url:"))
        val links = PorntrexParse.qualityLinks(embedFlashvars)
        assertEquals(1, links.size)
        assertEquals("480p", links[0].first)
        assertTrue(links[0].second.contains("?embed=true"))
        // the redirect-flagged page URLs must not appear as stream URLs
        assertTrue(links.none { it.second.contains("/video/") })
    }

    // ------------------------------------------------------------------
    // S2 (issue #768): PorntrexParse.qualityOf — the pure label/url -> quality Int surface,
    // mapping table decided in S1 (FINDINGS-767 section 4). RED step of the TDD chain
    // (ADR-0005): this suite must fail against the S2 skeleton; S3 (#769) implements the
    // mapping (green) and wires loadLinks. No HTTP mocking — pure-parse surface only.

    /** Data-driven: every row of the S1 mapping table, as fixture data. */
    @Test fun `qualityOf maps every row of the S1 mapping table fixture`() {
        val rows = File("src/test/resources/porntrex_quality_labels.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split("\t") }
        assertTrue("quality fixture rows missing", rows.size >= 9)
        for ((label, expected) in rows.map { it[0] to it[1].toInt() }) {
            assertEquals("label '$label'", expected, PorntrexParse.qualityOf(label))
        }
    }

    /** The recorded embed fixture still carries the exact live ladder S1 probed (order-guarded). */
    @Test fun `embed fixture carries the S1 label ladder in flashvar order`() {
        val labels = Regex("video_(?:url|alt_url\\d*)_text: '([^']+)'")
            .findAll(embedFlashvars).map { it.groupValues[1] }.toList()
        assertEquals(listOf("480p", "720p HD", "1080p FHD", "2160p 4K"), labels)
    }

    /** Flashvar labels flow through qualityLinks into the quality surface (the S3 wiring shape). */
    @Test fun `qualityLinks pairs map through qualityOf`() {
        val links = PorntrexParse.qualityLinks(embedFlashvars)
        assertEquals(1, links.size)
        val (label, url) = links[0]
        assertEquals(480, PorntrexParse.qualityOf(label, url))
    }

    /** null / blank / unmappable labels fall back to Qualities.Unknown — the #759 empty-tag symptom. */
    @Test fun `qualityOf falls back to Unknown for null blank and unmappable labels`() {
        assertEquals(Qualities.Unknown.value, PorntrexParse.qualityOf(null))
        assertEquals(Qualities.Unknown.value, PorntrexParse.qualityOf(""))
        assertEquals(Qualities.Unknown.value, PorntrexParse.qualityOf("   "))
        assertEquals(Qualities.Unknown.value, PorntrexParse.qualityOf("HD"))
    }

    /** URL-pattern fallback (e.g. `_1080p.mp4` in the path) when the text label doesn't map. */
    @Test fun `qualityOf derives quality from the url pattern when the label does not map`() {
        assertEquals(1080, PorntrexParse.qualityOf(
            null, "https://www.porntrex.com/get_file/10/1f12/3363000/3363831/3363831_1080p.mp4/?embed=true"))
        assertEquals(720, PorntrexParse.qualityOf(
            "HD", "https://www.porntrex.com/get_file/10/1f12/3363000/3363831/3363831_720p.mp4/"))
    }
}
