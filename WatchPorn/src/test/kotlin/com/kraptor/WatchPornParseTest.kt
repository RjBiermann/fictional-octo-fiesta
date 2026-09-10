package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixture-driven test (issue #214: load() must populate year from JSON-LD uploadDate). */
class WatchPornParseTest {

    private val videoPage = javaClass.getResourceAsStream("/video-28447.html")!!.readBytes().decodeToString()

    // Same ldJson extraction as WatchPorn.load()
    private val ldJson = Jsoup.parse(videoPage)
        .select("script[type=application/ld+json]")
        .firstOrNull { it.data().contains("\"@type\": \"VideoObject\"") }
        ?.data()

    @Test fun `uploadDate year from live fixture`() {
        assertEquals(2022, JsonLdParse.year(ldJson))
    }

    @Test fun `null when no uploadDate`() {
        assertNull(JsonLdParse.year("""{"duration": "PT1H0M5S"}"""))
        assertNull(JsonLdParse.year(null))
    }

    @Test fun `duration from live fixture is minutes`() {
        assertEquals(60, JsonLdParse.minutes(ldJson))
    }
}
