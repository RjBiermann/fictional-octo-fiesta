package com.rjbiermann

import org.junit.Assert.*
import org.junit.Test

/**
 * Homepage pagination (issue #517): the "Latest" homepage row paginates via
 * /page/N/ (live-checked 2026-10-01: 24 new post ids per page, rel=next/prev).
 * Facet rows are 1-page (FINDINGS-497): page > 1 ⇒ no fetch (null), empty page.
 */
class EroticmvPaginationTest {

    @Test fun `latest homepage row paginates to page-N`() {
        assertEquals(
            "https://eroticmv.com/page/2/",
            Eroticmv.homeUrlFor("https://eroticmv.com/", 2)
        )
        assertEquals("https://eroticmv.com/", Eroticmv.homeUrlFor("https://eroticmv.com/", 1))
    }

    @Test fun `facet rows are one page`() {
        assertNull(
            Eroticmv.homeUrlFor("https://eroticmv.com/category/genre/ghost/", 2)
        )
        assertEquals(
            "https://eroticmv.com/category/genre/ghost/",
            Eroticmv.homeUrlFor("https://eroticmv.com/category/genre/ghost/", 1)
        )
    }
}
