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

    // ---- video.js cfg form (issue #1316, S1 of #876) --------------------------

    /** AllClassicPorn video-205: the player moved off inline flashvars into a video.js
     *  `cfg` object; the stream is the camelCase `videoUrl` entry. Other `*Url` entries
     *  (poster/timeline) sit in the same block and must never be mistaken for the source. */
    private object Cfg {
        val url = "https://cdn3.allclassic.porn/get_file/1/9f8e/6000/205/205_480p.mp4"
        val shape = "var cfg = { videoUrl: '$url', " +
            "posterUrl: '/contents/videos_screenshots/0/205/preview.jpg', " +
            "timelineUrl: 'https://cdn3.allclassic.porn/timeline/205.vtt', skin: 'x' };"
        /** caption present, URL has no `_NNNp` filename token */
        val captionShape = "var cfg = { videoUrl: 'https://cdn3.allclassic.porn/get_file/1/9f8e/6000/205/205', " +
            "videoUrlText: '720p HD' };"
    }

    @Test fun `cfg camelCase colon form parses value`() {
        assertEquals(Cfg.url, KvsFlashvars.source(Cfg.shape))
    }

    @Test fun `cfg form survives surrounding js noise`() {
        val html = "<script>var player = videojs('vid'); var cfg = {videoUrl: 'https://h/cfg.mp4'," +
            " controls: true}; player.cfg(cfg);</script>"
        assertEquals("https://h/cfg.mp4", KvsFlashvars.source(html))
    }

    @Test fun `cfg escaped apostrophe unescapes`() {
        assertEquals("https://h/it's_480p.mp4",
            KvsFlashvars.source("""var cfg = { videoUrl: 'https://h/it\'s_480p.mp4' };"""))
    }

    /** Legacy match wins when a page carries both grammars (additive change). */
    @Test fun `legacy grammar wins over cfg when both present`() {
        val html = "video_url: 'https://h/legacy.mp4', var cfg = { videoUrl: 'https://h/cfg.mp4' };"
        assertEquals("https://h/legacy.mp4", KvsFlashvars.source(html))
        assertEquals(listOf("https://h/legacy.mp4"), KvsFlashvars.videoSources(html))
    }

    /** `field` itself is untouched: the multi-grammar match lives in the resolver. */
    @Test fun `field stays single-grammar for cfg pages`() {
        assertNull(KvsFlashvars.field(Cfg.shape, "video_url"))
    }

    /** Prefix safety: `videoUrl` must never match `videoUrlText` / `posterUrl` / `timelineUrl`. */
    @Test fun `cfg key prefix does not leak into neighbouring url keys`() {
        assertNull(KvsFlashvars.source(
            "var cfg = { videoUrlText: '480p', posterUrl: 'p.jpg', timelineUrl: 't.vtt' };"))
        // …and the real key still wins when it sits among those neighbours.
        assertEquals(Cfg.url, KvsFlashvars.source(Cfg.shape))
    }

    @Test fun `cfg blank and absent yield null`() {
        assertNull(KvsFlashvars.source("var cfg = { videoUrl: '' };"))
        assertNull(KvsFlashvars.source("var cfg = { controls: true };"))
        assertNull(KvsFlashvars.source(""))
        assertNull(KvsFlashvars.quality("var cfg = { videoUrl: '' };"))
    }

    @Test fun `cfg alt keys resolve through camelCase`() {
        assertEquals("https://h/alt.mp4",
            KvsFlashvars.source("var cfg = { videoAltUrl: 'https://h/alt.mp4' };", "video_alt_url"))
        assertEquals("https://h/alt2.mp4",
            KvsFlashvars.source("var cfg = { videoAltUrl2: 'https://h/alt2.mp4' };", "video_alt_url2"))
    }

    @Test fun `cfg sources feed videoSources in key order`() {
        assertEquals(listOf(Cfg.url), KvsFlashvars.videoSources(Cfg.shape))
    }

    @Test fun `quality derives from the url filename token`() {
        assertEquals("480p", KvsFlashvars.quality(Cfg.shape))
        assertEquals("1080p", KvsFlashvars.quality("video_url: 'https://h/get_file/28/hash_1080p'"))
    }

    @Test fun `quality falls back to the legacy caption`() {
        assertEquals("720p HD", KvsFlashvars.quality(Cfg.captionShape))
        assertEquals("1080p FHD",
            KvsFlashvars.quality("video_url: 'https://h/get_file/1/x', video_url_text: '1080p FHD'"))
    }

    @Test fun `quality null when neither filename token nor caption exists`() {
        assertNull(KvsFlashvars.quality("video_url: 'https://h/get_file/1/a27ec3614d24205b6a12d8dbd9427720/6000/6161'"))
        assertNull(KvsFlashvars.quality("var cfg = { videoUrl: 'https://h/get_file/1/6161' };"))
    }
}
