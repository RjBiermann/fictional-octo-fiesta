# CloudStream API cheat sheet

Distilled from the real source (`recloudstream/cloudstream`, `library/src/commonMain/.../
MainAPI.kt` and `utils/ExtractorApi.kt`, master). Use these signatures exactly; when in doubt,
read the sibling providers in this repo.

## MainAPI overrides (NSFW provider subset)

```kotlin
class X : MainAPI() {
    override var mainUrl = "https://…"
    override var name = "X"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/…" to "Section label",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse
    override suspend fun search(query: String, page: Int): SearchResponseList
    override suspend fun load(url: String): LoadResponse
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean
}
```

## Builders (all are `MainAPI.` extensions)

```kotlin
mainPageOf(vararg elements: Pair<String, String>): List<MainPageData>  // url to label

newHomePageResponse(list = HomePageList(name, list, isHorizontalImages), hasNext = true)

newSearchResponseList(list: List<SearchResponse>, hasNext: Boolean?): SearchResponseList

newMovieSearchResponse(
    name: String, url: String, type: TvType = TvType.Movie, fix: Boolean = true,
    initializer: MovieSearchResponse.() -> Unit = {},
)   // in initializer: this.posterUrl = …; this.score = …
    // url is fixed via fixUrl unless fix=false

newMovieLoadResponse(
    title: String, url: String, type: TvType, data: String,
    initializer: MovieLoadResponse.() -> Unit = {},
)   // in initializer: this.posterUrl, plot, tags, actors, year, duration, recommendations, score

newExtractorLink(
    source: String, name: String, url: String,
    type: ExtractorLinkType? = null,          // VIDEO / M3U8 / DASH / TORRENT / MAGNET
    initializer: suspend ExtractorLink.() -> Unit = {},
)   // in initializer: this.referer = …; this.quality = …; this.extractorLogger …
    // suspend — call inside loadLinks
```

## Helpers

```kotlin
fixUrlNull(url: String?): String?                    // null-safe relative→absolute
getQualityFromName(qualityName: String?): Int        // "1080"→1080, "4k"→2160, null→Unknown
Score.from10(value: String?): Score?                 // also from10(Int/Double/Float), from100
base64Decode(string: String) / base64Encode(…)
app.get(url, referer = …, headers = …).document      // NiceHttp + jsoup
app.get(url).text                                    // raw body
Log.d(tag, msg)                                      // com.lagradost.api.Log
```

## Types seen in this repo's providers

- `MovieSearchResponse.posterUrl: String?`, `.score: Score?`
- `MovieLoadResponse.plot/tags/actors/year/duration/recommendations`
- `addActors(actors: List<String>)` from `LoadResponse.Companion`
- Search responses in this repo return `newSearchResponseList(list, hasNext = true)`

## Pitfalls

- `loadLinks` is `suspend` and the `callback` is invoked per link found; return `true` on success.
- `newExtractorLink` is `suspend` — `await` nothing, just call it inside `loadLinks`.
- `hasNext` default is `list.isNotEmpty()` when omitted — set `true` for paginated sites.
- JSON-LD parsing: Jackson `ObjectMapper` is available (`com.fasterxml`), arrays may be
  `List<String>` or scalars — guard with `when (v is List<*>)`.
- Do NOT bump Jackson past 2.13.1 (repo rule — breaks older Android devices).
