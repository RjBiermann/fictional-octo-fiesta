package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Red→green for the Playerjs embed seam (issue #1442, FINDINGS-1442): JavGuru's searcho
 * mirror hands out `maxstream.org/embed-<id>.html`, whose Playerjs config is packed with
 * eval(p,a,c,k,e,d) — `file:"<signed m3u8>"` exists only after unpacking, so the provider's
 * own `\.m3u8` scan and every registered host row missed the stream.
 *
 * Fixture is the live capture from the run (2026-11-22); the signed URL is the exact field
 * the site served, never a re-typed sample.
 */
class PlayerjsParseTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString()

    @Test
    fun `maxstream packed playerjs config yields its signed m3u8`() {
        assertEquals(
            "https://s2.maxstream.org/hls2/02/00036/yv3xs7iymhis_n/master.m3u8" +
                "?t=SLT6Wx28siG0C1_a8dWdCJKiuULiAicU2bNnSt3Y2N0&s=1795318719&e=43200&f=182768&i=0.3",
            PlayerjsParse.streamUrl(fixture("maxstream_playerjs_embed.html"))
        )
    }

    /** Plain (unpacked) Playerjs pages are the same field without the packer. */
    @Test
    fun `plain playerjs config yields its file field`() {
        val html = """var player=new Playerjs({id:"playerjs",file:"https://cdn.example/x/master.m3u8?a=1",title:"t"});"""
        assertEquals("https://cdn.example/x/master.m3u8?a=1", PlayerjsParse.streamUrl(html))
    }

    @Test
    fun `mp4 files are accepted too`() {
        assertEquals(
            "https://cdn.example/x/v.mp4",
            PlayerjsParse.streamUrl("""new Playerjs({"file":"https://cdn.example/x/v.mp4"})""")
        )
    }

    /** Ad/deleted shells: no playerjs config at all. */
    @Test
    fun `shell without a playerjs file field yields null`() {
        assertNull(PlayerjsParse.streamUrl("<html><body>Video not found</body></html>"))
        assertNull(PlayerjsParse.streamUrl(""))
    }

    /** Non-media `file:` values (slide images, /dl?op=… endpoints) must not become a link. */
    @Test
    fun `non-media file values are declined`() {
        assertNull(PlayerjsParse.streamUrl("""new Playerjs({file:"/dl?op=get_slides&length=1"})"""))
        assertNull(PlayerjsParse.streamUrl("""new Playerjs({file:"https://cdn.example/p.jpg"})"""))
    }
}
