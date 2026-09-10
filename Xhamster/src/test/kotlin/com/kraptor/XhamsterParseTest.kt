package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD tests for xHamster parsing (issue #216 fix). Fixture derives from a live
 * desktop video page fetch (2026-09-09).
 */
class XhamsterParseTest {

    @Test fun `initials JSON parses videoModel title duration description`() {
        val videoModel = xHamster().getInitialsJson(fixture())?.videoModel
        assertEquals("Student fuck big boobs teacher for better grade", videoModel?.title)
        assertEquals(1402, videoModel?.duration)
        assertEquals("Hot MILF fucked by BBC.", videoModel?.description)
    }

    @Test fun `initials JSON parses hex source qualities`() {
        val h264 = xHamster().getInitialsJson(fixture())?.xplayerSettings?.sources?.standard?.h264.orEmpty()
        assertEquals(listOf("auto", "720p"), h264.map { it.quality })
        assertTrue(h264.first().url!!.startsWith("035bf1ebbe"))
        assertTrue(h264[1].url!!.startsWith("04bd004e1c73bdbe"))
    }

    @Test fun `videoModel parses thumbURL poster`() {
        val videoModel = xHamster().getInitialsJson(fixture())?.videoModel
        assertTrue(
            videoModel?.thumbURL?.startsWith("https://ic-vt-nss.xhcdn.com/") == true
        )
        assertTrue(videoModel?.thumbURL?.endsWith(".webp") == true)
    }

    @Test fun `preload poster style parses full https url`() {
        val poster = xHamster().parsePreloadPoster(
            "background-image: url('https://ic-vt-nss.xhcdn.com/a/K/s(w:1280),webp/2560x1440.201.webp');"
        )
        assertEquals(
            "https://ic-vt-nss.xhcdn.com/a/K/s(w:1280),webp/2560x1440.201.webp", poster
        )
        assertEquals(null, xHamster().parsePreloadPoster(null))
        assertEquals(null, xHamster().parsePreloadPoster("no image here"))
    }

    @Test fun `initials parser tolerates shell-only page`() {
        val initial = xHamster().getInitialsJson(
            "<script>window.initials={\"isBare\":true,\"layoutPage\":\"default\"};</script>"
        )
        assertEquals(null, initial?.videoModel)
    }

    @Test fun `decodeXhUrl decodes live-derived 720p hex URL`() {
        val hex =
            "04bd004e1c73bdbe5510e2f8d95830b83129a7dd6e301f06ff6739a24cc7b0ab1d24ff5ea5bde63f8dd7" +
                "9a0238d5d7c8b5103b46d68a3cb58396564a03a4cf427f3c335595b9f409eeb28fad21e7b3099620c82" +
                "b1cbe5b4c733c89990a5d767c9df13acda30489beb091efd0a425e7a11677f4633d9f7d54bc05576c2" +
                "6f659cb84e7f2cea96cabd468"
        val decoded = xHamster().decodeXhUrl(hex)
        assertTrue(
            "decode failed: $decoded",
            decoded != null && (decoded.startsWith("https://") || decoded.startsWith("//"))
        )
    }

    private fun fixture(): String = javaClass.getResource("/xhamster-video.html")!!.readText()
}
