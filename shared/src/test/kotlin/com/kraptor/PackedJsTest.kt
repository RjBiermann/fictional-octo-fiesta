package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixture: real Dean-Edwards packed eval() call inside an embed page fragment. */
class PackedJsTest {

    private val fixture by lazy {
        javaClass.getResourceAsStream("/packed_jwplayer_embed.html")!!.reader().readText()
    }

    @Test fun `unpacks a packed eval call`() {
        assertEquals("""var links="hls2" play""", PackedJs.unpack(fixture))
    }

    @Test fun `plain html yields null (caller falls back to the page)`() {
        assertNull(PackedJs.unpack("<html><body>no packer here</body></html>"))
    }
}
