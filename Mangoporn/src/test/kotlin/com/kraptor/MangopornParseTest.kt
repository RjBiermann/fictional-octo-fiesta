package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Mangoporn port (issue #443), TDD-first per ADR-0005.
 * All tests exercise the shipped Parse functions in MangopornParse /
 * CloudWish.unpack — HTTP flows are covered by pipeline Verification.
 */
class MangopornParseTest {

    // ---- CloudWish packed-JS unpack (provider-private grammar) ----

    @Test
    fun cloudWishUnpackExtractsMasterM3u8Path() {
        // k = ["", "/stream/v/", "master.m3u8"]: token 1 -> /stream/v/, token 2 -> master.m3u8
        val packed2 = "eval(function(p,a,c,k,e,d){}('1/2',10,3,'|/stream/v/|master.m3u8'.split('|'),0,{}))"
        val out = CloudWish().unpack(packed2)
        assertTrue(out!!.contains("/stream/v/"))
        assertTrue(out.contains("master.m3u8"))
        assertNull(CloudWish().unpack("no packed script here"))
    }

    // ---- dirty-word filter (production word lists from Mangoporn) ----

    @Test
    fun dirtyWordRegexMatchesTransTitles() {
        val pattern = MangopornParse.dirtyWordRegex(Mangoporn().igrencKelimeler)
        assertTrue(pattern.containsMatchIn("TS Seduction"))
        assertTrue(pattern.containsMatchIn("Trans fixed girl"))
        assertTrue(pattern.containsMatchIn("TGirl squad"))            // menu word
        assertFalse(pattern.containsMatchIn("Cum Gushers"))
        // "bi" (menu word) matches "Bi(g)" case-insensitively — upstream port quirk, pinned
        assertTrue(pattern.containsMatchIn("Big Boobs Blast"))
    }

    // ---- duration parse from load page ("2 hrs 5 mins") ----

    @Test
    fun durationParsesHoursAndMinutes() {
        assertEquals(125, MangopornParse.durationMinutes("2 hrs 5 mins")!!)
        assertEquals(35, MangopornParse.durationMinutes("35 mins")!!)
        assertNull(MangopornParse.durationMinutes(null))
        assertEquals(0, MangopornParse.durationMinutes("n/a")!!)
    }

    // ---- healed-site card grammar (issue #525): div.video-block cards, shared SearchCard ----

    private fun fixture(name: String): org.jsoup.nodes.Document =
        Jsoup.parse(javaClass.classLoader.getResourceAsStream(name)!!.readBytes().decodeToString())

    @Test
    fun homeCardsParseThroughSharedCardGrammar() {
        val fields = com.kraptor.SearchCard.homeCards(
            fixture("mangoporn-home.html"), "div.video-block", "a.infos",
            titleAttr = "title", posterSel = "a.thumb img"
        )
        assertEquals(48, fields.size)
        assertTrue(fields.any {
            it.title.startsWith("Major Creampie") && it.href.endsWith("/movies/major-creampie/")
        })
        // Home-root mixed lazy fill: many cards ship src="" — null poster, never fabricated
        assertTrue(fields.count { it.poster == null } >= 1)
        assertTrue(fields.count { it.poster?.startsWith("https://") == true } >= 40)
    }

    @Test
    fun searchCardsParseWithRealPosters() {
        val fields = com.kraptor.SearchCard.homeCards(
            fixture("mangoporn-search.html"), "div.video-block", "a.infos",
            titleAttr = "title", posterSel = "a.thumb img"
        )
        assertEquals(48, fields.size)
        assertTrue(fields.any {
            it.title.startsWith("Diamond Collection 44") && it.href.endsWith("/movies/diamond-collection-44-senior-sex/")
        })
        assertTrue(fields.all { it.poster?.startsWith("https://") == true })
        assertTrue(fields.none { it.title.contains("watch ", ignoreCase = true) })
        assertTrue(fields.maxOf { it.title.length } > 100)   // long site titles survive verbatim
    }

    // ---- load page (issue #525): JSON-LD VideoObject duration + thumbnail ----

    @Test
    fun videoLdJsonCarriesIsoDuration() {
        val ld = MangopornParse.videoLdJson(fixture("mangoporn-video.html"))
        assertTrue(ld!!.contains("VideoObject"))
        assertEquals(18, com.kraptor.JsonLdParse.minutes(ld))
    }

    @Test
    fun videoThumbnailNullWhenSiteServesEmpty() {
        val ld = MangopornParse.videoLdJson(fixture("mangoporn-video.html"))
        assertNull(MangopornParse.thumbnail(ld))   // page-inconsistent field: empty on major-creampie
        assertEquals(
            "https://i0.wp.com/pandanetwork.club/adult/wp-content/uploads/2026/06/3510965h.jpg",
            MangopornParse.thumbnail("{\"thumbnailUrl\":\"https://i0.wp.com/pandanetwork.club/adult/wp-content/uploads/2026/06/3510965h.jpg\"}")
        )
    }

    @Test
    fun liveVideoFixtureEmbedLinksExtract() {
        val links = MangopornParse.embedLinks(fixture("mangoporn-video.html"))
        assertTrue(links.any { it.startsWith("https://doply.net/e/") })
        assertTrue(links.any { it.startsWith("https://luluvid.com/e/") })
        assertFalse(links.any { it.contains("rapidgator") || it.contains("nitroflare") })
    }

    // ---- stream tab extraction from load page HTML (production selector) ----

    @Test
    fun petTabsExtractsEmbedLinksAndSkipsFileLockers() {
        val html = javaClass.classLoader
            .getResourceAsStream("mangoporn-pettabs.html")!!
            .readBytes().decodeToString()
        val links = MangopornParse.embedLinks(Jsoup.parse(html))
        assertTrue(links.contains("https://luluvid.com/e/jozkrpjgsueq"))
        assertTrue(links.contains("https://playmogo.com/e/80wvduwl22id"))
        assertFalse(links.any { it.contains("rapidgator") || it.contains("nitroflare") })
        assertTrue(links.isNotEmpty())
    }

    @Test
    fun oldArticleSelectorsMatchNothingOnLiveHtml() {
        assertEquals(0, fixture("mangoporn-home.html").select("div.items > article").size)
        assertEquals(0, fixture("mangoporn-search.html").select("article").size)
    }
}
