package com.film1k

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TDD for issue #233: related videos, year mapping. Fixtures from live film1k.com pages. */
class Film1kParseTest {

    private val byseNoSlashDoc by lazy {
        javaClass.classLoader.getResource("film1k_video_byse_noslash.html")!!.readText()
    }
    private val turbovidDeadDoc by lazy {
        javaClass.classLoader.getResource("film1k_video_turbovid_dead.html")!!.readText()
    }
    private val turbovidEmbed by lazy {
        javaClass.classLoader.getResource("film1k_turbovid_embed.html")!!.readText()
    }

    private val relatedDoc by lazy {
        Jsoup.parse(javaClass.classLoader.getResource("film1k_video_related.html")!!.readText())
    }

    /** issue #558: fresh tick-tock page — promo+sidebar ads shadow the player strip */
    private val video558Doc by lazy {
        javaClass.classLoader.getResource("film1k_video_558.html")!!.readText()
    }

    @Test fun `related cards parse from main-scoped section`() {
        val related = Film1kParse.relatedOf(relatedDoc)
        assertEquals(11, related.size)
        assertEquals(
            "https://www.film1k.com/coffin-full-of-dollars-1971.html",
            related.first().selectFirst("a")?.attr("href")
        )
        assertEquals("Coffin Full of Dollars (1971)", related.first().selectFirst("h2.entry-title")?.text())
    }

    @Test fun `related cards carry same card markup as listings`() {
        // first card has the figure img data-src the listing parser uses
        assertEquals(
            "https://i.imgur.com/JOwDFtx.jpg",
            relatedDoc.let { Film1kParse.relatedOf(it) }.first()
                .selectFirst("figure img")?.attr("data-src")
        )
    }

    @Test fun `empty related section yields empty list`() {
        assertEquals(0, Film1kParse.relatedOf(Jsoup.parse("<html></html>")).size)
        assertEquals(0, Film1kParse.relatedOf(Jsoup.parse("<main><section><h3>Other</h3></section></main>")).size)
    }

    /** issue #654: fresh 2026-10-17 search-page-2 (taboo) — pagination restored on the site. */
    private val searchTabooP2Doc by lazy {
        javaClass.classLoader.getResource("film1k_search_taboo_p2.html")!!.readText()
    }

    /** issue #654: the exhausted-search 404 wrap (/page/4/?s=taboo) — NOT empty: it seeds
     *  8 article.loop-post cards, so a bare has-articles hasNext would paginate the 404s. */
    private val searchNotFoundP4Doc by lazy {
        javaClass.classLoader.getResource("film1k_search_notfound_p4.html")!!.readText()
    }

    @Test fun `search pagination url builds page 1 as plain s`() {
        assertEquals("https://www.film1k.com/?s=taboo",
            Film1kParse.searchPageUrl("https://www.film1k.com", "taboo", 1))
    }

    @Test fun `search pagination url builds page n as page-path`() {
        assertEquals("https://www.film1k.com/page/2/?s=taboo",
            Film1kParse.searchPageUrl("https://www.film1k.com", "taboo", 2))
        assertEquals("https://www.film1k.com/page/3/?s=taboo%201",
            Film1kParse.searchPageUrl("https://www.film1k.com", "taboo%201", 3))
    }

    @Test fun `search page 2 fixture parses real result cards`() {
        val cards = Film1kParse.doc(searchTabooP2Doc).select("article.loop-post")
        assertEquals(24, cards.size)
        val titles = cards.select("h2.entry-title").map { it.text() }
        assert(titles.any { it.contains("Taboo 14") })
    }

    @Test fun `page-not-found template detected on the 404 wrap`() {
        assertTrue(Film1kParse.isNotFoundTemplate(Film1kParse.doc(searchNotFoundP4Doc)))
    }

    @Test fun `real search results and video pages are not the 404 template`() {
        assertTrue(!Film1kParse.isNotFoundTemplate(Film1kParse.doc(searchTabooP2Doc)))
        assertTrue(!Film1kParse.isNotFoundTemplate(Film1kParse.doc(video558Doc)))
    }

    @Test fun `year parses from og title`() {
        assertEquals(2000, Film1kParse.yearOf("Tick Tock (2000) - Watch Free Online | Film1k"))
        assertEquals(1980, Film1kParse.yearOf("Taboo (1980) - Watch Free Online | Film1k"))
    }

    @Test fun `no year yields null`() {
        assertNull(Film1kParse.yearOf("Some Movie - Watch Free Online | Film1k"))
        assertNull(Film1kParse.yearOf(null))
    }
    // ---- issue #408: embed-markup drift (Byse no trailing slash + new turbovidhls host) ----

    @Test fun `byse code from slugless iframe markup`() {
        assertEquals("m2pahe0ccyjt", Film1kParse.byseCode(byseNoSlashDoc))
        // path form still parses (slug variant)
        assertEquals(
            "dipzme6fc8um",
            Film1kParse.byseCode("""<source src="https://film1k.xyz/e/dipzme6fc8um/bitter-honey.mp4">""")
        )
    }

    @Test fun `byse code takes the first match when several sources exist`() {
        // restored first-match semantics (as pre-#408 `Regex.find`) — a page embedding
        // multiple /e/{code} sources (e.g. trailer + feature) keeps picking the first one
        assertEquals(
            "aaa111222333",
            Film1kParse.byseCode(
                """<iframe src="https://film1k.xyz/e/aaa111222333"></iframe>
                   |""".trimMargin().plus("<source src=\"https://film1k.xyz/e/zzz999888777\">")
            )
        )
    }

    @Test fun `no byse embed yields null`() {
        assertNull(Film1kParse.byseCode("<html>nothing here</html>"))
    }

    // ---- issue #558: on-page promos must not shadow the byse player strip ----

    @Test fun `no ads-page byse embed parses null first`() {
        // tick-tock fixture: promo aside (byte ~20k of a large aside) carries the 'player.abyssplayer.com'
        // video card token — with the /e/ regex, the promo is excluded; the true byse sources
        // (source/iframe in #video-op-a) match exactly once.
        assertEquals("4wsa1vlemk0e", Film1kParse.byseCode(video558Doc))
    }

    @Test fun `no abyssplayer embed yields null url`() {
        // the tick-tock promo aside embeds 'player.abyssplayer.com/?v=SQSlSGTUy' — that promo must
        // NOT fire the abyssplayer branch (its ?v= token is not a page player embed); and the
        // fixture has NO real abyssplayer player embed. (Makes `loadLinks` fall through cleanly when no host matches.)
        assertNull(Film1kParse.abyssUrl(video558Doc))
    }

    // ---- issue #496: turbovid embed family DNS-dead (NODATA across resolvers) ----

    @Test fun `no live embed on a turbovid-only page parses nothing`() {
        // live fixture: tarzan-x page embedding only turbovidhls.com — scored dead in #496.
        // No abyss fallback either: loadLinks falls through to `return false`, no stream,
        // no exception (the pre-fix uncaught DNS throw).
        assertNull(Film1kParse.abyssUrl(turbovidDeadDoc))
    }

    @Test fun `abyss-host page parses the strip token`() {
        // commuter-husbands fixture: the page's Option-1 player IS an abyss ?v= embed under
        // <video><source> — the scoped overload keeps it (real page player, not a promo)
        val abyssDoc = javaClass.classLoader.getResource("film1k_video_558_abyss.html")!!.readText()
        assertEquals(
            "abyssplayer.com/?v=m8Zgs7nAS",
            Film1kParse.abyssUrl(Film1kParse.doc(abyssDoc))
        )
        // …and it writes NO byse match (no film1k.xyz source on the page)
        assertNull(Film1kParse.byseCode(Film1kParse.doc(abyssDoc)))
    }

    @Test fun `raw-regex abyssplayer embeds still parse from plain html`() {
        assertNull(Film1kParse.abyssUrl("<html></html>"))
        assertEquals(
            "abyssplayer.com/?v=abc123XYZ",
            Film1kParse.abyssUrl("<iframe src=\"https://abyssplayer.com/?v=abc123XYZ\"></iframe>")
        )
        // apex domain — a player.-subdomain embed still matches (the regex keys on the
        // domain, not the subdomain); the scoped overload keeps what does end here
        assertEquals(
            "abyssplayer.com/?v=LTjOBeDQK",
            Film1kParse.abyssUrl("<iframe src=\"https://player.abyssplayer.com/?v=LTjOBeDQK\"></iframe>")
        )
    }

    @Test fun `turbovid master m3u8 extracted from embed page`() {
        val url = Film1kParse.turbovidStreamUrl(turbovidEmbed)
        assertEquals("https://cdn3.turboviplay.com/data3/696f9b3d701a3/696f9b3d701a3.m3u8", url)
    }

    // ---- issue #1462: card-markup drift - the theme renamed its card classes
    // (header.entry-header / h2.entry-title -> figure.film1k-card__media /
    // h2.film1k-card__title) while keeping the article.loop-post skeleton. Live
    // fixtures 2026-11-25: homepage p1 and a video page's Related Videos block.
    private val home20261125Doc by lazy {
        javaClass.classLoader.getResource("film1k_home_20261125.html")!!.readText()
    }
    private val video20261125Doc by lazy {
        javaClass.classLoader.getResource("film1k_video_related_20261125.html")!!.readText()
    }

    @Test fun `listing cards parse from the 2026-11 card skeleton`() {
        val cards = Film1kParse.doc(home20261125Doc).select("article.loop-post")
        assertEquals(24, cards.size)
        val first = Film1kParse.cardOf(cards.first()!!)!!
        assertEquals(
            "https://www.film1k.com/the-story-of-o-the-series-submission-1992-eng-sub.html",
            first.url
        )
        assertEquals("The Story of O \u2013 the Series: Submission (1992) [Eng Sub]", first.title)
        assertEquals(
            "https://img.film1k.com/video-thumbs/the-story-of-o-the-series-submission-1992-video.webp",
            first.poster
        )
    }

    @Test fun `lazy card poster resolves to data-src on the 2026-11 skeleton`() {
        val cards = Film1kParse.doc(home20261125Doc).select("article.loop-post")
        val second = Film1kParse.cardOf(cards[1])!!
        assertTrue(second.poster.startsWith("https://img.film1k.com/video-thumbs/"))
        assertTrue(!second.poster.startsWith("data:"))
    }

    @Test fun `listing cards stop at the page-not-found wrap`() {
        // review #1621 blocker: the restored card parser also parses the WP 404 wrap
        // (8 seeded article.loop-post cards), so a has-articles hasNext would paginate
        // the tail forever. The gate — not the card parser — is what ends a listing.
        val notFound = Film1kParse.doc(searchNotFoundP4Doc)
        assertTrue(Film1kParse.isNotFoundTemplate(notFound))
        assertEquals(
            8,
            notFound.select("article.loop-post").mapNotNull { Film1kParse.cardOf(it) }.size
        )
        assertEquals(0, Film1kParse.listingCards(notFound).size)
        // …and it never suppresses a real listing surface
        assertEquals(24, Film1kParse.listingCards(Film1kParse.doc(home20261125Doc)).size)
        assertEquals(24, Film1kParse.listingCards(Film1kParse.doc(searchTabooP2Doc)).size)
    }

    @Test fun `card link falls back to the post html link when the h2 has no anchor`() {
        // third fallback branch: title text is not wrapped in a link, the post link
        // lives on the figure — still one card, not a dropped listing row
        val card = Film1kParse.cardOf(
            Jsoup.parse(
                """<article class="loop-post">
                   |  <figure><a href="/some-film-1971.html"><img src="https://i.imgur.com/x.jpg"></a></figure>
                   |  <h2>Some Film (1971)</h2>
                   |</article>""".trimMargin()
            ).selectFirst("article.loop-post")!!
        )!!
        assertEquals("/some-film-1971.html", card.url)
        assertEquals("Some Film (1971)", card.title)
        assertEquals("https://i.imgur.com/x.jpg", card.poster)
    }

    @Test fun `legacy card skeleton still parses`() {
        val legacy = Film1kParse.relatedOf(relatedDoc).first()
        val card = Film1kParse.cardOf(legacy)!!
        assertEquals("https://www.film1k.com/coffin-full-of-dollars-1971.html", card.url)
        assertEquals("Coffin Full of Dollars (1971)", card.title)
        assertEquals("https://i.imgur.com/JOwDFtx.jpg", card.poster)
    }

    @Test fun `related section header moved from h3 to h2 page-header`() {
        val related = Film1kParse.relatedOf(Film1kParse.doc(video20261125Doc))
        assertEquals(20, related.size)
        assertEquals(
            "https://www.film1k.com/fanny-hill-2007.html",
            related.first().selectFirst("a")?.attr("href")
        )
        assertEquals("Fanny Hill (2007)", related.first().selectFirst("h2")?.text())
    }

    @Test fun `turbovid master m3u8 rejects look-alike host`() {
        // page-controlled look-alike (evilturboviplay.com) must not be handed to the player
        assertNull(
            Film1kParse.turbovidStreamUrl(
                """var urlPlay = 'https://evilturboviplay.com/data3/696f9b3d701a3/696f9b3d701a3.m3u8';"""
            )
        )
    }
}
