# CloudStream API cheat sheet

Distilled from the real source — recloudstream/cloudstream `master @ fe981345` (tag
`pre-release`), the exact artifact `com.lagradost:cloudstream3:pre-release` in
`build.gradle.kts` resolves to (`MainAPI.kt`, `utils/ExtractorApi.kt`, `plugins/BasePlugin.kt`,
`app/plugins/Plugin.kt`, `plugins/CloudstreamPlugin.kt`). Use these signatures
exactly; when in doubt, read the sibling providers in this repo.

## MainAPI override contract (learned from MainAPI.kt @ master, the real superclass)

```kotlin
class X : MainAPI() {
    // ---- required identity ----
    override var mainUrl = "https://…"      // also the clone-site override target
    override var name = "X"                 // plugin name shown in UI
    override var lang = "en"
    override val supportedTypes = setOf(TvType.NSFW)

    // ---- homepage (hasMainPage=false default) ----
    override val hasMainPage = true
    override val mainPage = mainPageOf(     // one entry = one homepage row
        "$mainUrl/…" to "Section label",
        // mainPage(url, name, horizontalImages = true) for horizontal card layouts
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

The app calls methods with **no try/catch** — "Every provider will **not** have try catch built
in" (comment in MainAPI.kt). One thrown exception kills the whole call; wrap per-item parsing.

### Data flow (what each method receives)

- `getMainPage(page, request)` is called **once per `mainPage` row**; `request.data` is the url
  you put in `mainPageOf`, `request.name` the label, `request.horizontalImages` the layout flag.
  `page` starts at 1 and increments on infinite scroll — translate `page` → URL yourself
  (path-based `"$data/$page/"` vs query `"$data?page=$page"` per the site).
- `search(query, page)` same page semantics, starts at 1. There is also `search(query)`
  (single-page, default `search(query, page)` delegates to it with `hasNext=false`) —
  override the paginated one.
- `quickSearch(query)` → `List<SearchResponse>?` — only called when `hasQuickSearch = true`
  (default false). If the site has no distinct quick-search endpoint, leave the default.
- `load(url)` gets a URL **from a SearchResponse you returned** (search, homepage row, or
  recommendations) — every URL you emit must be loadable. Return `LoadResponse?` (null =
  broken card, app handles it).
- `loadLinks(data, …)`: `data` is the **dataUrl** you set in `newMovieLoadResponse(…, dataUrl)`
  (or, for series, the `Episode.data` of the chosen episode). Anything non-blank works — a URL,
  a JSON blob, an id. Blank dataUrl sets `comingSoon = true` → video never plays.
  `callback` fires per `ExtractorLink` found, `subtitleCallback` per subtitle; return `true` on
  success.

### Secondary overrides (rarely needed — skip unless the site forces it)

```kotlin
override val hasQuickSearch = true                 // only with a real distinct endpoint
override val sequentialMainPage = true             // site rate-limits parallel homepage requests
override val sequentialMainPageDelay: Long = 0L    // + scroll variant, ms
override val hasChromecastSupport = false          // links need referer / can't chromecast
override val hasDownloadSupport = false            // encrypted links
override val usesWebView = true                    // disabled if no WebView
override val loadLinksTimeoutMs: Long? = null      // hint timeouts, only for very slow extractions
override suspend fun extractorVerifierJob(extractorData: String?)  // background job while playing
override fun getVideoInterceptor(link: ExtractorLink): Interceptor? // okhttp interceptor at playback
override val supportedSyncNames = setOf<SyncIdName>() + override suspend fun getLoadUrl(name, id) // sync deep links
```

`name`/`mainUrl` can be overridden at runtime by the clone-site feature (`canBeOverridden`,
`storedCredentials`); `sourcePlugin` is set by the app — don't touch.

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
    // dataUrl is exactly what loadLinks receives as `data` — keep them consistent
    // blank dataUrl silently sets comingSoon = true → video never plays; never emit ""
    // in initializer: posterUrl, plot, tags, actors, year, duration, recommendations, score

newEpisode(
    data: T,  // String url or any object → toJson()'d; becomes Episode.data → loadLinks data
    initializer: Episode.() -> Unit = {},
)   // series only; in initializer: name, season, episode, posterUrl, addDate("yyyy-MM-dd")
newTvSeriesLoadResponse(name, url, type, episodes: List<Episode>) // series shape (not used by NSFW tube sites)
newTvSeriesSearchResponse / newAnimeSearchResponse / newLiveSearchResponse / newTorrentSearchResponse — same initializer pattern

newExtractorLink(
    source: String, name: String, url: String,
    type: ExtractorLinkType? = null,          // null = INFER_TYPE (from URL: .m3u8/.mpd/.torrent/magnet)
    initializer: suspend ExtractorLink.() -> Unit = {},
)   // in initializer: this.referer = …; this.quality = …; this.headers = …
    // suspend — call inside loadLinks
```

## Plugin registration (the entrypoint — BasePlugin.kt + Plugin.kt @ master)

Extensions don't subclass `MainAPI` and expect discovery — the plugin class is the entrypoint.
The app's `PluginManager` scans the compiled jar for classes annotated `@CloudstreamPlugin`,
instantiates them, and calls `load(context)`.

```kotlin
@CloudstreamPlugin                              // com.lagradost.cloudstream3.plugins.CloudstreamPlugin
class XPlugin : BasePlugin() {                  // app-side: abstract class Plugin : BasePlugin()
    override fun load() {                       // cross-platform Plugin.load(context) falls back to this
        registerMainAPI(X())                    // sets sourcePlugin, adds to APIHolder.allProviders
        registerExtractorAPI(SomeExtractor())   // adds to global extractorApis list (optional)
    }
}
```

`registerMainAPI`/`registerExtractorAPI` live on `BasePlugin` and stamp `element.sourcePlugin =
filename` — registration IS discovery; nothing happens without it. Also on `Plugin` (not
`BasePlugin`): `registerVideoClickAction`, `resources`, `openSettings` — out of scope for this
repo. `beforeUnload()` exists for cleanup; NSFW providers don't need it. The repo routes ALL
extractor registration through `shared/` HostRegistry's `BasePlugin.registerHostExtractors()`
(ADR-0002) — a provider's plugin `load()` calls that instead of hand-registering extractors.

## ExtractorApi contract (ExtractorApi.kt @ master)

```kotlin
class SomeHost : ExtractorApi() {
    override val name = "SomeHost"            // shown in player source list
    override val mainUrl = "https://host.tld" // loadExtractor matches by prefix of this
    override val requiresReferer = true

    override suspend fun getUrl(              // the NEW 4-arg style — override this one
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // resolve page → stream URL(s); emit via callback(newExtractorLink(…) { … })
        // subtitles via subtitleCallback(newSubtitleFile(lang, url))
    }
    // old 2-arg getUrl(url, referer): List<ExtractorLink>? still exists (delegates default);
    // the 4-arg default calls it and forEach(callback) — override exactly one style.
    override fun getExtractorUrl(id: String) = id   // rarely overridden
}
```

No try/catch inside `getUrl` either — `loadExtractor` catches (and rethrows
CancellationException), but a direct call from `loadLinks` needs its own guard.

### loadExtractor matching (know the rules before registering)

- Compares `url.lowercase().strip(scheme/www)` against each registered extractor's `mainUrl`;
  **iterates the list in reverse — the LAST registered matching extractor wins**. That's why the
  repo's `HostRegistry` row order is load-bearing and must stay stable.
- Prefix match first; fallback pass matches mirror domains by `Levenshtein.partialRatio > 80`.
  Register a mirror explicitly if Levenshtein isn't a safe proxy.
- Returns `true` if an extractor handled the URL — the repo's embed ladder ends with
  `loadExtractor(...)` for this reason.

## ExtractorLink family (ExtractorApi.kt)

```kotlin
newExtractorLink(source, name, url, type = null, initializer)   // null type = INFER_TYPE
newDrmExtractorLink(source, name, url, type, uuid: java.util.UUID, initializer)
    // kotlin-uuid overload is @Prerelease — use the java.util.UUID one on stable
ExtractorLinkPlayList(source, name, playlist: List<PlayListItem>, referer, quality, …)
    // unorthodox m3u8 systems of concatenated small videos; PlayListItem(url, durationUs: Long)
```

- `ExtractorLinkType`: `VIDEO / M3U8 / DASH / TORRENT / MAGNET` — M3U8 supports encrypted
  playlists + download; DASH has no download; TORRENT/MAGNET no playback support.
- `link.getVideoSize(timeoutSeconds = 3)` — HEAD request, VIDEO type only, caches.
- `link.getAllHeaders()` merges referer into headers.
- `DrmExtractorLink` initializer fields: `kid`, `key`, `uuid` (CLEARKEY/WIDEVINE/PLAYREADY
  DRM_UUID), `kty = "oct"`, `keyRequestParameters`, `licenseUrl`.
- `Qualities`: P144…P2160, `Unknown.value = 400`; `getQualityFromName("1080p"|"4k") → Int`.

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
ExtractorApi.fixUrl(url)                             // relative→absolute against mainUrl ("//x"→https://x)
httpsify(url)                                        // "//host" → "https://host"
getAndUnpack(html)                                   // decode eval(function(p,a,c,k,e,…)) packed JS
unshortenLinkSafe(url)                               // resolve shortlinks, safe fallback to input
getPostForm(requestUrl, html)                        // generic op/id/mode/hash form submit + 5s delay
loadExtractor(url, referer?, subtitleCallback, callback): Boolean   // dispatch to registered extractor
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
