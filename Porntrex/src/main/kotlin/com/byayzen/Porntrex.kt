// ! This Extension Made By @ByAyzen for GizliKeyif

package com.byayzen

import com.kraptor.DurationParse
import com.kraptor.KvsFlashvars
import com.kraptor.registerHostExtractors
import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Porntrex : MainAPI() {
    override var mainUrl = "https://www.porntrex.com"
    override var name = "Porntrex"
    override val hasMainPage = true
    override var lang = "en"

    // issue #275: the site's quick-search endpoint /search_results.php?q=... returns an empty
    // "search" array for every sampled query (2026-09-10: milf, teen, asian, hardcore, big
    // tits, redhead) — suggestions are albums/categories/models, none loadable as videos.
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val tag = "gizlikeyif_${name}"

    override val mainPage = mainPageOf(
        "${mainUrl}/categories/4k-porn/" to "4K Porn",
        "${mainUrl}/categories/amateur/" to "Amateur",
        "${mainUrl}/categories/asian/" to "Asian",
        "${mainUrl}/categories/blonde/" to "Blonde",
        "${mainUrl}/categories/blowjob/" to "Blowjob",
        "${mainUrl}/categories/bondage/" to "Bondage",
        "${mainUrl}/categories/brunette/" to "Brunette",
        "${mainUrl}/categories/busty/" to "Busty",
        "${mainUrl}/categories/celebrities/" to "Celebrities",
        "${mainUrl}/categories/college/" to "College",
        "${mainUrl}/categories/cumshots/" to "Cumshots",
        "${mainUrl}/categories/doggystyle/" to "Doggystyle",
        "${mainUrl}/categories/fetish/" to "Fetish",
        "${mainUrl}/categories/fingering/" to "Fingering",
        "${mainUrl}/categories/hairy/" to "Hairy",
        "${mainUrl}/categories/handjob/" to "Handjob",
        "${mainUrl}/categories/hardcore/" to "Hardcore",
        "${mainUrl}/categories/hentai/" to "Hentai",
        "${mainUrl}/categories/homemade/" to "Homemade",
        "${mainUrl}/categories/lesbian/" to "Lesbian",
        "${mainUrl}/categories/masturbation/" to "Masturbation",
        "${mainUrl}/categories/milf/" to "Milf",
        "${mainUrl}/categories/petite/" to "Petite",
        "${mainUrl}/categories/pussy-licking/" to "Pussy licking",
        "${mainUrl}/categories/red-head/" to "Red Head",
        "${mainUrl}/categories/russian/" to "Russian",
        "${mainUrl}/categories/small-tits/" to "Small tits",
        "${mainUrl}/categories/solo/" to "Solo",
        "${mainUrl}/categories/teen/" to "Teen",
        "${mainUrl}/categories/toys/" to "Toys",
        "${mainUrl}/categories/uniform/" to "Uniform",
        "${mainUrl}/categories/vintage/" to "Vintage",
        "${mainUrl}/categories/webcam/" to "Webcam",
        "${mainUrl}/categories/wife/" to "Wife"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            "${request.data}?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&from=$page&_=${System.currentTimeMillis()}"
        } else {
            request.data
        }

        val document = app.get(
            url,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = request.data
        ).document

        val home = document.select("div.video-preview-screen.video-item").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = true),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("p.inf a")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.selectFirst("a.thumb")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img.cover")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val cleanQuery = query.trim().replace(" ", "-")
        val url = if (page > 1) {
            "${mainUrl}/search/$cleanQuery/?mode=async&function=get_block&block_id=list_videos_videos&q=$cleanQuery&category_ids=&sort_by=relevance&from=$page&_=${System.currentTimeMillis()}"
        } else {
            "${mainUrl}/search/$cleanQuery/"
        }

        val document = app.get(
            url,
            headers = if (page > 1) mapOf("X-Requested-With" to "XMLHttpRequest") else emptyMap(),
            referer = if (page > 1) "${mainUrl}/search/$cleanQuery/" else mainUrl
        ).document

        val searchAnswer = document.select("div.video-preview-screen.video-item").mapNotNull { it.toMainPageResult() }

        return newSearchResponseList(searchAnswer, hasNext = searchAnswer.isNotEmpty())
    }


    private fun videoId(url: String) = Regex("/video/(\\d+)/").find(url)?.groupValues?.get(1)

    override suspend fun load(url: String): LoadResponse? {
        Log.d(tag, "Load : $url")
        val document = app.get(url).document

        // 2026-10 (issue #171): guest video pages render an empty shell (no flashvars,
        // no title/details). The /embed/{id}/ page still carries the full KVS player config.
        var title = document.selectFirst("p.title-video")?.text()?.trim()
        var poster = fixUrlNull(document.selectFirst("#tab_screenshots img.thumb")?.attr("data-src"))
        var embed: org.jsoup.nodes.Document? = null
        if (title.isNullOrEmpty() && videoId(url) != null) {
            embed = app.get("${mainUrl}/embed/${videoId(url)}/").document
            title = Regex("title: '([^']+)'").find(embed.html())?.groupValues?.get(1)?.trim()
            if (poster == null)
                poster = fixUrlNull(Regex("preview_url: '([^']+)'").find(embed.html())?.groupValues?.get(1))
        }
        title ?: return null
        val description = document.selectFirst("div.videodesc em.des-link")?.text()?.trim()
        val tags        = document.select("div.js-categories a.js-cat").map { it.text() } +
                document.select("div.item:has(span.title-item:contains(Tags)) div.items-holder a").map { it.text() }
        // issue #275: shell pages carry no tag markup, but the embed flashvars still expose
        // video_categories + video_tags — merge them in when the page yields nothing.
        val resolvedTags = if (tags.isEmpty()) embed?.let { PorntrexParse.embedTags(it) } ?: emptyList() else tags
        val actors      = document.select("div.block-details div.item:has(span.title-item:contains(Models:)) div.items-holder a").map { it.ownText().trim() }.filter { it.isNotEmpty() }
        // issue #215: bare i.fa-clock-o first matches the navbar "Latest" icon -> always null;
        // scope to the details stats row and parse properly into seconds.
        val duration    = PorntrexParse.durationOf(document)
        var recommendations = document.select("div.video-list div.video-item").mapNotNull { it.toRecommendationResult() }
        // issue #171: shell pages render no related list; fall back to the live related-videos endpoint.
        if (recommendations.isEmpty() && videoId(url) != null) {
            recommendations = app.get("${mainUrl}/related_videos_html/${videoId(url)}/").document
                .select("a.player-related-videos-item.kt-api-related-item")
                .mapNotNull { it.toRelatedRecommendationResult() }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl       = poster
            this.plot            = description
            this.tags            = resolvedTags
            this.actors          = actors.map { ActorData(Actor(it)) }
            this.duration        = duration
            this.recommendations = recommendations
        }
    }

    // item nodes on /related_videos_html/{id}/ are <a class="...player-related-videos-item kt-api-related-item">
    private fun Element.toRelatedRecommendationResult(): SearchResponse? {
        val href  = fixUrlNull(attr("href")) ?: return null
        val title = selectFirst("span.title")?.text()?.trim().takeIf { !it.isNullOrBlank() }
            ?: attr("title").takeIf { it.isNotBlank() } ?: return null
        val poster = selectFirst("div.thumb")?.attr("style")
            ?.let { Regex("url\\(['\"]([^'\"]+)['\"]\\)").find(it)?.groupValues?.get(1) }
            ?.let { fixUrlNull(it) }
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title     = this.selectFirst("img.cover")?.attr("alt")?.ifEmpty { null } ?: return null
        val href      = fixUrlNull(this.selectFirst("a.thumb")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img.cover")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(tag, "data = $data")
        val html     = app.get(data).document.html()
        var flashvars = html
        if (!flashvars.contains("video_url:") && videoId(data) != null) {
            flashvars = app.get("${mainUrl}/embed/${videoId(data)}/").text
        }
        val links = PorntrexParse.qualityLinks(flashvars)
        if (links.isEmpty()) return false

        for ((label, url) in links) {
            callback(
                newExtractorLink(
                    source = name,
                    name   = if (label == null) name else "$name - $label",
                    url    = url,
                    type   = ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    // issue #759/#769: getQualityFromName mapped every suffix-bearing KVS label
                    // ('720p HD', '1080p FHD', '2160p 4K') to Qualities.Unknown (400), which the
                    // app renders as an empty quality tag and cannot quality-sort. Map through
                    // PorntrexParse.qualityOf (S1 decided table, FINDINGS-767 section 4); the
                    // display label stays — the app renders its own tag from the Int.
                    this.quality = PorntrexParse.qualityOf(label, url)
                }
            )
        }
        return true
    }
}
// Issue #215: pure parsing helpers, unit-tested against src/test/resources fixtures.
object PorntrexParse {

    /** Duration from the video details stats row (i.fa-clock-o inside block-details or the
     *  .video-info stats row, issue #309), in minutes (repo convention). The clock grammar
     *  itself lives in the shared fixture-tested DurationParse module. */
    fun durationOf(document: org.jsoup.nodes.Document): Int? =
        DurationParse.minutes(
            // issue #309: on live full-page DOM the badge sits in the .video-info stats row,
            // outside div.block-details. Widen to both; em.badge still keeps navbar "Latest" out.
            document.selectFirst("div.block-details div.item span:has(i.fa-clock-o) em.badge, .video-info .item span:has(i.fa-clock-o) em.badge")?.text()
        )

    /**
     * Issue #256: KVS flashvars carry video_url plus video_alt_url, video_alt_url2..4, each
     * with an optional <n>_text label. Variants flagged `video_<n>_redirect: '1'` hold the
     * video page URL (text/html), not a media file, so they are skipped. Returned (label, url)
     * pairs are in flashvar order (base first).
     */
    fun qualityLinks(flashvars: String): List<Pair<String?, String>> {
        val out = mutableListOf<Pair<String?, String>>()
        for (key in listOf("url", "alt_url", "alt_url2", "alt_url3", "alt_url4")) {
            // KVS's own redirect flag: 1 means the alt URL is a page, never an ExtractorLink.
            if (Regex("video_${key}_redirect:\\s*'1'").containsMatchIn(flashvars)) continue
            val url = KvsFlashvars.field(flashvars, "video_$key") ?: continue
            out.add(KvsFlashvars.label(flashvars, "video_$key") to url)
        }
        return out
    }

    /** Issue #429 → #759 chain: pure label/url -> quality Int, the S1 decided mapping table
     *  (FINDINGS-767 section 4), implemented in S3 (#769, green step):
     *
     *  1. leading resolution number from the flashvar `<key>_text` label, range-validated to
     *     144..4320 — the canonical KVS signal on this site (`480p`, `720p HD`, `1080p FHD`,
     *     `2160p 4K`; `getQualityFromName` degrades every suffix-bearing label to Unknown);
     *  2. numberless `4K`/`4k` — the decompiled getQualityFromName grammar (bytecode-verified
     *     in S1), inlined here: calling the real function would drag ExtractorApiKt's <clinit>
     *     — the full extractor registry — onto the unit-test classpath (S1 constraint);
     *  3. URL-pattern fallback (`_NNNp.mp4` in the path, same range validation) when the text
     *     label carries no usable signal;
     *  4. else Qualities.Unknown (400) — the #759 empty-quality-tag behavior.
     *
     *  Label first, per the S1 evidence: Porntrex filenames (`<id>.mp4`, signed /get_file/ URLs)
     *  carry no resolution — the URL pattern is a defensive fallback only. Range bound 4320
     *  covers current consumer cap (2160) with headroom for 4320p/8K ladders without letting
     *  garbage digits (timestamps, ids) pass as a resolution.
     */
    fun qualityOf(label: String?, url: String? = null): Int {
        val labelNumber = label?.let {
            Regex("""(\d{3,4})""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (labelNumber != null && labelNumber in 144..4320) return labelNumber
        // mnemonically getQualityFromName(label) — grammar inlined to keep the unit-test
        // classpath free of ExtractorApiKt (dev.whyoleg.cryptography is app-scope only).
        val name = (label ?: "").lowercase(java.util.Locale.ROOT).replace("p", "").trim()
        val fromName = when {
            name == "4k" -> Qualities.P2160.value
            else -> name.toIntOrNull()?.takeIf { it in 144..4320 } ?: Qualities.Unknown.value
        }
        if (fromName != Qualities.Unknown.value) return fromName
        val urlNumber = url?.let {
            Regex("""(\d{3,4})p\.mp4""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (urlNumber != null && urlNumber in 144..4320) return urlNumber
        return Qualities.Unknown.value
    }

    /**
     * Issue #275: on guest-shell video pages the /embed/{id}/ flashvars still expose
     * `video_categories: 'A, B'` and `video_tags: 'x, y'`. Returns categories followed by
     * tags (deduped, order preserved); empty when neither key exists.
     */
    fun embedTags(document: org.jsoup.nodes.Document): List<String> {
        val flashvars = document.selectFirst("script:containsData(var flashvars)")?.data() ?: return emptyList()
        fun key(name: String): List<String> =
            Regex("$name:\\s*'([^']*)'").find(flashvars)?.groupValues?.get(1)
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        return (key("video_categories") + key("video_tags")).distinct()
    }
}

@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class PorntrexPlugin : com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(Porntrex())
        registerHostExtractors()
    }
}
