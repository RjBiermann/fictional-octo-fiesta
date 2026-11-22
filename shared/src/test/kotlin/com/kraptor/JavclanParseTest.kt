package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Red→green for the packed-`links` player family (issue #1442, FINDINGS-1442): javclan.com
 * is registered and parses `links={hls2,hls3,hls4}` out of an eval(p,a,c,k,e,d) payload,
 * but jav.guru's searcho mirror also hands out `javplaya.com` and `maxstream.org`, which
 * serve the same player on unregistered hosts — so loadExtractor dropped them.
 *
 * `javplaya` carries the identical grammar (both fixtures are live captures, 2026-11-22);
 * `maxstream` does not (see PlayerjsParseTest), so it gets its own adapter.
 */
class JavclanParseTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString()

    @Test
    fun `javclan embed keeps its hls2 and hls4 streams`() {
        val streams = JavclanParse.streams(
            fixture("javclan_packed_embed.html"),
            "https://javclan.com"
        )
        assertEquals(2, streams.size)
        assertTrue(
            streams[0].startsWith(
                "https://54pkdcyxbsxbermn.premilkyway.com/hls2/01/15291/etc4wn4yhgc3_,l,n,h,.urlset/master.m3u8"
            )
        )
        assertEquals(
            "https://javclan.com/stream/8z_L_B_7DEIyPvFH-puZ-A/kjhhiuahiuhgihdf/1795361902/76455396/master.m3u8",
            streams[1]
        )
    }

    /** hls3 always 403s (documented on the adapter) — it must never be emitted. */
    @Test
    fun `hls3 is never emitted`() {
        val streams = JavclanParse.streams(fixture("javclan_packed_embed.html"), "https://javclan.com")
        assertTrue(streams.none { it.contains("hls3") || it.endsWith(".txt") })
    }

    @Test
    fun `javplaya embed parses through the same seam with its own host`() {
        val streams = JavclanParse.streams(
            fixture("javplaya_packed_embed.html"),
            "https://javplaya.com"
        )
        assertEquals(2, streams.size)
        assertTrue(
            streams[0].startsWith(
                "https://WPvsAhSVL0YO.dramiyos-cdn.com/hls2/01/00459/ihqv1gew0j6e_l/master.m3u8"
            )
        )
        assertEquals(
            "https://javplaya.com/stream/ihD6YiwEqKhAfGfgFe-qEg/hjkrhuihghfvu/1795361921/2299960/master.m3u8",
            streams[1]
        )
    }

    @Test
    fun `packed payload without a links object yields nothing`() {
        assertEquals(emptyList<String>(), JavclanParse.streams("<html>nothing here</html>", "https://javclan.com"))
    }
}
