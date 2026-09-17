package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Red→green for the shared duration clock grammar (HQPorner inline, Porntrex, MissAV copies).
 *  CloudStream `duration` is minutes (repo convention), floored; junk/absent/0 → null. */
class DurationParseTest {

    @Test fun `colon clock parses h m s to minutes`() {
        assertEquals(66, DurationParse.minutes("1:06:09"))
        assertEquals(6, DurationParse.minutes("06:09"))
        assertNull(DurationParse.minutes("0:59"))  // 59s floors to 0 minutes → null
    }

    @Test fun `min sec tokens parse`() {
        assertEquals(6, DurationParse.minutes("6min 09sec"))
        assertEquals(10, DurationParse.minutes("10min"))
        assertEquals(45, DurationParse.minutes("45m 30s"))
        assertNull(DurationParse.minutes("09sec"))  // sub-minute floors to 0 → null
    }

    @Test fun `h m tokens parse (HQPorner badge shape)`() {
        assertEquals(82, DurationParse.minutes("1h 22m"))
        assertEquals(22, DurationParse.minutes("22m"))
        assertEquals(60, DurationParse.minutes("1h"))
    }

    @Test fun `junk and absence yield null`() {
        assertNull(DurationParse.minutes(null))
        assertNull(DurationParse.minutes(""))
        assertNull(DurationParse.minutes("Latest"))
        assertNull(DurationParse.minutes("abc"))
        assertNull(DurationParse.minutes("0:00"))
    }

    @Test fun `fromSeconds converts the og video duration shape (MissAV)`() {
        assertEquals(120, DurationParse.fromSeconds("7256"))
        assertEquals(2, DurationParse.fromSeconds("120"))
        assertNull(DurationParse.fromSeconds("59"))
        assertNull(DurationParse.fromSeconds("abc"))
        assertNull(DurationParse.fromSeconds(null))
    }
}
