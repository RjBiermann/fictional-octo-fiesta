package com.rjbiermann

import com.kraptor.CardFields
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TDD for the 7mmtv Parse functions (fixtures: live HTML, issue #452 probe 2026-09-22). */
class SevenMmParseTest {

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().decodeToString()

    // --- mvarr server rows: 7-bit-binary + XOR 23 garble -> base64 -> AES-CBC(page key) ---

    @Test fun `mvarr rows decode to embed urls and play ids`() {
        val doc = Jsoup.parse(fixture("mism456-video.html"))
        val rows = SevenMm.serverRows(doc)
        assertEquals(4, rows.size)
        val urls = rows.map { it.src }
        assertTrue(urls.contains("https://mmsi02.com/e/xnozpqs7xev1"))
        assertTrue(urls.contains("https://mmvh02.com/v/1pei47qtonvk"))
        assertTrue(rows.any { it.src.startsWith("https://7mmtv.sx/assets/js/play/play.php?id=FI_") })
        // emturbovid row: decoded value IS the full 7mmtv iframe chain url
        assertTrue(rows.any { it.src.startsWith("https://7mmtv.sx/en/censored_iframeencrypteda/0") })
    }

    @Test fun `streamwish-only page still decodes (amateur group)`() {
        val rows = SevenMm.serverRows(
            Jsoup.parse(fixture("am134651-video.html"))
        )
        assertEquals(1, rows.size)
        assertEquals("https://mmsi02.com/e/jmtki9z9081m", rows[0].src)
    }

    @Test fun `no mvarr rows yields empty, never throws`() {
        val rows = SevenMm.serverRows(Jsoup.parse("<html><body>x</body></html>"))
        assertTrue(rows.isEmpty())
    }

    @Test fun `row kinds are classified per prefix`() {
        val rows = SevenMm.serverRows(Jsoup.parse(fixture("mism456-video.html")))
        assertEquals(2, rows.count { it.kind == SevenMm.Kind.EMBED })
        assertEquals(1, rows.count { it.kind == SevenMm.Kind.TURBOVID })
        assertEquals(1, rows.count { it.kind == SevenMm.Kind.PLAY })
    }

    // --- play.php player page: direct m3u8 videoSources ---

    @Test fun `playSources extracts deduped m3u8 list`() {
        val sources = SevenMm.playSources(Jsoup.parse(fixture("play-sources.html")))
        assertEquals(2, sources.size)
        assertTrue(sources.all { it.contains(".m3u8") })
        assertTrue(sources[0] != sources[1])
    }

    @Test fun `playSources empty on junk`() {
        assertTrue(SevenMm.playSources(Jsoup.parse("<p>nope</p>")).isEmpty())
    }

    // --- video page meta ---

    private fun meta(name: String) =
        SevenMm.meta(Jsoup.parse(fixture(name)))

    @Test fun `title from h1`() =
        assertEquals(
            "MISM-456 Fabulous Deep Throat - Japanese Women Are Beautiful - Tsubaki Hanagoromo, Aoi Aoi, Suzu Yuki",
            meta("mism456-video.html").title)

    @Test fun `year from date span`() = assertEquals(2026, meta("mism456-video.html").year)

    @Test fun `duration from 分 clock in minutes`() = assertEquals(203, meta("mism456-video.html").duration)

    @Test fun `tags from categories anchors`() {
        val tags = meta("mism456-video.html").tags
        assertTrue(tags.contains("Orgy") && tags.contains("Hi-Def") && tags.size == 7)
    }

    @Test fun `actors from idol anchors`() {
        val actors = meta("mism456-video.html").actors
        assertEquals(listOf("Flower Clothing", "Yuuki Suzu", "Aoi Ai"), actors)
    }

    @Test fun `plot from film introduction article`() =
        assertTrue(meta("mism456-video.html").plot!!.contains("deep throat festival"))

    @Test fun `poster from main cover`() =
        assertEquals("https://n1.1024cdn.sx/censored/b/438500_MISM-456.jpg", meta("mism456-video.html").poster)

    @Test fun `absent fields fall back null-safe`() {
        val m = SevenMm.meta(Jsoup.parse("<html><body>empty</body></html>"))
        assertNull(m.year); assertNull(m.duration); assertNull(m.plot)
        assertTrue(m.actors.isEmpty() && m.tags.isEmpty())
    }

    // --- related cards on the video page ---

    @Test fun `related cards parse via SearchCard and are distinct`() {
        val doc = Jsoup.parse(fixture("mism456-video.html"))
        val recs = SevenMm.recs(doc)
        assertEquals(8, recs.size)
        assertTrue(recs.all { it.href.contains("_content/") })
        assertTrue(recs.none { it.href.contains("206177") })   // never the video itself
        com.kraptor.DistinctBar.assertDistinctVideos(
            recs.map { com.kraptor.DistinctBar.VideoIdentity(it.title, it.poster, it.href) })
    }

    // --- TURBOVID chain: iframeencrypteda page → wrapped iframeencryptedb → m3u8 ---

    @Test fun `turbovidWrapped resolves absolute and protocol-relative iframe srcs`() {
        // live page (uncensored group, 2026-09-22): src='https://…' — the //heuristic misses it
        assertEquals(
            "https://7mmtv.sx/en/uncensored_iframeencryptedb/0/0/40.html",
            SevenMm.turbovidWrapped(
                "<iframe src='https://7mmtv.sx/en/uncensored_iframeencryptedb/0/0/40.html'></iframe>"))
        SevenMm.turbovidWrapped(fixture("turbovid-chain-a.html")).let { w ->
            assertTrue(w!!.startsWith("https://7mmtv.sx/en/uncensored_iframeencryptedb"))
        }
        assertNull(SevenMm.turbovidWrapped("<iframe src='https://other.com/e/x'></iframe>"))
    }

    @Test fun `turbovidM3u8 prefers urlPlay, falls back to data-hash`() {
        val page = Jsoup.parse(fixture("turbovid-chain-b.html")).html()
        assertTrue(SevenMm.turbovidM3u8(page)!!.contains(".m3u8"))
        assertEquals("https://cdn4.turboviplay.com/data1/x/x.m3u8",
            SevenMm.turbovidM3u8("<div data-hash=\"https://cdn4.turboviplay.com/data1/x/x.m3u8\"></div>"))
        assertNull(SevenMm.turbovidM3u8("<p>nope</p>"))
    }

    // --- listing / search cards, dedupe ---

    @Test fun `search page cards parse`() {
        val doc = Jsoup.parse(fixture("search-all.html"))
        val cards = SevenMm.cards(doc)
        assertTrue(cards.size >= 8)
        assertTrue(cards.all { it.href.contains("_content/") && it.title.isNotBlank() })
        com.kraptor.DistinctBar.assertDistinctVideos(
            cards.map { com.kraptor.DistinctBar.VideoIdentity(it.title, it.poster, it.href) })
    }

    /** Cross-pipeline repeats: MISM-456 appears at 206177 (censored) and 107156 — different
     *  ids and poster urls (jpg vs webp), same movie code in the href. Poster-key dedupe
     *  misses the jpg/webp pair; the movie-key dedupe collapses all repeats. */
    @Test fun `cards dedupe collapses cross-pipeline repeats by movie code, not poster`() {
        val doc = Jsoup.parse(fixture("search-all.html"))
        val cards = SevenMm.cards(doc)
        assertEquals(1, cards.count { it.href.endsWith("/MISM-456.html") })
        // the two repeats really do carry different poster urls — the old key was blind to this
        val repeats = com.kraptor.SearchCard.homeCards(doc, "div.video", "h3.video-title a")
            .filter { it.href.endsWith("/MISM-456.html") }
        assertEquals(2, repeats.size)
        assertTrue(repeats[0].poster != repeats[1].poster)
        // key derivation itself
        assertEquals("MISM-456", SevenMm.movieKey("https://7mmtv.sx/en/censored_content/206177/MISM-456.html"))
        assertEquals("134651", SevenMm.movieKey("https://7mmtv.sx/en/amateur_content/134651/content.html"))
    }

    /** Uncensored + reducing-mosaic groups: per-page key/IV pairing (first-alias heuristic)
     *  verified against live fixtures from those groups (issue's own example pages). */
    @Test fun `mvarr rows decode on uncensored and reducing-mosaic fixtures`() {
        val fc2 = SevenMm.serverRows(Jsoup.parse(fixture("fc24979713-video.html")))
        assertEquals(9, fc2.size)
        assertEquals(2, fc2.count { it.kind == SevenMm.Kind.PLAY })
        assertEquals(1, fc2.count { it.kind == SevenMm.Kind.TURBOVID })
        assertEquals(6, fc2.count { it.kind == SevenMm.Kind.EMBED })
        assertTrue(fc2.any { it.src == "https://mmsi02.com/e/qrdmwnvj3mjx" })
        assertTrue(fc2.any { it.src == "https://mmvh02.com/v/g2wchzd1bfae" })
        assertTrue(fc2.any { it.src.startsWith("https://7mmtv.sx/en/uncensored_iframeencrypteda/") })

        val jufe = SevenMm.serverRows(Jsoup.parse(fixture("jufe321-video.html")))
        assertEquals(2, jufe.size)
        assertEquals(1, jufe.count { it.kind == SevenMm.Kind.PLAY })
        assertEquals(1, jufe.count { it.kind == SevenMm.Kind.TURBOVID })
        assertTrue(jufe.any { it.src.startsWith("https://7mmtv.sx/en/reducing-mosaic_iframeencrypteda/") })
    }
}
