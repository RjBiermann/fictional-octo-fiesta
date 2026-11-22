package com.rjbiermann

import org.junit.Assert.*
import org.junit.Test

/** Stream quality comes from the watch page's og:video height (issue #1441, FINDINGS-1441). */
class EroticmvQualityTest {

    @Test fun `fixture video page exposes 720p`() {
        val html = javaClass.getResourceAsStream("/watch-tokyo-nights.html")!!.readBytes().decodeToString()
        assertEquals(720, Eroticmv.parseQuality(html))
    }

    @Test fun `other player heights map through`() {
        assertEquals(1080, Eroticmv.parseQuality("""<meta property="og:video:height" content="1080">"""))
        assertEquals(2160, Eroticmv.parseQuality("""<meta property="og:video:height" content="2160">"""))
    }

    @Test fun `missing or out-of-range height yields null`() {
        assertNull(Eroticmv.parseQuality("<html><body>no player meta</body></html>"))
        assertNull(Eroticmv.parseQuality("""<meta property="og:video:height" content="0">"""))
        assertNull(Eroticmv.parseQuality("""<meta property="og:video:height" content="9999">"""))
        assertNull(Eroticmv.parseQuality("""<meta property="og:video:height" content="">"""))
    }
}
