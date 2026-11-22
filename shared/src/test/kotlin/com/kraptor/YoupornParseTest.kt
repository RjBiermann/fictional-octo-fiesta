package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD seam for the youporn hub (issue #1445, ixiporn shape-B tier).
 *
 * ixiporn's import-era pages wrap a hub embed in the player-x b64 payload; the hub varies
 * per video and `www.youporn.com` is a third observable (pages around `/page/1400`, 2020-12
 * uploads — e.g. `/page/1400` 2/2 sampled). The youporn embed page is server-rendered (same
 * network as redtube: `ypncdn` media CDN, `/media/{hls,mp4}/?s=` signed hop, identical
 * variant JSON) but carries its legs as `mediaDefinition` — SINGULAR — so the
 * `RedtubeParse.flashvarsJson` grammar (a `var flashvars… =` assignment) never matches and
 * the videos resolved to zero links.
 *
 * Fixtures are live captures (2026-11-22, embed 14854709): `youporn_embed_live.html`,
 * `youporn_media_hls.json`, `youporn_media_mp4.json`.
 */
class YoupornParseTest {

    private fun resource(path: String) =
        javaClass.getResourceAsStream(path)!!.readBytes().decodeToString()

    private val embed = resource("/youporn_embed_live.html")
    private val hls = resource("/youporn_media_hls.json")
    private val mp4 = resource("/youporn_media_mp4.json")

    @Test fun `embed page yields both media legs as the signed media hop`() {
        val legs = YoupornParse.mediaFromEmbed(embed)
        assertEquals(2, legs.size)
        assertEquals(listOf("hls", "mp4"), legs.map { it.format })
        assertTrue(
            "hls leg is not the signed /media path: ${legs.firstOrNull()?.videoUrl}",
            legs.first().videoUrl.contains("/media/hls/?s=")
        )
        assertTrue(
            "mp4 leg is not the signed /media path: ${legs.lastOrNull()?.videoUrl}",
            legs.last().videoUrl.contains("/media/mp4/?s=")
        )
    }

    @Test fun `quality height is read from the leg`() {
        assertEquals(554, YoupornParse.mediaFromEmbed(embed).first().height)
    }

    @Test fun `a page with no mediaDefinition yields no legs (deleted shell declines)`() {
        assertTrue(YoupornParse.mediaFromEmbed("<html><body>removed</body></html>").isEmpty())
    }

    @Test fun `the signed media hop parses through the redtube variant grammar`() {
        val hlsVariants = RedtubeParse.variantsFromMediaJson(hls)
        assertEquals(2, hlsVariants.size)
        assertTrue(
            "hls variant is not an absolute master playlist: ${hlsVariants.firstOrNull()?.url}",
            hlsVariants.first().url.startsWith("https://") && hlsVariants.first().url.contains("master.m3u8")
        )
        assertEquals(480, hlsVariants.first().quality)

        val mp4Variants = RedtubeParse.variantsFromMediaJson(mp4)
        assertEquals(2, mp4Variants.size)
        assertTrue(
            "mp4 variant is not an absolute media URL: ${mp4Variants.firstOrNull()?.url}",
            mp4Variants.first().url.startsWith("https://") && mp4Variants.first().url.contains(".mp4")
        )
    }

    @Test fun `youporn hub has a registry row`() {
        val hosts = sharedHostRegistry()
            .map { it.mainUrl.substringAfter("//").substringBefore('/').lowercase() }
            .toSet()
        assertTrue(
            "no row answers www.youporn.com — ixiporn's shape-B youporn embeds emit zero links",
            "www.youporn.com" in hosts
        )
    }
}
