package com.allclassic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test fun `tags categories and year from fixture 549`() {
        val page = javaClass.getResourceAsStream("/video-549.html")!!.readBytes().decodeToString()
        assertTrue(AllClassicPornParse.parseTags(page).contains("VHS"))
        assertTrue(AllClassicPornParse.parseCategories(page).contains("Full Movie"))
        // "Zazel: Parfum d'Amour, Uncut (1996)" — also exercises an apostrophe in the h1 title
        assertEquals(1996, AllClassicPornParse.parseYear(AllClassicPornParse.parseTitle(page)))
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

    @Test fun `plot from itemprop div when og description absent (issue #289, video 5887)`() {
        val page = javaClass.getResourceAsStream("/video-5887.html")!!.readBytes().decodeToString()
        assertFalse("fixture is the og-description-absent shape", "og:description" in page)
        val plot = AllClassicPornParse.parsePlot(page)!!
        assertTrue("Venus Film V7: The Barbershop" in plot)
        assertFalse(plot.startsWith("Description:"))
    }

    @Test fun `plot prefers og description when present (issue #289, video 6403)`() {
        val page = javaClass.getResourceAsStream("/video-6403.html")!!.readBytes().decodeToString()
        assertTrue("fixture is the og-description-present shape", "og:description" in page)
        assertEquals(
            org.jsoup.Jsoup.parse(page).selectFirst("meta[property=og:description]")
                ?.attr("content")?.replace(Regex("<[^>]+>"), "")?.trim(),
            AllClassicPornParse.parsePlot(page)
        )
        val plot = AllClassicPornParse.parsePlot(page)!!
        assertFalse("embedded tags stripped from og:description plot", "<br" in plot)
    }

    @Test fun `itemprop fallback strips label and trims (issue #289)`() {
        assertEquals(
            "Some text here",
            AllClassicPornParse.parsePlot(
                """<html><div class="video-description" itemprop="description">
                   |<div class="description-container"><strong>Description:</strong>
                   |Some text here</div></div></html>""".trimMargin()
            )
        )
    }

    @Test fun `null when no description source (issue #289)`() {
        assertNull(AllClassicPornParse.parsePlot("<html></html>"))
    }

    @Test fun `quality caption matches the flashvars-bracket form (issue #322, fixture 2252)`() {
        assertEquals("480p", AllClassicPornParse.parseQuality(video2252))
    }

    @Test fun `quality caption parses either form on a fixture that carries both (6161)`() {
        assertEquals("480p", AllClassicPornParse.parseQuality(videoPage))
    }

    @Test fun `quality caption matches the colon form and is null when absent`() {
        assertEquals("720p", AllClassicPornParse.parseQuality("video_url_text: '720p'"))
        assertNull(AllClassicPornParse.parseQuality("no cue here"))
    }

    @Test fun `home page URL feed page 1 uses canonical suffix, other rows bare (issue #322 D1)`() {
        // feed base: page 1 must NOT request bare …/page/ (301→root); page 2+ append
        assertEquals("https://allclassic.porn/page/1/", AllClassicPornParse.homePageUrl("https://allclassic.porn/page/", 1))
        assertEquals("https://allclassic.porn/page/2/", AllClassicPornParse.homePageUrl("https://allclassic.porn/page/", 2))
        // decade/sort rows use their base bare for page 1, append for page 2+
        assertEquals("https://allclassic.porn/40s/", AllClassicPornParse.homePageUrl("https://allclassic.porn/40s/", 1))
        assertEquals("https://allclassic.porn/40s/2/", AllClassicPornParse.homePageUrl("https://allclassic.porn/40s/", 2))
        assertNotEquals("https://allclassic.porn/page/1/", AllClassicPornParse.homePageUrl("https://allclassic.porn/page/", 2))
    }

    @Test fun `escaped apostrophe in names survives the load outerHtml path (fixture 1573)`() {
        val raw = javaClass.getResourceAsStream("/video-1573.html")!!.readBytes().decodeToString()
        val html = org.jsoup.Jsoup.parse(raw).outerHtml()
        val actors = AllClassicPornParse.parseActors(html)
        assertTrue("Tracy O'Neil should be present from the real load() html", actors.contains("Tracy O'Neil"))
    }

    @Test fun `homepage fixture duplicates are deduped by href (issue #266)`() {
        val doc = org.jsoup.Jsoup.parse(javaClass.getResourceAsStream("/home-page.html")!!.readBytes().decodeToString())
        val cards = doc.select("a.th.item")
        assertEquals(84, cards.size)  // site serves 7 hrefs twice in-page
        assertEquals(77, AllClassicPornParse.distinctByHref(cards).size)
    }

    @Test fun `distinctByHref keeps unique blocks`() {
        val doc = org.jsoup.Jsoup.parse(
            """<a class="th item" href="/videos/1/a/"></a>
               |<a class="th item" href="/videos/1/a/"></a>
               |<a class="th item" href="/videos/2/b/"></a>""".trimMargin()
        )
        assertEquals(2, AllClassicPornParse.distinctByHref(doc.select("a.th.item")).size)
    }
}
