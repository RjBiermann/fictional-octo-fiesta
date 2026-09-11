// Deep parse module for ISO-8601 video metadata (glossary: Parse function).
// One home for the P[#D]T#H#M#S grammar and JSON-LD year keys that used to be
// duplicated across PerverZija, Javtiful, WatchPorn, Cat3Film, Sexfilm, Neporn
// and ixiporn. CloudStream `duration` is Minutes (repo convention), never seconds.
package com.kraptor

object JsonLdParse {

    private val YEAR = Regex("""\\?"?(?:datePublished|uploadDate)\\?"\s*:\s*\\?"(\d{4})""")
    private val DURATION_KEY = Regex("""\\?"duration\\?"\s*:\s*\\?"P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""")
    private val DURATION_BARE = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""") // bare token, e.g. Sexfilm meta[itemprop=duration]; day tokens per ixiporn P0DT0H41M45S

    /** Year from the JSON-LD datePublished/uploadDate field, plain or `\"`-escaped JSON. */
    fun year(jsonLd: String?): Int? =
        jsonLd?.let { YEAR.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** Minutes out of the first ISO-8601 P token (keyed, escaped, bare, or day-shaped). 0/absent → null. */
    fun minutes(jsonLd: String?): Int? {
        if (jsonLd == null) return null
        // Key-anchored matches first (JSON / escaped JSON), then bare tokens for
        // keyless input (Sexfilm meta[itemprop=duration], ixiporn meta content).
        // All components optional, so scan every match for the first real token:
        // an all-empty match ("duration":"PT", or a stray uppercase P earlier in
        // the document) must not shadow a valid P-token later in the string.
        val m = (DURATION_KEY.findAll(jsonLd) + DURATION_BARE.findAll(jsonLd))
            .firstOrNull { r -> r.groupValues.drop(1).any { it.isNotEmpty() } } ?: return null
        val (d, h, min, s) = m.destructured
        val total = (d.toIntOrNull() ?: 0) * 1440 + (h.toIntOrNull() ?: 0) * 60 + (min.toIntOrNull() ?: 0) + (s.toIntOrNull() ?: 0) / 60
        return total.takeIf { it > 0 }
    }
}
