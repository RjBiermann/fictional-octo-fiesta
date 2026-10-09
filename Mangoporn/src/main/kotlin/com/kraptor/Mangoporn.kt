package com.kraptor

import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlin.random.Random
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class Mangoporn : MainAPI() {
    override var mainUrl = "https://mangoporn.net"
    override var name = "Mangoporn"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val MAX_PAGE = 2000
    override val mainPage
        get() = mainPageOf(
            *(try {
                MangoAyarlar.getOrderedAndEnabledCategories().toTypedArray()
            } catch (e: Exception) {
                arrayOf(
                )
            })
        )


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cleanData = request.data.trim().removePrefix("/").removeSuffix("/")
        val url = if (cleanData.endsWith("random", ignoreCase = true)) {
            val randomPageNumber = Random.nextInt(1, MAX_PAGE + 1)
            "$mainUrl/movies/page/$randomPageNumber/"
        } else if (cleanData.isEmpty() || cleanData == "movies") {
            // healed home root: the old /movies/ archive is 404; the root paginates /page/N/ (FINDINGS-525)
            if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        } else if (page <= 1) {
            "$mainUrl/$cleanData/"
        } else {
            "$mainUrl/$cleanData/page/$page/"
        }

        val home = try {
            val document = app.get(url).document
            // Card grammar: div.video-block (a.infos[title] + a.thumb img poster), shared SearchCard
            document.select("div.video-block")
                .mapNotNull { searchCard(it, "a.infos", posterSel = "a.thumb img", titleAttr = "title") }
        } catch (_: Exception) {
            emptyList()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..8) {
            val document = app.get("$mainUrl/page/$i/?s=$query").document

            // Same div.video-block card grammar as home (shared SearchCard)
            val results = document.select("div.video-block")
                .mapNotNull { searchCard(it, "a.infos", posterSel = "a.thumb img", titleAttr = "title") }

            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }

            if (results.isEmpty()) break
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.video-title > h1")?.text().toString()
        // Character-video pages expose no og:image; thumbnailUrl is JSON-LD and blank on some pages.
        val poster = MangopornParse.thumbnail(MangopornParse.videoLdJson(document))

        val year = document.select("div#video-actors a[href*=/year/]").text().trim().toIntOrNull()
        val duration = MangopornParse.videoLdJson(document)?.let { JsonLdParse.minutes(it) }
        val description = document.selectFirst("div.video-description .desc p")?.text()
        val actors = document.select("div#video-actors a[href*=/pornstar/]").map { Actor(it.text()) }

        val tags = document.select("div#video-actors a[href*=/genre/]").map { it.text() }

        val recommendations = document.select("div.related-videos div.video-block")
            .mapNotNull { searchCard(it, "a.infos", posterSel = "a.thumb img", titleAttr = "title") }
        val imageHeaders = mapOf("Accept" to "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")

        if (tags.any { it.contains(kirliKelimeRegex) }) {
            val blockedTitle = "Disabled content."
            val blockedDescription = "This content disabled due to filters."
            val blockedPoster = "https://i.imgur.com/3eR1JvE.png"
            val urlBos = ""
            return newMovieLoadResponse(blockedTitle, urlBos, TvType.NSFW, urlBos) {
                this.posterUrl = blockedPoster
                this.plot = blockedDescription
                this.tags = tags
                this.posterHeaders = imageHeaders
                addActors(actors)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.duration = duration
                this.tags = tags
                this.recommendations = recommendations
                this.posterHeaders = imageHeaders
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("MANGOPORN", "LoadlStart | $data | $isCasting")

        val response = app.get(data)
        Log.d("MANGOPORN", "Res | ${response.code}")

        val document = response.document
        val tabs = MangopornParse.embedLinks(document)
        Log.d("MANGOPORN", "Sekme | ${tabs.size}")

        val links = tabs
        Log.d("MANGOPORN", "Linkler | ${links.size} | $links")

        return coroutineScope {
            val jobs = links.mapIndexed { index, link ->
                launch {
                    try {
                        val fullUrl = fixUrl(link)
                        Log.d("MANGOPORN", "Çıkan link [$index] | $fullUrl")

                        // All embed hosts are served by the shared extractor table
                        // (ADR-0002); dispatch flows through loadExtractor.
                        loadExtractor(fullUrl, subtitleCallback) { extractorLink ->
                            Log.d(
                                "MANGOPORN",
                                "Başarı[$index] | ${extractorLink.name} | ${extractorLink.url} | ${extractorLink.quality}"
                            )
                            callback.invoke(extractorLink)
                        }
                    } catch (e: Exception) {
                        Log.d("MANGOPORN", "Hata [$index] | ${e.message}")
                    }
                }
            }
            jobs.joinAll()
            links.isNotEmpty()
        }
    }

    internal val igrencKelimeler = listOf(
        "gay",
        "homosexual",
        "queer",
        "homo",
        "androphile",
        "femboy",
        "feminine boy",
        "effeminate",
        "trap",
        "scat",
        "coprophilia",
        "coprophagia",
        "fecal",
        "poo",
        "shit",
        "crap",
        "bm play",
        "trans",
        "Trade",
        "Vers",
        "Twink",
        "Otter",
        "Bear",
        "Femme",
        "Masc",
        "No fats, no fems",
        "Serving",
        "Gagged",
        "G.O.A.T.",
        "Receipts",
        "Kiki",
        "Kai Kai",
        "Werk",
        "Realness",
        "Hunty",
        "Snatched",
        "Beat",
        "Clocked",
        "Shade",
        "Daddy",
        "Zaddy",
        "Chosen family",
        "Closet case",
        "Out and proud",
        "Henny",
        "Queening out",
        "Slay",
        "Camp",
        "Fishy",
        "Cruising",
        "Bathhouse",
        "Power bottom",
        "Situationship",
        "Pegging",
        "Femdom",
        "futa",
        "tranny",
        "crossdress",
        "Bisexual"
    )

    private val kirliKelimeRegex = MangopornParse.dirtyWordRegex(igrencKelimeler)
}
