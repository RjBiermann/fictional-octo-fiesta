package com.rjbiermann

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class PornXP : MainAPI() {
    override var mainUrl = "https://pxp.news"
    override var name = "PornXP"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "New Videos",
        "$mainUrl/best/" to "Best Videos",
        "$mainUrl/released/" to "New Releases",
        "$mainUrl/hd/" to "HD"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/?page=$page"
        val document = app.get(url).document
        val videos = document.select(".item_cont").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = videos,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page <= 1) "$mainUrl/?q=$query" else "$mainUrl/?q=$query&page=$page"
        val document = app.get(url).document
        val videos = document.select(".item_cont").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(videos, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        try {
            val titleElement = this.selectFirst(".item_title a") ?: return null
            val link = titleElement.attr("href")
            val dataId = this.attr("data-id")
            val imgElement = this.selectFirst(".item_thumb img")
            val poster = imgElement?.attr("src")?.let { fixUrl(it) }

            return newMovieSearchResponse(
                titleElement.text().trim(),
                fixUrl(link),
                TvType.NSFW
            ) {
                this.posterUrl = poster
            }
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        // TODO: Fix quickSearch implementation 
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = app.get(url).document
            val title = document.selectFirst("h1")?.text()?.trim() ?: return null
            val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
                ?: document.selectFirst(".player_shadow img")?.attr("src")?.let { fixUrl(it) }
            val description = document.selectFirst("#desc")?.text()?.trim()
            val tags = document.select(".tags a").map { it.text().trim() }
            val uploadDateText = document.selectFirst("#desc")?.text()?.let { text ->
                val uploadMatch = """Uploaded: (\d{4}\.\d{2}\.\d{2})""".toRegex().find(text)
                uploadMatch?.groupValues?.get(1)
            }
            
            // Get recommendations from related videos
            val recommendations = document.select(".item_cont").mapNotNull { it.toRecommendationResult() }

            return newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.year = uploadDateText?.split(".")?.get(0)?.toIntOrNull()
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        try {
            val titleElement = this.selectFirst(".item_title a") ?: return null
            val link = titleElement.attr("href")
            val imgElement = this.selectFirst(".item_thumb img")
            val poster = imgElement?.attr("src")?.let { fixUrl(it) }

            return newMovieSearchResponse(
                titleElement.text().trim(),
                fixUrl(link),
                TvType.NSFW
            ) {
                this.posterUrl = poster
            }
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val document = app.get(data).document
            val videoElement = document.selectFirst("#player source")
            
            if (videoElement != null) {
                val videoUrl = videoElement.attr("src")
                if (videoUrl.isNotEmpty()) {
                    val quality = videoElement.attr("title")?.lowercase() ?: "unknown"
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = fixUrl(videoUrl),
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                        }
                    )
                    return true
                }
            }
        } catch (e: Exception) {
            // TODO: Implement proper fallback
            return false
        }
        
        return false
    }
}