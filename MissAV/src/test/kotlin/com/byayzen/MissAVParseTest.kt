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
        assertEquals(listOf("Nonoura Warm"), MissAVParse.parseActors(doc))
    }

    @Test fun `genres include Av Actress link without leaking into actors`() {
        val tags = MissAVParse.parseTags(doc)
        assertEquals(6, tags.size)
        assertEquals("Av Actress", tags.last())
    }
}
