package com.rjbiermann

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * S3 detail-page metadata (TDD-first, ADR-0005). The fixture is the live, post-301 capture of
 * video 76685 (`…-ebk44f/`, 2026-11-27, mobile Chrome UA): the page's `VideoObject` JSON-LD is
 * the structured source, and `PT0H8M00S` must land in CloudStream's minutes shape (repo
 * convention: duration is minutes, never seconds).
 */
class MetadataTest {

    private fun html(name: String): String =
        javaClass.classLoader.getResourceAsStream(name)!!.readBytes().decodeToString()

    private val metadata = YesPornVipParse.metadata(html("video-76685.html"))

    @Test
    fun detailPageYieldsTheVideosIdentity() {
        assertNotNull(metadata)
        assertEquals("The Threesome Tutorial FHD", metadata!!.title)
        // The 390x218/1.jpg screenshot the listing cards already show (the JSON-LD
        // thumbnailUrl, preview.jpg, is a different high-res hero file — using it made a
        // video's card art and load screen disagree).
        assertEquals(
            "https://yesporn.vip/contents/videos_screenshots/76000/76685/390x218/1.jpg",
            metadata.poster,
        )
    }

    @Test
    fun durationComesFromTheIsoTokenInMinutes() {
        // duration: PT0H8M00S -> 8 minutes (the visible badge "8:00" agrees).
        assertEquals(8, metadata!!.durationMinutes)
    }

    @Test
    fun uploadDateKeepsTheDayAndFeedsTheYear() {
        assertEquals("2026-11-21T11:44:44-05:00", metadata!!.uploadDate)
        assertEquals(2026, metadata.year)
    }

    @Test
    fun thePlotIsThePagesOwnDescription() {
        // meta[name=description] is real prose; the JSON-LD `description` is only the title
        // plus the model list, so it is not used.
        assertEquals(
            "Watch The Threesome Tutorial FHD with Serena Santos, Vanessa Sky, Tyler Nixon from " +
                "Brazzers in HD for free on YesPorn VIP. Threesome, Brunette, Latina, Big...",
            metadata!!.plot,
        )
    }

    @Test
    fun performersAreTheVideosModelsInPageOrder() {
        // JSON-LD actor (== the flashvars video_models list) — the video's performers, not the
        // uploader/member the page also links at /members/15878/.
        assertEquals(
            listOf("Serena Santos", "Vanessa Sky", "Tyler Nixon"),
            metadata!!.actors,
        )
    }

    @Test
    fun categoriesAndTagsStaySeparate() {
        // genre (flashvars video_categories) is title-cased, keywords (video_tags) lower-cased.
        assertEquals(
            listOf("Threesome", "Brunette", "Latina", "Big tits"),
            metadata!!.categories,
        )
        assertEquals(listOf("big tits", "latina", "threesome", "brunette"), metadata.tags)
    }

    @Test
    fun theTagRowDropsTheCaseTwins() {
        // genre (title-cased) and keywords (lower-cased) are the same taxonomy on this site,
        // so the load screen's single tag list holds 4 entries, not 8 — categories lead.
        assertEquals(
            listOf("Threesome", "Brunette", "Latina", "Big tits"),
            metadata!!.distinctTags,
        )
    }
}
