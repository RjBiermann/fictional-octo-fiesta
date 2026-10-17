package com.coxju

import com.kraptor.JsonLdParse
import com.kraptor.registerHostExtractors
import com.kraptor.searchCard

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ixiporn : MainAPI() {
    override var mainUrl              = "https://ixiporn.org"
    override var name                 = "ixiporn"
    override val hasMainPage          = true
    override var lang                 = "hi"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
            "${mainUrl}/page/" to "Latest Release",
            "${mainUrl}/tag/ullu-web-series/page/" to "Ullu Web Series",
            "${mainUrl}/search/Hunters/page/" to "Hunter Web Series",
            "${mainUrl}/search/fugi/page/" to "Fugi Web Series",
            "${mainUrl}/search/besharams/page/" to "Besharams Web Series",
            "${mainUrl}/search/primeplay/page/" to "Prime Play",
            "${mainUrl}/search/neonx/page/" to "Neonx",
            "${mainUrl}/search/Bang+Bros/page/" to "BangBros",
            "${mainUrl}/search/brazzers/page/" to "Brazzers",
            "${mainUrl}/search/voovi/page/" to "Voovi Web Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home     = document.select("div.col-12.col-md-4.col-lg-3.col-xl-3 > div.video-block")
            .mapNotNull { searchCard(it, "a.infos", titleAttr = "title", posterSel = "a.thumb > img") }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    // Card grammar lives in the shared SearchCard Parse function (title from a.infos[title],
    // poster a.thumb>img lazyload); emission (fixUrl + newMovieSearchResponse) via searchCard.

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.get("${mainUrl}/page/$page?s=$query").document

        val results = document.select("div.col-12.col-md-4.col-lg-3.col-xl-3 > div.video-block")
            .mapNotNull { searchCard(it, "a.infos", titleAttr = "title", posterSel = "a.thumb > img") }

        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title       = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
        val poster      = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val tags        = document.select("#video-tags a").map { it.text().trim() }
        val duration    = document.selectFirst("meta[itemprop=duration]")?.attr("content")?.let { JsonLdParse.minutes(it) } // shared ISO-8601 grammar (glossary: JSON-LD meta parse)
        val year        = document.selectFirst("meta[itemprop=uploadDate]")?.attr("content")?.take(4)?.toIntOrNull()
        val related     = document.select(".related-videos div.video-block")
            .mapNotNull { searchCard(it, "a.infos", titleAttr = "title", posterSel = "a.thumb > img") }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot      = description
            this.tags      = tags
            this.duration  = duration
            this.year      = year
            this.recommendations = related
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        var emitted = false
        document.select("div.video-player").forEach { res ->
            // shape-B pages (hub-embed era, #709) carry no contentUrl meta — emit nothing,
            // never the old fixUrl("null") garbage link (IxiPornParse.contentUrl, #655)
            val url = IxiPornParse.contentUrl(res) ?: return@forEach
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = fixUrl(url)
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
            emitted = true
        }

        return emitted
    }
}

/**
 * Pure Parse function (TDD seam, #655 deep pass 2026-10-17): the direct stream URL from a
 * `div.video-player` block — `meta[itemprop=contentUrl]` (casing `contentUrl` is #120's
 * jsoup case-sensitivity fix), trimmed, jsoup-decoded. The site also serves shape-B pages
 * (2021–2023 hub-embed era, #709): a base64 `clean-tube-player/player-x.php` iframe wrapping
 * an external hub embed (xvideos/redtube, hub varies per video) with NO contentUrl meta.
 * For those the function returns null and the provider emits nothing — never a "null"
 * garbage URL (the old `?.toString()` hazard). Embed-resolver work belongs to #709.
 */
object IxiPornParse {
    fun contentUrl(block: org.jsoup.nodes.Element?): String? {
        if (block == null) return null
        return block.selectFirst("meta[itemprop=contentUrl]")
            ?.attr("content")?.trim()
            ?.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
    }
}
@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class ixipornPlugin : com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(ixiporn())
        registerHostExtractors()
    }
}
