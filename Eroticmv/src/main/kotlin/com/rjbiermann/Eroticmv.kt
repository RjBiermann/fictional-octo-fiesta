package com.rjbiermann

import com.kraptor.decodeBase64
import com.kraptor.registerHostExtractors
import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*


class Eroticmv : MainAPI() {
    companion object {
        // Stars block: .actor-element.single-element with <a href=".../actor/slug/" title="Name">
        fun parseActors(document: Element): List<String> =
            document.select(".actor-element.single-element a[href*='/actor/']")
                .mapNotNull { it.attr("title").trim().takeIf { t -> t.isNotEmpty() } }
                .distinct()

        /**
         * og:video:url comes in two shapes (FINDINGS 2026-09-11):
         * A: "http://<base64>.m3u8" → decode token directly.
         * B: "...?video_embed=<id>" → stream lives on the embed page's <source src> tag;
         *    pass its HTML, null otherwise.
         */
        fun parseStreamUrl(raw: String, embedHtml: String?): String? {
            if (raw.contains("?video_embed=")) {
                val m = Regex("source src=\"([^\"]+\\.m3u8)\"").find(embedHtml ?: return null)
                return m?.groupValues?.get(1)
            }
            val token = raw.substringAfterLast("/").removeSuffix(".m3u8")
            val stream = decodeBase64(token) ?: return null
            return stream.takeIf { it.startsWith("http") }
        }
    }

    override var mainUrl = "https://eroticmv.com"
    override var name = "Eroticmv"
    override val hasMainPage = true
    override val hasQuickSearch = false  // plain form GET, no suggest endpoint (issue #290)
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val tag = "Eroticmv"

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Erotic Movies",  // pagination → /page/N/
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "$mainUrl/page/$page/" else request.data
        val document = app.get(url, referer = mainUrl).document

        val home = document.select("article.post-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        return try {
            val link = this.selectFirst("a.blog-img") ?: return null
            val href = fixUrlNull(link.attr("href")) ?: return null
            val title = link.attr("title").trim().ifEmpty { link.selectFirst("img")?.attr("alt")?.trim() }
                ?: return null
            // lazy poster: src is a placeholder, data-src is the real image (FINDINGS)
            val posterUrl = fixUrlNull(
                link.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotEmpty() }
                    ?: link.selectFirst("img")?.attr("src")
            )
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (page > 1) return newSearchResponseList(emptyList(), hasNext = false) // search has no pagination (FINDINGS: /page/2/?s= → 404)
        val document = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}", referer = mainUrl).document
        val results = document.select("article.post-item").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(tag, "Load : $url")
        val document = app.get(url, referer = mainUrl).document

        val title = document.selectFirst("meta[property=\"og:title\"]")?.attr("content")?.trim()
            ?.removePrefix("Watch ")?.substringBefore(" - Erotic Movies")
            ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
        val description = document.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()
        val recommendations = document
            .select(".single-related-posts article.post-item")
            .mapNotNull { it.toSearchResult() }

        // JSON-LD articleSection = genres; release year from og:title "(1987)"
        // (datePublished is the WP posting date, not the release year — FINDINGS)
        val jsonLd = document.selectFirst("script[type='application/ld+json']")?.data().orEmpty()
        val raw = jsonLd.substringAfter("articleSection", "").substringAfter("[", "").substringBefore("]")
        val tags = raw.split(",").map { it.trim('"', ' ') }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val actors = parseActors(document)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
            this.tags = tags
            this.year = year
            this.actors = actors.map { ActorData(Actor(it)) }
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

        // og:video:url has two shapes (FINDINGS 2026-09-11): A "http://<base64>.m3u8" (decode),
        // B "...?video_embed=<id>" (stream in the embed page's <source src>); parseStreamUrl handles both.
        val raw = Regex("og:video:url\"\\s*content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: return false
        val embedHtml = if (raw.contains("?video_embed=")) {
            Log.d(tag, "Shape B: fetching embed page $raw")
            app.get(raw, referer = mainUrl).text
        } else null
        val streamUrl = parseStreamUrl(raw, embedHtml) ?: run {
            Log.d(tag, "stream extraction failed for: $raw")
            return false
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
            }
        )
        return true
    }
}

@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class EroticmvPlugin : com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(Eroticmv())
        registerHostExtractors()
    }
}
