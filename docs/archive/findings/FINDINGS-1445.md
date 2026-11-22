# FINDINGS-1445 — ixiporn video sources (S7 of #1354)

Run: 2026-11-22 (`05-implement`, story **S7** of parent spec #1354). Scope per the spec: the
**video-source leg** (D2) across the D3 listing types — `new`/`latest`, `old`, `popular`,
`genres`, `categories`, `tags` — ≥2 video pages per type, sampled at both catalogue ends
where the type is pageable, with `loadLinks` resolved **end-to-end**. Listing correctness
(`#632–#659`) and *rows* work are out of scope.

Site: `https://ixiporn.org` → `301 ixiporn.info` → `301 ixiporn.live` → **200** — the standing
#468 chain, byte-for-byte unchanged (no re-filed condition). Every probe below ran against the
`.live` origin with the provider's desktop UA
(`Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0`) and
`Referer: https://ixiporn.live/`.

Registry at start: `last_deep` 2026-10-14, `drift_confirmed` 0, verdict `ok-drift`, standing
issue #468 (closed, redirect chain). Dedup searched open **and** closed issues for this
provider: #1445 (this spec), #835 (closed S26 API-contract audit — clean), #781/#780/#779/#778
(closed shape-B embed chain), #709/#708 (closed shape-B record), #655 (closed deep pass), #468
(closed redirect), #217/#127/#120 (closed card/field fixes). No standing issue covered either
condition confirmed in this run.

## Phase 0 — calibration (ran before any provider probing)

| canary | expected | observed (plain-curl) | reading |
|---|---|---|---|
| `film1k-challenge-pair` | plain 403 / TLS 200 | plain **200** / TLS unavailable | **expectation-stale** — open #1435; the live pair is recorded here, the `environment-degraded` branch is **not** taken |
| `eporner-healthy` | plain 200 | plain **200** | match — instrument healthy |
| `pandamovies-redirect-origin` | plain 302 / TLS 200 | plain **302 → https://pandamovies.org/** | match (redirect-following intact) |
| `ixiporn.org` (site itself) | — | **301 → .info → 301 → .live → 200** | probe tier available; no challenge |

```
$ UA="Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0"
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.film1k.com/
200
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.eporner.com/
200
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://pandamovies.pw/
302 https://pandamovies.org/
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://ixiporn.org/
301 https://ixiporn.info/
$ .pi/skills/site-probe/scripts/impersonate.sh https://www.film1k.com/
impersonate.sh: curl_cffi missing for python3. Install it, then re-run:
```

- **#1434 (open)** — TLS-impersonated tier unusable → `escalation_tier: unavailable`. Only
  `Blocked` verdicts downgrade to `Suspected`; **this run produced zero `Blocked` verdicts**.
- **#1435 (open)** — film1k's stale expectation is recorded, not treated as degradation.
- No genuine `environment-degraded` mismatch → instrument healthy.

## Listing enumeration (the D3 types as the site actually serves them)

Card identity is `data-post-id` (30 cards/page on every type). Page-1 vs page-2 intersections
below are computed on those ids.

| type | first page | far end | evidence |
|---|---|---|---|
| `new` / `latest` | `/page/1` — 30 cards (166913…166824) | `/page/1964` — 30 cards (90…35, the 2020-11 batch) | `/page/2` 30 cards (166821…166688); p1∩p2 = **0**; filter menu's `?filter=latest` is the same listing; paginator last page 1964 |
| `old` | `/page/1?orderby=date&order=ASC` — 30 cards (35…90) | `/page/1964?orderby=date&order=ASC` — 30 cards (166824…166913) | `/page/2?orderby=date&order=ASC` 30 cards (95…149), p1∩p2 = **0**. Honoured server-side (WP `orderby`/`order`) but **not linked anywhere** — the visible filter menu offers `latest / most-viewed / longest / popular / random` only |
| `popular` | `/page/1?filter=most-viewed` — 30 cards (155053…159929) | `/page/1964?filter=most-viewed` — 30 cards (153814…69108) | `/page/2?filter=most-viewed` 30 cards (159945…154008); p1∩p2 = **0**. `?filter=popular` is a separate **randomised** widget: 3 consecutive fetches returned 3 different card sets (131412… / 166877… / 111426…) and `/page/N?filter=popular` is unstable too, so sampling used the stable `most-viewed` sort |
| `genres` | — | — | **absent**: no `/genre/`, `/genres/`, `/genre` route (all `301 → /` shell), no genre taxonomy in the nav, the category index or the filter menu — `categories` + `tags` carry the semantics |
| `categories` | `/category/indian` — 30 cards (121220…120790) | `/category/indian/page/164` — 30 cards (3568…1068) | `/category/indian/page/2` 30 cards (120780…120316); p1∩p2 = **0**; paginator last page 164; index at `/porn-video-categories` (60 unique `category|tag` links); page numbers past the last page clamp to the homepage listing |
| `tags` | `/tag/ullu-web-series` — 30 cards (163304…162026) | `/tag/ullu-web-series/page/49` — 29 cards (112692…112136) | `/tag/ullu-web-series/page/2` 30 cards (162023…161145); p1∩p2 = **0**; paginator last page 49; `/porn-video-tags`, `/tags` and `/tag-cloud` are not routes (`301`/`500`/homepage shell) |

Search (the provider's `search()` surface) is pageable too: `/page/1?s=hunters` 30 cards
(146789…144693), `/page/2?s=hunters` 30 cards (143432…141410), p1∩p2 = **0**.

```
/page/1            30 · /page/2 30 (∩0) · /page/1964 30 (last)
/page/1?orderby=date&order=ASC 30 · p2 30 (∩0) · /page/1964?orderby=date&order=ASC 30
/page/1?filter=most-viewed 30 · p2 30 (∩0) · /page/1964?filter=most-viewed 30
/category/indian   30 · p2 30 (∩0) · p164 30 (last)
/tag/ullu-web-series 30 · p2 30 (∩0) · p49 29 (last)
/page/1?s=hunters  30 · /page/2?s=hunters 30 (∩0)
/old/ /popular/ /genre/ /oldest/ /categories/ → 301 https://ixiporn.live/   (not routes)
```

## Player shapes on the video page (all on `div.video-player`)

| shape | marker | who resolves it | share of the 2026-11-22 sample |
|---|---|---|---|
| **A** — direct file | `meta[itemprop=contentUrl]` | provider (unchanged) | newest uploads + 2021-07-on batches |
| **B** — b64 hub embed | `iframe[src*=player-x.php]?q=<b64>`, payload `type=iframe` | provider b64 chain (#780) → shared hub rows | 2020-12 … 2021-02 imports: xvideos + redtube + **youporn** (third hub, see below) |
| **D** — raw embed iframe | `meta[itemprop=embedUrl]` + bare `<iframe src=…>`, **no** `contentUrl`, **no** player-x | **was nobody → fixed in this run** | import batches around `/page/500–600`, `/page/1500`, `/page/1900` (vjav.com, videotxxx.com → canonical txxx.com) |

Census (24 listing pages spread over the catalogue, 2 videos each: `/page/1950 … /page/1`):
A 16 / B 24 / D 8. Shape D is not a one-off: `/page/1900` 6/6 and `/page/1500` 6/6 sampled
pages are shape D, `/page/500` and `/page/600` likewise.

## Evidence rows

`type | sample URL | instrument tier | stream host | quality | verdict`

Every row was resolved the way `loadLinks` does it. `quality` is what the site's player
declares — shape-A files carry no ladder at all (a single mp4 per video), the KVS player
exposes only `_hq.mp4`/`_sd.mp4`, and the youporn hub exposes a real 480/240 ladder.

| type | sample URL | tier | stream host | quality | verdict |
|---|---|---|---|---|---|
| `new` (p1) | `/hot-dancer-girl-fucked-2026-hindi-uncut-porn-video` | plain-curl | `cdn2.ixifile.xyz` mp4 | single 2026 mp4 (no ladder) | **pass** (206 video/mp4) |
| `new` (p1) | `/horny-bhabhiji-gangbang-2026-bindastimes-hindi-porn-video` | plain-curl | `cdn2.ixifile.xyz` mp4 | single mp4 | **pass** (206 video/mp4) |
| `new` (last p1964) | `/share-with-your-step-sister-s2e1` | plain-curl | `images.nubiles-porn.com` mp4 | `…_long_640.mp4` | **Suspected** — CDN answers **403** to every request (also for `/`, any referer) → host-side, in-app note |
| `new` (last p1964) | `/step-sister-anatomy-s2e4` | plain-curl | `images.nubiles-porn.com` mp4 | `…_long_640.mp4` | **Suspected** — same CDN 403 (5/5 sampled nubiles files 403) |
| `old` (p1 ASC) | `/step-sister-anatomy-s2e4` | plain-curl | `images.nubiles-porn.com` | — | **Suspected** — host-side 403 (oldest batch IS the nubiles batch) |
| `old` (last p1964 ASC) | `/two-milfs-threesome-2026-hunter-asia-hindi-porn-video` | plain-curl | `cdn2.ixifile.xyz` mp4 | single mp4 | **pass** (206 video/mp4) |
| `old` (last p1964 ASC) | `/tina-nandi-hot-romantic-sex-2026-hindi-uncut-porn-video` | plain-curl | `cdn2.ixifile.xyz` mp4 | single mp4 | **pass** (206 video/mp4) |
| `popular` (p1) | `/ek-deewana-tha-2024-ullu-originals-hindi-porn-web-series-ep-3` | plain-curl | `cdn2.ixifile.xyz` mp4 | single mp4 | **pass** (206 video/mp4) |
| `popular` (p1) | `/indian-step-mom-2024-xprime-hindi-desi-porn-video` | plain-curl | `cdn2.ixifile.xyz` mp4 | single mp4 | **pass** (206 video/mp4) |
| `popular` (last p1964) | `/gayer-sammandh-part-1-2023-mojflix-hindi-uncut-porn-video` | plain-curl | `cdn2.ixifile.xyz` mp4 | single mp4 | **pass** (206 video/mp4) |
| `popular` (last p1964) | `/daan-2023-primeplay-hindi-porn-web-series-episode-1` | plain-curl | `cdn2.ixifile.xyz` mp4 | single mp4 | **pass** (206 video/mp4) |
| `genres` | — | — | — | — | **absent** (no genre route/taxonomy) |
| `categories` (p1) | `/indian-hot-milf-bhabhi-hardcore-sex-toptenxxx-hindi-uncut-video` | plain-curl | `cdn2.ixifile.xyz/1/…` | single mp4 | **Suspected** — CDN **404** on the site's own declared file (dead site file, see below) |
| `categories` (p1) | `/indian-hot-couple-midnight-sex-mystudio07-hindi-sex-video-2021` | plain-curl | `cdn2.ixifile.xyz/1/…` | single mp4 | **Suspected** — CDN 404 (dead site file) |
| `categories` (last p164) | `/hot-indian-girl-sex-with-boyfriend` | plain-curl | `ixiporn.live/wp-content/uploads/2020/11/mom_and_son_sex_video_480p.mp4` | `480p` in filename | **pass** (206 video/mp4) |
| `categories` (last p164) | `/hungry-bhabi-hot-sex` | plain-curl | `ixiporn.live/wp-content/uploads/2020/11/bhabhi_480p.mp4` | `480p` in filename | **pass** (206 video/mp4) |
| `tags` (p1) | `/andhadhundh-2026-ullu-hindi-porn-web-series-episode-7` | plain-curl | `cdn2.ixifile.xyz/4/…` | single mp4 | **pass** (206 video/mp4) |
| `tags` (p1) | `/andhadhundh-2026-ullu-hindi-porn-web-series-episode-6` | plain-curl | `cdn2.ixifile.xyz/4/…` | single mp4 | **pass** (206 video/mp4) |
| `tags` (last p49) | `/mastram-hot-web-series-mxplayer-season-1-episode-2-full-video` | plain-curl | `ixifile.xyz` mp4 | single mp4 | **pass** (206 video/mp4) |
| `tags` (last p49) | `/riti-riwaj-pinjara-ullu-web-series-2021-part-2-full-video` | plain-curl | `ixifile.xyz` mp4 | single mp4 | **pass** (206 video/mp4) |
| shape-D (import batch p1900) | `/german-amateur-skinny-blonde-teen-at-outdoor-creampie-gangbang` | plain-curl | `videotxxx.com` → `txxx.com` → `/get_file/22/…_hq.mp4` | `_hq.mp4` | **pass after fix** (pre-fix: zero links — raw iframe ignored) |
| shape-D (import batch p1500) | `/milfiezu` | plain-curl | `vjav.com` → `/get_file/3/…_hq.mp4` | `_hq.mp4` | **pass after fix** (pre-fix: zero links) |
| shape-B (youporn hub, p1400) | `/familystrokes-boning-the-math-tutor` | plain-curl | `www.youporn.com/embed/14854709` → `ev-h.ypncdn.com` master.m3u8 | **480 / 240** | **pass after fix** (pre-fix: hub had no row → zero links) |
| shape-B (youporn hub, p1400) | `/familystrokes-teen-tied-up-and-fucked-by-step-daddy` | plain-curl | `www.youporn.com/embed/12323579` → `ev.ypncdn.com` mp4 | **480 / 240** (mp4-only page) | **pass after fix** |
| shape-B (youporn hub, p1400) | `/brother-step-sister-learn-to-get-along-riley-jean-family-therapy-2` | plain-curl | youporn `16181416` → **`/video-inactive/`** | — | **Suspected** — removed upstream (dead target declines, no junk link) |

Representative pass transcript (shape A, `/hot-dancer-girl-fucked…`, 2026):

```
$ curl -s -A "$UA" -e https://ixiporn.live/ https://ixiporn.live/hot-dancer-girl-fucked-2026-hindi-uncut-porn-video \
    | grep -o 'itemprop="contentUrl" content="[^"]*"'
itemprop="contentUrl" content="https://cdn2.ixifile.xyz/5/hot%20dancer%20girl%20fucked%202026%20uncut.mp4"
$ curl -s -A "$UA" -e https://ixiporn.live/ -H 'Range: bytes=0-64' -o /dev/null \
    -w '%{http_code} %{content_type}\n' "https://cdn2.ixifile.xyz/5/hot%20dancer%20girl%20fucked%202026%20uncut.mp4"
206 video/mp4
# the same URL WITHOUT the .live referer → 404 text/html (the CDN is referer-gated; the
# provider sets `referer = data`, so the app's requests carry it)
```

Shape-D chain (the fix), `/milfiezu` (vjav hub):

```
$ curl -s -A "$UA" -e https://ixiporn.live/ https://ixiporn.live/milfiezu \
    | grep -o 'itemprop="embedUrl" content="[^"]*"\|<iframe[^>]*src="[^"]*"'
<meta itemprop="embedUrl" content="https://vjav.com/embed/390635/" />
<iframe width="640" height="360" src="https://vjav.com/embed/390635/" …></iframe>
$ curl -s -A "$UA" -e https://vjav.com/embed/390635/ \
    "https://vjav.com/api/videofile.php?video_id=390635&lifetime=864000"
[{"format":"_hq.mp4","timelines_count":"31","timelines_interval":"30",
  "video_url":"L2dldF9maWxlLzМvNjRlYTАxNGViZDВjZjЕzYTRiМmQzYzАzZTIxYjNiNDNk…,ZD05МTАmYnI9МTkwJnRpPTЕ3OTUzNjIwМzY~",
  "is_default":1,"c":"memc"}]
# Cyrillic homoglyphs → Latin, `~`-terminated body, two comma-separated base64 parts
$ → https://vjav.com/get_file/3/64ea014e…/390000/390635/390635_hq.mp4/?d=910&br=190&ti=1795362036
$ curl -sL -A "$UA" -e https://vjav.com/embed/390635/ -H 'Range: bytes=0-1024' -o /dev/null \
    -w '%{http_code} %{content_type}\n' "<that url>"
206 video/mp4
# same grammar on the videotxxx mirror (17065297 → txxx.com → /get_file/22/…_hq.mp4 → 206)
# dead videos answer {"error":1,"msg":"not_found"} (vjav 390023) → no links, no throw
```

Shape-B youporn chain (the second fix):

```
$ curl -sL -A "$UA" -e https://ixiporn.live/ "https://www.youporn.com/embed/14854709/" \
    | grep -o 'mediaDefinition: \[.\{0,120\}'
mediaDefinition: [{"format":"hls","height":554,"width":720,
  "videoUrl":"https:\/\/www.youporn.com\/media\/hls\/?s=eyJ2a2V5Ijo0NTU5ODAzMSw…
$ curl -s -A "$UA" -e https://www.youporn.com/embed/14854709/ "<the /media/hls/?s=… leg>"
[{"defaultQuality":true,"format":"hls","height":554,"videoUrl":"https://ev-h.ypncdn.com/hls/…/480p_750k_14854709.mp4/master.m3u8?…","quality":"480"},
 {"defaultQuality":false,…,"quality":"240"}]
$ curl -sL -A "$UA" -e https://www.youporn.com/ …master.m3u8
200 application/vnd.apple.mpegurl
```

## Confirmed conditions (both fixed in this run, TDD red → green)

### 1. Shape D — the import batch's raw embed iframe was ignored (zero links)

`loadLinks` handled only `meta[itemprop=contentUrl]` and the `player-x.php` b64 wrapper. The
2020-12 … 2021-02 batch (~`/page/500–600`, `/page/1500`, `/page/1900`) carries a **bare**
iframe plus the site's own `meta[itemprop=embedUrl]`, and **no** player-x wrapper, so every one
of those videos emitted zero links (silently unplayable), and no registry row could help
because the URL never reached `loadExtractor`.

- `IxiPornParse.embedUrl(block)` — pure Parse seam: `meta[itemprop=embedUrl]` content first,
  else the first absolute `<iframe src>`; null for a `player-x.php` src (that is
  `hubEmbedUrl`'s b64 wrapper) and for relative srcs (the #655 no-junk-target contract).
- `loadLinks` — after the contentUrl and b64 branches, dispatch the extracted embed through
  the same `loadExtractor` call (cascade: contentUrl → player-x hub → embedUrl).
- Fixture: `ixiporn/src/test/resources/player-direct-iframe-videotxxx.block.html` (live
  verbatim block, 2026-11-22). Tests added to `IxiPornParseTest`: shape-D target, meta/iframe
  agreement, shape-A null, player-x-without-meta null, relative src null, null block null.

### 2. No registry row for the two new embed host families (zero links even once dispatched)

Both shape-D host families are KVS players whose embed page is a JS shell (no stream in the
HTML) — the stream comes from `/api/videofile.php?video_id=…&lifetime=864000` as a
homoglyph-obfuscated base64 `video_url`. The third shape-B hub (`youporn`) is server-rendered
but declares its legs as a **singular** `mediaDefinition`, which the redtube flashvars grammar
never matches.

- `shared/.../KvsVideofile.kt` — `KvsVideofileParse` (embed id → API URL, `~`/`,` payload
  deobfuscation with the Cyrillic→Latin map, JSON → absolute sources, `[]`/`error` decline)
  plus the `KvsVideofile(mainUrl, name)` adapter. Rows: `vjav.com`, `videotxxx.com`,
  `txxx.com`, `txxx.me` (dispatch is by the iframe's own host — the videotxxx 302 to
  `txxx.com` never helps, so each host needs its own row).
- `shared/.../YoupornEmbed.kt` — `YoupornParse.mediaFromEmbed` (bracket-scanned
  `mediaDefinition` array) + the `Youporn` adapter; the signed `/media/{hls,mp4}/?s=` hop and
  its variant JSON are `RedtubeParse`'s unchanged parser.
- Fixtures: `kvs_videofile_vjav.json`, `kvs_videofile_txxx.json`, `kvs_videofile_empty.json`,
  `youporn_embed_live.html`, `youporn_media_hls.json`, `youporn_media_mp4.json` (live captures,
  2026-11-22). Tests: `KvsVideofileParseTest` (7 parse + 1 registry), `YoupornParseTest`
  (5 + 1 registry), and the `Txxx` mirror-name row added to `HostRegistryTest`'s intentional
  collision allowlist.
- `ixiporn/build.gradle.kts` `version` **38 → 39** (a shared-row change only reaches users
  through a provider rebuild; ixiporn is the only provider whose pages emit these hosts —
  `grep -ri 'youporn\|vjav\|videotxxx\|txxx' */src` is empty outside this run's tests).

## Scope notes (not conditions)

- **`genres` is absent site-side.** No `/genre(s)/` route, no genre taxonomy in the nav or the
  category index (`/porn-video-categories` lists `category/*` + `tag/*` only), no genre entry in
  the filter menu. `categories` and `tags` are the two live taxonomies.
- **`old` is honoured but unlinked.** `?orderby=date&order=ASC` reverses the catalogue and
  paginates (1…1964, p1∩p2 = 0), but nothing in the UI offers it; recorded as a sampled type
  rather than a missing one.
- **`?filter=popular` is a random shuffle** (3/3 fetches returned different card sets), unlike
  the stable `most-viewed` / `longest` filters — a site-side widget, not a pagination defect.
  Recorded, no provider change (that widget is not a mainPage row).
- **Dead site files exist behind valid `contentUrl` metas.** `cdn2.ixifile.xyz/1/…` answers
  **404** for some 2021 uploads (`/indian-hot-milf-bhabhi…`, `/indian-hot-couple-midnight…`)
  and `ixiporn.live/files/*.mp4` answers the **homepage HTML** (200 `text/html`) for others
  (`/desi-college-girl-sucking…`, `/indian-mature-desi-wife…`) — the site's own broken links,
  nothing a provider can fix (a 404/HTML page is not a stream). Recorded, not a finding.
- **`images.nubiles-porn.com` 403s from this runner** for every sampled file *and* for `/`,
  with and without any referer — a host-side block (datacenter IP or global hotlink policy) on
  the 2020-11 batch. `Suspected` with the in-app-verification note; not a provider defect.
- **Dead targets decline as designed:** xvideos 33/37 and redtube 1/2 in the #781 sample;
  this run: youporn 1/3 (`/video-inactive/`), vjav 1/4 (`{"error":1,"msg":"not_found"}`) — all
  emit no links, never a junk entry.
- **Quick search stays absent** (no suggest endpoint; unchanged since #145/#655) and the card
  poster is a `data-src` lazyload attr the harness cannot attribute (provider's jsoup
  `a.thumb > img` handles it) — both recorded in FINDINGS, neither drift.
- **Actors are not exposed** on video pages (only in the description meta), so `actors` stays
  unset; `tags`/`year`/`duration`/`plot`/`recommendations` are populated and present on every
  verify sample.
- **Quality:** shape-A files expose no ladder (a single mp4), the KVS player only
  `_hq.mp4`/`_sd.mp4`, and `loadLinks` keeps `Qualities.Unknown` for those — the youporn hub is
  the one surface with a real 480/240 ladder and it is passed through. No invented resolution.

## Verification (delivery gate)

- `./gradlew ixiporn:test` — **green** (187 tests, 0 failures, incl. the new `IxiPornParseTest`
  cases, `KvsVideofileParseTest` 8, `YoupornParseTest` 6, `HostRegistryTest` with the new rows).
- `./gradlew ixiporn:make` — clean `ixiporn.cs3` at **version 39**.
- `./gradlew EPorner:test` — green (shared-splice control for the new shared modules).
- `.pi/skills/audit-providers/scripts/check-findings.sh` — **PASS**.
- `.pi/skills/verify-provider/scripts/verify.sh` — **PASS** (`--header 'Referer:
  https://ixiporn.live/'`, search 2 pages 30+30, home 2 pages 30+30, 4 video pages, streams
  4/4 **206 video/mp4**, related 12/page, tags/year/duration on all 4, LoadResponse complete).
  Harness NOTEs recorded above: quick search absent, actors unexposed, poster lazyload, one
  poster-variant hit (`Khaat.6-600x338.jpg` vs `Khaat.6.jpg` — same artwork, #680 rule).
- In-app playback stays the maintainer's at merge.

## Registry write

The provider entry and one `last_runs` entry (with `false_positives: []`) are posted as a
comment on #1445 beginning `<!-- findings-registry-entry -->`, not in this diff (ADR-0013).
