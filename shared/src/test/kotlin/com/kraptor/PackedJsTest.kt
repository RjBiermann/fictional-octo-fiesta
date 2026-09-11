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

    /** Fixture: radix-62 (Packer 'a' > 36) — unrepresentable for Kotlin's toString(radix). */
    private val radix62Fixture by lazy {
        javaClass.getResourceAsStream("/packed_radix62_embed.js")!!.reader().readText()
    }

    @Test fun `radix above 36 yields null instead of garbage or crash`() {
        assertNull(PackedJs.unpack(radix62Fixture))
    }

    /** Radix group overflowing Int cannot be defaulted to anything safe. */
    private val radixOverflowFixture =
        """eval(function(p,a,c,k,e,d){e=function(c){return c}}('w0',99999999999999999999,1,'w0'.split('|')))"""

    @Test fun `unparseable radix yields null instead of falling back to 36`() {
        assertNull(PackedJs.unpack(radixOverflowFixture))
    }
}
