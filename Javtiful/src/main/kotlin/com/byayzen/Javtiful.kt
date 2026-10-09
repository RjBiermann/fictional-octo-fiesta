// ! Bu araç @ByAyzen tarafından | @Cs-GizliKeyif için yazılmıştır.

package com.byayzen

import com.kraptor.JsonLdParse
import com.kraptor.registerHostExtractors
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import okhttp3.Request
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

// Page-URL builder for main-page rows; top-level (not on the MainAPI subclass) so its
// unit test can run without the cloudstream stubs on the test runtime classpath.
fun pagedUrl(url: String, page: Int): String =
    if (page <= 1) url else url + (if ("?" in url) "&" else "?") + "page=$page"

/** HLS only when the MIME says mpegurl or the URL ends in .m3u8 — anything else is MP4. */
fun isHls(src: String, mimeType: String?): Boolean =
    mimeType?.contains("mpegurl", ignoreCase = true) == true ||
        src.substringBefore('?').endsWith(".m3u8")

// Pure redesign selectors (issue #534): the site dropped the whole `front-*` vocabulary.
// Top-level so the unit tests run without the cloudstream stubs on the test classpath.

/** Listing cards are article.video-card; partner ad cards carry video-card--partner. */
fun videoCards(res: Element): Elements =
    res.select("article.video-card:not(.video-card--partner)")

/** Card title/href from a.video-card__title, poster from img[data-front-lazy-src]
 *  (src is a placeholder svg — lazy only). Null drops the card. */
fun videoCard(card: Element): Triple<String, String, String>? {
    val link = card.selectFirst("a.video-card__title") ?: return null
    val title = link.text().trim()
    val href = link.attr("href").trim()
    if (title.isEmpty() || href.isEmpty()) return null
    val img = card.selectFirst("a.video-card__thumbnail img")
    val poster = img?.attr("data-front-lazy-src")
        ?.trim()?.takeIf { it.isNotEmpty() }
        ?: img?.attr("src").orEmpty()
    return Triple(title, href, poster)
}

/** Redesigned pagination: Next label on a.pagination__link, disabled as is-disabled. */
fun hasNextPage(res: Element): Boolean =
    res.select("a.pagination__link").any { it.text() == "Next" && !it.hasClass("is-disabled") }

/** Year from the watch page's "Added on" datetime attribute. */
fun addedOnYear(res: Element): Int? =
    res.selectFirst("div.watch-detail:contains(Added on) time[datetime]")
        ?.attr("datetime")?.split("-")?.firstOrNull()?.toIntOrNull()

/** Categories watch-detail carries is-category chips, Tags carries plain ones. */
fun watchTags(res: Element): List<String> =
    res.select(
        "div.watch-detail:contains(Categories) a.watch-link-chip, " +
            "div.watch-detail:contains(Tags) a.watch-link-chip"
    ).map { it.text().trim() }

class Javtiful : MainAPI() {
    override var mainUrl = "https://javtiful.com"
    override var name = "Javtiful"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/videos" to "Newest",
        "${mainUrl}/videos?sort=most_viewed" to "Most Viewed",
        "${mainUrl}/videos?sort=top_rated" to "Top Rated",
        // "${mainUrl}/videos?sort=top_favorites" to "Top Favorites",
        //"${mainUrl}/videos?sort=being_watched" to "Being Watched",
        //"${mainUrl}/censored" to "Censored",
        "${mainUrl}/uncensored" to "Uncensored",
        "${mainUrl}/category/female-investigator" to "Female Investigator",
        "${mainUrl}/category/chinese-av" to "Chinese AV",
        "${mainUrl}/category/female-boss" to "Female Boss",
        "${mainUrl}/category/mature-woman" to "Mature Woman",
        "${mainUrl}/category/cosplay" to "Cosplay",
        "${mainUrl}/category/amateur" to "Amateur",
        "${mainUrl}/category/housekeeper" to "Housekeeper",
        "${mainUrl}/category/nurse" to "Nurse",
        "${mainUrl}/category/female-student" to "Female Student",
        "${mainUrl}/category/school-girls" to "School Girls",
        "${mainUrl}/category/office-lady" to "Office Lady",
        "${mainUrl}/category/sister-in-law" to "Sister-in-law",
        "${mainUrl}/category/hypnosis" to "Hypnosis",
        "${mainUrl}/category/beautiful-girl" to "Beautiful Girl",
        "${mainUrl}/category/bbw" to "BBW",
        "${mainUrl}/category/drama" to "Drama",
        "${mainUrl}/category/married-woman" to "Married Woman",
        "${mainUrl}/category/milf" to "Milf",
        "${mainUrl}/category/female-teacher" to "Female Teacher",
        "${mainUrl}/category/affair" to "Affair",
        "${mainUrl}/category/big-tits" to "Big Tits"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pagedUrl(request.data, page)
        val res = app.get(url).document
        val cards = videoCards(res)
        val home = cards.mapNotNull { it.mainPageResults() }
            .distinctBy { it.name } // site lists censored + reducing-mosaic variants of a code under one title
        val hasNext = hasNextPage(res)
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url =
            if (page <= 1) "$mainUrl/search?q=$query" else "$mainUrl/search?page=$page&q=$query"
        val res = app.get(url).document
        val results = videoCards(res).mapNotNull { it.mainPageResults() }
            .distinctBy { it.name } // same censored/reducing-mosaic variant dedupe
        val hasNext = hasNextPage(res)
        return newSearchResponseList(results, hasNext)
    }

    private fun Element.mainPageResults(): SearchResponse? {
        val (title, href, poster) = videoCard(this) ?: return null
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = fixUrlNull(poster)
        }
    }


    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url).document
        val title = res.selectFirst("div.watch-title h1")?.text()?.trim() ?: return null
        val poster = res.selectFirst("meta[property=\"og:image\"]")?.attr("content")

        val recommendations =
            res.select("div.video-grid.related-grid article.video-card")
                .mapNotNull { it.mainPageResults() }
                .distinctBy { it.name } // related grid repeats censored/reducing-mosaic variants

        val actorslist = res.select("a.watch-actor-card").map {
            val name = it.selectFirst("span")?.text()?.trim() ?: ""
            val image = it.selectFirst("img")?.attr("src")?.takeIf { img ->
                img.isNotEmpty() && !img.contains("profile-placeholder.png")
            }
            Actor(name, fixUrlNull(image))
        }

        val year = addedOnYear(res)
        val duration = JsonLdParse.minutes(res.html())
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.duration = duration
            this.posterUrl = fixUrlNull(poster)
            this.plot =
                res.selectFirst("meta[property=\"og:description\"]")?.attr("content")?.trim()
            this.year = year
            this.tags = watchTags(res)
            this.recommendations = recommendations
            addActors(actorslist)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data).document
        val configraw = res.selectFirst("script#watch-config")?.data()
        val configdata = try {
            configraw?.let { raw -> mapper.readValue<WatchConfig>(raw) }
        } catch (e: Exception) {
            null
        }

        configdata?.playerSources?.forEach { source ->
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    source.src
                ) {
                    this.quality = source.size ?: Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                    // config carries the MIME ("video/mp4"); extensionless stream URLs
                    // broke extension sniffing (issue #240, ExoPlayer 3002)
                    this.type =
                        if (isHls(source.src, source.type)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
        }

        return !configdata?.playerSources.isNullOrEmpty()
    }

    data class WatchConfig(
        @param:JsonProperty("playerSources") val playerSources: List<PlayerSource>? = null
    )

    data class PlayerSource(
        @param:JsonProperty("src") val src: String,
        @param:JsonProperty("type") val type: String? = null,
        @param:JsonProperty("size") val size: Int? = null
    )
}
@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class JavtifulPlugin : com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(Javtiful())
        registerHostExtractors()
    }
}
