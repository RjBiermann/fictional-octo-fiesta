package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Mangoporn port, TDD-first per ADR-0005.
 * Fixtures are fresh live captures (2026-10-17, issue #657 deep audit: the
 * theme now carries a rotating front-page widget row alongside the main
 * `div.grid--mc` listing, and the load page lost the `dp__panel`/`vid__txt`
 * wrappers). HTTP flows stay covered by pipeline Verification.
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

    // ---- card grammar (issue #657): the MAIN listing is `div.grid--mc article.card` ----
    // The front page also renders a rotating featured widget in `section.sec` whose cards
    // overlap later pagination pages — parsing the whole page recreated them across
    // pages (drift 3 / hardening: scope to the main grid, never accumulate the widget).

    private fun fixture(name: String): org.jsoup.nodes.Document =
        Jsoup.parse(javaClass.classLoader.getResourceAsStream(name)!!.readBytes().decodeToString())

    private fun mainCards(doc: org.jsoup.nodes.Document) = MangopornParse.mainCards(doc)

    @Test
    fun mainListGrammarScopesToTheMainGrid() {
        val fields = mainCards(fixture("mangoporn-home.html"))
        // 27 main-grid cards — the 11-card section.sec featured widget stays out
        assertEquals(27, fields.size)
        assertTrue(fields.all { it.poster?.startsWith("https://") == true })
        // widget-only cards live outside grid--mc — none may leak into the row
        val hrefs = fields.map { it.href }
        assertFalse(hrefs.any { it.endsWith("/movies/boundless-encounters-france/") })
        assertFalse(hrefs.any { it.endsWith("/movies/bbc-pie-17/") })
    }

    /** Binding grammar (verified live 2026-10-17): a 1-result search page renders
     *  its card grid in a bare `div.grid` — no `grid--mc` modifier. Site shape:
     *  that grid carries the query match plus ONE site-native promoted card
     *  (yamo-v88, structurally identical, absent from multi-result grids and the
     *  home grids) — the skeleton's own relevance-only injection, not drift. */
    @Test
    fun singleResultSearchStillParses() {
        val fields = mainCards(fixture("mangoporn-search-single.html"))
        assertEquals(2, fields.size)
        assertEquals("https://mangoporn.net/movies/boundless-encounters-france/", fields[0].href)
        assertEquals("Boundless Encounters – France", fields[0].title)
        assertTrue(fields[0].poster?.startsWith("https://") == true)
        // site-native promoted card, pinned — if it ever escapes a 1-result-only
        // shape onto ordinary listing grids this pin breaks and the grammar is wrong
        assertEquals("https://mangoporn.net/movies/yamo-v88/", fields[1].href)
    }

    @Test
    fun mainListDisjointAcrossPagination() {
        // the drift that outranked a selector swap: the section.sec widget rotates
        // cards onto page 2's main grid, so whole-page parsing produces cross-page
        // duplicates. Scoped to grid--mc the main listings stay disjoint.
        val p1 = mainCards(fixture("mangoporn-home.html")).map { it.href }.toSet()
        val p2 = mainCards(fixture("mangoporn-home-p2.html")).map { it.href }.toSet()
        assertTrue(p1.isNotEmpty() && p2.isNotEmpty())
        assertTrue(
            "home main lists overlap across pages: ${p1.intersect(p2)}",
            p1.intersect(p2).isEmpty()
        )
    }

    @Test
    fun headingTagVarianceDoesNotDropCards() {
        // home p2 titles render as h2.card__t, home p1 as h3.card__t — class-only match
        val p2 = mainCards(fixture("mangoporn-home-p2.html"))
        assertEquals(27, p2.size)
        assertTrue(p2.any { it.title == "Club VXN 14" })
        assertTrue(p2.all { it.poster?.startsWith("https://") == true })
    }

    @Test
    fun searchParsesTheSameMainGridGrammar() {
        // search result cards sit directly in div.grid--mc (no section wrapper) —
        // one grammar for both surfaces
        val fields = mainCards(fixture("mangoporn-search.html"))
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

    // ---- load page (issue #657): the dp__panel/vid__txt wrappers are gone —
    // plot + actors bind to the semantic rows (div.desc / div.row--stars) ----

    @Test
    fun actorsParseFromTheStarsRow() {
        val actors = MangopornParse.actors(fixture("mangoporn-video.html"))
        assertEquals(12, actors.size)
        assertTrue(actors.contains("Agatha Vega"))
        assertTrue(actors.contains("Julia Grandi"))
        // director/studio chips are NOT stars
        assertFalse(actors.contains("Vixen"))
    }

    @Test
    fun plotParsesTheDescParagraphs() {
        val plot = MangopornParse.plot(fixture("mangoporn-video.html"))
        assertTrue(plot!!.startsWith("Club VXN Vol. 14."))
        assertTrue(plot.contains("most exclusive club in adult entertainment"))
    }

    @Test
    fun plotAndActorsAbsentYieldNone() {
        assertNull(MangopornParse.plot(Jsoup.parse("<html><body></body></html>")))
        assertEquals(emptyList<String>(), MangopornParse.actors(Jsoup.parse("<html><body></body></html>")))
    }

    // ---- load page: JSON-LD VideoObject — duration + thumbnailUrl array ----

    @Test
    fun videoLdJsonCarriesIsoDuration() {
        val ld = MangopornParse.videoLdJson(fixture("mangoporn-video.html"))
        assertTrue(ld!!.contains("VideoObject"))
        assertEquals(168, com.kraptor.JsonLdParse.minutes(ld))
    }

    @Test
    fun videoThumbnailExtractedFromArrayForm() {
        val ld = MangopornParse.videoLdJson(fixture("mangoporn-video.html"))
        assertEquals(
            "https://i3.wp.com/pandanetwork.club/movies/wp-content/uploads/2026/10/16/12/3271838.jpg?resize=720,1080",
            MangopornParse.thumbnail(ld)
        )
        // bare (non-array) form still supported
        assertNull(MangopornParse.thumbnail("{}"))
        assertEquals("https://x/y.jpg", MangopornParse.thumbnail("""{"thumbnailUrl":["https://x/y.jpg"]}"""))
    }

    // ---- data-servers player extraction (section.hlm JSON embeds) ----

    @Test
    fun dataServersEmbedsExtractAllHosts() {
        val links = MangopornParse.embedLinks(fixture("mangoporn-video.html"))
        assertEquals(
            listOf(
                "https://vidara.so/e/7MJ7uhqsk2Bv5",
                "https://luluvido.com/e/iv8dh0v5h2xq",
                "https://playmogo.com/e/a16q31td576i",
                "https://voe.sx/e/zhkr0cshocbi",
                "https://mixdrop.ag/e/8lqj6qjluevnmz",
            ),
            links
        )
    }

    @Test
    fun dataServersMissingOrMalformedYieldsNoLinks() {
        assertEquals(emptyList<String>(), MangopornParse.embedLinks(Jsoup.parse("<html><body><section class='hlm'></section></body></html>")))
        assertEquals(emptyList<String>(), MangopornParse.embedLinks(Jsoup.parse("""<section class='hlm' data-servers='not json'></section>""")))
    }

    // ---- quick search (turbosearch suggest JSON) ----

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
