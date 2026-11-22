# FINDINGS-1441 — Eroticmv video sources (S3 of #1354)

Run: 2026-11-22 (`05-implement`, story **S3** of parent spec #1354). Scope per the
spec: the **video-source leg** (D2) across the D3 listing types — `new`/`latest`,
`old`, `popular`, `genres`, `categories`, `tags` — ≥2 video pages per type, sampled at
both catalogue ends where the type is pageable, with `loadLinks` resolved end-to-end.
Listing correctness (pagination/card fields) is out of scope beyond enumeration.

Site: `https://eroticmv.com` — **plain-curl on every surface**, desktop UA
(`Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0`),
`Referer: https://eroticmv.com/` (what the provider emits). Runner IP country: **US**
(`134.138.45.123`, AS8075 Microsoft/Azure, Atlanta).

Registry at start: `last_deep` 2026-10-09, `drift_confirmed` 0, verdict `ok`, standing
issue #497. No standing Eroticmv issue matched the condition found below (searched open
and closed issues: #814/#646/#538/#559/#517/#321/#202/#175 all unrelated to stream quality).

## Phase 0 — calibration (ran before any provider probing)

| canary | expected | observed (plain-curl) | reading |
|---|---|---|---|
| `film1k-challenge-pair` | plain 403 / TLS 200 | plain **200** / TLS unavailable | **expectation-stale** — open #1435; per the spec this run records the live pair and does **not** take the `environment-degraded` branch for this canary |
| `eporner-healthy` | plain 200 | plain **200** | match — instrument healthy |
| `pandamovies-redirect-origin` | plain 302 / TLS 200 | plain **302 → https://pandamovies.org/** | match (redirect-following intact) |
| `eroticmv` (site itself) | — | plain **200** | probe tier available; no challenge |

```
$ UA="Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0"
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://www.film1k.com/
200
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.eporner.com/
200
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://pandamovies.pw/
302 https://pandamovies.org/
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://eroticmv.com/
200
$ .pi/skills/site-probe/scripts/impersonate.sh https://eroticmv.com/
impersonate.sh: curl_cffi missing for python3. Install it, then re-run:
  python3 -m pip install curl_cffi
```

- **#1434 (open)** — the TLS-impersonated tier is unusable in this container →
  `escalation_tier: unavailable`. Only `Blocked` verdicts downgrade to `Suspected`;
  **this run produced zero `Blocked` verdicts**, so nothing was downgraded. Every surface
  answered plain curl (the 2026-10-16 plain-curl Cloudflare challenge from FINDINGS-646 is
  gone on this probe date).
- **#1435 (open)** — film1k's stale expectation is recorded, not treated as degradation.
- No genuine `environment-degraded` mismatch → the instrument is healthy.

## Listing enumeration (the D3 types as the site actually serves them)

All pages fetched with the desktop UA; counts are `article.post-item` elements (the
provider's card selector), not raw `post-item` class occurrences. Pagination is the
WordPress `/page/N/` path (`/page/<last+1>/` → 404 ⇒ empty list, `hasNext=false`).

| type | first page | far end | evidence |
|---|---|---|---|
| `new` / `latest` | `/` — 24 cards, `rel=next` → `/page/2/` | `/page/73/` — 18 cards; `/page/74/` 404 | `/page/2/` 24 cards |
| `old` | — | — | **absent as a listing**: the sort menu offers Latest, Most commented, Most viewed, Most liked, Title, Highest Rated — no ascending/oldest order; `/old/` 404, `/oldest/` 404, `/?sort=oldest` returns the chronological home (same first cards, sort ignored). The catalogue tail `/page/73/` is sampled as the old end of `new` |
| `popular` | `/category/decades/?archive_query=view&alphabet_filter&paged=1` — 24 cards | `&paged=73` — 16 cards; `&paged=74` 404 | `&paged=2` 24 cards; p1∩p2 = 0 hrefs. The menu's MOST VIEWED/ MOST LIKED links carry `category_name`/`paged` params that 301-strip to this query; the view order is server-rendered |
| `genres` | `/category/genre/classic-erotica/` — 24 cards | `/category/genre/classic-erotica/page/22/` — 11 cards; p23 404 | `/category/genre/lesbian/` 24 → `/page/9/` 24 → `/page/10/` 404 |
| `categories` | `/category/country/usa/` — 24 cards; `/category/decades/1970s/` — 24 | `/category/country/usa/page/32/` — 9; p33 404 · `/category/decades/1970s/page/16/` — 22; p17 404 | `/category/country/usa/page/2/` 24 |
| `tags` | — | — | **absent**: `/tag/test/` 404; the site's taxonomy is WordPress categories only (`country/`, `genre/`, `decades/`). Search is `/?s=<q>`, not a tag surface. Recorded absent, never a finding |

Provider-row check (D5 trigger: none — `drift_confirmed` was 0 and enumeration did not
break): the provider's 76 `mainPage` rows are the Latest row plus the 75 facet keys. Every
sampled facet URL above is one of those keys; all answered 200 with cards.

```
/articles: / 24, /page/2 24, /page/73 18, /page/74 404
/popular:  paged=1 24, paged=2 24 (overlap 0), paged=73 16, paged=74 404
/genre:    classic-erotica 24, p22 11, p23 404; lesbian 24, page/9 24, page/10 404
/category: country/usa 24, p2 24, p32 9, p33 404; 1970s 24, p2 24, p16 22, p17 404
/old/, /oldest/ 404; /tag/test/ 404
```

## Evidence rows

`type | sample URL | instrument tier | stream host | quality | verdict`

Every row was resolved exactly the way `loadLinks` does it: watch page →
`og:video:url` (Shape A base64 token, or Shape B `?video_embed=` → embed page
`<source src>`) → HLS request → `#EXTM3U` body. All rows are plain-curl. `quality` is
the site's `og:video:width` × `og:video:height` (1280×720 on every sampled page).

| type | sample URL | tier | stream host | quality | verdict |
|---|---|---|---|---|---|
| new (p1) | `/golf-student-married-woman-with-no-panties-comfortable-2021/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| new (p1) | `/charli-1981/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| new (p2) | `/her-way-to-star-1972/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| new (p2) | `/one-naked-night-1965/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| new (tail p73) | `/emmanuelle-in-space-ii-a-world-of-desire-1994/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| new (tail p73) | `/an-affair-my-wifes-friend-2-2018/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| old | — | — | — | — | **absent** (no ascending sort; tail sampled as `new`) |
| popular (p1) | `/what-every-frenchwoman-wants-1986/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| popular (p1) | `/the-preachers-daughter-2016/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| popular (tail p73) | `/fuh-se-fantasy-s1-ep-7-9-2019/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| popular (tail p73) | `/lemon-popsicle-4-1982/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| genres (p1) | `/freudus-sexualis-1965/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| genres (p1) | `/keep-it-up-downstairs-1976/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| genres (p22) | `/images-in-a-convent-1979/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| genres (p22) | `/emmanuelle-7-1993/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| genres → lesbian (p9) | `/emotion-2011/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| genres → lesbian (p9) | `/society-affairs-1982/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| categories → country (p1) | `/bare-wench-the-final-chapter-2005/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| categories → country (p2) | `/alien-sex-files-3-alien-ecstasy-2009/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| categories → country (p32) | `/embrace-the-darkness-1999/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| categories → country (p32) | `/night-of-the-pumpkin-2010/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| categories → decades (p1) | `/winter-sports-1970/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| categories → decades (p16) | `/schoolgirl-report-part-1-what-parents-dont-think-is-possible-1970/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| categories → decades (p16) | `/malabimba-1979/` | plain-curl | `data.eroticmvs.com` | 720p | **pass** |
| tags | — | — | — | — | **absent** (no tag taxonomy; `/tag/<x>/` 404) |

### Queue sample — 124 distinct video pages, all resolve

Across both ends of every pageable type, **124 distinct video pages** were resolved
(128 samples, 4 repeats across listings): **96 Shape A** (`og:video:url` = base64 token)
and **32 Shape B** (`og:video:url` = same-site `?video_embed=<id>` page). For all 128:

- `og:video:width`/`og:video:height` present, always **1280×720**;
- the resolved HLS URL answered **200/206 `application/vnd.apple.mpegurl`** with an
  `#EXTM3U` body (no failures, no redirects to HTML);
- stream paths are per-video distinct (slug-derived), within a page and across videos.

Shape B transcript (embed page is the site itself — no external embed host to register):

```
$ curl -s -A "$UA" -e https://eroticmv.com/ https://eroticmv.com/knock-outs-1992/ | grep og:video
og:video:url" content="https://eroticmv.com/knock-outs-1992/?video_embed=35401"
og:video:width" content="1280"
og:video:height" content="720"
$ curl -s -A "$UA" -e https://eroticmv.com/ 'https://eroticmv.com/knock-outs-1992/?video_embed=35401' | grep '<source'
<source src="https://data.eroticmvs.com/dat1/knockouts1992/knockouts1992.m3u8" type="application/x-mpegURL"/>
$ curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -A "$UA" -e https://eroticmv.com/ \
    https://data.eroticmvs.com/dat1/knockouts1992/knockouts1992.m3u8
206 application/vnd.apple.mpegurl
```

Shape A transcript (base64 token decodes to the CDN URL):

```
$ curl -s -A "$UA" -e https://eroticmv.com/ https://eroticmv.com/her-way-to-star-1972/ | grep og:video
og:video:url" content="http://aHR0cHM6Ly9kYXRhLmVyb3RpY212cy5jb20vZGF0MS9oZXJ3YXl0b3N0YXIxOTcyL2hlcndheXRvc3RhcjE5NzIubTN1OA==.m3u8"
$ python3 -c 'import base64;print(base64.b64decode("aHR0cHM6Ly9kYXRhLmVyb3RpY212cy5jb20vZGF0MS9oZXJ3YXl0b3N0YXIxOTcyL2hlcndheXRvc3RhcjE5NzIubTN1OA==").decode())'
https://data.eroticmvs.com/dat1/herwaytostar1972/herwaytostar1972.m3u8
$ curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -A "$UA" -e https://eroticmv.com/ \
    https://data.eroticmvs.com/dat1/herwaytostar1972/herwaytostar1972.m3u8
206 application/vnd.apple.mpegurl
$ curl -s -A "$UA" https://data.eroticmvs.com/dat1/herwaytostar1972/herwaytostar1972.m3u8 | head -4
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:10
#EXT-X-MEDIA-SEQUENCE:0
```

The `.m3u8` is a **media playlist** (segments listed directly), not a master ladder —
matching the site's single 720p variant. Segments are carried under `.png` URLs and
served `content-type: image/png`, but the payload is MPEG-TS (`47 40 11 …` sync bytes;
`file` reports “MPEG transport stream data”). ExoPlayer sniffs HLS segment payloads, and
this shape has been the site's stream since the previous deep pass; recorded as a site
behaviour, not a condition. Streams serve with **and** without the site referer.

## Confirmed condition (fixed in this run)

**The watch page exposes the player height in `og:video:height` (1280×720 on all 128
samples); the provider emitted the `ExtractorLink` with no quality.** That is a
Data-completeness gap — CloudStream showed the stream as quality-unknown although the
page names 720p. (`Correctness / data-completeness`, not drift: the site did not change;
the provider never populated the field.)

Fix, TDD-first red → green:

- new pure `Eroticmv.parseQuality(html: String): Int?` — reads `og:video:height`,
  range-guards 144..4320 (same guard as PornHub's `qualityOf`), null on absent/0/garbage;
- `loadLinks` passes it into the `newExtractorLink` builder (`quality?.let { this.quality = it }`);
- fixture: existing `watch-tokyo-nights.html` (live capture, 1280×720) + inline 1080/2160
  and missing/0/9999 cases;
- `EroticmvQualityTest` 3/3 green; provider `version` **20 → 21**.

```
before: newExtractorLink(source=name, name=name, url=streamUrl, type=M3U8) { referer = mainUrl }
after:  ... { referer = mainUrl; quality?.let { this.quality = it } }   // 720 from og:video:height
```

No stream URL, selector, or listing behaviour changed.

## Verification (delivery gate)

- `./gradlew Eroticmv:test` — **green** (Eroticmv suites 18/18 incl. `EroticmvQualityTest` 3/3;
  shared suites green).
- `./gradlew Eroticmv:make` — clean `.cs3` at version 21.
- `.pi/skills/verify-provider/scripts/verify.sh` — **RESULT: PASS**:
  search `/?s=emotion` 200 / 24 cards; home `/` + `/page/2/` 24+24, no duplicate hrefs;
  5 varied video pages 200 with `meta[property=og:video:url]`; streams 5/5
  **206 `application/vnd.apple.mpegurl`**, distinct; related videos 23–29/page;
  tags present 5/5; actors present 5/5; search↔load title match (poster variant is the
  known `-165x248` crop-rendition artifact, same artwork); LoadResponse fields all
  populated in Kotlin. Quick search remains absent (`/search/suggest/` is the theme
  search page; `hasQuickSearch=false` stays correct). Year is parsed from
  `og:title "(YYYY)"` (no separate year element); duration is not exposed on watch pages.
- `.pi/skills/audit-providers/scripts/check-findings.sh` — **PASS**.

## Scope notes (not conditions)

- **`old` and `tags` are absent site-side** — recorded above, never findings (D3).
- **Popular has no provider row**: the site serves a server-rendered Most-viewed order,
  but adding rows is listing work (#632–#659 territory), not this video-source leg.
- **Home page size is 24 `article.post-item` cards** (the raw HTML carries each card's
  class twice — 48 `post-item` string occurrences — so counting class occurrences
  double-counts; the provider's selector is correct).
- The 2026-10-16 plain-curl Cloudflare challenge (FINDINGS-646) did not reproduce; this
  run's plain-curl access is healthy, so no `ok-suspected` carry-over is needed.
- No unsupported/DRM embed host appeared: Shape B embed pages are same-site, and the
  stream host is a direct HLS origin, not an extractor host.
