package com.byayzen

/** Pure parse helpers for unit tests (ADR-0005). */
object JavseenParse {
    /**
     * Homepage row URL for a page (issue #507): bare-slug tag/category panels
     * paginate as {base}/recent/{N}/ with ajax=category_videos (or browse_videos for
     * /recent/ itself), while /tag/ pages have no {tag}/recent/{N}/ (404s) and use
     * ?ajax=browse_videos&page=N (FINDINGS-507).
     */
    fun homePageUrl(data: String, page: Int): String {
        val baseUrl = data.removeSuffix("/")
        if ("/tag/" in baseUrl) {
            return if (page <= 1) "$baseUrl/?ajax=browse_videos"
            else "$baseUrl/?ajax=browse_videos&page=$page"
        }
        val isRecent = baseUrl.endsWith("/recent")
        val ajaxParam = if (isRecent) "browse_videos" else "category_videos"
        val pageBase = when {
            page <= 1 -> baseUrl
            isRecent -> baseUrl.removeSuffix("/recent") + "/recent/$page"
            else -> "$baseUrl/recent/$page"
        }
        return "$pageBase/?ajax=$ajaxParam"
    }

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
