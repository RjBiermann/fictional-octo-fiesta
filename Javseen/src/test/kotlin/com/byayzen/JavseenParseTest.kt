package com.byayzen

import org.junit.Assert.assertEquals
import org.junit.Test

class JavseenParseTest {

    @Test fun `old-style description yields actor`() {
        // video-285424 (CEMD-204), probed 2026-09-09
        val desc = "CEMD-204 studio Serebu No Tomo Mosaic CEMD-204 First Lesbian Ban Lifted! Yuzu Sumeragi With Yui Hatano with tag ,High,Quality,Exclusive,Milf,Big,Tits,Finger,Fuck,Nasty,Hardcore,Squirting,Shaved,Lesbian,Kiss,Cunnilingus,4K,69,mosaic, reducing mosaic release 2022-07-26 and pornstar Hatano Yui and CEMD-204工作室 Serebu 的..."
        assertEquals(listOf("Hatano Yui"), JavseenParse.extractActors(desc))
    }

    @Test fun `type-X-pornstar description yields actor`() {
        // video-285650 (FLAV-311) shape
        val desc = "FLAV-311 studio reducing mosaic type mosaic pornstar Momono Yume and 移除马赛克..."
        assertEquals(listOf("Momono Yume"), JavseenParse.extractActors(desc))
    }

    @Test fun `no actor in generic site description`() {
        assertEquals(emptyList<String>(), JavseenParse.extractActors(
            "Watch Free JAV Sex Movies Streaming, Japanese Adult Videos, Tons of hot jav censored,Japanese tube, Japanese sex online."))
    }

    @Test fun `null description yields empty`() {
        assertEquals(emptyList<String>(), JavseenParse.extractActors(null))
    }
}
