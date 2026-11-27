package com.rjbiermann

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S3 walled-page drop (TDD-first, ADR-0005; spec: "No metadata-without-streams rows").
 *
 * `/login-required/` answers **200** with just the login modal — the shape a login/premium/geo
 * wall has (the download dialog's "Upgrade" button points here). It carries neither a
 * `VideoObject` JSON-LD block nor a `video_id` flashvar, so [YesPornVipParse.metadata] returns
 * null and [YesPornVip.loadVideo] drops the card instead of opening an unplayable row.
 */
class WalledTest {

    private fun html(name: String): String =
        javaClass.classLoader.getResourceAsStream(name)!!.readBytes().decodeToString()

    @Test
    fun aWallCarriesNoVideoMetadata() {
        assertNull(YesPornVipParse.metadata(html("walled-sample.html")))
    }

    @Test
    fun anEmptyOr404BodyCarriesNoVideoMetadata() {
        // The site answers 404 for an unknown id with a bare stub; nothing may be invented.
        assertNull(YesPornVipParse.metadata(""))
        assertNull(YesPornVipParse.metadata("<html><head><title>404 Not Found</title></head><body></body></html>"))
    }
}
