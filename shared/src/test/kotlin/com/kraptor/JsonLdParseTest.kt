package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fixtures are real JSON-LD fragments captured in FINDINGS evidence
 * (PerverZija / WatchPorn VideoObject). CloudStream `duration` is minutes
 * (repo convention); ISO-8601 PT#H#M#S is the site-side grammar this module
 * hides. Handles plain JSON, escaped (`\"`) JSON-in-JSON, and bare tokens.
 */
class JsonLdParseTest {

    @Test fun `minutes from plain VideoObject`() {
        assertEquals(39, JsonLdParse.minutes("""{"@type":"VideoObject","duration":"PT39M59S"}"""))
        assertEquals(36, JsonLdParse.minutes("""{"duration": "PT0H36M3S"}"""))
        assertEquals(39, JsonLdParse.minutes(""""duration":"PT39M"""")) // minutes-only, no S
    }

    @Test fun `minutes from escaped JSON-in-JSON`() =
        assertEquals(39, JsonLdParse.minutes("""config\",\"duration\":\"PT39M59S\",\"more"""))

    @Test fun `seconds-only ISO token floors to minutes`() =
        assertEquals(136, JsonLdParse.minutes("PT8173S")) // Sexfilm meta tag

    @Test fun `hour minute second combinations`() {
        assertEquals(90, JsonLdParse.minutes("PT1H30M"))
        assertEquals(60, JsonLdParse.minutes("PT1H"))
        assertEquals(45, JsonLdParse.minutes("PT45M"))
    }

    @Test fun `junk and empty yield null`() {
        assertNull(JsonLdParse.minutes(null))
        assertNull(JsonLdParse.minutes("<div>no duration here</div>"))
        assertNull(JsonLdParse.minutes("""{"duration":"PT"}"""))
        assertNull(JsonLdParse.minutes("PT0M0S"))
    }

    @Test fun `year from datePublished plain`() =
        assertEquals(2022, JsonLdParse.year("""{"@type":"VideoObject","datePublished":"2022-04-14T22:50:35+02:00","duration":"PT39M59S"}"""))

    @Test fun `year from uploadDate`() =
        assertEquals(2022, JsonLdParse.year("""{"uploadDate": "2022-07-02T13:09:00Z"}"""))

    @Test fun `year from escaped JSON-in-JSON`() =
        assertEquals(2022, JsonLdParse.year("""datePublished\":\"2022-04-14"""))

    @Test fun `year null cases`() {
        assertNull(JsonLdParse.year(null))
        assertNull(JsonLdParse.year("""{"@type":"VideoObject","duration":"PT39M"}"""))
        assertNull(JsonLdParse.year("""{"datePublished":""}"""))
    }
}
