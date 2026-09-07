package com.rjbiermann

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class PornXP : MainAPI() {
    override var mainUrl = "https://pxp.news"
    override var name = "PornXP"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
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
            val imgElement = this.selectFirst(".item_thumb img")
            // Lazy-loaded posters put the real URL in data-src, src is a spinner placeholder
            val poster = imgElement?.let { img -> if (img.hasAttr("data-src")) img.attr("data-src") else img.attr("src") }?.let { fixUrl(it) }

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
        return search(query, 1).items
    }

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = app.get(url).document
            val title = document.selectFirst("h1")?.text()?.trim() ?: return null
            // Poster lives on the <video id="player"> poster attribute (no og:image on these pages)
            val poster = document.selectFirst("#player")?.attr("poster")?.let { fixUrl(it) }
            val description = document.selectFirst("#desc")?.text()?.trim()
            val tags = document.select(".tags a").map { it.text().trim() }
            val uploadDateText = document.selectFirst("#desc")?.text()?.let { text ->
                val uploadMatch = """Uploaded: (\d{4}\.\d{2}\.\d{2})""".toRegex().find(text)
                uploadMatch?.groupValues?.get(1)
            }
            
            // Get recommendations from related videos
            val recommendations = document.select(".item_cont").mapNotNull { it.toRecommendationResult() }

            // No dedicated actor markup: multi-word tags are performer names,
            // minus the few multi-word genre tags the site also uses
            // ponytail: stoplist heuristic — swap for a real models endpoint if pxp.news adds one
            val genrePhrases = setOf("Big Ass", "Big Tits", "Cum In Mouth", "Big Cock", "Step Mom", "Step Sister", "Step Daughter", "Step Brother")
            val actors = tags.filter { it.contains(" ") && it !in genrePhrases }.map { Actor(it) }

            return newMovieLoadResponse(title, url, TvType.NSFW, url) {
                addActors(actors)
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
            val poster = imgElement?.let { img -> if (img.hasAttr("data-src")) img.attr("data-src") else img.attr("src") }?.let { fixUrl(it) }

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
            // Emit one ExtractorLink per <source> quality (FINDINGS documents 360p/720p/1080p)
            val sources = document.select("#player source")
            for (source in sources) {
                val videoUrl = source.attr("src")
                if (videoUrl.isEmpty()) continue
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixUrl(videoUrl),
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = getQualityFromName(source.attr("title"))
                        this.referer = mainUrl
                    }
                )
            }
            return sources.isNotEmpty()
        } catch (e: Exception) {
            return false
        }
    }
}

@CloudstreamPlugin
class PornXPPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PornXP())
    }
}