// Parse function for Dean-Edwards packed JS (eval(function(p,a,c,k,e,d){}...)) —
// the grammar extracted from Sexfilm's private unpackPacked. Pure, fixture-tested;
// the only unit-tested layer per the Parse-function seam convention.
package com.kraptor

object PackedJs {

    /**
     * Unpacks the first packed eval() call found in [packedJs] (a whole page or a
     * bare script). Returns null when nothing packed is present — callers fall
     * back to the raw input if that is their documented behavior.
     */
    fun unpack(packedJs: String?): String? {
        if (packedJs == null) return null
        val m = Regex(
            """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(packedJs) ?: return null
        val payload = m.groupValues[1].replace("\\'", "'")
        val radix = m.groupValues[2].toIntOrNull() ?: 36
        val keys = m.groupValues[4].split('|')
        val map = HashMap<String, String>()
        keys.forEachIndexed { i, v -> if (v.isNotEmpty()) map[i.toString(radix)] = v }
        return Regex("""\b[a-z0-9]+\b""", RegexOption.IGNORE_CASE)
            .replace(payload) { mr -> map[mr.value] ?: mr.value }
    }
}
