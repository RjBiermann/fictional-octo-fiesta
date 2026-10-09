// Deep parse module (glossary: Parse function) — issue #536: the site's details block
// dropped the Description:/Models:/Duration cells; the fields moved to structured markup
// (meta + JSON-LD) and a renamed Pornstars: cell. Fixture-tested against video-93820265
// (latest-updates) and video-93404552 (search), probed 2026-10-09.
package com.kerimmkirac

import org.jsoup.nodes.Document

object FreePornVideosParse {

    private fun ld(doc: Document): String? =
        doc.select("script[type=application/ld+json]")
            .firstOrNull { it.data().contains("VideoObject") }?.data()

    fun tags(doc: Document): List<String> =
        doc.select("div.block-details a.btn_tag").map { it.text() }.filter { it.isNotBlank() }

    fun actors(doc: Document): List<String> =
        doc.select("div.block-details a.btn_model").map { it.text() }.filter { it.isNotBlank() }

    fun plot(doc: Document): String? =
        doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

    /** Duration minutes: meta[property=video:duration] seconds, JSON-LD ISO token as fallback. */
    fun duration(doc: Document): Int? =
        com.kraptor.DurationParse.fromSeconds(doc.selectFirst("meta[property=video:duration]")?.attr("content"))
            ?: com.kraptor.JsonLdParse.minutes(ld(doc))

    fun year(doc: Document): Int? = com.kraptor.JsonLdParse.year(ld(doc))
}
