package com.byayzen

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fixture: real missav.live /en/abf-384 meta rows (issue #274). */
class MissAVParseTest {

    private val doc by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/missav-video-meta.html")!!, "UTF-8", "https://missav.live/")
    }

    @Test fun `actress row only - no genre pollution`() {
        assertEquals(listOf("Nonoura Warm"), MissAVParse.parseActors(Jsoup.parse("""
            <div class="text-secondary"><span>Actress:</span><a href="#">Nonoura Warm</a></div>
            <div class="text-secondary"><span>Genre:</span><a href="#">Av Actress</a></div>""", "https://missav.live/")))
    }

    /** Site exposes both Actress: and Actor: rows (issue #328, roe-469) — both must feed addActors. */
    @Test fun `actor row is included alongside actress`() {
        assertEquals(listOf("Nonoura Warm", "Tooru Ozawa"), MissAVParse.parseActors(doc))
    }

    @Test fun `genres include Av Actress link without leaking into actors`() {
        val tags = MissAVParse.parseTags(doc)
        assertEquals(6, tags.size)
        assertEquals("Av Actress", tags.last())
    }

    /** og:video:duration is seconds; CloudStream wants minutes (issue #302, midv-852 = 7256s = 120min). */
    @Test fun `duration seconds to minutes`() {
        assertEquals(120, MissAVParse.parseDuration("7256"))
        assertEquals(2, MissAVParse.parseDuration("120"))
        assertEquals(null, MissAVParse.parseDuration("59")) // sub-minute rounds to 0 → null, like other providers
        assertEquals(null, MissAVParse.parseDuration("abc"))
        assertEquals(null, MissAVParse.parseDuration(null))
    }
}
