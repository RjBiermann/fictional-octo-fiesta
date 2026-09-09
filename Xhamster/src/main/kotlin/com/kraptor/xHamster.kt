package com.kraptor

import com.kraptor.registerHostExtractors
import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import com.fasterxml.jackson.module.kotlin.readValue

@Suppress("ClassName")
class xHamster : MainAPI() {
    override var mainUrl = "https://xhamster.com"
    override var name = "xHamster"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    // Desktop pages serve xplayerSettings:null to guests; only the mobile page exposes sources.
    private val mobileHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/newest/" to "Newest",
        "${mainUrl}/most-viewed/weekly/" to "Weekly Most Viewed",
        "${mainUrl}/most-viewed/monthly/" to "Monthly Most Viewed",
        "${mainUrl}/most-viewed/" to "All Time Most Viewed",
        "${mainUrl}/4k/" to "4K",
        "${mainUrl}/hd/2?quality=1080p" to "1080p",
        "${mainUrl}/categories/teen" to "Teen",
        "${mainUrl}/categories/mom" to "Mom",
        "${mainUrl}/categories/milf" to "Milf",
        "${mainUrl}/categories/mature" to "Mature",
        "${mainUrl}/categories/big-ass" to "Big Ass",
        "${mainUrl}/categories/anal" to "Anal",
        "${mainUrl}/categories/hardcore" to "Hardcore",
        "${mainUrl}/categories/homemade" to "Homemade",
        "${mainUrl}/categories/amateur" to "Amateur",
        "${mainUrl}/categories/complilation" to "Compilation",
        "${mainUrl}/categories/lesbian" to "Lesbian",
        "${mainUrl}/categories/russian" to "Russian",
        "${mainUrl}/categories/european" to "European",
        "${mainUrl}/categories/latina" to "Latina",
        "${mainUrl}/categories/asian" to "Asian",
        "${mainUrl}/categories/jav" to "JAV",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            "${request.data}/$page?geo=us",
            cookies = mapOf("video_titles_translation" to "0")
        ).document
        val home = document.select("div.thumb-list div.thumb-list__item")
            .mapNotNull { it.toSearchResult() }


        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }


    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a.video-thumb-info__name")?.text() ?: return null
        val href = fixUrl(this.selectFirst("a.video-thumb-info__name")!!.attr("href"))
        val posterUrl = fixUrlNull(this.select("img.thumb-image-container__image").attr("src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document =
            app.get(
                "${mainUrl}/search/${
                    query.replace(
                        " ",
                        "+"
                    )
                }/?page=$page&x_platform_switch=desktop&geo=us",
                cookies = mapOf("video_titles_translation" to "0")
            ).document

        val aramaCevap = document.select("div.thumb-list div.thumb-list__item")
            .mapNotNull { it.toSearchResult() }

        return newSearchResponseList(aramaCevap, hasNext = true)
    }

    override suspend fun load(url: String): LoadResponse {
        val document =
            app.get("${url}?geo=us", cookies = mapOf("video_titles_translation" to "0")).document

        // 2026-09 re-probe (issue #216): div.with-player-container h1 and
        // div.controls-info div.ab-info p are gone from the template; title, plot and
        // duration now come from window.initials.videoModel.
        val initialData = getInitialsJson(document.html())
        val videoModel = initialData?.videoModel
        val title = videoModel?.title
            ?: document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = fixUrlNull(
            document.selectFirst("div.xp-preload-image")?.attr("style")?.substringAfter("https:")
                ?.substringBefore("\');")
        )
        val description = videoModel?.description?.replace("\\s+".toRegex(), " ")

        val actors = document.select("a.entity-author-container__name").map { aTag ->
            val name = aTag.selectFirst("span")?.text()?.trim() ?: ""
            val image =
                aTag.selectFirst("img")?.attr("src") ?: aTag.selectFirst("img")?.attr("data-src")
            Actor(name, image)
        }

        val tags =
            document.select("div[data-role='video-tags-list'] a[href*='/categories/'], div[data-role='video-tags-list'] a[href*='/tags/']")
                .map { it.text().trim() }

        val recommendations = document.select("div[data-role='related-item']").mapNotNull {
            val name = it.selectFirst("a.video-thumb-info__name")?.text() ?: return@mapNotNull null
            val link =
                it.selectFirst("a[data-role='thumb-link']")?.attr("href") ?: return@mapNotNull null
            val thumb =
                it.selectFirst("img")?.attr("src") ?: it.selectFirst("img")?.attr("data-src")

            newMovieSearchResponse(name, link, TvType.NSFW) {
                this.posterUrl = thumb
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.duration = videoModel?.duration
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false
        val sourceName = name

        val document: Document = try {
            app.get(
                "${data}?geo=us",
                headers = mobileHeaders,
                cookies = mapOf("video_titles_translation" to "0")
            ).document
        } catch (e: Exception) {
            Log.e(sourceName, "Failed to fetch document: ${e.message}")
            return false
        }


        val preloadLinks = document.select("link[rel=preload][as=fetch]")
        preloadLinks.forEach { link ->
            val href = link.attr("href")
            if (href.isNotEmpty() && href.contains(".m3u8")) {
                val fixed = fixUrl(href)
                Log.d(sourceName, "Found M3U8 URL from preload: $fixed")

                callback(
                    newExtractorLink(
                        source = sourceName,
                        name = sourceName,
                        url = fixed,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                    }
                )
                foundLinks = true
            }
        }


        val initialData = getInitialsJson(document.html())

        // Guest tier: desktop initials carry xplayerSettings:null; the mobile page's
        // xplayerSettings.sources.standard carries hex-obfuscated direct MP4s + HLS masters.
        initialData?.xplayerSettings?.sources?.standard?.let { std ->
            // h264 entries first (widest device support), av1 master as backup.
            for (entry in std.h264.orEmpty() + std.av1.orEmpty()) {
                val decoded = decodeXhUrl(entry.url)
                    ?: decodeXhUrl(entry.fallback)
                    ?: continue
                if (!decoded.startsWith("http")) continue
                val isHls = decoded.contains(".m3u8")
                Log.d(sourceName, "Decoded source ${entry.quality}: $decoded")
                callback(
                    newExtractorLink(
                        source = sourceName,
                        name = sourceName,
                        url = decoded,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = data
                        this.quality = entry.quality?.filter { it.isDigit() }?.toIntOrNull()
                            ?: Qualities.Unknown.value
                    }
                )
                foundLinks = true
            }
        }
        initialData?.xplayerSettings?.subtitles?.tracks?.forEach { track ->
            track.urls?.vtt?.let { url ->
                val fixed = fixUrl(url)
                val cleanLabel =
                    track.label?.replace(Regex("\\s*\\(auto-generated\\)"), "") ?: track.lang
                    ?: "Unknown"

                Log.d(sourceName, "Subtitle $cleanLabel: $fixed")
                subtitleCallback(newSubtitleFile(lang = cleanLabel, url = fixed))
            } ?: Log.w(sourceName, "Subtitle missing VTT: $track")
        } ?: Log.w(sourceName, "No subtitles in JSON.")

        if (!foundLinks) {
            Log.w(sourceName, "No video links found.")
        }

        return foundLinks
    }

    data class InitialsJson(
        val videoModel: VideoModel? = null,
        val xplayerSettings: XPlayerSettings? = null,
        val downloadDropdownComponent: DownloadDropdown? = null
    )

    // Current video's own metadata from window.initials (duration is in seconds).
    data class VideoModel(
        val title: String? = null,
        val duration: Int? = null,
        val description: String? = null
    )

    data class XPlayerSettings(
        val sources: VideoSources? = null,
        val subtitles: Subtitles? = null
    )

    data class VideoSources(
        val hls: HlsSources? = null,
        val standard: StandardSources? = null
    )

    data class HlsSources(val h264: HlsSource? = null)
    data class HlsSource(val url: String? = null)

    data class StandardSources(
        val h264: List<StandardSourceQuality>? = null,
        val av1: List<StandardSourceQuality>? = null
    )
    data class StandardSourceQuality(
        val quality: String? = null,
        val url: String? = null,
        val fallback: String? = null
    )

    data class Subtitles(val tracks: List<SubtitleTrack>? = null)
    data class SubtitleTrack(
        val label: String? = null,
        val lang: String? = null,
        val urls: SubtitleUrls? = null
    )

    data class SubtitleUrls(val vtt: String? = null)

    data class DownloadDropdown(val sources: DownloadSources? = null)
    data class DownloadSources(val mp4: Map<String, String>? = null)

    // Deobfuscation of xplayerSettings.sources URLs, ported from the site's player
    // (static-nss.xhcdn.com/xh-mobile/js/xplayer-mobile.js): hex bytes, byte 0 = algoId,
    // bytes 1-4 = little-endian seed, remainder XORed with the keystream.
    internal fun decodeXhUrl(hex: String?): String? {
        if (hex.isNullOrEmpty() || hex.length % 2 != 0 || hex.length < 12) return null
        return try {
            val b = IntArray(hex.length / 2) {
                hex.substring(it * 2, it * 2 + 2).toInt(16)
            }
            val alg = b[0]
            var s = b[1] or (b[2] shl 8) or (b[3] shl 16) or (b[4] shl 24)
            var out = ""
            for (i in 5 until b.size) {
                val k = when (alg) {
                    1 -> { s = s * 1664525 + 0x3c6ef35f; s and 255 }
                    2 -> { s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5); s and 255 }
                    3 -> {
                        s += 0x9e3779b9.toInt()
                        var e = s xor (s ushr 16)
                        e = (e.toLong() * 0x85ebca77L).toInt()
                        e = e xor (e ushr 13)
                        e = (e.toLong() * 0xc2b2ae3dL).toInt()
                        (e xor (e ushr 16)) and 255
                    }
                    4 -> {
                        s += 0x6d2b79f5.toInt()
                        var e = (s shl 7) or (s ushr 25)
                        e += 0x9e3779b9.toInt()
                        e = e xor (e ushr 11)
                        e = (e.toLong() * 0x27d4eb2dL).toInt()
                        e and 255
                    }
                    5 -> {
                        s = s xor (s shl 7); s = s xor (s ushr 9); s = s xor (s shl 8)
                        s = s + 0xa5a5a5a5.toInt()
                        s and 255
                    }
                    6 -> {
                        // update seed first; both the xor and the shift amount use the NEW seed
                        s = (s.toLong() * 0x2c9277b5L).toInt() + 0xac564b05.toInt()
                        ((s xor (s ushr 18)) and 255) shr (s ushr 27 and 31)
                    }
                    7 -> {
                        s += 0x9e3779b9.toInt()
                        var e = s xor (s shl 5)
                        e = (e.toLong() * 0x7feb352dL).toInt()
                        e = e xor (e ushr 15)
                        e = (e.toLong() * 0x846ca68bL).toInt()
                        e and 255
                    }
                    else -> return null
                }
                out += ((b[i] xor k) and 255).toChar()
            }
            out
        } catch (e: Exception) {
            Log.e("xHamster", "decodeXhUrl failed: ${e.message}")
            null
        }
    }

    internal fun getInitialsJson(html: String): InitialsJson? {
        return try {
            val regex = Regex("window\\.initials\\s*=\\s*(\\{.*?\\});", RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(html) ?: return null
            val jsonString = match.groupValues[1]
            val parsedJson = mapper.readValue<InitialsJson>(jsonString)
            parsedJson
        } catch (e: Exception) {
            Log.e("xHamster", "getInitialsJson failed: ${e.message}")
            null
        }
    }
}
@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
@Suppress("ClassName")
class xHamsterPlugin : com.lagradost.cloudstream3.plugins.BasePlugin() {
    override fun load() {
        registerMainAPI(xHamster())
        registerHostExtractors()
    }
}
