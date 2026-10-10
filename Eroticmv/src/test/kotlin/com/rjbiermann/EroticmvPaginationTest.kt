package com.rjbiermann

import org.junit.Assert.*
import org.junit.Test

/**
 * Homepage pagination (issue #517): the "Latest" homepage row paginates via
 * /page/N/ (live-checked 2026-10-01: 24 new post ids per page, rel=next/prev).
 * Facet rows paginate too since late 2026 (issue #559, FINDINGS-559.md
 * 2026-10-14: /category/X/page/2/ → 200, 24 fresh cards, zero overlap;
 * past the last page → 404 ⇒ empty list, hasNext=false).
 */
class EroticmvPaginationTest {

    @Test fun `latest homepage row paginates to page-N`() {
        assertEquals(
            "https://eroticmv.com/page/2/",
            Eroticmv.homeUrlFor("https://eroticmv.com/", 2)
        )
        assertEquals("https://eroticmv.com/", Eroticmv.homeUrlFor("https://eroticmv.com/", 1))
    }

    @Test fun `facet rows paginate to page-N`() {
        assertEquals(
            "https://eroticmv.com/category/genre/ghost/page/2/",
            Eroticmv.homeUrlFor("https://eroticmv.com/category/genre/ghost/", 2)
        )
        assertEquals(
            "https://eroticmv.com/category/genre/ghost/",
            Eroticmv.homeUrlFor("https://eroticmv.com/category/genre/ghost/", 1)
        )
    }

    @Test fun `homepage row predicate is the single source of truth`() {
        assertTrue(Eroticmv.homepageRow("https://eroticmv.com/"))
        assertFalse(Eroticmv.homepageRow("https://eroticmv.com/category/genre/ghost/"))
    }
}
