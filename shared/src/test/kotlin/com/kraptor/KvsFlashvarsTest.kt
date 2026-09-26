package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TDD for the shared KVS flashvars grammar (audit finding 1): Neporn, Javbangers,
 * AllClassicPorn and Porntrex each hand-rolled the same unquoted-key / single-quoted-value
 * field match. Values here are copied from the live fixtures (allclassic video-6161,
 * porntrex_embed_page) — real shapes, truncated URLs stay truncated on purpose.
 */
class KvsFlashvarsTest {

    /** AllClassicPorn video-6161: object-literal form. */
    @Test fun `colon form parses value`() {
        val html = "video_url: 'https://allclassic.porn/get_file/1/a27ec3614d24205b6a12d8dbd9427720/6000/6161'"
        assertEquals("https://allclassic.porn/get_file/1/a27ec3614d24205b6a12d8dbd9427720/6000/6161",
            KvsFlashvars.field(html, "video_url"))
    }

    /** AllClassicPorn video-6161: flashvars['x'] = bracket form (issue #322, D2). */
    @Test fun `bracket form parses value`() {
        val html = "flashvars['video_url_text'] = '480p';"
        assertEquals("480p", KvsFlashvars.field(html, "video_url_text"))
    }

    @Test fun `escaped apostrophe unescapes`() {
        assertEquals("Alice, O'Brien", KvsFlashvars.field("""video_models: 'Alice, O\'Brien'""", "video_models"))
    }

    @Test fun `key prefix does not leak into suffixed keys`() {
        val html = VideoSources.shape
        // video_url must not match video_url_text / video_alt_url2
        assertEquals("https://h/get_file/a_720p.mp4", KvsFlashvars.field(html, "video_url"))
        assertNull(KvsFlashvars.field(html, "video_url2"))
    }

    @Test fun `videoSources distinct first-match per key in key order`() {
        assertEquals(
            listOf("https://h/get_file/a_720p.mp4", "https://h/get_file/b.mp4"),
            KvsFlashvars.videoSources(VideoSources.shape, listOf("video_url", "video_url", "video_alt_url"))
        )
    }

    @Test fun `blank and absent yield null`() {
        assertNull(KvsFlashvars.field("no flashvars here", "video_url"))
        assertNull(KvsFlashvars.field("video_url: ''", "video_url"))
    }

    @Test fun `porntrex-alt-url keys resolve`() {
        val html = "video_alt_url2: 'https://www.porntrex.com/get_file/28/58549fb928fd5_1080p', " +
            "video_alt_url2_text: '1080p FHD'"
        assertEquals("https://www.porntrex.com/get_file/28/58549fb928fd5_1080p", KvsFlashvars.field(html, "video_alt_url2"))
        assertEquals("1080p FHD", KvsFlashvars.field(html, "video_alt_url2_text"))
    }

    private object VideoSources {
        val shape = "video_url: 'https://h/get_file/a_720p.mp4', tonumber; " +
            "video_url_text: '720p', " +
            "video_alt_url: 'https://h/get_file/b.mp4', " +
            "video_alt_url_text: '480p'"
    }
}
