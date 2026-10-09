package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #518: video details page showed no poster (black) on plain/library loads because
 * the video page embeds no image of the current video. The player embed carries the
 * video's own cover — hqwo iframe src: img=<b64>; mydaddy embed body: poster="//…".
 * Fixtures are real probe captures (HQPorner/FINDINGS-518.md).
 */
class PlayerPosterParseTest {

    private fun fixture(name: String) = javaClass.classLoader
        ?.getResourceAsStream("issue518/$name")?.bufferedReader().use { it?.readText() } ?: ""

    @Test fun `hqwo iframe src img param decodes to the video cover`() {
        val src = fixture("hqwo_iframe_src.txt").trim()
        assertEquals(
            "https://hqporner.com/imgs/thumbs/14/29/b73b1d8b4f15e18_cover.jpg",
            PlayerPosterParse.fromSrc(src),
        )
    }

    @Test fun `mydaddy embed body poster= yields the normalized CDN frame`() {
        assertEquals(
            "https://s62.bigcdn.cc/pubs/6ac80831a84bc1.47204963/main.jpg",
            PlayerPosterParse.fromBody(fixture("mydaddy_player.html")),
        )
    }

    @Test fun `no img param and no poster tag yields null`() {
        assertNull(PlayerPosterParse.fromSrc("//mydaddy.cc/video/abc/"))
        assertNull(PlayerPosterParse.fromBody("<html><body>no player here</body></html>"))
    }
}
