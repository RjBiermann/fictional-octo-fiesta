# FINDINGS-1454 — Cat3Film video sources (S16 of #1354)

Audit of the **video-source leg** (D2) of the Cat3Film spec (`cat3film.com`). Scope: for every
listing type in D3 (`new`/`latest`, `old`, `popular`, `genres`, `categories`, `tags`) open ≥2
video pages — sampled at both ends of the catalogue where the type is pageable — and resolve
`loadLinks` end-to-end: detail page → `.epbtn[data-ep]` → `/api/v1/episodes/{id}/sources` →
`master.abyssssss.top/<token>/index.m3u8` **206 `application/vnd.apple.mpegurl`**, embed host
identified and registered, quality passed to CloudStream where the site exposes it.

Provider state at run start: registry `last_deep` = 2026-10-16, `drift_confirmed` = **0**,
verdict at last deep `ok` (#636). `drift_confirmed` = 0 ⇒ a targeted fix would be allowed if a
condition were confirmed (the ≥3 hardening rung is not reached).

**Result: no condition confirmed on the video-source leg — the leg is clean, no code change, no
version bump (stays 28).** No finding was filed: every listing type the site exposes answered
plain-curl, and all 19 sampled rows resolved through both routes (primary token → HLS, backup
embed → dec-abyss → sora mp4) — 18 distinct titles, since `/hidden-face` is sampled twice
(rows 5 and 10).

Run: `05-implement` for #1454, 2026-11-23, plain-curl tier, runner IP US.
Workflow run: `https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/45282587022`.

Out of scope per spec: a full listing audit (pagination/card-field correctness was #632–#659) and
*rows* work (uncensored/ura row additions). Listing correctness was checked only as far as
enumeration needed, and not re-probed beyond that (`drift_confirmed` = 0, enumeration intact).

---

## Phase 0 — canary calibration (before touching the site)

| Canary | Expected | Observed | Result |
| --- | --- | --- | --- |
| `eporner-healthy` | plain-curl 200 | plain-curl **200** (312 661 B — real homepage) | match — instrument healthy |
| `film1k-challenge-pair` | plain-curl 403, tls-impersonated 200 | plain-curl **200** (214 908 B) | **expectation-stale** (#1435 open) — live pair recorded here; **no** `environment-degraded` branch taken |
| `pandamovies-redirect-origin` | plain-curl 302, tls-impersonated 200 | plain-curl **302 → https://pandamovies.org/** | plain-tier match; the TLS leg cannot run (#1434) |

```
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://www.eporner.com/   → 200 312661
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://www.film1k.com/    → 200 214908
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://pandamovies.pw/      → 302 https://pandamovies.org/
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://cat3film.com/      → 200 152339
$ python3 -c 'import curl_cffi'
ModuleNotFoundError: No module named 'curl_cffi'      # #1434 still open at run time
```

**#1434 is still open**, so the registry `last_runs` entry carries `escalation_tier: unavailable`
and any `Blocked` this run would downgrade to `Suspected`. None occurred — cat3film.com answered
plain-curl on every surface below, including all 19 streams (18 distinct titles) and both routes.

Instrument = **plain-curl tier**, desktop Chrome UA
(`Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36`).
No proxy, no impersonation, no browser instrument anywhere below. Stream fetches were re-run with
app-like UAs (`CloudStream/4.9.1`, `Dalvik/2.1.0`) — same answers (see *Headers / referer*).

---

## Listing-type inventory (D3)

The site is a **custom Go/template engine** (not KVS, not WP): server-rendered `a.card` grids,
JW Player on the watch page, internal JSON API `/api/v1/...`, AJAX search at `/_ajax/search`,
Cloudflare in front (`cf-ray`, no challenge seen this run). Taxonomies live at
`/genre/<slug>`, `/year/<yyyy>`, `/actor/<slug>`, `/country/<slug>`, `/director/<slug>`; listings
paginate with `?page=N`; the `/movies` filter bar carries `select[name=genre]`,
`select[name=country]`, `select[name=year]` and `select[name=sort]` (`newest` / `oldest` /
`random`).

Every row probed plain-curl. A type the site does not expose is recorded **`absent`** with the
evidence that proves it, never assumed.

| type | surface | evidence |
| --- | --- | --- |
| `new` / `latest` | **`/movies`** (the provider's row; stable insertion order — two fetches ~2 s apart returned the same first five cards) — p1 **30**, p2 **30**, p1↔p2 overlap **0**, no in-page duplicate hrefs; far end `/movies?page=56` **200, 26** cards, `/movies?page=57` **404** (catalogue ≈ 1 676 movies). The year-sorted variant `/movies?sort=newest` is also live: p1 **30** (2024→2020), far end p56 **26** (1968→1933), p57 **404** | 200 |
| `old` | **`/movies?sort=oldest`** (the filter bar's `sort=oldest` option; there is no `/old` or `/oldest` route — both **404**) — p1 **30** (1933, 1938, 1952 … 1968), p2 **30** (1968–1969), p1↔p2 overlap **0**; far end p56 **26** (2021–2024), p57 **404**. `/movies` and `/movies?sort=newest` p1 overlap the oldest p1 in **1** / **0** cards, confirming the ordering is real, not a re-render | 200 |
| `popular` | **`/top`** — p1 **30**, p2 **30**, overlap **0**; far end `/top?page=57` **200, 11**, `/top?page=58` **404**. p1 shares **0** cards with `/movies` p1 (separate ranking) | 200 |
| `genres` | **`/genre/<slug>`** — `/genre/drama` p1 **30**, p2 **30**, overlap **0**, far end `/genre/drama?page=33` **200, 19**, `/genre/drama?page=34` **404**. There is no `/genre` index (**404**), but the `/movies` filter form carries `select[name=genre]` with **20** slugs (`action, adventure, animation, comedy, crime, documentary, drama, family, fantasy, history, horror, music, mystery, romance, science-fiction, short, thriller, tv-movie, uncategorized`) and `?genre=horror` serves **200, 30** — that select is the genre vocabulary index. Recorded **present** | 200 |
| `categories` | **absent as its own namespace.** `/categories` → **404** (19 B), `/category/drama` → **404**. The site's genre/category vocabulary *is* the `/genre/` namespace sampled above, so this is a route-shape fact, not a missing surface | 404 |
| `tags` | **absent.** `/tags` → **404** (19 B), `/tag/drama` → **404**. No tag route exists anywhere in the nav, filter form, or detail-page markup (only `/genre/`, `/actor/`, `/year/`, `/country/`, `/director/` taxonomy links) | 404 |

Provider-row surfaces re-probed for enumeration (not D3 types, same leg): `/tv-series` **200, 15**
cards (14 on 2026-10-16 — catalogue grew) and `/tv-series?page=2` **404** (single page);
`/year/1984` **200, 30** cards; `/` (rank grid) **200, 60** cards.

### Search and quick search

```
$ curl -s -A "$UA" 'https://cat3film.com/_ajax/search?q=handmaiden'
{"results":[{"format":"Movie","slug":"the-handmaiden","thumb":"/uploads/672b550f7349ff8432a3.jpg","title":"The Handmaiden","year":2016}]}
$ curl -s -A "$UA" 'https://cat3film.com/_ajax/search?q=hache'
{"results":[{"format":"TV","slug":"hache","thumb":"/uploads/3f55d994b79f7a0e5266.jpg","title":"Hache","year":2019}]}
$ curl -s -A "$UA" 'https://cat3film.com/_ajax/search?q=hot%20line'   → 1 hit, slug "hot-line"
$ curl -s -A "$UA" 'https://cat3film.com/_ajax/search?q=seule'        → 1 hit, slug "seule"
$ curl -s -A "$UA" 'https://cat3film.com/_ajax/search?q=milf'         → {"results":[]}   (catalogue reality; healthy terms return hits)
$ curl -s -A "$UA" https://cat3film.com/search?q=handmaiden           → 404
```

The contract (`format`, `slug`, `thumb`, `title`, `year`; single page, no pagination) is unchanged
since #636. **Quick search does not exist as a separate endpoint** — `static/js/site.js` wires the
live-typing dropdown to the same JSON surface:

```js
function doSearch(q) {
  fetch("/_ajax/search?q=" + encodeURIComponent(q))
    .then(function (r) { return r.json(); })
```

so `verify.sh`'s check-1b NOTE (no distinct quick-search endpoint) is the documented outcome, not a
gap. The site has **no HTML search page**; this is the third consecutive record of the tool's
check 1 being a FAIL-by-tool on this provider.

---

## Evidence rows (D4)

`type | sample URL | instrument tier | stream host | quality | verdict` — 19 videos, ≥2 per present
type, both catalogue ends where the type is pageable. Every row resolved **end-to-end**: detail
page → watch page `.epbtn[data-ep]` → sources API → `master.abyssssss.top/<token>/index.m3u8`
**206 `application/vnd.apple.mpegurl`**; the backup embed (`player.abyssplayer.com/<id>`) was also
decoded for **all 18 distinct titles** (the 19 rows re-sample `/hidden-face` at rows 5 and 10),
each returning at least one `status: true` rung.

| # | type | sample URL | listing position | tier | stream host | quality | verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | new/latest (`/movies`) | `/seule` | p1 head | plain-curl | `master.abyssssss.top` | single HLS; backup 480p/720p/1080p | **pass** |
| 2 | new/latest (`/movies`) | `/youtopia` | p1 tail | plain-curl | `master.abyssssss.top` | single HLS; backup 480p/720p | **pass** |
| 3 | new/latest (`/movies`) | `/love-lesson` | p56 head (last page) | plain-curl | `master.abyssssss.top` | single HLS; backup 360p/720p | **pass** |
| 4 | new/latest (`/movies`) | `/ciao-ciao` | p56 tail (last page) | plain-curl | `master.abyssssss.top` | single HLS; backup 360p/720p | **pass** |
| 5 | new/latest (`?sort=newest`) | `/hidden-face` | p1 head (2024) | plain-curl | `master.abyssssss.top` | single HLS; backup 480p/720p | **pass** |
| 6 | new/latest (`?sort=newest`) | `/boobs-a-lot` | p56 tail (1968) | plain-curl | `master.abyssssss.top` | single HLS; backup 360p/480p | **pass** |
| 7 | old (`?sort=oldest`) | `/baby-face` | p1 head (1933) | plain-curl | `master.abyssssss.top` | single HLS; backup 360p/720p | **pass** |
| 8 | old (`?sort=oldest`) | `/one-swedish-summer` | p1 tail (1968) | plain-curl | `master.abyssssss.top` | single HLS; backup 360p/480p | **pass** |
| 9 | old (`?sort=oldest`) | `/palitan` | p56 head (2021) | plain-curl | `master.abyssssss.top` | single HLS; backup 360p/720p/1080p | **pass** |
| 10 | old (`?sort=oldest`) | `/hidden-face` | p56 tail (2024) | plain-curl | `master.abyssssss.top` | single HLS; backup 480p/720p | **pass** |
| 11 | popular (`/top`) | `/dreams-of-a-country-girl` | p1 head | plain-curl | `master.abyssssss.top` | single HLS; backup 360p | **pass** |
| 12 | popular (`/top`) | `/hit-spot` | p1 tail (TV, 8 eps) | plain-curl | `master.abyssssss.top` | single HLS; backup 480p/720p | **pass** |
| 13 | popular (`/top`) | `/dear-john` | p57 head (last page) | plain-curl | `master.abyssssss.top` | single HLS; backup 1080p **av1** | **pass** |
| 14 | popular (`/top`) | `/madness` | p57 tail (last page) | plain-curl | `master.abyssssss.top` | single HLS; backup 360p/720p/1080p | **pass** |
| 15 | genres (`drama`) | `/porn-theatre` | `/genre/drama` p1 tail | plain-curl | `master.abyssssss.top` | single HLS; backup 480p | **pass** |
| 16 | genres (`drama`) | `/female-war-a-nasty-deal` | `/genre/drama` p33 head | plain-curl | `master.abyssssss.top` | single HLS; backup 360p/720p | **pass** |
| 17 | genres (`drama`) | `/an-endless-night` | `/genre/drama` p33 (last page) | plain-curl | `master.abyssssss.top` | single HLS; backup 360p/720p/1080p | **pass** |
| 18 | new/latest (TV row) | `/hache` | `/tv-series` p1 (14 eps, 2 seasons) | plain-curl | `master.abyssssss.top` | single HLS; backup 360p/720p | **pass** |
| 19 | new/latest (TV row) | `/hot-line` | `/tv-series` p1 (18 eps, 2 seasons) | plain-curl | `master.abyssssss.top` | single HLS; backup 480p | **pass** |

`categories` and `tags` are `absent` (see inventory) so they contribute no row — absence is the
recorded outcome, not a finding.

**Quality.** The primary token route is a **single-rendition media playlist**: no
`#EXT-X-STREAM-INF`, no `RESOLUTION`, one `index.m3u8` whose segments are served from
`player.abyssssss.top`. The site exposes **no quality ladder on that route**, so the provider's
`quality = Qualities.Unknown` is correct there. The site's **backup embed** does expose a ladder,
and the shared `AbyssPlayer` adapter maps the `type` labels through unchanged (360p/480p/720p/1080p
on the samples above). The card/detail `HD`/`SD` chip is a badge, not a per-source rendition
label — not something `loadLinks` can map to a stream.

### The stream chain (re-proven, unchanged)

```
GET https://cat3film.com/seule                                                    → 200, 37 238 B
  <h1 class="info-title">Seule</h1>   badges [2012, 1h 33m, Movie]
  <a href="/watch/seule?sv=1&part=1">Watch Online</a>

GET https://cat3film.com/watch/seule?sv=1&part=1                                  → 200, 19 172 B
  <button class="epbtn on" data-ep="1845" data-name="Full" data-sv="1" data-ss="1" data-no="1">

GET https://cat3film.com/api/v1/episodes/1845/sources   (Referer: https://cat3film.com/)  → 200
  {"backup":"https://player.abyssplayer.com/pFpmF0ohO",
   "sources":[{"file":"https://master.abyssssss.top/2Typyj5r_eTxJZqyYpMVwtl4i9QkPmZDf2JpQZOKcgU",
               "type":"hls"}],
   "subs":[],"success":true}

GET https://master.abyssssss.top/2Typyj5r…/index.m3u8   -r 0-2047                → 206
  content-type: application/vnd.apple.mpegurl
  #EXTM3U
  #EXT-X-TARGETDURATION:4
  #EXT-X-ALLOW-CACHE:YES
  #EXT-X-PLAYLIST-TYPE:VOD
  #EXT-X-VERSION:3
  #EXT-X-MEDIA-SEQUENCE:1
  #EXTINF:4.000,
  https://player.abyssssss.top/2Typyj5r…/seg-1-v1-a1.jpg
  …

GET https://player.abyssssss.top/2Typyj5r…/seg-1-v1-a1.jpg  -r 0-1023           → 206 image/jpeg (1024 B)
```

`Parse.streamUrl()` appends `/index.m3u8` to the bare token exactly as the site's `withSuffix()`
does (`static/js/site.js`: pass through `/\.(m3u8|json)(\?|$)/`, else append `/index.m3u8` on
iOS/Mac Safari and `/index.json` otherwise; both suffixes serve the same `#EXTM3U` body — 2026-10-15
record, re-confirmed by the playlist above).

### Backup embed leg (2nd route, fully playable today)

```
GET https://player.abyssplayer.com/pFpmF0ohO                                       → 200
  <title>5507.mp4</title>   const datas = "eyJzbHVnIjoiT3dqS1BtV1pXIiwibWQ1X2lkIjoyODMxODg3OCw…"
POST https://enc-dec.app/api/dec-abyss   (Origin: https://enc-dec.app)             → 200
  {"status":200,"result":{"sources":[
    {"url":"https://p5bxlr4lz51.sssrr.org/sora/628137591/NkpMUVVU…","size":628137591,"type":"360p","codec":"h264","status":true},
    {"url":"https://tk8b7ce9830.sssrr.org/sora/1184738611/bVorT0F4…","size":1184738611,"type":"720p","codec":"h264","status":true}]}}
GET https://p5bxlr4lz51.sssrr.org/sora/…  -r 0-2047                              → 302
  location: https://saint-stands-nations-ben.trycloudflare.com/sora/…
GET (follow) https://saint-stands-nations-ben.trycloudflare.com/sora/…  -r 0-2047 → 206 video/mp4
  00000000: 0000 0020 6674 7970 6973 6f6d 0000 0200  ... ftypisom....
```

`player.abyssplayer.com` is registered in shared `HostRegistry` (`AbyssPlayerEmbed`, #613 Story 2),
and the apex `AbyssPlayer` row is also present — the sources-API backup URL prefix-matches the
explicit row, so the provider's second route no longer rides the Levenshtein fallback. **Note:**
the 2026-10-16 deep pass recorded the final sora CDN (`*.sssrr.org`) hard-blocking datacenter IPs;
today the sora hop redirects to a `*.trycloudflare.com` mirror that serves **206 video/mp4** from
this runner, so both routes were playable end-to-end this run.

### Headers / referer

Nothing is required. The sources API answers **200** with `Referer: https://cat3film.com/`, with the
watch-page referer, and with **no Referer** at all; the token playlist serves **206** with and
without `Referer`, and with `CloudStream/4.9.1` / Android Chrome UAs. The provider sets
`referer = "$mainUrl/"` unconditionally — harmless, and it stays as the hedge for per-shard
behaviour.

### Geo

Probed from a **US** runner IP; no region wall observed on listings, video pages, either stream
route, or the backup mirror. In-app verification note for non-US users stands at merge.

---

## Related videos

`section#related a.card` — **4** cards on **18/18** sampled detail pages, no duplicate hrefs, no
self-reference, non-empty titles. The rail is present on movies, single-server series
(`/hit-spot`) and multi-season series (`/hache`, `/hot-line`). The provider reads exactly this
selector; unchanged.

## Episodes

`Parse.episodes` re-confirmed on the two shapes the site serves:

- **Multi-season** — `/hache`: 2 `.wserver` panes ("Season 1" 449–456, "Season 2" 457–462),
  14 `.epbtn[data-ep]`, `data-no` restarts per season; `/hot-line`: 2 panes, 18 episodes
  (1009–1026, `data-no` 1–6 then 1–12).
- **Single-server / movie** — one `.wserver` ("Server 1"), one `.epbtn` (`data-no="1"`,
  `data-name="Full"`), season null. `/hit-spot` is a TV series on a single pane with 8 episodes.

Episode ids churn across imports (e.g. `/ciao-ciao` → ep 1, `/love-lesson` → ep 27) — ids are not
stable sentinels; the provider reads them fresh from `data-ep` every time. Not drift.

---

## Instrument / site-side notes (recorded, never filed)

1. **No HTML search page** — the site's only search surface is the JSON `/_ajax/search?q=`.
   `verify.sh` asserts HTML `a.card`s, so check 1 is a **FAIL-by-tool** for this provider (third
   consecutive record: 2026-09-11, 2026-10-15, 2026-10-16, today). Agreement is proven by curl
   instead: `q=seule` → `{"slug":"seule","title":"Seule","year":2012}` matches load page
   `/seule` (h1 `Seule`, og:image `/uploads/4ffbb01dea08e1d29924.jpg` — the card's own
   `/uploads/4ffbb01dea08e1d29924.jpg` path).
2. **`og:title` is empty on detail pages** (`h1.info-title` is the real title) — unchanged since
   2026-09-11; the provider already prefers `h1.info-title`, and `verify.sh` is passed
   `--video-title-selector 'h1.info-title'`.
3. **Duration is ld+json-only** (`"duration":"PT76M"` in the `Movie`/`TVSeries` object); there is no
   CSS element for `verify.sh`'s 2a bar, so no `--video-duration-selector` is passed and this NOTE
   is the documented outcome. `JsonLdParse.minutes` is the provider surface and is unit-tested.
4. **Tags / actors / year are link-based** (`a[href^="/genre/"]`, `a[href^="/actor/"]`,
   `a[href^="/year/"]`) inside `.info-people` label groups — present on **5/5** verify pages and all
   18 sampled detail pages; three selectors passed, so no NOTE.
5. **Card poster is a render, load poster is the original**: cards use `/uploads/<id>.jpg` (plus
   width variants in `srcset`), detail pages use `og:image` `/uploads/<id>.jpg` — same artwork path,
   so check 5's poster comparison passes when the same video is in the search draw.
6. **`dear-john` backup is AV1-only** (1080p, `codec: av1`, 206 video/mp4). The shared adapter
   faithfully emits it; playback depends on the device's AV1 support (in-app note below), and the
   primary HLS route is H.264, so no user is stranded. Site-side encoding, not drift.

---

## Delivery gate

```
$ ./gradlew Cat3Film:test        → BUILD SUCCESSFUL (394 test executions, 0 failures)
$ ./gradlew Cat3Film:make        → Cat3Film/build/Cat3Film.cs3 (171 211 B)
$ .pi/skills/audit-providers/scripts/check-findings.sh → findings-lint: PASS
```

`verify.sh` (live, plain-curl tier) — **provider-relevant checks PASS; the single FAILED line is the
pre-documented check-1 FAIL-by-tool** (JSON-only search surface, note 1). 5 video pages spanning
new/latest, old, popular, genres and the TV row, 5 position-matched `--stream-url` overrides
(streams are JS-built from the sources API, the page carries no CSS stream element — the documented
override path), `--provider-src Cat3Film`:

```
── check 1: search pages (1)
GET https://cat3film.com/_ajax/search?q=seule → 200; 'a.card' matches: 0
FAIL search (https://cat3film.com/_ajax/search?q=seule)        ← JSON surface, note 1
── check 1a: homepage pages (2)
GET https://cat3film.com/movies → 200; 'a.card' matches: 30
GET https://cat3film.com/movies?page=2 → 200; 'a.card' matches: 30
── check 1b: quick search (0)
NOTE: no --quick-search-url — … (no distinct endpoint; site.js reuses /_ajax/search)
── check 2: video pages (5 URLs)
GET …/seule → 200; 'a[href*="/watch/"]' matches: 1
GET stream (…/2Typyj5r…/index.m3u8) → 206 application/vnd.apple.mpegurl
GET …/seule → 'section#related a.card' matches: 4
… (5/5 pages 200, 5/5 streams 206 m3u8, 4 related cards each)
field 'tags': present on all 5 sampled pages
field 'actors': present on all 5 sampled pages
field 'year': present on all 5 sampled pages
NOTE: no --video-duration-selector — … (ld+json only, note 3)
── check 5: search ↔ load agreement
NOTE: no sampled video appears in the search page(s) — … (JSON surface has no cards; curl proof in note 1)
── check 6: LoadResponse completeness (code vs FINDINGS)
field 'recommendations': 3 · 'tags': 2 · 'plot': 3 · 'duration': 3 · 'year': 3 · 'actors': 4 · 'score': 2 · 'posters': 4
RESULT: FAIL                                                      ← check 1 only
```

Invocation notes (the FINDINGS lines each flag requires):

- **Quick search** — no distinct endpoint; `hasQuickSearch` stays as shipped (the provider does not
  set it), and the site's live-typing dropdown calls `/_ajax/search` (transcript above).
- **Home page 2** — `/movies` + `/movies?page=2`, 30 + 30, 0 overlap, 0 in-page duplicates.
- **Search page 2** — the JSON surface has no pagination; the provider returns `hasNext = false`.
- **Check 5** — NOTE, not exercised by the tool: the JSON search draw has no HTML cards. Title,
  poster and year agreement is transcribed in note 1 from live curl.
- **Tags / actors / year** — link selectors present 5/5 (note 4).
- **Duration** — ld+json only (note 3).
- **Streams** — positional `--stream-url` overrides fetched fresh at invocation time (tokens are
  short-lived); all five 206 `application/vnd.apple.mpegurl`.
- **Plot** — `meta[property=og:description]`, present 5/5.

---

## Registry note

`audits/findings.json` is **not** in this diff (ADR-0013). The provider entry refresh + this run's
`last_runs` entry are posted as a `<!-- findings-registry-entry -->` comment on #1454 for the
orchestrator's `registry_sync`: `verdict` **`ok`**, `tier` **`plain-curl`**, `last_deep`
**2026-11-23**, `drift_confirmed` **0**, `chronic` false, `issue` null, `standing_issue` null, and one
`last_runs` entry with `false_positives: []` and `escalation_tier: unavailable` (#1434).

## False positives

None — **0** conditions confirmed, **0** findings filed, **0** standing issues. The notes above are
site-side shapes or instrument limits, not drift: the card grammar, episodes/`.epbtn` contract,
sources-API shape, suffix rule, both stream routes and the related rail all held.

## In-app-verification notes (maintainer, at merge)

1. **Primary route** — HLS token playlist (206, single rendition, H.264 segments served under a
   `.jpg` name); shows as one source with Unknown quality, as before.
2. **Backup route** — `AbyssPlayerEmbed` decodes to 360p–1080p mp4 rungs; the sora hop currently
   redirects to a `*.trycloudflare.com` mirror (206). If a mirror dies, the primary route is
   unaffected.
3. **AV1 sample** — `/dear-john` backup is 1080p **AV1** only; on a device without AV1 support the
   backup rung may not play. The primary HLS route for that title is H.264.
4. **TV series** — `/hache` (14 eps, 2 seasons) and `/hot-line` (18 eps, 2 seasons) exercise the
   multi-`.wserver` episode path; `/hit-spot` exercises single-pane multi-episode.
