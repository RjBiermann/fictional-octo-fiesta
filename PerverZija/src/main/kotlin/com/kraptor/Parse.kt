package com.kraptor

import org.jsoup.nodes.Document

/** Pure parse helpers for PerverZija — unit-tested against real page fragments. */
object Parse {
    /**
     * Year from JSON-LD `datePublished` (e.g. "2022-04-14T..."), the same script
     * block that yields the ISO duration. Selector `div.extra span.C a` is dead on
     * the live site (audit #212); relative "x ago" post-dates have no 4-digit year.
     */
    fun year(doc: Document): Int? {
        val jsonLd = doc.selectFirst("script[type=application/ld+json]")?.data() ?: return null
        return Regex("datePublished\"?:\"(\\d{4})").find(jsonLd)?.groupValues?.get(1)?.toIntOrNull()
    }
}
