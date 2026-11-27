package com.rjbiermann

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 download-endpoint chain (TDD-first, ADR-0005). Fixtures are the live
 * `/view_video_download.php?id=76685&format=<q>` answers (2026-11-27, mobile Chrome UA) — one
 * page per format, each carrying a single signed `a.btn.download` href.
 *
 * The query tail (`?download_filename=…&download=true[&br=…]`) is the signed part of the URL
 * and must survive verbatim: it is what makes the 302 to `cdn3.yesporn.vip` answer `206
 * video/mp4` instead of 404.
 */
class DownloadLinkTest {

    private fun html(name: String): String =
        javaClass.classLoader.getResourceAsStream(name)!!.readBytes().decodeToString()

    @Test
    fun eachFormatPageYieldsItsOwnSignedUrl() {
        val base = "https://yesporn.vip/get_file/5/"
        val u480 = YesPornVipParse.downloadLink(html("video-76685-download-480.html"), 480)!!
        val u720 = YesPornVipParse.downloadLink(html("video-76685-download-720.html"), 720)!!
        val u1080 = YesPornVipParse.downloadLink(html("video-76685-download-1080.html"), 1080)!!

        assertTrue(u480.startsWith(base))
        assertTrue(u720.startsWith(base))
        assertTrue(u1080.startsWith(base))
        // The format is the filename rung: base file for 480, `_<q>p` above it.
        assertTrue(u480.endsWith("/76000/76685/76685.mp4/?download_filename=the-threesome-tutorial-fhd-ebk44f.mp4&download=true"))
        assertTrue(u720.contains("/76685_720p.mp4/?download_filename="))
        assertTrue(u1080.contains("/76685_1080p.mp4/?download_filename="))
        // Query tail preserved verbatim, including the bitrate marker the site appends to HD.
        assertTrue(u720.endsWith("&download=true"))
        assertTrue(u1080.endsWith("&download=true&br=3320"))
    }

    @Test
    fun qualityIsReadFromTheUrlNotTheRequest() {
        assertEquals(480, YesPornVipParse.qualityOf(
            YesPornVipParse.downloadLink(html("video-76685-download-480.html"), 480)!!,
        ))
        assertEquals(720, YesPornVipParse.qualityOf(
            YesPornVipParse.downloadLink(html("video-76685-download-720.html"), 720)!!,
        ))
        assertEquals(1080, YesPornVipParse.qualityOf(
            YesPornVipParse.downloadLink(html("video-76685-download-1080.html"), 1080)!!,
        ))
    }

    @Test
    fun aFormatThePageDoesNotOfferIsNull() {
        // The 720 page asked for as 1080 (the shape of a short format ladder / site fallback)
        // must yield nothing: never a 1080 link that is really the 720 file.
        assertNull(YesPornVipParse.downloadLink(html("video-76685-download-720.html"), 1080))
        assertNull(YesPornVipParse.downloadLink(html("video-76685-download-480.html"), 720))
        // A page with no download button at all (the login wall) yields nothing either.
        assertNull(YesPornVipParse.downloadLink(html("walled-sample.html"), 480))
    }

    @Test
    fun aLaterButtonOnAMultiButtonPageIsStillFound() {
        // Every live page carries exactly one button today (11 titles re-probed 2026-11-27), but
        // the `format=` ladder makes a multi-button dialog a plausible theme change: the format
        // must be matched across all buttons, not decided by the first anchor on the page.
        val page = """
            <a class="btn download" href="https://yesporn.vip/get_file/5/76000/76685/76685.mp4/?download_filename=x.mp4&amp;download=true">480p</a>
            <a class="btn download" href="https://yesporn.vip/get_file/5/76000/76685/76685_1080p.mp4/?download_filename=x.mp4&amp;download=true">1080p</a>
        """.trimIndent()
        assertEquals(480, YesPornVipParse.qualityOf(YesPornVipParse.downloadLink(page, 480)!!))
        assertEquals(1080, YesPornVipParse.qualityOf(YesPornVipParse.downloadLink(page, 1080)!!))
        assertNull(YesPornVipParse.downloadLink(page, 720))
    }

    @Test
    fun theEndpointUrlCarriesTheIdAndFormat() {
        assertEquals(
            "https://yesporn.vip/view_video_download.php?id=76685&format=1080",
            YesPornVipParse.downloadPageUrl("76685", 1080),
        )
        assertEquals(listOf(480, 720, 1080), YesPornVipParse.FORMATS)
    }
}
