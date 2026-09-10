package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture: real jav.guru video meta markup (issues #210, #267). */
class JavGuruParseTest {

    private val doc by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/jav-guru-video-meta.html")!!, "UTF-8", "https://jav.guru/")
    }

    private val uncensoredDoc by lazy {
        Jsoup.parse(javaClass.getResourceAsStream("/jav-guru-uncensored-meta.html")!!, "UTF-8", "https://jav.guru/")
    }

    @Test fun `actors come from Actor and Actress rows - no Tags or Series pollution`() {
        val actors = JavGuruParse.parseActors(doc)
        assertEquals(
            listOf("Yuta Aoi", "Tyson Tsubasa", "Futaba Sara", "Akizuki Marina", "Mashiro An", "Mishima Natsuko"),
            actors
        )
    }

    /** Uncensored pages have no actor rows; Tags must not leak (regression from #210). */
    @Test fun `uncensored page without actor rows yields no actors`() {
        assertEquals(emptyList<String>(), JavGuruParse.parseActors(uncensoredDoc))
    }

    @Test fun `title from h1 dot titl`() {
        assertTrue(JavGuruParse.parseTitle(doc).startsWith("JJBK-087"))
    }
}
