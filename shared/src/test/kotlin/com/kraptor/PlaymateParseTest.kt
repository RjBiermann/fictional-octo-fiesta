package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** issue #522: cat3movie sv3 embeds moved to playmate.to/watch/<code>; the adapter
 *  regex only matched /embed/ and /e/ and silently dropped /watch/ URLs. */
class PlaymateParseTest {
    @Test
    fun `watch embeds parse`() {
        assertEquals("RXzd0bHW0ivs", PlaymateParse.code("https://playmate.to/watch/RXzd0bHW0ivs"))
        assertNull(PlaymateParse.code(null))
        assertEquals("vtdPI6hiPT1D", PlaymateParse.code("https://playmate.to/watch/vtdPI6hiPT1D"))
    }

    @Test
    fun `legacy embed shapes still parse`() {
        assertEquals("abc123", PlaymateParse.code("https://playmate.to/embed/abc123"))
        assertEquals("abc123", PlaymateParse.code("https://playmate.to/e/abc123"))
    }

    @Test
    fun `non-playmate urls are rejected`() {
        assertNull(PlaymateParse.code("https://playmate.to/"))
        assertNull(PlaymateParse.code("https://other.host/watch/abc123"))
    }
}
