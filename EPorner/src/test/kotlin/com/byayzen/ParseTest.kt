package com.byayzen

import com.kraptor.JsonLdParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fixture: real JSON-LD fragment from a live EPorner video page
 * (/video-R7ZATY8jOpO, probe of 2026-09-11). Year must come from the
 * VideoObject uploadDate — the span.C DOM markup the old selector used is
 * gone from current pages. Parsing itself is shared JsonLdParse
 * (com.kraptor), already covered there; this pins the EPorner wiring.
 */
class ParseTest {

    private val epornerJsonLd =
        """{"@context":"https://schema.org","@type":"VideoObject","uploadDate":"2025-10-27T11:31:24+01:00","duration":"PT11H15M17S","actor":[{"@type":"Person","name":"Natasha Nice"}]}"""

    @Test fun `year from real page uploadDate`() = assertEquals(2025, JsonLdParse.year(epornerJsonLd))

    @Test fun `duration present but year absent yields null`() =
        assertNull(JsonLdParse.year("""{"@type":"VideoObject","duration":"PT11H"}"""))
}
