package com.kerimmkirac

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TDD for issue #536: the site's details block was rebuilt — the Description:/Models:/
 *  Duration cells are gone; labels are now Channel / Network / Categories / Pornstars
 *  inside div.hidden_tags (label wrapped in a <span>, so the old div-text() xpaths match
 *  nothing). Duration lives only in meta[property=video:duration] (seconds) and the JSON-LD
 *  VideoObject; plot only in the og:description meta. Fixture: live video page
 *  /videos/93820265/deprived-and-horny/ fetched 2026-10-09 (TLS-impersonated curl). */
class FreePornVideosParseTest {

    private val doc by lazy {
        Jsoup.parse(javaClass.classLoader.getResource("freepornvideos_video_93820265.html")!!.readText())
    }

    /** Fresh clone per stripping test — a shared lazy clone makes strip-tests order-dependent. */
    private fun bareDoc() = doc.clone()

    @Test fun `categories parse from the renamed hidden_tags cell`() {
        assertEquals(
            listOf(
                "Anal", "Blowjob", "Cumshot", "Facial", "Handjob", "Masturbation", "Blonde",
                "Natural Tits", "Interracial", "Black", "Big Cock", "Deepthroat",
                "Ass-To-Mouth", "Gaping", "Pussy to Mouth", "Beautiful Ass"
            ),
            FreePornVideosParse.tags(doc)
        )
    }

    @Test fun `pornstars cell (renamed from Models) parses as actors`() {
        assertEquals(listOf("Jovan Jordan", "Mckenzie Mae"), FreePornVideosParse.actors(doc))
    }

    @Test fun `plot comes from og description (Description cell is gone)`() {
        assertEquals(
            "Full Length 😎 Starring Jovan Jordan and Mckenzie Mae. Resolution: 4K HD. Release date: October 09, 2026.",
            FreePornVideosParse.plot(doc)
        )
    }

    @Test fun `duration from meta video-duration seconds (1894s to floor 31min)`() {
        assertEquals(31, FreePornVideosParse.duration(doc))
    }

    @Test fun `duration falls back to json-ld PT duration when meta missing`() {
        val d = bareDoc().apply { selectFirst("meta[property=video:duration]")?.remove() }
        assertEquals(31, FreePornVideosParse.duration(d)) // JSON-LD PT0H31M34S
    }

    @Test fun `duration absent everywhere yields null (never sentinel 0)`() {
        val d = bareDoc().apply {
            selectFirst("meta[property=video:duration]")?.remove()
            select("script[type=application/ld+json]").remove()
        }
        assertNull(FreePornVideosParse.duration(d))
    }

    @Test fun `year from json-ld uploadDate`() {
        assertEquals(2026, FreePornVideosParse.year(doc, "Blacks on Blondes - Deprived And Horny / 09.10.2026"))
    }

    @Test fun `year falls back to the title-tail date`() {
        val d = bareDoc().apply { select("script[type=application/ld+json]").remove() }
        assertEquals(2026, FreePornVideosParse.year(d, "Deprived And Horny / 09.10.2026"))
        assertNull(FreePornVideosParse.year(d, "Some Title Without A Date Suffix"))
    }

    @Test fun `the pre-536 xpaths match nothing on current live markup`() {
        // drift pin: these are exactly the dead selectors the provider shipped before #536
        assertEquals(0, doc.selectXpath("//div[contains(text(), 'Description:')]/em").size)
        assertEquals(0, doc.selectXpath("//div[contains(text(), 'Models:')]/a").size)
        assertEquals(0, doc.selectXpath("//span[contains(text(), 'Duration')]/em").size)
        // Categories label lives inside a <span> now (fixture line ~1257) — the old
        // div-text() xpath is dead on current markup too (spec-issue premise was stale)
        assertEquals(0, doc.selectXpath("//div[contains(text(), 'Categories:')]/a").size)
    }

    @Test fun `cells with empty markup yield empty lists not throw`() {
        val d = bareDoc().apply {
            selectFirst("div.block-details")?.remove()
            selectFirst("meta[property=video:duration]")?.remove()
            select("script[type=application/ld+json]").remove()
        }
        assertTrue(FreePornVideosParse.tags(d).isEmpty())
        assertTrue(FreePornVideosParse.actors(d).isEmpty())
        assertNull(FreePornVideosParse.duration(d))
        assertNull(FreePornVideosParse.year(d, ""))
    }

    /** Issue #1464 (video-source leg): the video-js player carries one `<source>` per tier;
     *  get_file 302s to a signed fpvcdn.com CDN URL, so the tier list stays a pure parse seam.
     *  Fixture: /videos/93820265/ source markup (2160p / 720p / 480p). */
    @Test fun `stream sources parse from video-js source markup with quality labels`() {
        assertEquals(
            listOf(
                FreePornVideosParse.StreamSource(
                    "2160p",
                    "https://www.freepornvideos.xxx/get_file/8512/1ca965b4623ec97aaccc3ab54cb1f6e4684dad3afd/93820000/93820265/93820265_2160m.mp4/"
                ),
                FreePornVideosParse.StreamSource(
                    "720p",
                    "https://www.freepornvideos.xxx/get_file/8512/3bcec2afbbc5e2264d54412cf54b33e18211f91b33/93820000/93820265/93820265_720m.mp4/"
                ),
                FreePornVideosParse.StreamSource(
                    "480p",
                    "https://www.freepornvideos.xxx/get_file/8512/994cddd6e2900b7241bc0647f2ec0bd0a1f75e8f7b/93820000/93820265/93820265_480m.mp4/"
                )
            ),
            FreePornVideosParse.sources(doc)
        )
    }

    @Test fun `sources drop src-less mirrors and keep label-less tiers honest`() {
        val d = bareDoc().apply {
            select("video source").forEach { it.attr("label", "") }
            selectFirst("video source")?.attr("src", "")
        }
        val sources = FreePornVideosParse.sources(d)
        assertEquals(2, sources.size) // the empty-src mirror is skipped
        assertNull(sources.first().label) // never fabricate a quality
        assertEquals(3, FreePornVideosParse.sources(doc).size)
    }

    @Test fun `a removed-video page with no source elements yields no tiers`() {
        val d = bareDoc().apply { select("video source").remove() }
        assertTrue(FreePornVideosParse.sources(d).isEmpty())
    }

    @Test fun `bare title tail too short to be a year yields null`() {
        val d = bareDoc().apply { select("script[type=application/ld+json]").remove() }
        assertNull(FreePornVideosParse.year(d, "abc"))
    }
}
