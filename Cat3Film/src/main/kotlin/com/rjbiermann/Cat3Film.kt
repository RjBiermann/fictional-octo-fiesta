package com.rjbiermann

import com.lagradost.api.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

// Custom engine (see FINDINGS.md): server-rendered cards + /api/v1 JSON API + JW Player.
class Cat3Film : MainAPI() {
    override var mainUrl        = "https://cat3film.com"
    override var name           = "Cat3Film"
    override val hasMainPage    = true
    override var lang           = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    private val mapper = ObjectMapper().registerKotlinModule()

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Movies",
        "$mainUrl/tv-series" to "TV Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val document = app.get(url).document
        val home = document.select("a.card").mapNotNull {
            try { it.toSearchResult() } catch (e: Exception) { null }
        }
        return newHomePageResponse(
            HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = true,
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst(".card-title")?.text()?.trim().takeIf { !it.isNullOrBlank() }
            ?: attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null
        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (page > 1) return newSearchResponseList(emptyList(), hasNext = false)
        val res = app.get("$mainUrl/_ajax/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}")
        val results = try {
            mapper.readValue<SearchJson>(res.text).results.orEmpty()
        } catch (e: Exception) {
            Log.d(name, "search: ${e.message}"); emptyList()
        }
        val list = results.mapNotNull { r ->
            if (r.slug.isNullOrBlank() || r.title.isNullOrBlank()) return@mapNotNull null
            newMovieSearchResponse(r.title, "$mainUrl/${r.slug}", TvType.NSFW) {
                posterUrl = fixUrlNull(r.thumb)
            }
        }
        return newSearchResponseList(list, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.info-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
            ?: url

        val poster = fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
        val plot = document.selectFirst("meta[property=\"og:description\"]")?.attr("content")
            ?: document.selectFirst("#tab-overview p")?.text()

        val genres = document.select(".info-people").firstOrNull {
            it.selectFirst(".ip-label")?.text()?.contains("Genre") == true
        }?.select("a")?.map { it.text() } ?: emptyList()

        val actors = document.select(".info-people").firstOrNull {
            it.selectFirst(".ip-label")?.text()?.contains("Cast") == true
        }?.select("a")?.map { it.text() } ?: emptyList()

        val year = document.selectFirst(".badges a[href^=\"/year/\"]")?.text()?.trim()?.toIntOrNull()
            ?: Regex("datePublished\\D*?(\\d{4})").find(document.html())?.groupValues?.get(1)?.toIntOrNull()

        val duration = Regex("duration\\\":\\\"PT(?:(\\d+)H)?(?:(\\d+)M)?")
            .find(document.html())
            ?.let { (it.groupValues[1].toIntOrNull() ?: 0) * 60 + (it.groupValues[2].toIntOrNull() ?: 0) }
            ?.takeIf { it > 0 }

        val isSeries = url.contains("watch") || document.selectFirst(".badges")?.text()?.contains("Series") == true

        val recommendations = document.select("section#related a.card").mapNotNull {
            try { it.toSearchResult() } catch (e: Exception) { null }
        }

        val slug = url.removePrefix("$mainUrl/").trimEnd('/')
        return newTvSeriesLoadResponse(
            title, url, TvType.NSFW, loadEpisodes(slug)
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
            this.year = year
            this.duration = duration
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    // FINDINGS: /watch/{slug}?sv=1&part=1 exposes .epbtn[data-ep]; sources via /api/v1/episodes/{id}/sources
    private suspend fun loadEpisodes(slug: String): List<Episode> {
        val watch = app.get("$mainUrl/watch/$slug?sv=1&part=1", referer = "$mainUrl/$slug")
        val episodes = watch.document.select(".epbtn[data-ep]").mapIndexed { idx, btn ->
            newEpisode(btn.attr("data-ep")) {
                this.name = btn.selectFirst(".epname")?.text() ?: "Episode ${idx + 1}"
                this.episode = idx + 1
            }
        }
        return episodes.ifEmpty { listOf(newEpisode("1")) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = episode id
        try {
            val res = app.get("$mainUrl/api/v1/episodes/$data/sources", referer = "$mainUrl/")
            val json = mapper.readValue<SourcesJson>(res.text)
            json.sources.orEmpty().forEach { src ->
                if (src.file.isNullOrBlank()) return@forEach
                callback.invoke(
                    newExtractorLink(name, name, fixUrl(src.file)) {
                        this.referer = "$mainUrl/"
                        this.type = ExtractorLinkType.M3U8
                    }
                )
            }
        } catch (e: Exception) {
            Log.d(name, "loadLinks: ${e.message}")
        }
        return true
    }

    private data class SearchJson(val results: List<SearchItem>? = null)
    private data class SearchItem(val slug: String? = null, val title: String? = null,
                                  val thumb: String? = null, val year: Int? = null, val format: String? = null)
    private data class SourcesJson(val sources: List<SourceItem>? = null, val success: Boolean? = null)
    private data class SourceItem(val file: String? = null, val type: String? = null)
}

@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class Cat3FilmPlugin : com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(Cat3Film())
    }
}
