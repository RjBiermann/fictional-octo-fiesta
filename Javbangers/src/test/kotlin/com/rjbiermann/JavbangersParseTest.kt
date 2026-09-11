package com.rjbiermann

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
}
