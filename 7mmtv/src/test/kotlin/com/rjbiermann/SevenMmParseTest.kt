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

    // --- listing / search cards ---

    @Test fun `search page cards parse`() {
        val doc = Jsoup.parse(fixture("search-all.html"))
        val cards = SevenMm.cards(doc)
        assertTrue(cards.size >= 8)
        assertTrue(cards.all { it.href.contains("_content/") && it.title.isNotBlank() })
        com.kraptor.DistinctBar.assertDistinctVideos(
            cards.map { com.kraptor.DistinctBar.VideoIdentity(it.title, it.poster, it.href) })
    }
}
