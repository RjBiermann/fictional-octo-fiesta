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

    @Test fun `empty or non-age badge yields null`() {
        assertEquals(null, JavbangersParse.yearFromAge(""))
        assertEquals(null, JavbangersParse.yearFromAge(null))
        assertEquals(null, JavbangersParse.yearFromAge("1 136")) // views badge
    }

    @Test fun `badge is scoped inside details item`() {
        val badge = details?.select("div.item span em.badge")?.firstOrNull { it.text().contains("ago") }
        assertEquals("3 years ago", badge?.text())
        assertEquals(2024, JavbangersParse.yearFromAge(badge?.text(), cal(2027, 0, 1)))
    }
}
