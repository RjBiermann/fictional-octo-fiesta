package com.rjbiermann

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** Tags parse from the watch-page categories block (fixture: tokyo-nights-2025, 2026-10-09).
 *  Issue #538: JSON-LD articleSection is gone from VideoObject — tags must come from
 *  `.categories-wrap a.category-item`, scoped to the post header, not the nav. */
class EroticmvTagsTest {

    // Full fresh page captured with the nav intact: the header block must win over nav,
    // and the nav's /category/ links must contribute nothing.
    private val doc = Jsoup.parse(
        javaClass.getResourceAsStream("/watch-tokyo-nights-2025.html")!!
            .readBytes().decodeToString()
    )

    @Test fun `parses tags from categories block`() {
        assertEquals(
            listOf("2020s", "Asian Erotica", "Philippines", "Swinging", "Threesome"),
            Eroticmv.parseTags(doc)
        )
    }

    @Test fun `page without categories block yields empty list`() {
        assertEquals(0, Eroticmv.parseTags(Jsoup.parse("<html><body><p>hi</p></body></html>")).size)
    }
}
