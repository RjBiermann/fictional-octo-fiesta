package com.byayzen

import org.junit.Assert.assertEquals
import org.junit.Test

class JavseenParseTest {

    @Test fun `category row page 1 uses bare-slug ajax`() {
        // bare-slug panel /solowork/ (existing row), page 1
        assertEquals("https://javseen.tv/solowork/?ajax=category_videos",
            JavseenParse.homePageUrl("https://javseen.tv/solowork/", 1))
    }

    @Test fun `category row pages 2+ use recent-path pagination`() {
        // page-2 URL verified live 2026-09-30: /solowork/recent/2/?ajax=category_videos → 200 JSON
        assertEquals("https://javseen.tv/solowork/recent/2/?ajax=category_videos",
            JavseenParse.homePageUrl("https://javseen.tv/solowork/", 2))
    }

    @Test fun `recent row keeps browse_videos ajax and recent path`() {
        assertEquals("https://javseen.tv/recent/?ajax=browse_videos",
            JavseenParse.homePageUrl("https://javseen.tv/recent/", 1))
        assertEquals("https://javseen.tv/recent/2/?ajax=browse_videos",
            JavseenParse.homePageUrl("https://javseen.tv/recent/", 2))
    }

    @Test fun `tag row uses browse_videos with page param`() {
        // FINDINGS-507: /tag/uncensored/ has no {tag}/recent/{N}/ (404s); the panel's own
        // script fetches ?ajax=browse_videos. Page 1 probed 2026-09-30 returns
        // {status:1,total:30,next_url:?page=2}; page 2 returns video-286035.
        assertEquals("https://javseen.tv/tag/uncensored/?ajax=browse_videos",
            JavseenParse.homePageUrl("https://javseen.tv/tag/uncensored/", 1))
        assertEquals("https://javseen.tv/tag/uncensored/?ajax=browse_videos&page=2",
            JavseenParse.homePageUrl("https://javseen.tv/tag/uncensored/", 2))
    }

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
