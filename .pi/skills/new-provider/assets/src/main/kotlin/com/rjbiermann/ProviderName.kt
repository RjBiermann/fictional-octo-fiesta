package com.rjbiermann

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// Shape: direct-source tube (see FreePornVideos.kt). Fill every selector/URL from FINDINGS.
class ProviderName : MainAPI() {
    override var mainUrl        = "https://example.com"     // FINDINGS: homepage
    override var name           = "ProviderName"
    override val hasMainPage    = true
    override var lang           = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    // FINDINGS: listing sections. Homepage usually paginates as "$mainUrl/{page}/".
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Recent",
    )

    private fun pageUrl(base: String, page: Int, query: String? = null): String {
        val q = query?.let { "?s=$it" } ?: ""
        return if (page <= 1) "$base$q" else "$mainUrl/$page/$q"   // FINDINGS: pagination pattern
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(pageUrl(request.data, page)).document
        val home = document.select("a.VIDEOCARD_SELECTOR").mapNotNull {   // FINDINGS: item selector
            try { it.toSearchResult() } catch (e: Exception) { null }
        }
        return newHomePageResponse(
            HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = true,                                             // FINDINGS: pagination exists
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        try {
            val url = attr("href").takeIf { it.isNotBlank() } ?: return null
            val title = selectFirst(".TITLE_SELECTOR")?.text()?.takeIf { it.isNotBlank() } ?: return null
            val poster = selectFirst("img[src*=\"/t/\"]")?.attr("src")     // FINDINGS: poster selector
            return newMovieSearchResponse(title, url, TvType.NSFW) {
                posterUrl = fixUrlNull(poster)
            }
        } catch (e: Exception) {
            Log.d(name, "toSearchResult: ${e.message}")
            return null
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.get(pageUrl(mainUrl, page, java.net.URLEncoder.encode(query, "UTF-8"))).document
        val results = document.select("a.VIDEOCARD_SELECTOR").mapNotNull {   // FINDINGS
            try { it.toSearchResult() } catch (e: Exception) { null }
        }
        return newSearchResponseList(results, hasNext = true)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: url      // FINDINGS: title selector
        val poster = document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val duration = /* FINDINGS: duration source */ null

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(poster)
            duration?.let { this.duration = it }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select("source[src]").forEach { src ->                   // FINDINGS: stream selector
            try {
                val url = src.attr("src")
                val quality = src.attr("res")                             // FINDINGS: quality attr
                callback.invoke(
                    newExtractorLink(name, name, url) {
                        this.referer = mainUrl                            // FINDINGS: referer policy
                        this.quality = getQualityFromName(quality)
                    }
                )
            } catch (e: Exception) {
                Log.d(name, "loadLinks: ${e.message}")
            }
        }
        return true
    }
}