package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Red→green for the Streamwish embed seam (issue #1442, FINDINGS-1442): `streamhihi.com`
 * is jav.guru's "STREAM SB" slot and a registered streamwish mirror, but it now ships the
 * whole `sources:{file:"…"}` config eval(p,a,c,k,e,d)-packed — the adapter's raw
 * `file:"…"` regex found nothing, so the link was silently dropped.
 *
 * Fixture is the live packed script from the run (2026-11-22).
 */
class StreamwishParseTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString()

    @Test
    fun `packed streamhihi player script yields its signed m3u8`() {
        assertEquals(
            "https://woq6sdyrmxnj.tnmr.org/hls2/02/04765/5z7n2h9wcx16_h/master.m3u8" +
                "?t=PAxLqyU56X5mJgEnvDtIxfr9EL9wLSYlsReOoaqswQo&s=1795318679&e=28800&f=23830964&i=0.3&sp=0",
            StreamwishParse.streamUrl(fixture("streamhihi_packed_player.js"))
        )
    }

    /** The plain shape every other streamwish mirror still serves must keep working. */
    @Test
    fun `plain player script keeps the existing single-quote match`() {
        val script = """jwplayer("vplayer").setup({sources:[{file:"https://cdn.example/x/master.m3u8"}],""".trimIndent()
        assertEquals("https://cdn.example/x/master.m3u8", StreamwishParse.streamUrl(script))
    }

    @Test
    fun `script without a file field yields null`() {
        assertNull(StreamwishParse.streamUrl("jwplayer('vplayer').setup({});"))
        assertNull(StreamwishParse.streamUrl(""))
    }
}
