// ! Bu araç @ByAyzen tarafından | @Cs-GizliKeyif için yazılmıştır.

package com.byayzen

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MissAV : MainAPI() {
    override var mainUrl = "https://missav.live"
    override var name = "MissAV"
    override val hasMainPage = true
    override var lang = "jp"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    val subtitleCatUrl = "https://www.subtitlecat.com"

    override val mainPage = mainPageOf(
        "$mainUrl/dm169/en/weekly-hot?sort=weekly_views" to "Weekly Hot",
        "$mainUrl/dm263/en/monthly-hot?sort=views" to "Monthly Hot",
        "$mainUrl/en/new?sort=published_at" to "Newly Added",
        "$mainUrl/en/english-subtitle" to "English Subtitles",
        "$mainUrl/dm628/en/uncensored-leak" to "Uncensored Leak",
        "$mainUrl/dm150/en/fc2" to "FC2",
        "$mainUrl/dm35/en/madou" to "Madou",
        "$mainUrl/en/klive" to "K-Live",
        "$mainUrl/en/clive" to "C-Live",
        "$mainUrl/dm29/en/tokyohot" to "Tokyo Hot",
        "$mainUrl/dm1198483/en/heyzo" to "HEYZO",
        "$mainUrl/dm2469695/en/1pondo" to "1pondo",
        "$mainUrl/dm3959622/en/caribbeancom" to "Caribbeancom",
        "$mainUrl/dm48032/en/caribbeancompr" to "Caribbeancom Premium",
        "$mainUrl/dm3710098/en/10musume" to "10musume",
        "$mainUrl/dm1342558/en/pacopacomama" to "Pacopacomama",
        "$mainUrl/dm136/en/gachinco" to "Gachinco",
        "$mainUrl/dm29/en/xxxav" to "XXX-AV",
        "$mainUrl/dm24/en/marriedslash" to "Married Slash",
        "$mainUrl/dm20/en/naughty4610" to "Naughty 4610",
        "$mainUrl/dm22/en/naughty0930" to "Naughty 0930"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "${request.data}${separator}page=$page"

        val document = app.get(url).document

        val home = document.select("div.grid.grid-cols-2 > div, div.thumbnail.group")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            list = listOf(
                HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = true
                )
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = selectFirst("a[href*='/en/'], a[href*='/dm']") ?: return null
        val url = fixUrlNull(link.attr("abs:href")) ?: return null

        val baseTitle = selectFirst("div.my-2 a, div.title a, a.text-secondary")?.text()?.trim()
            ?: link.text().trim()

        if (baseTitle.isBlank()) return null

        val blacklist = listOf("Recent update", "Contact", "Support", "DMCA", "Home")
        if (blacklist.any { baseTitle.equals(it, ignoreCase = true) }) return null

        val isUncensored = (link.attr("alt") + link.attr("href") + this.outerHtml())
            .contains(Regex("uncensored[-_ ]?leak", RegexOption.IGNORE_CASE))

        val title = if (isUncensored && !baseTitle.startsWith("Uncensored - ", ignoreCase = true))
            "Uncensored - $baseTitle" else baseTitle

        val posterUrl = fixUrlNull(
            selectFirst("img")?.let {
                it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }
            }
        )

        if (posterUrl == null) return null

        return newMovieSearchResponse(title, url, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) {
            "${mainUrl}/en/search/${query}"
        } else {
            "${mainUrl}/en/search/${query}?page=$page"
        }

        val document = app.get(url).document

        val aramaCevap =
            document.select("div.grid.grid-cols-2 > div").mapNotNull { it.toMainPageResult() }


        return newSearchResponseList(aramaCevap, hasNext = true)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.text-base")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val year = document.selectFirst("time")?.text()?.split("-")?.firstOrNull()?.toIntOrNull()

        val tags = document.select("div.text-secondary:contains(genre) a").map {
            it.text().trim() }
        val actresses = document.select("div.text-secondary:contains(actress) a").map {
            Actor(it.text().trim()) }
        val plot = document.selectFirst("head meta[property='og:description']")?.attr("content")
        val duration = document.selectFirst("head meta[property='og:video:duration']")?.attr("content")?.toIntOrNull()
        val dvdId = url.trimEnd('/').substringAfterLast('/')
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.year = year
            this.tags = tags
            this.plot = plot
            this.duration = duration
            this.recommendations = getRecommendations(dvdId)
            addActors(actresses)
        }
    }

    // ponytail: recombee public token is embedded in the site's own app.js bundle;
    // if the site rotates it, breakage is caught by verify probe.
    private val recombeeToken = "Ikkg568nlM51RHvldlPvc2GzZPE9R4XGzaH9Qj4zK9npbbbTly1gj9K4mgRn0QlV"

    private fun hmacSign(path: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(recombeeToken.toByteArray(), "HmacSHA1"))
        return mac.doFinal(path.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private suspend fun getRecommendations(dvdId: String): List<SearchResponse> {
        return try {
            val props = "[\"title\",\"duration\",\"dm\"]"
            val timestamp = System.currentTimeMillis() / 1000
            val path = "/missav-default/recomms/items/$dvdId/items/"
            val sign = hmacSign("$path?scenario=desktop-watch-next-side&frontend_timestamp=$timestamp")
            val body = mapOf(
                "targetUserId" to "cs-${(1000000..9999999).random()}",
                "count" to 12,
                "scenario" to "desktop-watch-next-side",
                "returnProperties" to true,
                "includedProperties" to listOf("title", "duration", "dm"),
                "cascadeCreate" to true,
            )
            val res = app.post(
                "https://client-rapi-missav.recombee.com$path?scenario=desktop-watch-next-side&frontend_timestamp=$timestamp&frontend_sign=$sign",
                json = body
            )
            val root = com.fasterxml.jackson.databind.ObjectMapper().readTree(res.text)
            root.get("recomms")?.mapNotNull { item ->
                val id = item.get("id")?.textValue() ?: return@mapNotNull null
                val values = item.get("values")
                val recTitle = values?.get("title")?.textValue()?.takeIf { it.isNotBlank() } ?: id
                val dm = values?.get("dm")?.asInt() ?: 0
                val recUrl = if (dm > 0) "$mainUrl/dm$dm/en/$id" else "$mainUrl/en/$id"
                newMovieSearchResponse(recTitle, recUrl, TvType.NSFW) {
                    this.posterUrl = "https://fourhoi.com/$id/cover-t.jpg"
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        getAndUnpack(response.text).let { unpacked ->
            val playlistId = """/([a-f0-9\-]{36})/""".toRegex().find(unpacked)?.groupValues?.get(1)

            if (playlistId != null) {
                callback.invoke(
                    newExtractorLink(
                        source = "MissAV",
                        name = "MissAV",
                        url = "https://surrit.com/$playlistId/playlist.m3u8",
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = mapOf("Referer" to "$mainUrl/")
                    }
                )
            }
        }

        try {
            val doc = response.document
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
            val javCode = "([a-zA-Z]+-\\d+)".toRegex().find(title)?.groups?.get(1)?.value
            if(!javCode.isNullOrEmpty())
            {
                val query = "$subtitleCatUrl/index.php?search=$javCode"
                val subDoc = app.get(query, timeout = 15).document
                val subList = subDoc.select("td a")
                for(item in subList)
                {
                    if(item.text().contains(javCode,ignoreCase = true))
                    {
                        val fullUrl = "$subtitleCatUrl/${item.attr("href")}"
                        val pDoc = app.get(fullUrl, timeout = 10).document
                        val sList = pDoc.select(".col-md-6.col-lg-4")
                        for(item in sList)
                        {
                            try {
                                val language = item.select(".sub-single span:nth-child(2)").text()
                                val text = item.select(".sub-single span:nth-child(3) a")
                                if(text != null && text.size > 0 && text[0].text() == "Download")
                                {
                                    val url = "$subtitleCatUrl${text[0].attr("href")}"
                                    subtitleCallback.invoke(
                                        SubtitleFile(
                                            language.replace("\uD83D\uDC4D \uD83D\uDC4E",""),  // Use label for the name
                                            url     // Use extracted URL
                                        )
                                    )
                                }
                            } catch (e: Exception) { }
                        }

                    }
                }

            }
        } catch (e: Exception) { }
        return true
    }
}
@com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class MissAVPlugin: com.lagradost.cloudstream3.plugins.Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(MissAV())
    }
}
