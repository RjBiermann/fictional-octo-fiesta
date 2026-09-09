package com.rjbiermann

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TDD for the card-block parser on the video page (fixture: live HTML 2026-09-09, AVOP-179). */
class JavmostParseTest {

    private fun info() = Javmost.Parse.cardBlock(
        Jsoup.parse(javaClass.classLoader.getResource("avop-179.html")!!.readText())
    )

    @Test fun `year from Release text`() = assertEquals(2015, info().year)

    @Test fun `duration in minutes from Time text`() = assertEquals(130, info().duration)

    @Test fun `actors from star anchors`() =
        assertEquals(listOf("Kana Sekikawa"), info().actors)

    @Test fun `tags from category anchors, trimmed`() = assertEquals(
        listOf("Creampie", "Solowork", "Married Woman", "Kimono", "Mourning", "Cuckold", "Hot Spring", "AV OPEN 2015 Milf Dept."),
        info().tags
    )

    @Test fun `missing fields are null or empty`() {
        val info = Javmost.Parse.cardBlock(Jsoup.parse("<div class=\"card-block\"><p>nothing here</p></div>"))
        assertNull(info.year)
        assertNull(info.duration)
        assertEquals(emptyList<String>(), info.actors)
        assertEquals(emptyList<String>(), info.tags)
    }
}
