// Deep parse module for the Mangoporn port (glossary: Parse function).
// Pure logic only — HTTP flows stay covered by pipeline Verification.
// Title filter: Mangoporn's dirty-word blocklist + the "Home menu boars"
// extra words, one regex built once. Duration: "2 hrs 5 mins" clock grammar.
// Pettab extraction: the div#pettabs anchor links (Rtable watch/download tabs) from a
// load page, file lockers filtered out.
package com.kraptor

object MangopornParse {

    /** Extra blocked words the home menu contributes (Mangoporn.Anamenudekiboklar). */
    private val menuWords = listOf("TS", "Trans", "TGirl", "gay", "pegging", "bi", "femboy", "T-Boy", "Bisexual", "Transsexual", "Trans")

    /** The full blocked-word regex: [words] + menuWords, `\b(word…)\w*\b`, case-insensitive. */
    fun dirtyWordRegex(words: List<String>): Regex = Regex(
        "\\b(?:${(words + menuWords).joinToString("|") { Regex.escape(it) }})\\w*\\b",
        RegexOption.IGNORE_CASE
    )

    /** Minutes from load-page duration text ("2 hrs 5 mins" → 125, "35 mins" → 35, junk → 0). */
    fun durationMinutes(text: String?): Int? {
        if (text == null) return null
        val hours = Regex("""(\d+)\s*hrs""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*mins""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return (hours * 60) + minutes
    }

    /** File-locker hosts loadLinks skips (rapidgator/nitroflare-style download links). */
    private val blockedHosts = listOf("rapidgator.net", "nitroflare.com", "uploaded.net", "filefactory.com")

    /** Embed links from the watch tabs (`div#pettabs a[href]`), file lockers filtered out. */
    fun embedLinks(document: org.jsoup.nodes.Document): List<String> =
        document.select("div#pettabs a").map { it.attr("href") }
            .filter { it.isNotEmpty() }
            .filterNot { link -> blockedHosts.any { link.contains(it) } }

    /** The page's JSON-LD VideoObject script text (schema.org metadata: duration, thumbnailUrl). */
    fun videoLdJson(document: org.jsoup.nodes.Document): String? =
        document.selectFirst("script[type=application/ld+json]:containsData(VideoObject)")?.data()

    /** JSON-LD `thumbnailUrl` when the site serves a real URL — absent/blank on some video pages. */
    fun thumbnail(jsonLd: String?): String? = jsonLd
        ?.let { Regex("\"thumbnailUrl\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
}
