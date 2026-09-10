package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #278: quickSearch via /suggest/ — fixture is the live q=deep transcript. */
class WatchPornSuggestTest {

    private val json = javaClass.getResourceAsStream("/suggest-deep.json")!!.readBytes().decodeToString()

    @Test fun `parses videos only`() {
        val results = SuggestParse.videos(json)
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.second.contains("/video/") })
    }

    @Test fun `maps a known video suggestion`() {
        val results = SuggestParse.videos(json)
        assertTrue(results.any { (title, url) ->
            title.startsWith("Alexa Payne") &&
                url == "https://watchporn.to/video/117655/alexa-payne-stretching-her-deep-milfbody/"
        })
    }

    @Test fun `skips model entries`() {
        // 20 Models + 20 Videos in this transcript; models must not appear
        val results = SuggestParse.videos(json)
        assertTrue(results.none { it.second.contains("/models/") })
        assertEquals(20, results.size)
    }
}
