package com.allclassic

import com.kraptor.JsonLdParse
import com.kraptor.registerHostExtractors
import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AllClassicPorn : MainAPI() {
    override var mainUrl = "https://allclassic.porn"
    override var name = "AllClassicPorn"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val tag = "AllClassicPorn"

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "New Videos",  // feed base; page 1 fetched as /page/1/ — /page/ alone 301s to site root (issue #322, D1)
        "$mainUrl/40s/" to "40s",
        "$mainUrl/50s/" to "50s",
        "$mainUrl/60s/" to "60s",
        "$mainUrl/70s/" to "70s",
        "$mainUrl/80s/" to "80s",
        "$mainUrl/90s/" to "90s",
        "$mainUrl/2000s/" to "2000s",
        "$mainUrl/best/" to "Best",
        "$mainUrl/most-popular/" to "Most Popular",
        "$mainUrl/longest/" to "Longest",
        "$mainUrl/most-commented/" to "Most Commented",
        "$mainUrl/most-favourited/" to "Most Favourited",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = AllClassicPornParse.homePageUrl(request.data, page)  // feed page 1 → …/page/1/ (issue #322, D1)
        val document = app.get(url, referer = mainUrl).document

        val home = AllClassicPornParse.distinctByHref(document.select("a.th.item"))
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        return try {
            val href = fixUrlNull(this.attr("href")) ?: return null
            val title = this.selectFirst("div.th-description")?.text()?.trim()
                ?: this.selectFirst("img")?.attr("alt")?.trim()
                ?: return null
            val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            null  // one broken card must not kill the list
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/search/${query.trim().replace(" ", "-")}/" + if (page > 1) "$page/" else ""
        val document = app.get(url, referer = mainUrl).document
        val results = document.select("a.th.item").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(tag, "Load : $url")
        val document = app.get(url, referer = mainUrl).document

        // h1[itemprop=name] matches the card title (div.th-description), incl. the "- (yyyy)" suffix; og:title omits it (issue #204).
        val title = AllClassicPornParse.parseTitle(document) ?: return null
        val html = document.outerHtml()
        // Note: KVS video pages expose actors/tags/categories in the inline flashvars JS (issue #205, D1);
        // empty video_models on some videos (e.g. 6161) is handled gracefully by parseActors.
        val actors = AllClassicPornParse.parseActors(html)
        val tags = (AllClassicPornParse.parseTags(html) + AllClassicPornParse.parseCategories(html)).distinct()
        val poster = fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
        // og:description first, itemprop div fallback (issue #289, D-1 — video 5887 lacks og:description)
        val description = AllClassicPornParse.parsePlot(html)
        val duration = document.selectFirst("meta[itemprop=\"duration\"]")?.attr("content")
            ?.let { JsonLdParse.minutes(it) } // shared ISO-8601 grammar (glossary: JSON-LD meta parse)
        val recommendations = AllClassicPornParse.distinctByHref(
            document.select("#list_videos_related_videos_items a.th.item")
        ).mapNotNull { it.toSearchResult() }  // site serves duplicated related entries (issue #266)
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.duration = duration
            this.recommendations = recommendations
            this.actors = actors.map { ActorData(Actor(it)) }
            this.tags = tags
            this.year = AllClassicPornParse.parseYear(title)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(tag, "data = $data")
        val html = app.get(data, referer = mainUrl).text

        // Direct KVS flashvars: video_url + video_url_text pairs; skip ?login upsell "alt" urls.
        // flashvars caption uses the [X] = form on every live page; colon form kept as fallback (issue #322, D2).
        val quality = AllClassicPornParse.parseQuality(html)
        val videoUrl = Regex("video_url:\\s*'([^']+)'").find(html)?.groupValues?.get(1) ?: return false

        callback(
            newExtractorLink(
                source = name,
                name = name + (quality?.let { " - $it" } ?: ""),
                url = videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = data
            }
        )

        return true
    }
}

@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class AllClassicPornPlugin : com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(AllClassicPorn())
        registerHostExtractors()
    }
}
