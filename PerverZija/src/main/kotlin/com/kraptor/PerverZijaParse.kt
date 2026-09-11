package com.kraptor

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Pure parsing helpers for issue #273 (recommendations) — test-safe, no framework types. */
object PerverZijaParse {

    /** Related-videos cards. The old div.srelacionados block is gone from the site;
     *  the WP theme now serves div.xs-related-item (title link + poster img). */
    fun related(doc: Document): List<Element> = doc.select("div.xs-related-item").toList()

    /** Home/search listing cards. The bare div#sidebar widget column is also a
     *  div.col-md-3 (issue #333) — excluding it by id keeps video-card rows for
     *  both live variants (col-sm-3 on search, col-sm-6 col-xs-6 on home). */
    fun listingCards(doc: Document): List<Element> =
        doc.select("div.col-md-3:not(#sidebar)").toList()

    /** Title from div.xs-related-title a — img alt/title repeat the host page's
     *  title on the live site, so they must never be used. */
    fun titleOf(item: Element): String? =
        item.selectFirst("div.xs-related-title a")?.text()?.trim()?.takeIf { it.isNotEmpty() }

    /** Poster from the item's img (plain src; no data-src on this markup). */
    fun posterOf(item: Element): String? =
        item.selectFirst("a img")?.attr("src")?.takeIf { it.isNotEmpty() }
}
