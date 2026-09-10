package com.byayzen

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

    @Test fun `duration scoped to details row parses min sec`() {
        assertEquals(6 * 60 + 9, PorntrexParse.durationOf(doc))
    }

    @Test fun `duration parses H MM SS clock text`() {
        val doc2 = Jsoup.parse("""<div class="block-details"><div class="item">
            <span><i class="fa fa-clock-o"></i> <em class="badge">1:06:09</em></span></div></div>""")
        assertEquals(3969, PorntrexParse.durationOf(doc2))
    }

    @Test fun `no details row yields null not navbar garbage`() {
        val doc3 = Jsoup.parse("""<a><i class="fa fa-clock-o"></i>Latest</a>""")
        assertNull(PorntrexParse.durationOf(doc3))
    }

    @Test fun `parseDurationSeconds pure cases`() {
        assertEquals(369, PorntrexParse.parseDurationSeconds("6min 09sec"))
        assertEquals(3969, PorntrexParse.parseDurationSeconds("1:06:09"))
        assertEquals(600, PorntrexParse.parseDurationSeconds("10min"))
        assertNull(PorntrexParse.parseDurationSeconds("Latest"))
        assertNull(PorntrexParse.parseDurationSeconds(null))
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
}
