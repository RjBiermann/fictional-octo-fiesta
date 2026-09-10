package com.allclassic

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parsing helpers for AllClassicPorn — unit-testable without CloudStream.
 *
 * Title source of truth is `h1[itemprop="name"]`, which matches the card title
 * (`div.th-description`) exactly, including the site's canonical "- (yyyy)" suffix.
 * `og:title` is only a fallback and omits the year suffix (audit finding C1, issue #204).
 *
 * Issue #205 (D1): actors/tags/categories come from the inline KVS `flashvars` JS
 * (`video_models` / `video_tags` / `video_categories`), year from the "(yyyy)" title suffix.
 */
object AllClassicPornParse {

    fun parseTitle(html: String): String? = parseTitle(Jsoup.parse(html))

    fun parseTitle(document: Document): String? =
        document.selectFirst("h1[itemprop=\"name\"]")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

    /** Year from the canonical "(yyyy)" title suffix (issue #205, D1). */
    fun parseYear(title: String?): Int? =
        title?.let { Regex("\\((\\d{4})\\)\\s*$").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** Comma-separated flashvars field from the inline KVS JS (issue #205, D1). */
    private fun flashvarsField(html: String, field: String): String? {
        // KVS escapes literal apostrophes in names as \' inside the JS string.
        // Matches both `video_models: 'x'` (object literal) and `flashvars['video_models'] = 'x'` forms.
        val value = Regex("(?:$field\\s*:|$field'\\]\\s*=)\\s*'((?:[^'\\\\]|\\\\.)*)'").find(html)?.groupValues?.get(1) ?: return null
        return value.replace("\\'", "'").trim().takeIf { it.isNotEmpty() }
    }

    fun parseActors(html: String): List<String> = listFromField(html, "video_models")
    fun parseTags(html: String): List<String> = listFromField(html, "video_tags")
    fun parseCategories(html: String): List<String> = listFromField(html, "video_categories")

    /** Drop cards whose href already appeared earlier — the site serves some videos twice in-page (issue #266). */
    fun distinctByHref(blocks: List<Element>): List<Element> = blocks.distinctBy { it.attr("href") }

    private fun listFromField(html: String, field: String): List<String> =
        flashvarsField(html, field)?.split(", ")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}
