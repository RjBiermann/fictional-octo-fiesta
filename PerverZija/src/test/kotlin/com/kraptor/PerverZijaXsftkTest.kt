package com.kraptor

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD for issue #846 (probe story of #840): the xtremestream player now boots an
 * XSFTK signed-token layer. The player page exposes the boot call in plain text —
 *
 *     XSFTK.boot(<video_id>, "<hex>.<expiry>", player)
 *
 * — and the playback protocol moves to /player/proto_api.php (op=key / op=manifest /
 * op=sign / op=seg, header X-XS-P: 1), with op=manifest serving bytes XORed by
 * SHA-256("M|<id>|<token>"). Fixtures cut live 2026-10-21 (pervl3.xtremestream.xyz).
 */
class PerverZijaXsftkTest {

    private val playerHtml by lazy {
        javaClass.classLoader.getResource("perverzija_player_xsftk.html")!!.readText()
    }

    @Test fun `boot id and token extracted from player page`() {
        val boot = PerverZijaParse.xsftkBoot(playerHtml)!!
        assertEquals("4e316bf7cad703ffd97bd72c174b72c6", boot.id)
        assertEquals("7cb593191aaa3bed708b7930.1792558800", boot.token)
    }

    @Test fun `boot returns null on a page without the XSFTK layer`() {
        assertNull(PerverZijaParse.xsftkBoot("<html><body>no player here</body></html>"))
    }

    @Test fun `proto api url is built relative to the player subdomain`() {
        val boot = PerverZijaParse.xsftkBoot(playerHtml)!!
        assertEquals(
            "https://pervl3.xtremestream.xyz/player/proto_api.php" +
                "?data=4e316bf7cad703ffd97bd72c174b72c6&op=key&vt=7cb593191aaa3bed708b7930.1792558800",
            PerverZijaParse.protoApiUrl(
                "https://pervl3.xtremestream.xyz/player/index.php?data=4e316bf7cad703ffd97bd72c174b72c6",
                boot, "key"
            )
        )
        // extra query params (e.g. op=seg&n=<signed>) appended verbatim
        val seg = PerverZijaParse.protoApiUrl(
            "https://j2.xtremestream.xyz/player/index.php?data=whatever", boot, "seg", "&q=480&i=0&n=1"
        )
        assertTrue(seg.startsWith("https://j2.xtremestream.xyz/player/proto_api.php?data=${boot.id}&op=seg&vt="))
        assertTrue(seg.endsWith("&q=480&i=0&n=1"))
    }

    @Test fun `manifest decodes to quality json with cdn hosts`() {
        val boot = PerverZijaParse.xsftkBoot(playerHtml)!!
        val raw = javaClass.classLoader.getResourceAsStream("perverzija_proto_manifest.bin")!!
            .readBytes()
        val json = PerverZijaParse.decodeXsManifest(raw, boot)!!
        val tree = ObjectMapper().readTree(json)
        val qualities = tree.fieldNames().asSequence().toList().filter { it != "cdn" }
        assertTrue(qualities.contains("480"))
        assertTrue(qualities.contains("720"))
        assertEquals(734, tree.get("480").get("n").asInt())
        assertEquals(734, tree.get("480").get("durs").size())
        assertEquals(777, tree.get("720").get("n").asInt())
        assertEquals(
            "pervl3.xspcdn01.click",
            tree.get("cdn").get(0).asText()
        )
    }

    @Test fun `manifest rejects garbage instead of throwing`() {
        val boot = PerverZijaParse.xsftkBoot(playerHtml)!!
        assertNull(PerverZijaParse.decodeXsManifest(ByteArray(100) { it.toByte() }, boot))
    }
}
