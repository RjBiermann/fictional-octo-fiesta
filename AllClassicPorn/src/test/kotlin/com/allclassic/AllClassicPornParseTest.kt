package com.allclassic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture-driven tests for AllClassicPornParse (issue #204: load() title must match card title). */
class AllClassicPornParseTest {

    private val videoPage = javaClass.getResourceAsStream("/video-6161.html")!!.readBytes().decodeToString()
    private val searchPage = javaClass.getResourceAsStream("/search-milf.html")!!.readBytes().decodeToString()
    private val video2252 = javaClass.getResourceAsStream("/video-2252.html")!!.readBytes().decodeToString()
    private val loadTitle = AllClassicPornParse.parseTitle(videoPage)

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

    @Test fun `actors from flashvars`() {
        assertEquals(
            listOf("Anna Romeo", "Brooke Lane"),
            AllClassicPornParse.parseActors("<script>flashvars['video_models'] = 'Anna Romeo, Brooke Lane';</script>")
        )
        assertEquals(
            listOf("John Holmes", "Tracy O'Steen"),
            AllClassicPornParse.parseActors("x\"video_models: 'John Holmes, Tracy O\\'Steen'")
        )
    }

    @Test fun `actors empty when flashvars has none (video 6161)`() {
        assertTrue(AllClassicPornParse.parseActors(videoPage).isEmpty())
    }

    @Test fun `actors from fixture video 2252`() {
        assertEquals(
            "Anna Romeo", AllClassicPornParse.parseActors(video2252).first()
        )
        assertEquals(13, AllClassicPornParse.parseActors(video2252).size)
    }

    @Test fun `tags and categories from fixture video 6161`() {
        val tags = AllClassicPornParse.parseTags(videoPage)
        assertEquals("Chubby", tags.first())
        assertTrue(tags.contains("VHS"))
        assertTrue(AllClassicPornParse.parseCategories(videoPage).contains("MILF"))
    }

    @Test fun `year from title suffix`() {
        assertEquals(1998, AllClassicPornParse.parseYear(loadTitle))
        assertEquals(1998, AllClassicPornParse.parseYear("Mature Milfs - Part Three - HOMEMADE VHS - (1998)"))
        assertEquals(1996, AllClassicPornParse.parseYear("Zazel, Full movie (1996)"))
        assertNull(AllClassicPornParse.parseYear("No Year Here"))
        assertNull(AllClassicPornParse.parseYear(null))
    }

    @Test fun `null when no title source`() {
        assertNull(AllClassicPornParse.parseTitle("<html></html>"))
    }

    @Test fun `escaped apostrophe in names survives the load outerHtml path (fixture 1573)`() {
        val raw = javaClass.getResourceAsStream("/video-1573.html")!!.readBytes().decodeToString()
        val html = org.jsoup.Jsoup.parse(raw).outerHtml()
        val actors = AllClassicPornParse.parseActors(html)
        assertTrue("Tracy O'Neil should be present from the real load() html", actors.contains("Tracy O'Neil"))
    }
}
