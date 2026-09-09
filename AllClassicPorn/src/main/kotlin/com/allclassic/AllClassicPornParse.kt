package com.allclassic

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Pure HTML parsing helpers for AllClassicPorn — unit-testable without CloudStream.
 *
 * Title source of truth is `h1[itemprop="name"]`, which matches the card title
 * (`div.th-description`) exactly, including the site's canonical "- (yyyy)" suffix.
 * `og:title` is only a fallback and omits the year suffix (audit finding C1, issue #204).
 */
object AllClassicPornParse {

    fun parseTitle(html: String): String? = parseTitle(Jsoup.parse(html))

    fun parseTitle(document: Document): String? =
        document.selectFirst("h1[itemprop=\"name\"]")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
}
