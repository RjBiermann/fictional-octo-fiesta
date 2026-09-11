package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD tests for xHamster parsing (issue #339 fix). Fixtures derive from live
 * desktop video pages (2026-09-11): window.initials carries videoEntity
 * (title/description/duration/pornstars) and videoPageComponent.relatedVideos
 * …videoThumbProps (recommendations); videoModel was slimmed down to
 * author/channelModel/duration/id/sponsor.
 */
class XhamsterParseTest {

    @Test fun `initials JSON parses videoEntity title duration description`() {
        for (fixture in listOf("v1", "v2")) {
            val initials = xHamster().getInitialsJson(fixture(fixture))!!
            val entity = initials.videoEntity!!
            assertNotNull(entity.duration)
            assertTrue("description too short (${entity.description?.length})",
                (entity.description?.length ?: 0) >= 164)
            assertNull("videoModel must not carry title anymore", initials.videoModel?.title)
            assertNull("videoModel must not carry description anymore", initials.videoModel?.description)
        }
        assertEquals("Two Cougars on the Prowl", xHamster().getInitialsJson(fixture("v1"))!!.videoEntity!!.title)
        assertEquals("My MILF Stepmom Gives Me A Laundry Lesson", xHamster().getInitialsJson(fixture("v2"))!!.videoEntity!!.title)
    }

    @Test fun `videoEntity parses pornstarModels actors`() {
        assertEquals(
            listOf("Desifilmy45", "Karla Insatiable", "Jason Pierce", "Madame D"),
            xHamster().getInitialsJson(fixture("v1"))!!.videoEntity!!.pornstarModels!!.map { it.name }
        )
        assertEquals(
            listOf("Jax Slayher", "Hailey Rose", "Kera Bear"),
            xHamster().getInitialsJson(fixture("v2"))!!.videoEntity!!.pornstarModels!!.map { it.name }
        )
    }

    @Test fun `relatedVideos videoThumbProps parse with absolute pageURLs and thumbs`() {
        for (fixture in listOf("v1", "v2")) {
            val recs = xHamster().getInitialsJson(fixture(fixture))!!
                .videoPageComponent!!.relatedVideos!!.videoTabInitialData!!
                .videoListProps!!.videoThumbProps!!
            assertEquals(11, recs.size)
            recs.forEach { rec ->
                assertTrue(rec.pageURL!!.startsWith("https://xhamster.com/videos/"))
                assertTrue(rec.thumbURL!!.startsWith("https://"))
                assertTrue(rec.title!!.isNotBlank())
            }
        }
    }

    @Test fun `videoEntity thumbBig parses xhcdn poster`() {
        val poster = xHamster().getInitialsJson(fixture("v1"))!!.videoEntity!!.thumbBig
        assertTrue(poster!!.startsWith("https://ic-vt-nss.xhcdn.com/"))
        assertTrue(poster.endsWith(".webp"))
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
        assertEquals(null, initial?.videoEntity)
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

    private fun fixture(name: String): String =
        javaClass.getResource("/xhamster-video-$name.html")!!.readText()
}
