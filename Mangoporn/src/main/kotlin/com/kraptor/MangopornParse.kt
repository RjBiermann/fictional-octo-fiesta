// Deep parse module for the Mangoporn port (glossary: Parse function).
// Pure logic only — HTTP flows stay covered by pipeline Verification.
// Title filter: Mangoporn's dirty-word blocklist + the "Home menu boars"
// extra words, one regex built once. Duration: "2 hrs 5 mins" clock grammar.
// Pettab extraction: the div#pettabs > ul a embed links from a load page.
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

    /** Search URL for page n; query is URL-encoded (raw space breaks OkHttp/NiceHttp). */
    fun searchUrl(baseUrl: String, query: String, page: Int): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return if (page <= 1) "$baseUrl/?s=$encoded" else "$baseUrl/page/$page/?s=$encoded"
    }

    /** Embed links from the stream tabs (`div#pettabs > ul a[href]`), file lockers filtered out. */
    fun embedLinks(document: org.jsoup.nodes.Document): List<String> =
        document.select("div#pettabs > ul a").map { it.attr("href") }
            .filter { it.isNotEmpty() }
            .filterNot { link -> blockedHosts.any { link.contains(it) } }
}
