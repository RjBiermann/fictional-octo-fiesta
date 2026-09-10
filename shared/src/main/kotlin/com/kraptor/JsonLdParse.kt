// Deep parse module for ISO-8601 video metadata (glossary: Parse function).
// One home for the PT#H#M#S grammar and JSON-LD year keys that used to be
// duplicated across PerverZija, Javtiful, WatchPorn, Cat3Film, Sexfilm and
// Neporn. CloudStream `duration` is Minutes (repo convention), never seconds.
package com.kraptor

object JsonLdParse {

    private val YEAR = Regex("""\\?"?(?:datePublished|uploadDate)\\?"\s*:\s*\\?"(\d{4})""")
    private val DURATION_KEY = Regex("""\\?"duration\\?"\s*:\s*\\?"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""")
    private val DURATION_BARE = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""") // bare token, e.g. Sexfilm meta[itemprop=duration]

    /** Year from the JSON-LD datePublished/uploadDate field, plain or `\"`-escaped JSON. */
    fun year(jsonLd: String?): Int? =
        jsonLd?.let { YEAR.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** Minutes out of the first ISO-8601 PT token (plain, escaped, or bare). 0/absent → null. */
    fun minutes(jsonLd: String?): Int? {
        if (jsonLd == null) return null
        val m = DURATION_KEY.find(jsonLd)?.let { r ->
            // An empty match (`"duration":"PT"`) is not a real token; fall through to
            // the bare grammar scan. The bare scan is what handles keyless input such
            // as Sexfilm's meta[itemprop=duration]="PT8173S".
            if (r.groupValues[1].isEmpty() && r.groupValues[2].isEmpty() && r.groupValues[3].isEmpty()) null
            else r
        } ?: DURATION_BARE.find(jsonLd) ?: return null
        val (h, min, s) = m.destructured
        val total = (h.toIntOrNull() ?: 0) * 60 + (min.toIntOrNull() ?: 0) + (s.toIntOrNull() ?: 0) / 60
        return total.takeIf { it > 0 }
    }
}
