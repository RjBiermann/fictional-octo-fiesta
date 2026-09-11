package com.rjbiermann

import com.kraptor.registerHostExtractors
import com.lagradost.api.Log
import org.jsoup.nodes.Element
import java.util.Calendar
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
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val details = doc.selectFirst("div.block-details")
        val tags = JavbangersParse.tagsFromDetails(details, title)
        // "Submitted: <em class=badge>N years ago</em>" — no absolute date on the page
        val year = JavbangersParse.yearFromAge(
            details?.select("div.item span em.badge")
                ?.firstOrNull { it.text().contains("ago") }?.text()
        )
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
            this.year = year
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

// TDD for issue #297: video pages carry a Tags row (div.item starting "Tags:") whose
// anchors have no href — the old categories-only selector never captured it.
object JavbangersParse {
    /** Categories + Tags row merged, deduped, title-echo entries dropped. */
    fun tagsFromDetails(details: Element?, title: String?): List<String> {
        if (details == null) return emptyList()
        val out = LinkedHashSet<String>()
        details.select("a[href*=\"/categories/\"]").forEach { add(out, it.text().trim(), title) }
        // Tags row: div.item whose combined text starts "Tags:"; its anchors carry no href.
        details.select("div.item").firstOrNull { it.text().trim().startsWith("Tags:") }?.let { row ->
            row.select("a").forEach { add(out, it.text().trim(), title) }
        }
        return out.toList()
    }

    private fun add(out: LinkedHashSet<String>, text: String, title: String?) {
        if (text.isNotEmpty() && text != title?.trim()) out += text
    }

    /** Relative-age badge text ("3 years ago") → upload year; empty/absent → null. */
    fun yearFromAge(badgeText: String?, today: Calendar = Calendar.getInstance()): Int? {
        val m = Regex("(\\d+)\\s*(year|month|week|day|hour)s?\\s+ago", RegexOption.IGNORE_CASE)
            .find(badgeText ?: return null) ?: return null
        val n = m.groupValues[1].toInt()
        val year = today.get(Calendar.YEAR)
        return when (m.groupValues[2].lowercase()) {
            "year" -> year - n
            "month" -> (today.get(Calendar.YEAR) * 12 + today.get(Calendar.MONTH) - n) / 12
            // weeks/days/hours: always recent — only slips a year when today is within that
            // many days of Jan 1 (site renders "N week ago" singular, hence the s? above)
            else -> {
                val days = if (m.groupValues[2].equals("week", ignoreCase = true)) n * 7 else n
                if (today.get(Calendar.DAY_OF_YEAR) <= days) year - 1 else year
            }
        }
    }
}

@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class JavbangersPlugin : com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(Javbangers())
        registerHostExtractors()
    }
}
