package com.rjbiermann

import org.junit.Assert.*
import org.junit.Test

/** loadLinks stream extraction: Shape A (base64 og:video:url) vs Shape B (?video_embed= page). */
class EroticmvStreamTest {

    // Shape A: og:video:url = "http://<base64>.m3u8" — token decodes to stream URL
    @Test fun `shape A base64 token decodes`() {
        val raw = "http://aHR0cHM6Ly92aWRjZG4yLmVyb3RpY212LmNvbS9kYXQxL2NyZWFtcGllMjAyNi9jcmVhbXBpZTIwMjYubTN1OA==.m3u8"
        val stream = Eroticmv.parseStreamUrl(raw, embedHtml = null)
        assertEquals("https://vidcdn2.eroticmv.com/dat1/creampie2026/creampie2026.m3u8", stream)
    }

    // Shape B: og:video:url = post URL with ?video_embed= — stream comes from the embed page's source tag
    @Test fun `shape B video_embed token uses embed page source tag`() {
        val embedHtml = """<video><source src="https://vidcdn2.eroticmv.com/dat1/gorgeousbustycurvypalemilfcuckold/gorgeousbustycurvypalemilfcuckold.m3u8"></video>"""
        val stream = Eroticmv.parseStreamUrl(
            "https://eroticmv.com/gorgeous-curvy-milf-cuckold/?video_embed=32878",
            embedHtml = embedHtml
        )
        assertEquals(
            "https://vidcdn2.eroticmv.com/dat1/gorgeousbustycurvypalemilfcuckold/gorgeousbustycurvypalemilfcuckold.m3u8",
            stream
        )
    }

    @Test fun `unsupported raw token returns null`() {
        assertNull(Eroticmv.parseStreamUrl("https://example.com/?video_embed=x", embedHtml = null))
        assertNull(Eroticmv.parseStreamUrl("garbage", embedHtml = null))
    }
}
