package com.byayzen

/**
 * Parse helpers for Javseen listing pagination (ADR-0005: pure, unit-tested).
 *
 * Category listings now paginate as {cat}/{sort}/{page}/ — e.g. /big-tits/recent/2/ —
 * and the sort segment is only discoverable from a page-1 AJAX response's "pagination"
 * field. Extracts this page's path from that HTML; null when the page isn't listed
 * (caller falls back to {cat}/recent/{page}/).
 */
object Parse {

    fun categoryPageUrl(paginationHtml: String, page: Int): String? =
        Regex("""href="([^"]*/(?<!\d)$page/)""").find(paginationHtml)?.groupValues?.get(1)
}
