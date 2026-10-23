package com.example.neporn

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fixture: raw div.info-content from https://neporn.com/video/35636/... (issue #301). */
class NepornParseTest {

    private fun doc() = Jsoup.parse(
        javaClass.getResourceAsStream("/info-content-35636.html")!!.reader().readText(),
        "https://neporn.com/"
    )

    // Regression for #301: the model anchor also holds a .button-info span with the
    // member's video count ("Marica Hase 32"); only the .name span is the actor name.
    @Test fun `actors come from the name span, without the video-count span`() {
        assertEquals(listOf("Marica Hase"), Parse.actors(doc()))
    }

    @Test fun `missing models row yields empty`() {
        assertEquals(emptyList<String>(), Parse.actors(Jsoup.parse("<div></div>")))
    }

    /** Fixture: the JSON-LD VideoObject block from
     *  https://neporn.com/video/44461/pristine-edge-s-1st-bbc/ (issue #826). */
    private fun durationDoc() = Jsoup.parse(
        javaClass.getResourceAsStream("/ldjson-duration-44461.html")!!.reader().readText(),
        "https://neporn.com/"
    )

    // Issue #826: LoadResponse.addDuration feeds getDurationFromString, which understands
    // only word forms ("20 min") — the site's colon form ("20:48") degrades to null and the
    // duration silently ships empty. JSON-LD carries the canonical ISO-8601 token.
    @Test fun `duration is minutes from the JSON-LD ISO-8601 token`() {
        assertEquals(20, Parse.durationMinutes(durationDoc()))
    }

    @Test fun `missing JSON-LD yields no duration`() {
        assertEquals(null, Parse.durationMinutes(Jsoup.parse("<div></div>")))
    }
}
