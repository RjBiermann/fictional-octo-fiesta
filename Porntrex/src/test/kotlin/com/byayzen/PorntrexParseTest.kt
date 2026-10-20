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
        val fv = Regex("var flashvars = \\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(
            File("src/test/resources/porntrex_embed_page.html").readText())?.value ?: ""
        assertTrue(fv.contains("video_url:"))
        val links = PorntrexParse.qualityLinks(fv)
        assertEquals(1, links.size)
        assertEquals("480p", links[0].first)
        assertTrue(links[0].second.contains("?embed=true"))
        // the redirect-flagged page URLs must not appear as stream URLs
        assertTrue(links.none { it.second.contains("/video/") })
    }

    // ==== Issue #759 / story S2 (#768): pure quality mapping (label, url) -> Int — RED ====
    // S1 evidence (#767): the app's quality sort only recognizes the resolution Int;
    // compound KVS labels ('1080p FHD', '2160p 4K', ...) fall through getQualityFromName
    // to Unknown(400). getQualityFromName itself is not unit-loadable (ExtractorApiKt
    // <clinit>), so assertions run against the Qualities enum constants directly.

    private val qualityCases: List<Triple<String?, String, Int>> by lazy {
        File("src/test/resources/porntrex_quality_cases.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split("\t")
                require(parts.size == 3) { "bad case line: $line" }
                Triple(parts[0].takeIf { it != "<null>" }, parts[1], parts[2].toInt())
            }
    }

    /** The whole decided S1 table, fixture-driven (porntrex_quality_cases.txt). */
    @Test fun `quality mapping matches the decided S1 table`() {
        assertTrue("fixture rows missing", qualityCases.size >= 15)
        for ((label, url, expected) in qualityCases) {
            assertEquals("label=$label url=$url", expected, PorntrexParse.qualityOf(label, url))
        }
    }

    /** Compound KVS labels must yield the enum Int the app's quality sort recognizes. */
    @Test fun `compound labels map to Qualities enum values`() {
        assertEquals(Qualities.P1080.value, PorntrexParse.qualityOf("1080p FHD", "https://x/get_file/a/2491818.mp4/"))
        assertEquals(Qualities.P2160.value, PorntrexParse.qualityOf("2160p 4K", "https://x/get_file/a/2491818.mp4/"))
        assertEquals(Qualities.P720.value, PorntrexParse.qualityOf("720p HD", "https://x/get_file/a/2491818.mp4/"))
    }

    /** S1: numberless '4K' gets the getQualityFromName-style fallback to 2160. */
    @Test fun `numberless 4K label maps to 2160`() {
        assertEquals(Qualities.P2160.value, PorntrexParse.qualityOf("4K", "https://x/get_file/a/2491818.mp4/"))
        assertEquals(Qualities.P2160.value, PorntrexParse.qualityOf("4k", null))
    }

    /** Bare NNNp labels parse natively; 540 is no Qualities constant — quality is a plain Int. */
    @Test fun `bare NNNp labels parse to their resolution`() {
        assertEquals(Qualities.P480.value, PorntrexParse.qualityOf("480p", null))
        assertEquals(Qualities.P240.value, PorntrexParse.qualityOf("240p", null))
        assertEquals(540, PorntrexParse.qualityOf("540p", null))
    }

    /** S2 spec: URL-pattern fallback (_NNNp.mp4 in the path) when the text label doesn't map. */
    @Test fun `url pattern fallback when the text label does not map`() {
        assertEquals(Qualities.P1080.value,
            PorntrexParse.qualityOf(null, "https://x/get_file/a/2913997_1080p.mp4/?embed=true"))
        assertEquals(Qualities.P720.value,
            PorntrexParse.qualityOf("HD", "https://x/get_file/a/2491818_720p.mp4/"))
    }

    /** S1: map from the label, never the URL — the URL fallback only fires when it doesn't map. */
    @Test fun `label wins over the url pattern when it maps`() {
        assertEquals(Qualities.P480.value,
            PorntrexParse.qualityOf("480p", "https://x/get_file/a/2491818_1080p.mp4/"))
    }

    /** Null/blank/unmappable label with no URL signal stays Unknown(400) — the live embed base shape. */
    @Test fun `null or blank label with no url signal stays Unknown`() {
        val bare = "https://x/get_file/a/2913997.mp4/?embed=true"
        assertEquals(Qualities.Unknown.value, PorntrexParse.qualityOf(null, bare))
        assertEquals(Qualities.Unknown.value, PorntrexParse.qualityOf("", bare))
        assertEquals(Qualities.Unknown.value, PorntrexParse.qualityOf("HD", bare))
        // range-validated: 9999p is not a resolution the app knows
        assertEquals(Qualities.Unknown.value, PorntrexParse.qualityOf("9999p", bare))
    }
}
