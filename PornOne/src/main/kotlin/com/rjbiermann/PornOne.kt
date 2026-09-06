package com.rjbiermann

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

// Built from PornOne/FINDINGS.md — every selector/URL there has live evidence.
class PornOne : MainAPI() {
    override var mainUrl        = "https://pornone.com"
    override var name           = "PornOne"
    override val hasMainPage    = true
    override var lang           = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    // FINDINGS: homepage is the only general listing; paginates as /{page}/
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Recent",
    )

    // FINDINGS: pagination is path-based — page N of a listing = "$mainUrl/{N}/" + query
    private fun listingUrl(page: Int, query: String? = null): String {
        val q = query?.let { "?s=$it" } ?: ""
        return if (page <= 1) "$mainUrl/$q" else "$mainUrl/$page/$q"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(listingUrl(page)).document
        val home = document.select("a.videocard").mapNotNull {
            try {
                it.toSearchResult()
            } catch (e: Exception) {
                Log.d(name, "getMainPage item: ${e.message}")
                null
            }
        }
        return newHomePageResponse(
            HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = true,
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        try {
            val url = attr("href").takeIf { it.isNotBlank() } ?: return null
            val title = selectFirst(".titlecont")?.text()?.takeIf { it.isNotBlank() } ?: return null
            val poster = selectFirst("img[src*=\"/t/\"]")?.attr("src")
            return newMovieSearchResponse(title, url, TvType.NSFW) {
                posterUrl = poster
            }
        } catch (e: Exception) {
            Log.d(name, "toSearchResult: ${e.message}")
            return null
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val q = URLEncoder.encode(query, "UTF-8")
        val document = app.get(listingUrl(page, q)).document
        val results = document.select("a.videocard").mapNotNull {
            try {
                it.toSearchResult()
            } catch (e: Exception) {
                Log.d(name, "search item: ${e.message}")
                null
            }
        }
        return newSearchResponseList(results, hasNext = true)
    }

    // FINDINGS: <script type="application/ld+json" data-react-helmet> VideoObject carries
    // name/description/thumbnailUrl/uploadDate/duration/contentUrl
    private fun Document.videoObjectLd(): Map<*, *>? {
        val mapper = ObjectMapper()
        for (el in select("script[type=application/ld+json]")) {
            val text = el.data()
            if (!text.contains("VideoObject")) continue
            return try {
                mapper.readValue(text, Map::class.java)
            } catch (e: Exception) {
                Log.d(name, "json-ld parse: ${e.message}")
                null
            }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val ld = document.videoObjectLd()

        val title = (ld?.get("name") as? String)?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: url
        val poster = (ld?.get("thumbnailUrl") as? List<*>)?.firstOrNull() as? String
            ?: document.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val description = ld?.get("description") as? String
        val uploadDate = ld?.get("uploadDate") as? String
        val year = uploadDate?.take(4)?.toIntOrNull()
        val duration = parseIsoDurationToMinutes(ld?.get("duration") as? String)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = description
            this.year = year
            this.duration = duration
        }
    }

    // "P0DT0H21M7S" → 21
    private fun parseIsoDurationToMinutes(iso: String?): Int? {
        if (iso == null) return null
        val hours = Regex("(\\d+)H").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)M(?!\\d)").find(iso)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return hours * 60 + minutes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // FINDINGS: direct MP4 <source src res="1080"> — no JS decode, no extractor needed
        document.select("source[src]").forEach { src ->
            try {
                val streamUrl = src.attr("src")
                if (streamUrl.isBlank()) return@forEach
                callback.invoke(
                    newExtractorLink(name, name, streamUrl) {
                        this.referer = mainUrl
                        this.quality = getQualityFromName(src.attr("res"))
                    }
                )
            } catch (e: Exception) {
                Log.d(name, "loadLinks: ${e.message}")
            }
        }

        // JSON-LD contentUrl fallback (lowest quality) if no <source> present
        if (document.select("source[src]").isEmpty()) {
            (document.videoObjectLd()?.get("contentUrl") as? String)?.takeIf { it.isNotBlank() }?.let {
                callback.invoke(
                    newExtractorLink(name, name, it) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }
}