package com.kraptor

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD for issue #845 (fix story of #840): wire the XSFTK chain into playback.
 *
 * Live-verified 2026-10-21 (perv.xtremestream.xyz, rkprime-abella scene; transcript in
 * PerverZija/FINDINGS.md FINDINGS-845): the per-segment sign/sep round-trip is NOT needed
 * for native playback — the AES-128-CBC segment files live OPEN on the CDN at
 *
 *     https://<cdn-host>/cdn/down/<video_id>/<q>_enc/<q><i>.html      (200, no token)
 *
 * encrypted with the proto_api op=key bytes and IV = 16-byte big-endian segment index.
 * That is byte-for-byte the HLS "no IV attribute → IV = media sequence number" default,
 * so a synthesized VOD playlist
 *
 *     #EXT-X-KEY:METHOD=AES-128,URI="<proto_api op=key url>"   (no IV=)
 *     #EXTINF:<dur>,<seg url i>
 *
 * replays natively: the key itself must be fetched WITH header X-XS-P: 1 (403 without),
 * which ships on the ExtractorLink headers map (HlsFree precedent, issue #247).
 */
class PerverZijaSynthTest {

    private val playerHtml by lazy {
        javaClass.classLoader.getResource("perverzija_player_xsftk.html")!!.readText()
    }

    private val playerUrl = "https://pervl3.xtremestream.xyz/player/index.php" +
        "?data=4e316bf7cad703ffd97bd72c174b72c6"

    private val manifestJson: String by lazy {
        val boot = PerverZijaParse.xsftkBoot(playerHtml)!!
        val raw = javaClass.classLoader.getResourceAsStream("perverzija_proto_manifest.bin")!!
            .readBytes()
        PerverZijaParse.decodeXsManifest(raw, boot)!!
    }

    private fun expectedKeyUrl(): String =
        "https://pervl3.xtremestream.xyz/player/proto_api.php" +
            "?data=4e316bf7cad703ffd97bd72c174b72c6&op=key&vt=7cb593191aaa3bed708b7930.1792558800"

    // ---- segment URL shape (live-verified) ----------------------------------

    @Test fun `enc segment url uses cdn down id q_enc shape`() {
        val boot = PerverZijaParse.xsftkBoot(playerHtml)!!
        assertEquals(
            "https://pervl3.xspcdn01.click/cdn/down/4e316bf7cad703ffd97bd72c174b72c6/720_enc/7200.html",
            XsftkParse.xsSegmentUrl("pervl3.xspcdn01.click", boot.id, "720", 0)
        )
        assertEquals(
            "https://pervl3.xspcdn01.click/cdn/down/4e316bf7cad703ffd97bd72c174b72c6/480_enc/480776.html",
            XsftkParse.xsSegmentUrl("pervl3.xspcdn01.click", boot.id, "480", 776)
        )
    }

    // ---- quality ordering -----------------------------------------------------

    @Test fun `qualities ordered highest resolution first`() {
        assertEquals(listOf("720", "480"), XsftkParse.qualities(manifestJson))
    }

    @Test fun `qualities survive garbage json`() {
        assertEquals(emptyList<String>(), XsftkParse.qualities("not json"))
    }

    // ---- synthesized VOD playlist ---------------------------------------------

    @Test fun `playlist for 720 has key line, no IV, exact segment names and endlist`() {
        val boot = PerverZijaParse.xsftkBoot(playerHtml)!!
        val pl = XsftkParse.variantPlaylist(manifestJson, boot, playerUrl, "720")!!

        assertTrue(pl.startsWith("#EXTM3U"))
        assertTrue(pl.contains("#EXT-X-VERSION:3"))
        assertTrue(pl.contains("#EXT-X-MEDIA-SEQUENCE:0"))
        assertTrue(pl.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        // key URL must be absolute (data: URIs have no base for resolution) and headerless
        assertTrue(pl.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"${expectedKeyUrl()}\""))
        // NO IV attribute: default IV = media sequence number = BE(i) — exactly the
        // site's big-endian counter (FINDINGS-840 IV note). An explicit wrong IV would
        // be a silent decryption failure, so absence is a contract, not an omission.
        assertFalse(pl.contains("IV="))
        // segments come from the live-open CDN shape, in index order 0..n-1
        assertTrue(pl.contains("\n#EXTINF:8.333333,\n" +
            "https://pervl3.xspcdn01.click/cdn/down/4e316bf7cad703ffd97bd72c174b72c6/720_enc/7200.html"))
        assertTrue(pl.contains("/720_enc/720776.html"))
        assertFalse(pl.contains("/720_enc/720777.html"))
        assertTrue(pl.contains("#EXT-X-ENDLIST"))
        // TARGETDURATION covers the longest EXTINF (spec: >= rounded max duration)
        val tree = ObjectMapper().readTree(manifestJson)
        val maxDur = tree.get("720").get("durs").map { it.asDouble() }.max()
        assertEquals("#EXT-X-TARGETDURATION:${Math.ceil(maxDur).toLong()}", pl.lineSequence().first { it.startsWith("#EXT-X-TARGETDURATION:") })
        // exactly n segment entries
        assertEquals(777, pl.lineSequence().count { it.startsWith("#EXTINF:") })
    }

    @Test fun `playlist target duration covers max duration on a tiny manifest`() {
        val boot = PerverZijaParse.xsftkBoot(playerHtml)!!
        val tiny = """{"480":{"td":1,"n":2,"durs":[4,2.5],"bw":1,"res":"854x480"},"cdn":["c1.host","c2.host"]}"""
        val pl = XsftkParse.variantPlaylist(tiny, boot, playerUrl, "480")!!
        assertTrue(pl.contains("#EXT-X-TARGETDURATION:4"))
        assertEquals(
            "#EXTINF:4,\nhttps://c1.host/cdn/down/${boot.id}/480_enc/4800.html\n" +
            "#EXTINF:2.5,\nhttps://c1.host/cdn/down/${boot.id}/480_enc/4801.html\n#EXT-X-ENDLIST",
            pl.lineSequence().dropWhile { it.startsWith("#EXT-X-KEY") || it.startsWith("#EXT-X-TARGETDURATION") || it.startsWith("#EXT-X-") || it.startsWith("#EXTM3U") || !it.startsWith("#EXTINF") }.joinToString("\n")
        )
    }

    @Test fun `unknown quality and broken json yield null playlist`() {
        val boot = PerverZijaParse.xsftkBoot(playerHtml)!!
        assertNull(XsftkParse.variantPlaylist(manifestJson, boot, playerUrl, "2160"))
        assertNull(XsftkParse.variantPlaylist("garbage", boot, playerUrl, "480"))
    }

    // ---- loopback playback URL (#1353) ----------------------------------------

    @Test fun `published playlist is served over loopback http`() {
        val pl = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-ENDLIST"
        val url = XsftkPlaylistServer.publish(pl)
        // the app's player only speaks http(s): a data: URI dies as 2001 (#1353)
        assertTrue(url, url.startsWith("http://127.0.0.1:"))
        assertTrue(url, url.endsWith(".m3u8"))
        assertEquals(pl, java.net.URL(url).readText())
    }
}
