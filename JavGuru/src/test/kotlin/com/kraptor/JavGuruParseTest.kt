package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture: real jav.guru video meta markup (issue #210 transcript). */
class JavGuruParseTest {

    private val doc by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/jav-guru-video-meta.html")!!, "UTF-8", "https://jav.guru/")
    }

    @Test fun `actors come from Actress row only - no Tags or Series pollution`() {
        val actors = JavGuruParse.parseActors(doc)
        assertEquals(listOf("Futaba Sara", "Akizuki Marina", "Mashiro An", "Mishima Natsuko"), actors)
    }

    @Test fun `title from h1 dot titl`() {
        assertTrue(JavGuruParse.parseTitle(doc).startsWith("JJBK-087"))
    }
}
