package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** issue #247 hlsfree preflight helpers (shared HlsFree adapter). */
class HlsFreeParseTest {
    @Test
    fun `hlsfree token parses from embed page`() {
        val embed = """const defaultHlsUrl = "https://hlsfree.com/api/hls/serve?token=d6df6bbbf2d7"; // embed replaces with real URL"""
        assertEquals("d6df6bbbf2d7", HlsFreeParse.hlsfreeToken(embed))
        assertEquals(null, HlsFreeParse.hlsfreeToken("<html>no token here</html>"))
    }

    @Test
    fun `dead hlsfree manifests are rejected`() {
        assertTrue(HlsFreeParse.isPlayableManifest(200, "#EXTM3U\n#EXTINF:10,\nseg.ts"))
        assertFalse(HlsFreeParse.isPlayableManifest(200, "<!DOCTYPE html><title>Just a moment</title>"))
        assertFalse(HlsFreeParse.isPlayableManifest(403, "<!DOCTYPE html>"))
        assertFalse(HlsFreeParse.isPlayableManifest(429, "rate limited"))
        assertFalse(HlsFreeParse.isPlayableManifest(500, "Proxy error"))
    }
}