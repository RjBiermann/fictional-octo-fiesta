// ! Bu araç @kerimmkirac tarafından | @Cs-GizliKeyif için yazılmıştır.

package com.kerimmkirac

import com.kraptor.DurationParse
import com.kraptor.JsonLdParse
import org.jsoup.nodes.Document

/** Pure parse helpers for unit tests (ADR-0005).
 *  Issue #536: the site's details block was rebuilt — the Description:/Models:/Duration
 *  cells are gone; labels are now Channel / Network / Categories / Pornstars inside
 *  div.hidden_tags, label wrapped in a <span> (so the old div-text() xpaths match nothing).
 *  Duration lives only in meta[property=video:duration] (seconds) + JSON-LD VideoObject;
 *  plot only in the og:description meta. CloudStream duration is minutes. */
object FreePornVideosParse {

    private fun jsonLd(doc: Document): String? =
        doc.selectFirst("script[type=application/ld+json]")?.data()

    /** Detail cell by span label (defensive against label renames like Models → Pornstars).
     *  Scope: div.block-details — jsoup normalizes the site's unclosed div soup so the
     *  Categories cell lands under div.hidden_tags but Pornstars under div.info (both
     *  descendants of block-details). */
    private fun cell(doc: Document, label: String): org.jsoup.nodes.Element? =
        doc.select("div.block-details div.item").firstOrNull {
            it.selectFirst("> span")?.text()?.startsWith(label) == true
        }

    fun tags(doc: Document): List<String> =
        cell(doc, "Categories")?.select("a[href*=categories]")?.eachText().orEmpty()

    fun actors(doc: Document): List<String> =
        cell(doc, "Pornstars")
            ?.select("a[href*=models], a[href*=pornstars]")
            ?.eachText().orEmpty()

    fun plot(doc: Document): String? =
        doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.ifEmpty { null }

    fun duration(doc: Document): Int? =
        DurationParse.fromSeconds(doc.selectFirst("meta[property=video:duration]")?.attr("content"))
            ?: JsonLdParse.minutes(jsonLd(doc))

    fun year(doc: Document, fullTitle: String): Int? =
        JsonLdParse.year(jsonLd(doc)) ?: fullTitle.takeLast(4).toIntOrNull()
}
