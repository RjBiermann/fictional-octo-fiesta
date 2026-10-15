package com.rjbiermann

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #292: data-completeness. ratingValue sits in the page's single ld+json script's
 * @graph[0].aggregateRating; watch pages carry one .wserver per season (names "Season 1/2",
 * episode numbers in data-no, ids in data-ep) all present on a single ?sv=1&part=1
 * response. Single-server pages (movies) label the server "Server 1".
 */
class Cat3FilmParseTest {

    private fun res(name: String) =
        javaClass.getResourceAsStream("/$name")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("fixture $name missing")

    // --- home page type (#416) ----------------------------------------------

    @Test fun `movie detail page has Movie badge`() {
        assertTrue(Parse.movieTypeTag(res("detail-handmaiden.html")) == "Movie")
    }

    @Test fun `series detail page uses TV badge not Movie`() {
        assertFalse(Parse.movieTypeTag(res("detail-hache.html")) == "Movie")
    }

    // --- score ---------------------------------------------------------------

    @Test fun `ratingValue is extracted from ld+json`() {
        assertEquals(8.1, Parse.rating(res("detail-handmaiden.html"))!!, 0.001)
    }

    @Test fun `ratingValue absent returns null`() {
        assertNull(Parse.rating("<html><body>no ld here</body></html>"))
    }

    // --- episodes ------------------------------------------------------------

    @Test fun `multi-season watch page splits into seasons`() {
        val eps = Parse.episodes(Jsoup.parse(res("watch-hache.html")))
        assertEquals(14, eps.size)
        assertEquals(listOf(1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2), eps.map { it.season })
        assertEquals((1..8).toList() + (1..6).toList(), eps.map { it.number })
        assertEquals("449", eps[0].data)
        assertEquals("462", eps.last().data)
    }

    @Test fun `movie single server keeps episode 1 without season`() {
        val eps = Parse.episodes(Jsoup.parse(res("watch-handmaiden.html")))
        assertEquals(1, eps.size)
        assertEquals(1, eps[0].number)
        assertNull(eps[0].season)
        assertEquals("405", eps[0].data)
    }

    // --- stream URL suffix (#410) -------------------------------------------

    @Test fun `bare token gets index m3u8 suffix`() {
        assertEquals("https://cat3.asuka-vod.site/TOKEN/index.m3u8",
            Parse.streamUrl("https://cat3.asuka-vod.site/TOKEN"))
    }

    @Test fun `trailing slash is stripped before suffixing`() {
        assertEquals("https://cat3.asuka-vod.site/TOKEN/index.m3u8",
            Parse.streamUrl("https://cat3.asuka-vod.site/TOKEN/"))
    }

    @Test fun `already-suffixed urls pass through`() {
        assertEquals("https://cdn/x/index.m3u8", Parse.streamUrl("https://cdn/x/index.m3u8"))
        assertEquals("https://cdn/x/index.json", Parse.streamUrl("https://cdn/x/index.json"))
        assertEquals("https://cdn/x/index.m3u8?k=v", Parse.streamUrl("https://cdn/x/index.m3u8?k=v"))
        // progress/DASH urls are not pass-through: bare-token suffix rule applies.
        assertEquals("https://cdn/v.mp4/index.m3u8", Parse.streamUrl("https://cdn/v.mp4"))
    }

    @Test fun `blank or null yields null`() {
        assertNull(Parse.streamUrl(null))
        assertNull(Parse.streamUrl("   "))
    }

    // --- backup mirror embed (#487) ------------------------------------------

    @Test fun `backup field extracts mirror embed url`() {
        assertEquals("https://player.abyssplayer.com/yXQfibqVK",
            Parse.backupEmbed(res("sources-1798.json")))
    }

    @Test fun `json-escaped backup url unescapes`() {
        assertEquals("https://player.abyssplayer.com/yXQfibqVK",
            Parse.backupEmbed("""{"backup":"https:\/\/player.abyssplayer.com\/yXQfibqVK","sources":[]}"""))
    }

    @Test fun `missing blank or non-json backup yields null`() {
        assertNull(Parse.backupEmbed("""{"sources":[],"success":true}"""))
        assertNull(Parse.backupEmbed("""{"backup":"",\"sources\":[]}"""))
        assertNull(Parse.backupEmbed(null))
        assertNull(Parse.backupEmbed("<html>Just a moment…</html>"))
    }

    // --- 2026-10-15 re-probe (issue #613, Story 1): fresh evidence shape ------
    // Fixture reproduces the live /api/v1/episodes/1815/sources body (token
    // anonymized in the path; shape and hosts verbatim from FINDINGS.md).

    /**
     * Drift machine: the CDN host has moved cat3.asuka-vod.site → abyssssss.top →
     * master.abyssssss.top while tokens kept working under each host. The suffix rule
     * must be host-agnostic — pinned for every host recorded in FINDINGS.md.
     */
    @Test fun `bare token suffix rule is host-agnostic across recorded hosts`() {
        val token = "M3D7o8kenCnnenP0XvPr0Xw9VfrIMr3lPcEjTnyRLc"
        for (cdn in listOf(
            "https://cat3.asuka-vod.site",          // first-probe host (2026-09-10)
            "https://abyssssss.top",                 // #416 move (2026-09-16)
            "https://master.abyssssss.top"           // #617 move (2026-10-15)
        )) {
            assertEquals("$cdn/$token/index.m3u8", Parse.streamUrl("$cdn/$token"))
        }
    }

    @Test fun `fresh 1815 fixture parses backup embed and stream url`() {
        assertEquals("https://player.abyssplayer.com/lD0RMD3Q_",
            Parse.backupEmbed(res("sources-1815.json")))
        assertEquals("https://master.abyssssss.top/M3D7o8kenCnnenP0XvPr0Xw9VfrIMr3lPcEjTnyRLc/index.m3u8",
            Parse.streamUrl("https://master.abyssssss.top/M3D7o8kenCnnenP0XvPr0Xw9VfrIMr3lPcEjTnyRLc"))
    }
}
