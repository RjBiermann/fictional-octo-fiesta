package com.rjbiermann

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 flashvars fallback branch (TDD-first, ADR-0005).
 *
 * The grammar (legacy `video_url: '…'`, the `<key>_text` caption, the video.js cfg `videoUrl`
 * spelling) belongs to the shared, fixture-tested [com.kraptor.KvsFlashvars] module — these
 * tests pin only what the provider adds on top: the `function/0/` unwrap, the
 * `video_alt_url_redirect` skip and the resolution mapping.
 *
 * Live evidence: on this site every flashvars URL is dead — the `*_trim.mp4` files answer 404
 * (3/3 formats, 2026-11-27) — which is why the download chain is the effective resolver.
 * [YesPornVip.loadLinks] probes each candidate before emitting it, so the branch can only ever
 * add a working link.
 */
class FlashvarsTest {

    private fun html(name: String): String =
        javaClass.classLoader.getResourceAsStream(name)!!.readBytes().decodeToString()

    @Test
    fun detailPageYieldsThreeSourcesInQualityOrder() {
        val sources = YesPornVipParse.flashvars(html("video-76685.html"))

        assertEquals(listOf(480, 720, 1080), sources.map { it.quality })
        // `function/0/` is a player prefix in front of an absolute URL: unwrapped, not resolved
        // against the site root (which would be a different, still-404 path).
        assertTrue(sources.all { it.url.startsWith("https://yesporn.vip/get_file/5/") })
        assertTrue(sources[0].url.endsWith("/76000/76685/76685_trim.mp4/"))
        assertTrue(sources[1].url.endsWith("/76000/76685/76685_720p_trim.mp4/"))
        // Query tail stays verbatim: this one carries the bitrate marker.
        assertTrue(sources[2].url.endsWith("/76000/76685/76685_1080p_trim.mp4/?br=3125"))
    }

    @Test
    fun aRedirectVariantIsNotAMediaSource() {
        // The embed player serves its 720p as the *page* URL with the KVS redirect flag; without
        // the skip it would pass a liveness probe (200 HTML) and ship a page as a stream.
        val page = """
            <script>
            var flashvars = {
                video_url: 'function/0/https://yesporn.vip/get_file/5/aaa/76000/76685/76685.mp4/?embed=true',
                video_url_text: '480p',
                video_alt_url: 'https://yesporn.vip/video/76685/the-threesome-tutorial-fhd-2av635/',
                video_alt_url_text: '720p',
                video_alt_url_redirect: '1',
                video_url_alt_text: '',
            };
            </script>
        """.trimIndent()

        val sources = YesPornVipParse.flashvars(page)

        assertEquals(listOf("https://yesporn.vip/get_file/5/aaa/76000/76685/76685.mp4/?embed=true"), sources.map { it.url })
        assertEquals(listOf(480), sources.map { it.quality })
    }

    @Test
    fun theCfgGrammarIsDelegatedToo() {
        // Issue #1316's drift: a player that ships only the video.js cfg object. KvsFlashvars
        // resolves it, so the provider inherits the fix instead of re-parsing.
        val cfg = """
            <script>
            var cfg = { videoUrl: 'https://yesporn.vip/get_file/5/bbb/76000/76685/76685_1080p.mp4/?v-acctoken=1' };
            </script>
        """.trimIndent()

        val sources = YesPornVipParse.flashvars(cfg)

        assertEquals(listOf("https://yesporn.vip/get_file/5/bbb/76000/76685/76685_1080p.mp4/?v-acctoken=1"), sources.map { it.url })
        assertEquals(listOf(1080), sources.map { it.quality })
    }
}
