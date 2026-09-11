package com.byayzen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.kraptor.JsonLdParse

class ParseTest {

    private val epornerJsonLd =
        """{"@context":"https://schema.org","@type":"VideoObject","uploadDate":"2025-10-27T11:31:24+01:00","duration":"PT11H15M17S","actor":[{"@type":"Person","name":"Natasha Nice"}]}"""

    @Test fun `year from real page uploadDate`() = assertEquals(2025, JsonLdParse.year(epornerJsonLd))

    @Test fun `duration present but year absent yields null`() =
        assertNull(JsonLdParse.year("""{"@type":"VideoObject","duration":"PT11H"}"""))

    // --- mainPage page-2 URL construction (issue #293) ---

    @Test fun `segment style lists swap segment order on page 2`() {
        // 301 -> base for /most-viewed/2/ and /longest/2/ (probe 2026-09-11, #293);
        // the site paginates these as /2/<list>/ (200, fresh cards)
        assertEquals("https://www.eporner.com/2/most-viewed/", EPornerParse.pageUrl("https://www.eporner.com/most-viewed/", 2))
        assertEquals("https://www.eporner.com/3/longest/", EPornerParse.pageUrl("https://www.eporner.com/longest/", 3))
    }

    @Test fun `other rows keep path-suffix pagination`() {
        assertEquals("https://www.eporner.com/2/", EPornerParse.pageUrl("https://www.eporner.com/", 2))
        assertEquals("https://www.eporner.com/top-rated/2/", EPornerParse.pageUrl("https://www.eporner.com/top-rated/", 2))
        assertEquals("https://www.eporner.com/tag/cowgirl/2/", EPornerParse.pageUrl("https://www.eporner.com/tag/cowgirl/", 2))
        assertEquals("https://www.eporner.com/cat/housewives/2/", EPornerParse.pageUrl("https://www.eporner.com/cat/housewives/", 2))
    }

    @Test fun `page one is the request url untouched`() =
        assertEquals("https://www.eporner.com/most-viewed/", EPornerParse.pageUrl("https://www.eporner.com/most-viewed/", 1))

    @Test fun `unknown top-level segments do not swap`() =
        assertEquals("https://www.eporner.com/search/foo/2/", EPornerParse.pageUrl("https://www.eporner.com/search/foo/", 2))
}
