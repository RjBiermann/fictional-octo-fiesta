package com.byayzen

import org.junit.Assert.assertEquals
import org.junit.Test

/** ADR-0005: page-URL builder must join with & when the row URL already has a query string. */
class JavtifulPagingUrlTest {

    @Test fun `sorted row page 2 uses ampersand`() {
        assertEquals(
            "https://javtiful.com/videos?sort=most_viewed&page=2",
            pagedUrl("https://javtiful.com/videos?sort=most_viewed", 2)
        )
    }

    @Test fun `plain row page 2 uses question mark`() {
        assertEquals(
            "https://javtiful.com/videos?page=2",
            pagedUrl("https://javtiful.com/videos", 2)
        )
    }

    @Test fun `page 1 returns url unchanged`() {
        assertEquals(
            "https://javtiful.com/videos?sort=top_rated",
            pagedUrl("https://javtiful.com/videos?sort=top_rated", 1)
        )
    }
}
