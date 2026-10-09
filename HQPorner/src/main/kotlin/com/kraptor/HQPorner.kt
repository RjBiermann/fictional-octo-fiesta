// ! Bu araç @Kraptor123 tarafından | @Cs-GizliKeyif için yazılmıştır.

package com.kraptor

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Document

class HQPorner : MainAPI() {
    override var mainUrl              = "https://hqporner.com"
    // ponytail: hqporner 302s mobile UAs to m.hqporner.com, whose cards don't match our selectors — pin the desktop UA on every request
    private val desktopUa             = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) Gecko/20100101 Firefox/130.0"
    override var name                 = "HQPorner"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val supportedTypes       = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "${mainUrl}/top"                           to "All time best porn",
        "${mainUrl}/top/month"                     to "Month top porn",
        "${mainUrl}/top/week"                      to "Week top porn",
        "${mainUrl}/category/1080p-porn"           to "1080p porn",
        "${mainUrl}/category/4k-porn"              to "4k porn",
        "${mainUrl}/category/60fps-porn"           to "60fps",
        "${mainUrl}/category/amateur"              to "Amateur",
        "${mainUrl}/category/anal-sex-hd"          to "Anal",
        "${mainUrl}/category/asian"                to "Asian",
        "${mainUrl}/category/babe"                 to "Babe",
        "${mainUrl}/category/bdsm"                 to "Bdsm",
//        "${mainUrl}/category/beach-porn"           to "beach",
        "${mainUrl}/category/big-ass"              to "Big Ass",
//        "${mainUrl}/category/big-dick"             to "big dick",
        "${mainUrl}/category/big-tits"             to "Big Tits",
//        "${mainUrl}/category/bisexual"             to "bisexual",
        "${mainUrl}/category/blonde"               to "Blonde",
        "${mainUrl}/category/blowjob"              to "Blowjob",
        "${mainUrl}/category/bondage"              to "Bondage",
        "${mainUrl}/category/brunette"             to "Brunette",
//        "${mainUrl}/category/casting"              to "casting",
        "${mainUrl}/category/creampie"             to "Creampie",
//        "${mainUrl}/category/cumshot"              to "cumshot",
//        "${mainUrl}/category/deepthroat"           to "deepthroat",
        "${mainUrl}/category/ebony"                to "Ebony",
//        "${mainUrl}/category/fetish"               to "fetish",
//        "${mainUrl}/category/fingering"            to "fingering",
//        "${mainUrl}/category/fisting"              to "fisting",
        "${mainUrl}/category/gangbang"             to "GangBang",
//        "${mainUrl}/category/group-sex"            to "group sex",
//        "${mainUrl}/category/hairy-pussy"          to "hairy pussy",
        "${mainUrl}/category/handjob"              to "HandJob",
//        "${mainUrl}/category/hentai"               to "hentai",
//        "${mainUrl}/category/interracial"          to "interracial",
        "${mainUrl}/category/japanese-girls-porn"  to "Japanese",
//        "${mainUrl}/category/latina"               to "latina",
        "${mainUrl}/category/lesbian"              to "Lesbian",
//        "${mainUrl}/category/long-hair"            to "long hair",
//        "${mainUrl}/category/masturbation"         to "masturbation",
        "${mainUrl}/category/mature"               to "Mature",
        "${mainUrl}/category/milf"                 to "Milf",
//        "${mainUrl}/category/moaning"              to "moaning",
        "${mainUrl}/category/old-and-young"        to "Old and Young",
//        "${mainUrl}/category/orgasm"               to "orgasm",
//        "${mainUrl}/category/orgy"                 to "orgy",
        "${mainUrl}/category/outdoor"              to "Outdoor",
//        "${mainUrl}/category/pickup"               to "pickup",
        "${mainUrl}/category/pov"                  to "Pov",
        "${mainUrl}/category/public"               to "Public",
//        "${mainUrl}/category/pussy-licking"        to "pussy licking",
        "${mainUrl}/category/redhead"              to "Redhead",
        "${mainUrl}/category/russian"              to "Russian",
//        "${mainUrl}/category/porn-massage"         to "sex massage",
//        "${mainUrl}/category/sex-parties"          to "sex party",
        "${mainUrl}/category/shaved-pussy"         to "Shaved Pussy",
//        "${mainUrl}/category/shemale"              to "shemale",
        "${mainUrl}/category/small-tits"           to "Small Tits",
//        "${mainUrl}/category/squeezing-tits"       to "squeezing tits",
//        "${mainUrl}/category/squirt"               to "squirt",
        "${mainUrl}/category/stockings"            to "Stockings",
        "${mainUrl}/category/tattooed"             to "Tattooed",
        "${mainUrl}/category/teen-porn"            to "Teen porn",
        "${mainUrl}/category/vintage"              to "Vintage",
//        "${mainUrl}/category/threesome"            to "threesome",
//        "${mainUrl}/category/undressing"           to "undressing",
        "${mainUrl}/category/uniforms"             to "Uniforms",
//        "${mainUrl}/category/vibrator"             to "vibrator",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/$page", referer = "$mainUrl/", headers = mapOf("User-Agent" to desktopUa)).document
        // ponytail: mobile UA serves no hover span.icon in cards; a.image is stable in both layouts
        // ponytail: site randomizes the card class token per request (box feature / box features / …); anchor on the stable a.image-popup link inside the section
        val home     = document.select("div.row section:has(a.image)").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(HomePageList(request.name, home, true))
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val ftitle  = this.selectFirst("img")?.attr("alt") ?: return null
        val title     = ftitle.replaceFirstChar { it.uppercase() }
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val src       = this.selectFirst("img")?.attr("src") ?: return null
        val posterUrl = if (src.startsWith("//")) "https:$src" else src

        return newMovieSearchResponse(title, "${href}kraptor$posterUrl", TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.get("${mainUrl}/?q=${query}&p=$page", referer = "${mainUrl}/", headers = mapOf("User-Agent" to desktopUa)).document

        val aramaCevap = document.select("div.row section:has(a.image)").mapNotNull { it.toMainPageResult() }
        return newSearchResponseList(aramaCevap, hasNext = true)
    }


    override suspend fun load(url: String): LoadResponse? {
        // Library-saved urls carry only currentUrl (search urls append "kraptor" + posterUrl);
        // the video page's static HTML embeds no image of the current video (iframe player, no
        // og:image), so a library reload gets a null poster rather than a related card's cover.
        val parts = url.split("kraptor")
        val currentUrl = parts[0].trim()
        val feedPoster = parts.getOrNull(1)?.trim()
        val document = app.get(currentUrl, referer = "$mainUrl/", headers = mapOf("User-Agent" to desktopUa)).document

        val title           = document.selectFirst("h1")?.text()?.trim() ?: return null
        val description     = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year            = document.selectFirst("div.extra span.C a")?.text()?.trim()?.toIntOrNull()
        val tags            = document.select("section h3 + p a").map { it.text() }
        val score           = document.selectFirst("span.dt_rating_vgs")?.text()?.trim()
        // duration clock grammar ("1h 22m" badges) lives in the shared DurationParse Parse function
        val duration        = DurationParse.minutes(document.selectFirst("li.icon.fa-clock-o")?.text())

        val recommendations = document.select("div.\\34 u section").mapNotNull { it.toMainPageResult() }
        val actors          = document.select("li.icon.fa-star-o a").map { Actor(it.text()) }

        return newMovieLoadResponse(title, currentUrl, TvType.NSFW, currentUrl) {
            this.posterUrl       = feedPoster ?: playerPoster(document, currentUrl)
            this.posterHeaders   = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
            this.plot            = description
            this.year            = year
            this.tags            = tags
            this.score           = Score.from10(score)
            this.duration        = duration
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    // ponytail: black details-page poster on plain/library loads (issue #518) — the video page
    // embeds no image of the current video (no og:image, only other cards' covers). The player
    // embed carries the video's own cover: hqwo iframe src has img=<b64 cover> (no extra fetch),
    // mydaddy embed body has poster="//s62.bigcdn.cc/pubs/<key>/main.jpg". Null on any failure —
    // feed loads keep the kraptor-embedded poster and pay no extra request.
    private suspend fun playerPoster(document: Document, pageUrl: String): String? {
        val iframe = fixUrlNull(document.selectFirst("iframe[src*=mydaddy], iframe[src*=hqwo]")?.attr("src")) ?: return null
        val cover = PlayerPosterParse.fromSrc(iframe)
        if (cover != null) return cover
        val body = app.get(iframe, referer = pageUrl + "/", headers = mapOf("User-Agent" to FIREFOX_UA)).text
        return PlayerPosterParse.fromBody(body)
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("kraptor_$name", "data = ${data}")
        val document = app.get(data, referer = "${data}/", headers = mapOf(
            "User-Agent" to FIREFOX_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer" to "${mainUrl}/",
        )).document

        // ponytail: two player domains in the wild — recent videos embed mydaddy.cc,
        // older uploads (e.g. 39719-Maria_Ozawa_2 from issue #431) embed hqwo.cc; hqwo bodies carry the same a href='…/pubs/<key>/<res>.mp4' markup the shared MyDaddyExtractor regex parses
        val iframe = fixUrlNull(document.selectFirst("iframe[src*=mydaddy], iframe[src*=hqwo]")?.attr("src")) ?: ""

//        Log.d("kraptor_$name", "iframe = ${iframe}")

        loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)

        return true
    }
}
// Pure Parse (issue #518): resolve the current video's own cover from its player embed.
// hqwo iframe src: ?img=<URL-encoded base64 of the cover url>; mydaddy embed body:
// poster="//s62.bigcdn.cc/pubs/<key>/main.jpg" (the same CDN path shape the videos use).
object PlayerPosterParse {
    fun fromSrc(iframeSrc: String?): String? {
        val b64 = Regex("[?&]img=([A-Za-z0-9+/=%]+)").find(iframeSrc.orEmpty())?.groupValues?.get(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?: return null
        val cover = decodeBase64(b64) ?: return null
        return if (cover.startsWith("//")) "https:$cover" else cover
    }

    fun fromBody(playerBody: String): String? {
        val poster = Regex("poster=\\\\?\"([^\"\\\\]+)").find(playerBody)?.groupValues?.get(1)
            ?: return null
        return if (poster.startsWith("//")) "https:$poster" else poster
    }
}

@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class HQPornerPlugin: com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(HQPorner())
        registerHostExtractors()
    }
}
