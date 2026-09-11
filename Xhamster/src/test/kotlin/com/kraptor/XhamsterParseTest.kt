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

    // algo-6 (issue #338): the site JS shifts the UNMASKED xor value —
    // 255 & ((i ^ i>>>18) >>> (i>>>27 & 31)). Real algo-6 hex captured live 2026-09-11
    // (video /videos/xhpglku, h264 240p url).
    private val algo6Hex =
        "06e060773166265a464c342eea75686a3eaf91ee60d51356015b35283618217008ab4bd78b0a47a1ca1f1b7db9c2584484b9ee7457db1aaeee2d2cf6c6698b794735074f79a91d3773b73c59c5413a66210c06c8923eeceed33269a86311e000496be7f382de774183626697412dba2a5e46ecc23030f421742d5d2dd76878f65348557d4cf92f9979"

    @Test fun `algo6 decode matches site semantics with real hex fixture`() {
        assertEquals(
            "https://video-h.xhcdn.com/key=l2na9rOxunUJpEq6cVwT-Q,end=1789110000,limit=3/data=20.118.214.103-mv/speed=0/029/334/118/240p.h264.mp4",
            xHamster().decodeXhUrl(algo6Hex)
        )
    }

    // 2026-09-11 (issue #338): listing cards come from window.initials JSON —
    // searchResult.videoThumbProps (search), layoutPage.videoListProps (e.g. /newest)
    // or trendingVideoListProps (e.g. /4k) — no SSR cards anywhere.
    @Test fun `search page initials cards parse`() {
        for (name in listOf("search-v1", "search-v2")) {
            val cards = xHamster().cardsFromInitials(xHamster().getInitialsJson(pageFixture("xhamster-$name.html")))
            assertTrue("$name: ${cards.size} cards", cards.size >= 40)
            cards.forEach { c ->
                assertTrue(c.url!!.startsWith("https://xhamster.com/videos/"))
                assertTrue(c.name!!.isNotBlank())
                assertTrue(c.posterUrl!!.startsWith("https://"))
            }
        }
        val a = xHamster().cardsFromInitials(xHamster().getInitialsJson(pageFixture("xhamster-search-v1.html")))
        val b = xHamster().cardsFromInitials(xHamster().getInitialsJson(pageFixture("xhamster-search-v2.html")))
        assertTrue("page2 must not repeat page1", a.intersect(b.map { it.url!! }).isEmpty())
    }

    @Test fun `home pages initials cards parse for both list surfaces`() {
        val newest = xHamster().cardsFromInitials(xHamster().getInitialsJson(pageFixture("xhamster-home-newest.html")))
        val fourK = xHamster().cardsFromInitials(xHamster().getInitialsJson(pageFixture("xhamster-home-4k.html")))
        assertTrue("newest ${newest.size}", newest.size >= 40)
        assertTrue("4k ${fourK.size}", fourK.size >= 40)
        newest.forEach { assertTrue(it.url!!.startsWith("https://xhamster.com/videos/")) }
        fourK.forEach { assertTrue(it.url!!.startsWith("https://xhamster.com/videos/")) }
    }

    private fun pageFixture(name: String): String =
        javaClass.getResource("/$name")!!.readText()

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
