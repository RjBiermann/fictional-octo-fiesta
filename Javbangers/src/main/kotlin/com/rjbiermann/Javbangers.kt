package com.rjbiermann

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// Same KVS engine as Porntrex (porntrex-box markup), see Javbangers/FINDINGS.md.
class Javbangers : MainAPI() {
    override var mainUrl = "https://www.javbangers.com"
    override var name = "Javbangers"
    override val hasMainPage = true
    override var lang = "ja"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val tag = "javbangers"

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updates/" to "Latest Updates",
        "$mainUrl/most-popular/" to "Most Viewed",
        "$mainUrl/top-rated/" to "Top Rated",
        "$mainUrl/categories/milf/" to "Milf",
        "$mainUrl/categories/uncensored/" to "Uncensored",
        "$mainUrl/categories/creampie/" to "Creampie",
        "$mainUrl/categories/cosplay/" to "Cosplay",
        "$mainUrl/categories/hentai/" to "Hentai",
        "$mainUrl/categories/chinese/" to "Chinese", // categories/asian/ 404s (verified); chinese is a live slug (24 cards)
        "$mainUrl/categories/teen/" to "Teen",
    )

    private fun Element.toResult(): SearchResponse? = try {
        val a = selectFirst("a.thumb") ?: return null
        val title = a.attr("title").ifBlank {
            selectFirst("p.inf a")?.text() ?: return null
        }
        newMovieSearchResponse(title, a.attr("href"), TvType.NSFW) {
            posterUrl = fixUrlNull(selectFirst("img.cover")?.attr("data-original"))
        }
    } catch (e: Exception) {
        Log.d(tag, "toResult: ${e.message}"); null
    }

    // FINDINGS: path pagination /{base}/{N}/
    private fun pageUrl(base: String, page: Int) = if (page <= 1) base else "${base.removeSuffix("/")}/$page/"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = app.get(pageUrl(request.data, page)).document
            .select("div.video-item").mapNotNull { it.toResult() }
        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = true),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // FINDINGS: pages beyond 1 unreachable (404 / ignored params) — page 1 only.
        val doc = app.get("$mainUrl/search/${query.trim().replace(" ", "-")}/").document
        val results = doc.select("div.video-item").mapNotNull { it.toResult() }
        return newSearchResponseList(results, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document ?: return null
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val details = doc.selectFirst("div.block-details")
        val tags = details?.select("a[href*=\"/categories/\"]")?.map { it.text() } ?: emptyList()
        val recommendations = doc.select("div.related-videos div.video-item")
            .mapNotNull { rel -> rel.selectFirst("a.thumb")?.let { a ->
                newMovieSearchResponse(
                    a.attr("title").ifBlank { rel.selectFirst("p.inf a")?.text() ?: "" },
                    a.attr("href"), TvType.NSFW
                ) {
                    posterUrl = fixUrlNull(rel.selectFirst("img.cover")?.attr("data-original"))
                }
            } }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = fixUrlNull(doc.selectFirst("meta[property=\"og:image\"]")?.attr("content"))
            plot = doc.selectFirst("div.videodesc em")?.text()?.trim()
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).document.html()
        val flash = html.substringAfter("flashvars = {").substringBefore("};")
        val sources = mutableMapOf(
            "video_url" to flash.substringAfterJb("video_url"),
            "video_alt_url" to flash.substringAfterJb("video_alt_url")
        )
        var pushed = false
        for ((key, url) in sources) {
            val v = url ?: continue
            // quality label from video_url_text / video_alt_url_text
            val qualityKey = key + "_text"
            val label = Regex("$qualityKey:\\s*'([^']+)'").find(flash)?.groupValues?.get(1)
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = v,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = getQualityFromName(label ?: "")
                }
            )
            pushed = true
        }
        return pushed
    }
}

// FINDINGS: flashvars keys are unquoted (video_url: 'https://...'), values single-quoted. Keep it first-order: if a future video ships an encrypted / shifted flashvars instead of a plain URL, detect the missing get_file and drop a comment — do not guess the KVS cipher.
private fun String.substringAfterJb(key: String): String? {
    val m = Regex(key + "\\s*:\\s*'([^']+)'").find(this)?.groupValues?.get(1)
    return m?.takeIf { it.startsWith("http") }
}

@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class JavbangersPlugin: com.lagradost.cloudstream3.plugins.Plugin() {
    override fun load() {
        registerMainAPI(Javbangers())
    }
}
