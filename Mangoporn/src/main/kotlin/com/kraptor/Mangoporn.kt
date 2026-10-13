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
    override val hasQuickSearch = true
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
            // random row: the /movies/ archive is 404 (issue #564); the root paginates /page/N/
            val randomPageNumber = Random.nextInt(1, MAX_PAGE + 1)
            "$mainUrl/page/$randomPageNumber/"
        } else if (cleanData.isEmpty() || cleanData == "movies") {
            // healed home root: the old /movies/ archive is 404; the root paginates /page/N/ (FINDINGS-525)
            if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        } else if (page <= 1) {
            "$mainUrl/$cleanData/"
        } else {
            "$mainUrl/$cleanData/page/$page/"
        }

        val document = try {
            app.get(url).document
        } catch (_: Exception) {
            null
        }
        // Card grammar (issue #564): article.card BEM cards (a.card__th link +
        // .card__t a title + img.card__img poster) shared by every listing surface
        val home = document?.select("article.card")?.mapNotNull {
            searchCard(it, ".card__t a", hrefSel = "a.card__th", posterSel = "img.card__img")
        } ?: emptyList()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            // per-page counts vary per theme — the a.next anchor is the signal
            hasNext = MangopornParse.hasNextPage(document)
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // Same article.card card grammar as home (shared SearchCard)
        val document = app.get("$mainUrl/page/$page/?s=$query").document
        val results = document.select("article.card").mapNotNull {
            searchCard(it, ".card__t a", hrefSel = "a.card__th", posterSel = "img.card__img")
        }

        return newSearchResponseList(results, hasNext = MangopornParse.hasNextPage(document))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = try {
        val res = app.get("$mainUrl/wp-json/turbosearch/v1/suggest?q=${
            java.net.URLEncoder.encode(query.trim(), "UTF-8")
        }")
        MangopornParse.suggestPosts(res.text).map {
            newMovieSearchResponse(it.title, fixUrl(it.href), TvType.NSFW) {
                this.posterUrl = it.poster
            }
        }
    } catch (e: Exception) {
        emptyList()   // turbosearch absent/malformed → app falls back to full search
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // issue #564: article.vid grammar — breadcrumb title (h1.vid__t fallback),
        // JSON-LD VideoObject carries duration + thumbnailUrl (now array-form)
        val title = document.selectFirst("span.bc__c")?.text()?.trim()
            ?: document.selectFirst("h1.vid__t")?.text()?.trim()
            ?: ""
        val poster = MangopornParse.thumbnail(MangopornParse.videoLdJson(document))

        val details = document.selectFirst("div.dp__panel[data-panel=details]")
        val year = (details ?: document).select("a[href*=/year/]")
            .firstOrNull()?.text()?.trim()?.toIntOrNull()
        val duration = MangopornParse.videoLdJson(document)?.let { JsonLdParse.minutes(it) }
        val description = document.selectFirst("div.vid__txt p")?.text()
        val actors = details?.select("a.chip--star")?.map { Actor(it.text()) } ?: emptyList()

        val tags = details?.select("a[href*=/genre/]")?.map { it.text() }
            ?: document.select("a[href*=/genre/]").map { it.text() }

        val recommendations = document.select("section.sec article.card").mapNotNull {
            searchCard(it, ".card__t a", hrefSel = "a.card__th", posterSel = "img.card__img")
        }
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
