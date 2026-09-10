package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TDD for issue #233: abyssplayer.com embed chain (shared AbyssPlayer adapter). */
class AbyssParseTest {

    private val embedHtml by lazy { javaClass.classLoader.getResource("abyss_embed.html")!!.readText() }
    private val decJson by lazy { javaClass.classLoader.getResource("abyss_encdec_result.json")!!.readText() }

    @Test fun `datas parses from embed page`() {
        val datas = AbyssParse.datasFromEmbed(embedHtml)
        assertTrue(datas!!.startsWith("eyJzbHVnIjoiTFRqT0JlRFFLIiw"))
        assertNull(AbyssParse.datasFromEmbed("<html>no player here</html>"))
    }

    @Test fun `dec-abyss result maps to playable sources`() {
        val sources = AbyssParse.sourcesFromResult(decJson)
        // 360p/720p/1080p all status:true in fixture
        assertEquals(3, sources.size)
        assertTrue(sources.all { it.url.startsWith("https://") })
        assertEquals(360, sources.first { it.url.contains("309840326") }.quality)
        assertEquals(720, sources.first { it.url.contains("1645998546") }.quality)
        assertEquals(1080, sources.first { it.url.contains("3496319387") }.quality)
    }

    @Test fun `dead sources are dropped`() {
        val dead = """{"status":200,"result":{"sources":[
            {"url":"https://a.sssrr.org/sora/1/AAA","type":"360p","status":true},
            {"url":"https://b.sssrr.org/sora/2/BBB","type":"720p","status":false}]}}"""
        assertEquals(1, AbyssParse.sourcesFromResult(dead).size)
    }

    @Test fun `garbage result yields empty list`() {
        assertEquals(0, AbyssParse.sourcesFromResult("not json").size)
        assertEquals(0, AbyssParse.sourcesFromResult("""{"status":500}""").size)
    }
}
