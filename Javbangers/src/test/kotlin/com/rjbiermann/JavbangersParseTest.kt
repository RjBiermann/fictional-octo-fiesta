package com.rjbiermann

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

/** TDD for issue #297: Tags row (href-less anchors) must be mapped alongside categories. */
class JavbangersParseTest {

    private val doc by lazy {
        Jsoup.parse(File("src/test/resources/javbangers_details.html").readText())
    }

    private val details get() = doc.selectFirst("div.block-details")

    @Test fun `tags row anchors are captured and merged with categories`() {
        val tags = JavbangersParse.tagsFromDetails(details, "Japanese busty milf hardcore gangbang")
        assertTrue("japanese" in tags)
        assertTrue("gangbang" in tags)
        assertTrue("rough-sex" in tags)
        assertTrue("pussy" in tags)
        // categories still present
        assertTrue("Milf" in tags)
        assertTrue("Big Boobs" in tags)
        // categories still present; shared "Milf"/"big-boobs"↔"Big Boobs" dedupe: categories(col2) ∪ tags(18) −1 dup = 19
        assertEquals(19, tags.size)
    }

    @Test fun `title-echo entry is dropped`() {
        val tags = JavbangersParse.tagsFromDetails(details, "JAV")
        assertFalse("JAV" in tags)
        assertTrue("asian" in tags)
    }

    @Test fun `no tags row yields categories only`() {
        val d = Jsoup.parse("""<div class="block-details"><a href="/categories/milf/">Milf</a></div>""")
        assertEquals(listOf("Milf"), JavbangersParse.tagsFromDetails(d.body(), "t"))
    }

    @Test fun `null details yields empty list`() {
        assertEquals(emptyList<String>(), JavbangersParse.tagsFromDetails(null, "t"))
    }

    private fun cal(year: Int, month0: Int, day: Int): Calendar =
        Calendar.getInstance().apply { clear(); set(year, month0, day) }

    /** Issue #324: relative-age badge → upload year. */
    @Test fun `years ago maps to current year minus n`() {
        assertEquals(2023, JavbangersParse.yearFromAge("3 years ago", cal(2026, 8, 11)))
        assertEquals(2017, JavbangersParse.yearFromAge("9 years ago", cal(2026, 8, 11)))
    }

    @Test fun `months ago floors to correct year`() {
        // 2 months before Nov 2026 → Sep 2026; 3 months before Jan 2026 → Oct 2025
        assertEquals(2026, JavbangersParse.yearFromAge("2 months ago", cal(2026, 10, 15)))
        assertEquals(2025, JavbangersParse.yearFromAge("3 months ago", cal(2026, 0, 31)))
    }

    @Test fun `days ago is same year unless near jan 1`() {
        assertEquals(2026, JavbangersParse.yearFromAge("10 hours ago", cal(2026, 8, 11)))
        assertEquals(2026, JavbangersParse.yearFromAge("3 days ago", cal(2026, 0, 31)))
        assertEquals(2025, JavbangersParse.yearFromAge("3 days ago", cal(2026, 0, 2)))
    }

    /** Site renders "N week ago" (singular) as well as days/hours. */
    @Test fun `weeks ago maps across the year boundary`() {
        assertEquals(2026, JavbangersParse.yearFromAge("1 week ago", cal(2026, 8, 11)))
        assertEquals(2025, JavbangersParse.yearFromAge("2 weeks ago", cal(2026, 0, 5)))
        assertEquals(2026, JavbangersParse.yearFromAge("2 weeks ago", cal(2026, 0, 20)))
    }

    @Test fun `empty or non-age badge yields null`() {
        assertEquals(null, JavbangersParse.yearFromAge(""))
        assertEquals(null, JavbangersParse.yearFromAge(null))
        assertEquals(null, JavbangersParse.yearFromAge("1 136")) // views badge
    }

    // ---- stream sources (issue #1451) ----------------------------------------
    //
    // Live evidence 2026-11-23: 18 of 29 file-bearing video pages carry THREE renditions
    // (video_url 480p / video_alt_url 720p / video_alt_url2 1080p). loadLinks hardcoded a
    // two-key list, so the 1080p rendition was dropped on every one of them.

    private val flashvars412669 by lazy {
        File("src/test/resources/flashvars_412669.js").readText()
    }

    @Test fun `all three renditions parse in key order with their captions`() {
        // Same slice loadLinks feeds the grammar (inside the flashvars object literal).
        val flash = flashvars412669.substringAfter("flashvars = {").substringBefore("};")
        val sources = JavbangersParse.sources(flash)
        assertEquals(3, sources.size)
        assertEquals(listOf("480p", "720p", "1080p"), sources.map { it.first })
        assertTrue(sources[0].second.endsWith("412669.mp4/") || sources[0].second.contains("/412669.mp4/"))
        assertTrue(sources[1].second.contains("/412669_720p.mp4/"))
        assertTrue(sources[2].second.contains("/412669_1080p.mp4/"))
        assertTrue(sources.all { it.second.startsWith("https://www.javbangers.com/get_file/") })
    }

    @Test fun `two-rendition page yields two sources`() {
        val flash = "video_url: 'https://h/get_file/1/a/1000/110229/110229.mp4/', video_url_text: '480p', " +
            "video_alt_url: 'https://h/get_file/2/b/1000/110229/110229_720p.mp4/', video_alt_url_text: '720p'"
        assertEquals(listOf("480p", "720p"), JavbangersParse.sources(flash).map { it.first })
    }

    @Test fun `caption-less single source keeps a null label`() {
        // /video/412690/i-doll-31 ships one file and no *_text caption — quality stays
        // Unknown rather than being invented (the filename carries no _NNNp token either).
        val flash = "video_url: 'https://h/get_file/4/5d02/412000/412690/412690.mp4/?v-acctoken=x'"
        assertEquals(listOf<Pair<String?, String?>>(null to "https://h/get_file/4/5d02/412000/412690/412690.mp4/?v-acctoken=x"),
            JavbangersParse.sources(flash))
    }

    @Test fun `filename token supplies the label when the caption is absent`() {
        val flash = "video_url: 'https://h/get_file/4/5d02/412000/412669/412669_1080p.mp4/'"
        assertEquals(listOf("1080p" to "https://h/get_file/4/5d02/412000/412669/412669_1080p.mp4/"),
            JavbangersParse.sources(flash))
    }

    @Test fun `non-http value is not a source`() {
        // KVS's encrypted / shifted shape: the value is not a plain URL, so the provider
        // drops it instead of guessing a cipher (FINDINGS contract).
        val flash = "video_url: 'function/0/https://h/get_file/4/x/412000/412669/412669.mp4/'"
        assertEquals(emptyList<Pair<String?, String>>(), JavbangersParse.sources(flash))
    }

    @Test fun `blank or absent flashvars yields no sources`() {
        assertEquals(emptyList<Pair<String?, String>>(), JavbangersParse.sources(""))
        assertEquals(emptyList<Pair<String?, String>>(),
            JavbangersParse.sources("video_url: '', video_alt_url: ''"))
    }

    @Test fun `badge is scoped inside details item`() {
        val badge = details?.select("div.item span em.badge")?.firstOrNull { it.text().contains("ago") }
        assertEquals("3 years ago", badge?.text())
        assertEquals(2024, JavbangersParse.yearFromAge(badge?.text(), cal(2027, 0, 1)))
    }
}
