package com.byayzen

/** Pure parse helpers for unit tests (ADR-0005). */
object JavseenParse {
    /**
     * Video actor lives only in the description meta, e.g.
     * "... release 2022-07-26 and pornstar Hatano Yui and CEMD-204..." or
     * "... type mosaic pornstar Momono Yume and ...". One regex covers both shapes:
     * grab the name between "pornstar" and the next "and", stopping before the CJK text.
     */
    fun extractActors(description: String?): List<String> {
        if (description == null) return emptyList()
        return Regex("""pornstar\s+(.+?)\s+and\s+""").findAll(description)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }
}
