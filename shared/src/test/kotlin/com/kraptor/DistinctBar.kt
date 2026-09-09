package com.kraptor

/**
 * Distinct-bar assertions for Parse-function fixture tests (glossary: Distinct).
 * Identity fields — title, poster, url/stream — must differ across the sampled videos in a
 * fixture; a collision fails red before any live verification run. Repeatable fields (tags,
 * actors, year, duration, score) are deliberately NOT covered: they legitimately repeat.
 */
object DistinctBar {

    data class VideoIdentity(val title: String?, val poster: String?, val url: String?)

    fun assertDistinctVideos(videos: List<VideoIdentity>) {
        checkField("title", videos.map { it.title })
        checkField("poster", videos.map { it.poster })
        checkField("url", videos.map { it.url })
    }

    private fun checkField(field: String, values: List<String?>) {
        val seen = HashMap<String, Int>()
        for (v in values) {
            val k = v?.trim()?.lowercase() ?: continue
            seen[k] = (seen[k] ?: 0) + 1
        }
        val dups = seen.filterValues { it > 1 }
        check(dups.isEmpty()) {
            "Distinct bar: duplicate $field values in fixture: $dups " +
                "(two different videos share the same $field — placeholder or misparse)"
        }
    }
}
