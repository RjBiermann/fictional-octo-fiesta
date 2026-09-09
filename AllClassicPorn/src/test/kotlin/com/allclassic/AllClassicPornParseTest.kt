package com.allclassic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture-driven tests for AllClassicPornParse (issue #204: load() title must match card title). */
class AllClassicPornParseTest {

    private val videoPage = javaClass.getResourceAsStream("/video-6161.html")!!.readBytes().decodeToString()
    private val searchPage = javaClass.getResourceAsStream("/search-milf.html")!!.readBytes().decodeToString()

    @Test fun `video page title includes year and matches card title from search fixture`() {
        val loadTitle = AllClassicPornParse.parseTitle(videoPage)
        assertEquals("Mature Milfs - Part Three - HOMEMADE VHS - (1998)", loadTitle)
        val cardTitle = Regex("<div class=\"th-description\">Mature Milfs[^<]*")
            .find(searchPage)!!.value.removePrefix("<div class=\"th-description\">").trim()
        assertEquals(cardTitle, loadTitle) // search card ↔ load page agreement (C1)
    }

    @Test fun `search fixture has card titles`() {
        assertTrue(searchPage.contains("th-description"))
    }

    @Test fun `og title fallback when no h1`() {
        assertEquals(
            "Some Video",
            AllClassicPornParse.parseTitle("<html><meta property=\"og:title\" content=\"Some Video\"/></html>")
        )
    }

    @Test fun `null when no title source`() {
        assertNull(AllClassicPornParse.parseTitle("<html></html>"))
    }
}
