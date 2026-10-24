package com.allclassic

import com.kraptor.KvsFlashvars
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

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

    /**
     * Plot: og:description when present, else the itemprop div (issue #289, D-1 — some pages,
     * e.g. video 5887, lack og:description). Strips the div's "Description:" label.
     */
    fun parsePlot(html: String): String? {
        val document = Jsoup.parse(html)
        // og:description keeps embedded HTML entities (e.g. <br \/>); strip them like the old load() path did.
        document.selectFirst("meta[property=og:description]")?.attr("content")
            ?.replace(Regex("<[^>]+>"), "")?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        return document.selectFirst("div.video-description[itemprop=description] .description-container")
            ?.text()?.replaceFirst(Regex("^Description:\\s*"), "")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Comma-separated flashvars field from the inline KVS JS (issue #205, D1).
     *  Both `video_models: 'x'` (object literal) and `flashvars['video_models'] = 'x'`
     *  forms, `\'` escapes unescaped — the shared grammar (audit finding 1). */
    private fun flashvarsField(html: String, field: String): String? = KvsFlashvars.field(html, field)

    fun parseActors(html: String): List<String> = listFromField(html, "video_models")
    fun parseTags(html: String): List<String> = listFromField(html, "video_tags")
    fun parseCategories(html: String): List<String> = listFromField(html, "video_categories")

    /**
     * Stream URL, multi-grammar (issue #876, S2): the legacy KVS flashvars form first
     * (`video_url: '…'` / `flashvars['video_url'] = '…'`), then the video.js `cfg` entry
     * (`videoUrl: '…'`) via the shared resolver. Legacy wins when a page carries both, so
     * historical fixtures keep resolving to the same URL.
     */
    fun parseVideoUrl(html: String): String? = KvsFlashvars.source(html)

    /**
     * Quality caption: the legacy `video_url_text` flashvars in either grammar
     * (issue #322, D2). When that caption is absent — the video.js `cfg` player ships
     * none — derive it from the resolved URL's filename token (`…_480p.mp4` → `480p`)
     * via the shared resolver. Null when neither source yields a label: never invent one.
     */
    fun parseQuality(html: String): String? =
        KvsFlashvars.field(html, "video_url_text") ?: KvsFlashvars.quality(html)

    /** Home-page URL for a paginated row. Feed base `…/page/` 301s to root when bare, so page 1 uses canonical `…/page/1/` (issue #322, D1). */
    fun homePageUrl(base: String, page: Int): String = when {
        page > 1 -> "$base$page/"
        base.endsWith("/page/") -> "${base}1/"
        else -> base
    }

    private fun listFromField(html: String, field: String): List<String> =
        flashvarsField(html, field)?.split(", ")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
}
