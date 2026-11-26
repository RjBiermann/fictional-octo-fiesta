// ! Bu araç @ByAyzen tarafından | @Cs-GizliKeyif için yazılmıştır.

package com.byayzen

import com.kraptor.JavtifulEmbedParse
import com.kraptor.JsonLdParse
import com.kraptor.registerHostExtractors
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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

private const val STREAM_ORIGIN = "https://javtiful.com"

/** Absolute stream URL for the handshake's page-relative `/media/video/…` paths
 *  (`/media/video/<id>/720p?expires=…&signature=…` is same-origin, not a CDN URL). */
fun absoluteStream(src: String?, origin: String = STREAM_ORIGIN): String? = when {
    src.isNullOrBlank() -> null
    src.startsWith("http") -> src
    else -> origin.trimEnd('/') + "/" + src.trimStart('/')
}

/** loadLinks' pure seam (issue #1463): the 2026-11 page shape dropped the inline
 *  `playerSources` list — `script#watch-config` now carries a one-shot
 *  `playback{endpoint,ticket}` handshake and the sources only arrive from
 *  `POST <endpoint>` with the form body `ticket=…`. Empty when the page has no handshake
 *  (pre-drift save, deleted video) or the POST body is junk, so a broken link is never
 *  emitted. Parsing itself lives in shared/ (`JavtifulEmbedParse`), where the same
 *  handshake already backs the javtiful embed host (#1444). */
fun playbackSources(html: String?, playbackBody: String?): List<JavtifulEmbedParse.Source> {
    if (JavtifulEmbedParse.playback(html) == null) return emptyList()
    return JavtifulEmbedParse.sources(playbackBody).mapNotNull { source ->
        absoluteStream(source.src)?.let { source.copy(src = it) }
    }
}

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

/** Year from the watch page's "Added on" timestamp. The 2026-11-25 drift (issue #1463)
 *  moved the meta rows out of `div.watch-detail` into `div.watch-detail-fact`, which broke
 *  a container-class anchor — so anchor on the structural parts instead: the "Added on"
 *  label's own row, then the `<time datetime>` inside it (a watch page carries exactly one
 *  timestamp; the first one is the fallback if the label ever moves). */
fun addedOnYear(res: Element): Int? {
    val labelledRow = res.select(":containsOwn(Added on)").firstOrNull()?.parent()
    val time = labelledRow?.selectFirst("time[datetime]") ?: res.selectFirst("time[datetime]")
    return time?.attr("datetime")?.split("-")?.firstOrNull()?.toIntOrNull()
}

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

    /** Two-step player bootstrap (issue #1463): the page hands out
     *  `playback{endpoint,ticket}`, the POST trades the ticket for signed sources. */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text
        val playback = JavtifulEmbedParse.playback(html) ?: return false
        val endpoint = absoluteStream(playback.endpoint) ?: return false
        val playbackBody = try {
            app.post(
                endpoint,
                data = mapOf("ticket" to playback.ticket),
                referer = data,
                headers = mapOf(
                    "Origin" to STREAM_ORIGIN,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text
        } catch (e: Exception) {
            null
        }

        val sources = playbackSources(html, playbackBody)
        sources.forEach { source ->
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    source.src
                ) {
                    this.quality = source.size ?: Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                    // the source carries the MIME ("video/mp4"); the `/media/video/<id>/720p`
                    // path is extensionless, so extension sniffing would say M3U8
                    // (issue #240, ExoPlayer 3002)
                    this.type =
                        if (isHls(source.src, source.type)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
        }

        return sources.isNotEmpty()
    }
}
@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class JavtifulPlugin : com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(Javtiful())
        registerHostExtractors()
    }
}
