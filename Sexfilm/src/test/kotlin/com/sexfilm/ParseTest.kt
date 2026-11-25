package com.sexfilm

import com.kraptor.PackedJs
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are real page fragments (block parameters-info + div.flist + meta tags). */
class ParseTest {

    private fun doc() =
        Jsoup.parse(javaClass.getResourceAsStream("/classy-page-fragment.html")!!.reader().readText(), "https://en.sex-film.biz/")

    @Test fun `year parses from parameters-info`() {
        assertEquals(2024, Parse.year(doc()))
    }

    @Test fun `actors parse from Casting row`() {
        assertEquals(listOf("Amateur"), Parse.actors(doc()))
    }

    @Test fun `tags come from the Genre anchor row, not the 1-tag meta`() {
        assertEquals(
            listOf("All Sex", "Teens", "Couples", "Cumshots", "International", "Lingerie", "Stockings", "Tattoos"),
            Parse.tags(doc())
        )
    }

    @Test fun `meta-tag fallback when no Genre row`() {
        val d = Jsoup.parse("""<meta itemprop="genre" content="A&#160;,&#160;B">""")
        assertEquals(listOf("A", "B"), Parse.tags(d))
    }

    private fun embedsHtml() =
        javaClass.getResourceAsStream("/classy-embeds.html")!!.reader().readText()

    // #1459: part of the catalogue's filmcdm.top/e/<id> embeds now 302 to the
    // vsembed.com SPA player, whose JW config the packed-JS branch cannot parse —
    // the shared vsembed registry row resolves its per-tier signed mp4s instead,
    // so addSource dispatches that redirect target through loadExtractor.
    @Test fun `vsembed redirect target is dispatched to the shared row`() {
        assertEquals("https://vsembed.com/r0hg", Parse.vsembedRedirect("https://vsembed.com/r0hg"))
        assertEquals("https://vsembed.com/6ulj9", Parse.vsembedRedirect(" https://vsembed.com/6ulj9 "))
        assertEquals("https://vsembed.com/uk6g?foo=1", Parse.vsembedRedirect("https://vsembed.com/uk6g?foo=1"))
        // the embed host itself stays on the packed-JS path
        assertEquals(null, Parse.vsembedRedirect("https://filmcdm.top/e/yztkcwckgqn1"))
        // no lookalike host, no non-http scheme
        assertEquals(null, Parse.vsembedRedirect("https://vsembed.com.evil.example/r0hg"))
        assertEquals(null, Parse.vsembedRedirect("https://notvsembed.com/r0hg"))
        assertEquals(null, Parse.vsembedRedirect("javascript:alert(1)"))
        assertEquals(null, Parse.vsembedRedirect(null))
    }

    @Test fun `embeds collects filmcdm and morencius, not cloudflare-blocked playmogo`() {
        assertEquals(
            listOf("https://filmcdm.top/e/9kvptxttubh8", "https://morencius.com/embed/tg373qlpcs6v"),
            Parse.embeds(embedsHtml())
        )
    }

    // morencius embed ships the same Dean-Edwards packed JW config as filmcdm;
    // addSource unpacks it — relative hls4 resolves against the embed host.
    @Test fun `morencius packed-JW embed yields master m3u8 links`() {
        val html = javaClass.getResourceAsStream("/com/sexfilm/morencius-embed.html")!!.reader().readText()
        val unpacked = PackedJs.unpack(html) ?: html
        val links = Regex(""""hls\d":"([^"]*master\.m3u8[^"]*)"""").findAll(unpacked)
            .map { it.groupValues[1] }.toList()
        assertTrue(links.any { it.startsWith("/stream/") })
        assertTrue(links.any { it.startsWith("https://") && it.contains("acek-cdn") })
    }

    @Test fun `missing rows yield empty or null`() {
        val d = Jsoup.parse("<div></div>")
        assertEquals(null, Parse.year(d))
        assertEquals(emptyList<String>(), Parse.actors(d))
        assertEquals(emptyList<String>(), Parse.tags(d))
    }

    // issue #493 poster: og:image is the proven live selector; when a page omits it
    // (or leaves the content blank), the load page's own full-size image anchor inside
    // the description (DLE TBegin/highslide) carries the same URL
    @Test fun `poster prefers og-image meta and falls back to highslide anchor`() {
        // both sources live → og:image wins (fixture: og:image + div#s-desc highslide)
        assertEquals(
            "https://sex-empire.org/uploads/posts/2026-09/classy.webp",
            Parse.poster(doc())
        )
        // og:image absent → highslide anchor inside div#s-desc
        val d = Jsoup.parse(
            """<div id="s-desc"><a href="https://sex-empire.org/uploads/posts/2026-09/classy.webp" class="highslide">""" +
                """<img data-src="https://sex-empire.org/uploads/posts/2026-09/thumbs/classy.webp" alt=""></a></div>"""
        )
        assertEquals(
            "https://sex-empire.org/uploads/posts/2026-09/classy.webp",
            Parse.poster(d)
        )
        // og:image present but content blank → fallback fires
        val blank = Jsoup.parse(
            """<meta property="og:image" content="">""" +
                """<div id="s-desc"><a href="https://sex-empire.org/uploads/posts/2026-09/classy.webp" class="highslide"></a></div>"""
        )
        assertEquals(
            "https://sex-empire.org/uploads/posts/2026-09/classy.webp",
            Parse.poster(blank)
        )
        // neither → no poster
        assertEquals(null, Parse.poster(Jsoup.parse("<div></div>")))
    }

    // DLE search pagination: page 1 without search_start, page N appends &search_start=N;
    // raw spaces in the query break the request (HTTP 000), so story= must stay encoded
    @Test fun `search url paginates via search_start and encodes query`() {
        assertEquals(
            "https://en.sex-film.biz/index.php?do=search&subaction=search&story=milf+hd",
            Parse.searchUrl("https://en.sex-film.biz", " milf hd ", 1)
        )
        assertEquals(
            "https://en.sex-film.biz/index.php?do=search&subaction=search&story=milf&search_start=2",
            Parse.searchUrl("https://en.sex-film.biz", "milf", 2)
        )
        assertEquals(
            "https://en.sex-film.biz/index.php?do=search&subaction=search&story=milf&search_start=5",
            Parse.searchUrl("https://en.sex-film.biz", "milf", 5)
        )
    }
}
