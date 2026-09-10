package com.film1k

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Pure parsing helpers for issue #233 (related videos, year) — test-safe, no framework types. */
object Film1kParse {
    /** Related-videos block: scoped to <main>, the section after the `Related Videos` header.
     *  Card markup is identical to listings (`article.loop-post`), so the caller reuses the
     *  same card parser as parseList. Sidebar sections live outside <main> and are skipped. */
    fun relatedOf(doc: Document): List<Element> {
        val main = doc.selectFirst("main") ?: return emptyList()
        val h3 = main.select("h3").firstOrNull {
            it.text().equals("Related Videos", ignoreCase = true)
        } ?: return emptyList()
        val section = h3.closest("section") ?: return emptyList()
        return section.select("article.loop-post").toList()
    }

    /** Year from og:title — every title carries "(<year>)" (fixture: "Tick Tock (2000) - ..."). */
    fun yearOf(ogTitle: String?): Int? =
        ogTitle?.let { Regex("""\((\d{4})\)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
}
