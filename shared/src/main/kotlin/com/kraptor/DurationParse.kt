// Deep parse module for duration clocks (glossary: Parse function).
// One home for the h/m/min/sec and h:m:s clock grammars that used to be re-implemented
// in HQPorner (inline, untested), Porntrex (PorntrexParse.parseDurationSeconds) and
// MissAV (MissAVParse.parseDuration). CloudStream `duration` is minutes (repo
// convention), floored — never seconds. ISO-8601 JSON-LD input stays with JsonLdParse.
package com.kraptor

object DurationParse {

    private val H = Regex("""(\d+)\s*(?:h(?:ours?)?)\b""", RegexOption.IGNORE_CASE)
    private val M = Regex("""(\d+)\s*(?:m(?:in(?:utes?)?)?)\b""", RegexOption.IGNORE_CASE)
    private val S = Regex("""(\d+)\s*(?:s(?:ec(?:onds?)?)?)\b""", RegexOption.IGNORE_CASE)

    /** Minutes from mixed clock text: "1:06:09", "6min 09sec", "10min", "1h 22m", "45m 30s".
     *  Junk, absence, or a sub-minute result → null. A bare number (no unit, no colon)
     *  is never accepted — its unit is unknowable. */
    fun minutes(text: String?): Int? {
        if (text == null) return null
        val t = text.trim().lowercase()
        if (t.contains(':')) {
            val parts = t.split(':').map { it.trim().toIntOrNull() ?: return null }
            if (parts.size < 2 || parts.any { it < 0 }) return null
            return secondsToMinutes(parts.fold(0) { acc, p -> acc * 60 + p })
        }
        val h = H.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = M.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val s = S.find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (h == 0 && m == 0 && s == 0) return null
        return secondsToMinutes(h * 3600 + m * 60 + s)
    }

    /** Minutes from a plain seconds value ("og:video:duration" content); junk/0 → null. */
    fun fromSeconds(seconds: String?): Int? =
        secondsToMinutes(seconds?.trim()?.toIntOrNull() ?: return null)

    private fun secondsToMinutes(total: Int): Int? = (total / 60).takeIf { it > 0 }
}
