# CloudStream API cheat sheet

Distilled from the real source — recloudstream/cloudstream `master @ fe981345` (tag
`pre-release`), the exact artifact `com.lagradost:cloudstream3:pre-release` in
`build.gradle.kts` resolves to (`MainAPI.kt`, `utils/ExtractorApi.kt`). Use these signatures
exactly; when in doubt, read the sibling providers in this repo.

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
        // mainPage(url, name, horizontalImages) for horizontal card layouts
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

`search(query: String, page: Int)` has a default implementation that delegates to
`search(query)` — a single-page provider may override only `search(query)`.

## Builders (all are `MainAPI.` extensions)

```kotlin
mainPageOf(vararg elements: Pair<String, String>): List<MainPageData>  // url to label

newHomePageResponse(list = HomePageList(name, list, isHorizontalImages), hasNext = true)
newHomePageResponse(request: MainPageRequest, list, hasNext)           // carries horizontalImages

newSearchResponseList(list: List<SearchResponse>, hasNext: Boolean?): SearchResponseList
list.toNewSearchResponseList(hasNext)                                  // extension shorthand

newMovieSearchResponse(
    name: String, url: String, type: TvType = TvType.Movie, fix: Boolean = true,
    initializer: MovieSearchResponse.() -> Unit = {},
)   // in initializer: this.posterUrl = …; this.score = …
    // url is fixed via fixUrl unless fix=false

newMovieLoadResponse(
    title: String, url: String, type: TvType, dataUrl: String,
    initializer: suspend MovieLoadResponse.() -> Unit = {},
)   // ALSO a generic overload: data: T? — non-String data is toJson()'d into dataUrl
    // blank dataUrl silently sets comingSoon = true → video never plays; never emit ""
    // in initializer: posterUrl, plot, tags, actors, year, duration, recommendations, score

newExtractorLink(
    source: String, name: String, url: String,
    type: ExtractorLinkType? = null,          // null = INFER_TYPE (from URL: .m3u8/.mpd/.torrent/magnet)
    initializer: suspend ExtractorLink.() -> Unit = {},
)   // in initializer: this.referer = …; this.quality = …; this.headers = …
    // suspend — call inside loadLinks
```

## ExtractorLink fields (the floor)

`url / referer / quality: Int / type: ExtractorLinkType (VIDEO, M3U8, DASH, TORRENT, MAGNET) /
headers / audioTracks` — plus `extractorData` (extractorVerifierJob) and `isM3u8`/`isDash`
helpers. Set `quality` from the page (`getQualityFromName`), `referer` when the host requires
it, and let type infer unless the URL hides the container. `Qualities.Unknown.value = 400` —
never ship it when the site states a quality.

## Helpers

```kotlin
fixUrlNull(url: String?): String?                    // null-safe relative→absolute
getQualityFromName(qualityName: String?): Int        // "1080"→1080, "4k"→2160, null→Unknown
getQualityFromString(string: String?): SearchQuality? // "hd"→SearchQuality.HD (enum, for addQuality)
SearchResponse.addQuality(quality: String)           // sets the SearchQuality badge
Score.from10(value: String?): Score?                 // also from10(Int/Double/Float), from100, from(v, maxScore)
LoadResponse.addScore(text: String?, maxValue: Int = 10) / addScore(score: Score?)
LoadResponse.addDuration(input: String?)             // "1h 20min" → Int minutes
fixTitle(str: String) / capitalizeString(str: String)
USER_AGENT                                           // upstream Chrome UA constant
base64Decode(string: String) / base64Encode(…)
app.get(url, referer = …, headers = …).document      // NiceHttp + jsoup
app.get(url).text                                    // raw body
Log.d(tag, msg)                                      // com.lagradost.api.Log
newSubtitleFile(lang, url) { headers = … }           // suspend builder, headers optional
MainAPI.updateUrl(url)                               // rebase an old link onto the current mainUrl (clone-site)
```

`sequentialMainPage = true` (+ `sequentialMainPageDelay`) when the site rate-limits parallel
homepage requests.

## LoadResponse field inventory (populate what the site exposes — Data-complete)

`name, url, type, posterUrl, posterHeaders, backgroundPosterUrl, logoUrl, year, plot, score,
tags, duration (minutes), trailers, recommendations, actors (List<ActorData>),
comingSoon, syncData, contentRating, uniqueUrl` — helpers: `addActors(List<String>)`,
`addActors(List<Pair<Actor, String?>>)`, `addPoster(url, headers)`.

## Pitfalls

- Many old constructors are now `@Deprecated(level = ERROR)` — they do not compile:
  `HomePageResponse(…)`, `SearchResponseList(…)`, `MovieSearchResponse(…)`, direct
  `ExtractorLink(…)`, `Episode(…)` → always use the `new*` builders.
- `rating`, `addRating`, `Score.toOld/fromOld`, `getRhinoContext` — also ERROR-deprecated.
  Use `score` / `Score.from10` / `newJsContext`.
- `@Prerelease` APIs (e.g. `splitUrlParameters`, the Kotlin-`Uuid` `newDrmExtractorLink`
  overload) compile but crash CloudStream **stable** at runtime — avoid unless targeting
  prerelease-only.
- `loadLinks` is `suspend`; the `callback` fires per link found; return `true` on success.
- `newExtractorLink` is `suspend` — call it inside `loadLinks`, don't await anything.
- `hasNext` defaults to `list.isNotEmpty()` when omitted — set `true` for paginated sites.
- JSON-LD parsing: Jackson `ObjectMapper` is available (`com.fasterxml`); values may be
  `List<String>` or scalars — guard with `when (v is List<*>)`.
- Do NOT bump Jackson past 2.13.1 (repo rule — breaks older Android devices).
