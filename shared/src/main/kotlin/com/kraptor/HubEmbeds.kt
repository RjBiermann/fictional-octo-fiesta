// Unknown-hub decline seam (issue #779, approved Q1): the ixiporn shape-B tier's
// b64 payload frames an external hub that varies per video — xvideos (#778) and
// embed.redtube.com are covered; a hub with NO adapter row must gracefully produce
// nothing (decline via the normal extractor match: no crash, no dead link), or the
// tier becomes a chronic point-fix one hub at a time.
package com.kraptor

import com.lagradost.cloudstream3.utils.ExtractorApi

/**
 * Parse-level seam of the framework's loadExtractor dispatch (#779): `covered(url)`
 * answers whether some registry row would accept the embed URL — an unknown hub
 * yields false and the caller emits nothing. Data-driven on the registry itself, so
 * adding a hub row (xvideos #778) extends coverage without changing this logic.
 */
object HubEmbeds {
    // the framework's own normalization (ExtracterApiKt.loadExtractor: lowercase, then
    // strip this prefix pattern from both the URL and every row's mainUrl before startsWith)
    private val dispatchStrip = Regex("^(https:|)//(www\\.|)")

    /**
     * True when a registry row answers this embed URL — the same mainUrl-prefix
     * dispatch rule loadExtractor applies, expressed purely so tests and the
     * provider-side b64 chain (#780) can pin the unknown-hub decline without HTTP.
     * Data-driven on the registry itself: adding a hub row extends coverage with zero
     * logic change; a hub with no row declines (covered=false → emit nothing).
     */
    fun covered(embedUrl: String, registry: List<ExtractorApi> = sharedHostRegistry()): Boolean {
        val target = embedUrl.lowercase().replace(dispatchStrip, "")
        return registry.any { target.startsWith(it.mainUrl.lowercase().replace(dispatchStrip, "")) }
    }
}
