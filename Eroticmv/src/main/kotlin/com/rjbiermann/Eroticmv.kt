package com.rjbiermann

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Base64

class Eroticmv : MainAPI() {
    override var mainUrl = "https://eroticmv.com"
    override var name = "Eroticmv"
    override val hasMainPage = true
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

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
            this.tags = tags
            this.year = year
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

        // og:video:url content is "http://<base64>.m3u8"; strip suffix, decode → HLS URL (FINDINGS)
        val raw = Regex("og:video:url\"\\s*content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: return false
        val token = raw.substringAfterLast("/").removeSuffix(".m3u8")
        val padded = token + "=".repeat((4 - token.length % 4) % 4) // android.util.Base64 requires padding
        val streamUrl = try {
            String(Base64.decode(padded, Base64.NO_WRAP))
        } catch (e: IllegalArgumentException) {
            Log.d(tag, "base64 decode failed: $token")
            return false
        }
        if (!streamUrl.startsWith("http")) return false

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
class EroticmvPlugin : com.lagradost.cloudstream3.plugins.Plugin() {
    override fun load() {
        registerMainAPI(Eroticmv())
    }
}
