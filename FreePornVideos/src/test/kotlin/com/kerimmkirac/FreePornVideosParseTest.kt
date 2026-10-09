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

    private val bareDoc by lazy { doc.clone() } // mutable copies for stripping tests

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
        val d = bareDoc.apply { selectFirst("meta[property=video:duration]")?.remove() }
        assertEquals(31, FreePornVideosParse.duration(d)) // JSON-LD PT0H31M34S
    }

    @Test fun `duration absent everywhere yields null (never sentinel 0)`() {
        val d = bareDoc.apply {
            selectFirst("meta[property=video:duration]")?.remove()
            select("script[type=application/ld+json]").remove()
        }
        assertNull(FreePornVideosParse.duration(d))
    }

    @Test fun `year from json-ld uploadDate`() {
        assertEquals(2026, FreePornVideosParse.year(doc, "Blacks on Blondes - Deprived And Horny / 09.10.2026"))
    }

    @Test fun `year falls back to the title-tail date`() {
        val d = bareDoc.apply { select("script[type=application/ld+json]").remove() }
        assertEquals(2026, FreePornVideosParse.year(d, "Deprived And Horny / 09.10.2026"))
        assertNull(FreePornVideosParse.year(d, "Some Title Without A Date Suffix"))
    }

    @Test fun `the pre-536 xpaths match nothing on current live markup`() {
        // drift pin: these are exactly the dead selectors the provider shipped before #536
        assertEquals(0, doc.selectXpath("//div[contains(text(), 'Description:')]/em").size)
        assertEquals(0, doc.selectXpath("//div[contains(text(), 'Models:')]/a").size)
        assertEquals(0, doc.selectXpath("//span[contains(text(), 'Duration')]/em").size)
    }

    @Test fun `cells with empty markup yield empty lists not throw`() {
        val d = bareDoc.apply {
            selectFirst("div.block-details")?.remove()
            selectFirst("meta[property=video:duration]")?.remove()
            select("script[type=application/ld+json]").remove()
        }
        assertTrue(FreePornVideosParse.tags(d).isEmpty())
        assertTrue(FreePornVideosParse.actors(d).isEmpty())
        assertNull(FreePornVideosParse.duration(d))
        assertNull(FreePornVideosParse.year(d, ""))
    }

    @Test fun `bare title tail too short to be a year yields null`() {
        val d = bareDoc.apply { select("script[type=application/ld+json]").remove() }
        assertNull(FreePornVideosParse.year(d, "abc"))
    }
}
