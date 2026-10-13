package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Mangoporn port, TDD-first per ADR-0005.
 * Fixtures are fresh live captures (2026-10-16, issue #564, BEM card/data-servers
 * grammar). HTTP flows stay covered by pipeline Verification.
 */
class MangopornParseTest {

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

    // ---- card grammar (issue #564): article.card BEM cards, shared SearchCard ----

    private fun fixture(name: String): org.jsoup.nodes.Document =
        Jsoup.parse(javaClass.classLoader.getResourceAsStream(name)!!.readBytes().decodeToString())

    private fun cards(doc: org.jsoup.nodes.Document) =
        com.kraptor.SearchCard.homeCards(
            doc, "article.card", ".card__t a", hrefSel = "a.card__th", posterSel = "img.card__img"
        )

    @Test
    fun homeCardsParseThroughSharedCardGrammar() {
        val fields = cards(fixture("mangoporn-home.html"))
        assertEquals(38, fields.size)
        assertTrue(fields.any {
            it.title.startsWith("Top-Tier SSS Beauty") &&
                it.href.endsWith("/movies/top-tier-sss-beauty-returns-for-a-pure-unscripted-moment-of-passion/")
        })
        assertTrue(fields.all { it.poster?.startsWith("https://") == true })
    }

    @Test
    fun headingTagVarianceDoesNotDropCards() {
        // home p2 titles render as h2.card__t, home p1 as h3.card__t — class-only match
        val p2 = cards(fixture("mangoporn-home-p2.html"))
        assertTrue(p2.any { it.title == "Fetish Fantasies" })
        assertTrue(p2.all { it.poster?.startsWith("https://") == true })
    }

    @Test
    fun searchCardsParseWithRealPosters() {
        val fields = cards(fixture("mangoporn-search.html"))
        assertEquals(27, fields.size)
        assertTrue(fields.any {
            it.title == "Anal" && it.href.endsWith("/movies/anal-2/")
        })
        assertTrue(fields.all { it.poster?.startsWith("https://") == true })
        // the studio chip link (card__tx) must never win the href binding
        assertTrue(fields.all { !it.href.contains("studios/") })
    }

    @Test
    fun oldGrammarSelectorsMatchNothingOnLiveHtml() {
        val home = fixture("mangoporn-home.html")
        assertEquals(0, home.select("div.video-block").size)
        assertEquals(0, fixture("mangoporn-video.html").select("div#pettabs").size)
    }

    // ---- load page (issue #564): JSON-LD VideoObject — duration + thumbnailUrl array ----

    @Test
    fun videoLdJsonCarriesIsoDuration() {
        val ld = MangopornParse.videoLdJson(fixture("mangoporn-video.html"))
        assertTrue(ld!!.contains("VideoObject"))
        assertEquals(149, com.kraptor.JsonLdParse.minutes(ld))
    }

    @Test
    fun videoThumbnailExtractedFromArrayForm() {
        val ld = MangopornParse.videoLdJson(fixture("mangoporn-video.html"))
        assertEquals(
            "https://i3.wp.com/pandanetwork.club/movies/wp-content/uploads/2026/10/03/17/3255674.jpg?resize=720,1080",
            MangopornParse.thumbnail(ld)
        )
        // bare (non-array) form still supported
        assertNull(MangopornParse.thumbnail("{}"))
        assertEquals("https://x/y.jpg", MangopornParse.thumbnail("""{"thumbnailUrl":["https://x/y.jpg"]}"""))
    }

    // ---- data-servers player extraction (issue #564: section.hlm JSON embeds) ----

    @Test
    fun dataServersEmbedsExtractAllHosts() {
        val links = MangopornParse.embedLinks(fixture("mangoporn-video.html"))
        assertEquals(
            listOf(
                "https://playmate.to/embed/lIwXIX2evZ6ho",
                "https://playmogo.com/e/ote1kbil63o9",
                "https://luluvido.com/e/8n9fsmwl1tdt",
                "https://voe.sx/e/f3nrt622h53i",
                "https://mixdrop.ag/e/03vxj3mvsk96pq1",
            ),
            links
        )
    }

    @Test
    fun dataServersMissingOrMalformedYieldsNoLinks() {
        assertEquals(emptyList<String>(), MangopornParse.embedLinks(Jsoup.parse("<html><body><section class='hlm'></section></body></html>")))
        assertEquals(emptyList<String>(), MangopornParse.embedLinks(Jsoup.parse("""<section class='hlm' data-servers='not json'></section>""")))
    }

    // ---- quick search (issue #564: turbosearch suggest JSON) ----

    @Test
    fun suggestPostsParseTurbosearchJson() {
        val json = """{"q":"anal","posts":[
            {"id":32116,"title":"Anal","url":"https:\/\/mangoporn.net\/movies\/anal-2\/",
             "thumb":"https:\/\/i3.wp.com\/pandanetwork.club\/x.jpg?resize=360,540","meta":"29:00"},
            {"id":3011,"title":"Anal","url":"https:\/\/mangoporn.net\/movies\/anal\/",
             "thumb":"","meta":"17:00"}],"terms":[],"total":2}"""
        val posts = MangopornParse.suggestPosts(json)
        assertEquals(2, posts.size)
        assertEquals("https://mangoporn.net/movies/anal-2/", posts[0].href)
        assertTrue(posts[0].poster?.contains("pandanetwork.club") == true)
        assertEquals("https://mangoporn.net/movies/anal/", posts[1].href)
        assertNull(posts[1].poster)   // blank thumb → null, never fabricated
        assertEquals(0, MangopornParse.suggestPosts("""{"posts":[],"terms":[]}""").size)
        assertEquals(0, MangopornParse.suggestPosts("garbage").size)
    }
}
