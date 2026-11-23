package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1448 (Cat3Movie video-source audit): the hlsfast.com → turboembed.com →
 * signed-mp4 chain. The old provider-side AES client (`/api/v1/video`) answers 404 for
 * every hash; the live player shell resolves `/#<hash>` through a public JSON API whose
 * destination host rotates. These tests pin the pure parse seams the shared adapters use.
 *
 * Fixtures are live captures from 2026-11-23 (`turbo-chain/`), tokens/URLs kept verbatim
 * where the grammar matters and placeholder-shaped where a session token would otherwise
 * bake a real one into the repo.
 */
class HlsFastParseTest {

    private fun res(name: String) =
        javaClass.getResourceAsStream("/turbo-chain/$name")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("fixture $name missing")

    @Test
    fun `hash comes from the player shell fragment`() {
        assertEquals("c5acrp", HlsFastParse.hash("https://hlsfast.com/#c5acrp"))
        assertEquals("c5acrp", HlsFastParse.hash("https://hlsfast.com/#c5acrp "))
    }

    @Test
    fun `no fragment or hostile fragment yields null`() {
        assertNull(HlsFastParse.hash("https://hlsfast.com/"))
        assertNull(HlsFastParse.hash("https://hlsfast.com/#"))
        assertNull(HlsFastParse.hash(null))
        assertNull(HlsFastParse.hash("https://hlsfast.com/#../etc/passwd"))
    }

    @Test
    fun `resolve target is the destination embed`() {
        assertEquals("https://turboembed.com/1hri", HlsFastParse.resolveTarget(res("hlsfast-resolve.json")))
    }

    @Test
    fun `non http destination is refused`() {
        assertNull(HlsFastParse.resolveTarget("""{"ok":true,"url":"javascript:alert(1)"}"""))
        assertNull(HlsFastParse.resolveTarget("""{"ok":false}"""))
        assertNull(HlsFastParse.resolveTarget("<html>maintenance</html>"))
    }

    @Test
    fun `direct media destinations skip the registry hop`() {
        assertTrue(HlsFastParse.isDirectMedia("https://cdn.example/abc/index.m3u8"))
        assertTrue(HlsFastParse.isDirectMedia("https://cdn.example/abc.mp4?token=1"))
        assertFalse(HlsFastParse.isDirectMedia("https://turboembed.com/1hri"))
    }
}

/** turboembed.com download-tier grammar (the hlsfast destination host). */
class TurboEmbedParseTest {

    private fun res(name: String) =
        javaClass.getResourceAsStream("/turbo-chain/$name")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("fixture $name missing")

    @Test
    fun `code comes from the embed path`() {
        assertEquals("1hri", TurboEmbedParse.code("https://turboembed.com/1hri"))
        assertEquals("1hri", TurboEmbedParse.code(" https://turboembed.com/1hri "))
        assertNull(TurboEmbedParse.code("https://turboembed.com/"))
        assertNull(TurboEmbedParse.code("https://not-turboembed.com/1hri"))
    }

    @Test
    fun `tiers carry the exposed quality label`() {
        val tiers = TurboEmbedParse.tiers(res("turboembed-tiers.json"))
        assertEquals(1, tiers.size)
        assertEquals("540p", tiers[0].file)
        assertEquals("540p", tiers[0].label)
    }

    @Test
    fun `dead or malformed tier payloads yield no sources`() {
        assertEquals(emptyList<TurboEmbedParse.Tier>(), TurboEmbedParse.tiers("""{"data":{"tiers":[]}}"""))
        assertEquals(emptyList<TurboEmbedParse.Tier>(), TurboEmbedParse.tiers("""{"message":"Player not found"}"""))
        assertEquals(emptyList<TurboEmbedParse.Tier>(), TurboEmbedParse.tiers("not json"))
    }
}

/** cdn.loadvid.com page-config grammar + the single-segment manifest shape. */
class LoadVidParseTest {

    private fun res(name: String) =
        javaClass.getResourceAsStream("/turbo-chain/$name")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("fixture $name missing")

    @Test
    fun `config parses csrf token and video hash`() {
        val cfg = LoadVidParse.config(res("loadvid-page.html"))
        assertEquals("EXAMPLEcsrfToken0000000000000000000", cfg?.csrf)
        assertEquals("SiBZWePhaGYvOjJwjunK", cfg?.hash)
        assertTrue(cfg!!.token.startsWith("EXAMPLEtoken"))
    }

    @Test
    fun `page without the config script yields null`() {
        assertNull(LoadVidParse.config("<html><body>no player</body></html>"))
    }

    @Test
    fun `hash comes from the play path`() {
        assertEquals("SiBZWePhaGYvOjJwjunK",
            LoadVidParse.hash("https://cdn.loadvid.com/videos/play/SiBZWePhaGYvOjJwjunK"))
        assertEquals("SiBZWePhaGYvOjJwjunK",
            LoadVidParse.hash(" https://cdn.loadvid.com/videos/play/SiBZWePhaGYvOjJwjunK "))
        assertNull(LoadVidParse.hash("https://cdn.loadvid.com/videos/play/"))
    }

    @Test
    fun `single-segment playlist yields its segment`() {
        assertEquals(
            "https://server8.stream-baby1.top/user/390/hls/0/e27e28fb-6855-417c-994b-6181a518fc4e-1793711268/vikk7at6539d16.png",
            LoadVidParse.singleSegment(res("loadvid-manifest.m3u8"))
        )
    }

    @Test
    fun `multi-segment and non-manifest bodies are dropped`() {
        val two = "#EXTM3U\n#EXTINF:10,\nhttps://cdn.example/a.png\n#EXTINF:10,\nhttps://cdn.example/b.png\n"
        assertNull(LoadVidParse.singleSegment(two))
        assertNull(LoadVidParse.singleSegment("<!DOCTYPE html><title>error</title>"))
        assertNull(LoadVidParse.singleSegment("#EXTM3U\n#EXT-X-ENDLIST\n"))
    }
}
