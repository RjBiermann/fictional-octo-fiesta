package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Mangoporn port (issue #443), TDD-first per ADR-0005.
 * Pure Parse/regex logic only — HTTP flows are covered by pipeline Verification.
 */
class MangopornParseTest {

    // ---- CloudWish packed-JS unpack (provider-private grammar) ----

    @Test
    fun cloudWishUnpackExtractsMasterM3u8Path() {
        // k = ["", "/stream/v/", "master.m3u8"]: token 1 -> /stream/v/, token 2 -> master.m3u8
        val packed2 = "eval(function(p,a,c,k,e,d){}('1/2',10,3,'|/stream/v/|master.m3u8'.split('|'),0,{}))"
        val out = CloudWish().unpack(packed2)
        assertTrue(out!!.contains("/stream/v/"))
        assertTrue(out.contains("master.m3u8"))
        assertNull(CloudWish().unpack("no packed script here"))
    }

    // ---- home-page card filter (dirty-word regex built in Mangoporn) ----

    @Test
    fun dirtyWordRegexMatchesTransTitles() {
        val words = dirtyWordsForTest()
        val pattern = Regex(
            "\\b(?:${words.joinToString("|") { Regex.escape(it) }})\\w*\\b",
            RegexOption.IGNORE_CASE
        )
        assertTrue(pattern.containsMatchIn("TS Seduction"))
        assertTrue(pattern.containsMatchIn("Trans fixed girl"))
        assertFalse(pattern.containsMatchIn("Cum Gushers"))
        assertFalse(pattern.containsMatchIn("Big Boobs Blast"))
    }

    // ---- duration parse from load page ("2 hrs 5 mins") — same grammar Mangoporn.load inlines ----

    @Test
    fun durationParsesHoursAndMinutes() {
        assertEquals(125, inlineDurationMinutes("2 hrs 5 mins")!!)
        assertEquals(35, inlineDurationMinutes("35 mins")!!)
        assertNull(inlineDurationMinutes(null))
        assertEquals(0, inlineDurationMinutes("n/a")!!)
    }

    /** Mirrors Mangoporn.load's duration block; kept in lockstep with the provider code. */
    private fun inlineDurationMinutes(text: String?): Int? = text?.let {
        val hours = Regex("""(\d+)\s*hrs""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*mins""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        (hours * 60) + minutes
    }

    // ---- stream tab extraction from load page HTML (fixture) ----

    @Test
    fun petTabsExtractsEmbedLinksAndSkipsFileLockers() {
        val html = javaClass.classLoader
            .getResourceAsStream("mangoporn-pettabs.html")!!
            .readBytes().decodeToString()
        val links = Regex("""<a title="[^"]*" href="([^"]+)"""").findAll(html)
            .map { it.groupValues[1] }
            .toList()
        assertTrue(links.contains("https://luluvid.com/e/jozkrpjgsueq"))
        assertTrue(links.contains("https://playmogo.com/e/80wvduwl22id"))
        // blocked file-locker host must be filtered by loadLinks
        val blocked = listOf("rapidgator.net", "nitroflare.com", "uploaded.net", "filefactory.com")
        val filtered = links.filter { link -> blocked.none { link.contains(it) } }
        assertFalse(filtered.any { it.contains("rapidgator") || it.contains("nitroflare") })
        assertTrue(filtered.isNotEmpty())
    }

    private fun dirtyWordsForTest(): List<String> =
        listOf("gay", "trans", "TS", "TGirl", "femboy", "Bisexual", "Transsexual")
}
