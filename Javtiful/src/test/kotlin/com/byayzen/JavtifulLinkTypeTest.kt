package com.byayzen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0005: stream type detection. Javtiful streams are mp4 at extensionless
 *  URLs (fast-stream.jav.si/p/<hex>, MIME video/mp4) — extension sniffing flagged
 *  them M3U8 and ExoPlayer failed with parsing_manifest_malformed (issue #240). */
class JavtifulLinkTypeTest {

    @Test fun `extensionless mp4 with video-mp4 mime is not HLS`() {
        assertFalse(isHls("https://fast-stream.jav.si/p/57a971d0-8c8820cc", "video/mp4"))
    }

    @Test fun `mpegurl mime is HLS`() {
        assertTrue(isHls("https://fast-stream.jav.si/p/abc", "application/x-mpegurl"))
        assertTrue(isHls("https://fast-stream.jav.si/p/abc", "application/vnd.apple.mpegurl"))
    }

    @Test fun `mpegurl mime is case-insensitive`() {
        assertTrue(isHls("https://host/p/abc", "Application/X-MPEGURL"))
    }

    @Test fun `m3u8 extension is HLS even without mime`() {
        assertTrue(isHls("https://host/hls/stream.m3u8", null))
    }

    @Test fun `mp4 extension without mime is not HLS`() {
        assertFalse(isHls("https://host/video.mp4", null))
    }
}
