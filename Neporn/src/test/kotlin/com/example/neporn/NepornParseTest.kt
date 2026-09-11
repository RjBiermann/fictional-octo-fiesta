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
}
