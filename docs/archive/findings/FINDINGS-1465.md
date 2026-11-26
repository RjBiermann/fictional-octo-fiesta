# FINDINGS-1465 — PornHub (www.pornhub.com) video-source pass

Run: 2026-11-26. Issue **#1465** — *S27 — PornHub video sources*, story 27 of 27 of the
video-sources audit (#1354), so this pass is the last one and measures the provider against the
fleet-wide baseline the previous 26 stories established. Scope is the **video-source leg
(D2/D3/D4)**: one row per listing type, ≥2 video pages per type sampled at both catalogue ends,
`loadLinks` resolved end-to-end. Listing correctness was re-probed only as far as enumeration
needed it (D5) — no `drift_confirmed` history exists for this provider, so nothing was inherited.

**Verdict: `ok-drift` — one confirmed condition, fixed in this run (v17 → v18).**
`drift_confirmed` 0 → **1** (first write for this provider); `last_deep` none → **2026-11-26**.
Instrument tier: **plain-curl** (desktop Chrome UA, cookie-less; the site never challenged this
egress). `escalation_tier: available` — `curl_cffi` 0.17.0 was installed mid-run
(`pip install --user curl_cffi`) after #1434's container gap, and it works; no row needed it.
No `Blocked` recorded, so nothing is downgraded. Egress: **3.42.157.123, Boardman/Oregon (US),
AS16509 Amazon** — the geo/SFW listing gate (#1416) did **not** fire here (`/video` answered 200
with `ul#videoCategory`), see *Geo surface* below.

The single confirmed condition is on the quality surface, first recorded as an observation in
`docs/archive/providers/PornHub/FINDINGS.md` §Stream sources (S3/S4 of #1490/#1406) and now
measured on a third, independent sample: some watch pages exchange the entry's `height` and
`width` fields, so the provider's quality ints were the 1080p **width** (1920) instead of the
1080p **height**. The fix is structural — read the ladder marker the entry already carries
(`quality: "1080"`, backed by the URL's `/1080P_4000K_` token) instead of the field that swaps.

---

## Phase 0 — calibration (ran before touching the site)

| canary | expected | observed (2026-11-26, egress above) | reading |
|---|---|---|---|
| `film1k-challenge-pair` | plain 403 / TLS 200 | **plain-curl 403** (5 244 B challenge) / **TLS 200** / browser UA 200 | **match** (both tiers) |
| `eporner-healthy` | plain 200 | **plain-curl 200** (315 518 B) | match |
| `pandamovies-redirect-origin` | plain 302 / TLS 200 @ .org | plain **302** → `https://pandamovies.org/` (match) / TLS **403** at the .org target | expectation-stale on the TLS half — see below |

```
$ curl -s -o /dev/null -w 'HTTP %{http_code} %{size_download}B\n' https://www.film1k.com/
HTTP 403 5244B
$ bash .pi/skills/site-probe/scripts/impersonate.sh https://www.film1k.com/
HTTP 200 https://www.film1k.com/
$ curl -s -o /dev/null -w 'HTTP %{http_code} %{size_download}B\n' https://www.eporner.com/
HTTP 200 315518B
$ curl -s -o /dev/null -w 'HTTP %{http_code} %{size_download}B -> %{redirect_url}\n' https://pandamovies.pw/
HTTP 302 143B -> https://pandamovies.org/
$ bash .pi/skills/site-probe/scripts/impersonate.sh https://pandamovies.pw/
HTTP 403 https://pandamovies.org/
```

**#1435 (film1k expectation-stale) does not reproduce today.** The plain tier challenges again
(403, the recorded expectation) while the browser UA and the TLS tier answer 200 — the
UA-conditional datacenter challenge the S25/S26 runs also measured. Per the issue text the
`environment-degraded` branch was **not** taken; the live pair is recorded here and #1435 stays
open for the maintainer's expectation decision.

**`pandamovies-redirect-origin`, TLS half — proposed expectation update (maintainer confirms).**
The recorded expectation is `plain 302 / TLS 200 @ pandamovies.org`; today the TLS tier follows
the redirect and gets **403** at `pandamovies.org` (the plain tier still 302s, which is what the
canary's stated purpose — *"Validates redirect-following + reachable-origin detection"* — reads).
`on_mismatch: expectation-stale`, so no degraded branch: the proposal is to record the pair as
`plain 302 / TLS 403 @ .org` (or to drop the TLS half, since a CF challenge at the .org target is
not a redirect-following signal). Filed nowhere — the maintainer confirms canary changes in
review, and `audits/canaries.json` is untouched in this PR.

**Escalation ladder.** All three rungs exist this run:

```
plain-curl                       → curl 8.18.0 (system)
tls-impersonated (#1434)         → curl_cffi 0.17.0, impersonate.sh works (installed mid-run)
browser diagnosis                → not needed (no challenge on any PornHub surface)
```

No PornHub surface answered a challenge from this egress on either tier, so the ladder was never
walked for this provider.

---

## Listing types (D3) — one row per type

PornHub's own sort menu (`div#headerSortingMenu`, `/video`) offers **Featured Recently** (`/video`),
**Most Viewed** (`?o=mv`), **Top Rated** (`?o=tr`), **Hottest** (`?o=ht`), **Longest** (`?o=lg`)
and **Newest** (`?o=cm`) — there is **no ascending / oldest order**, so the `old` type is
`absent` by the site's own taxonomy, not by a parse failure (`/video?o=da` and `/video?o=old` both
answer HTTP 404 `Page Not Found`). Genres exist as `/categories/<slug>` landing pages (the SFW
category route the site advertises on every watch page: `/categories`, `/categories/teen`,
`/categories/hentai`, `/categories/pornstar`), and the **tags** a watch page shows are search
links (`/video/search?search=<tag>`), so the tag listing type is the search surface itself.

| type | sample URL (page 1 / page 2 / deep) | instrument tier | stream host | quality | verdict |
|---|---|---|---|---|---|
| `new`/`latest` | `/video?o=cm` · `&page=2` · `&page=342`, `&page=427` | plain-curl | `ev-h.phncdn.com` (HLS master) | 240/480/720/1080 | **pass** |
| `old` | — (`/video?o=da` 404, `/video?o=old` 404) | plain-curl | — | — | **absent** (no ascending sort on the site) |
| `popular` | `/video?o=mv` · `&page=2` · `&page=427`; `/video?o=tr` · `&page=2` · `&page=59`; `/video?o=ht`, `/video?o=lg` | plain-curl | `ev-h.phncdn.com` / `ev.phncdn.com` | 240/480/720/1080 | **pass** |
| `genres` | `/categories/teen` · `?page=2` · `?page=50`; `/categories/hentai` | plain-curl | `ev-h.phncdn.com` | 240/480/720/1080 | **pass** |
| `categories` | `/video?c=10` (*Bondage*) · `&page=2` · `&page=100` | plain-curl | `ev-h.phncdn.com` / `ev.phncdn.com` | 240/480 | **pass** |
| `tags` | `/video/search?search=teen` · `&page=2` · `&page=10` | plain-curl | `ev-h.phncdn.com` / `ev.phncdn.com` | 240/480/720/1080 | **pass** |

### Enumerability transcript

Card counts are **scoped** to the listing container the provider parses (`ul#videoCategory` for
every `/video` row, `/video?c=`, `/categories/<slug>`; `ul#videoSearchResult` for the tag/search
surface) — the header "Hottest" dropdown and `ul#bottomVideos` repeat the same card markup on
every page, so a bare `li.pcVideoListItem` count is not a card count.

```
type        url                                     http  bytes    cards  distinct  rel=next  shared with page 1
new         /video?o=cm                            200   1459209  31     31        yes       —
new         /video?o=cm&page=2                     200   1546829  43     43        yes        0
new         /video?o=cm&page=342                  200   1481272  35     35        yes        0 (vs p1)
new         /video?o=cm&page=427                  200   1537956  43     43        yes
new         /video?o=cm&page=428                  200   1539547  43     43        yes        (catalogue still growing)
popular     /video?o=mv                           200   1460584  31     31        yes
popular     /video?o=mv&page=2                     200   1545645  43     43        yes        0
popular     /video?o=mv&page=427                  200   1542139  43     43        yes        0 (vs p1)
popular     /video?o=tr                           200   1459280  31     31        yes
popular     /video?o=tr&page=2                     200   1541730  43     43        yes        0
popular     /video?o=tr&page=59                   200   1548050  43     43        yes        0 (vs p1)
popular     /video?o=tr&page=60                   200   1550192  43     43        yes
popular     /video?o=ht                           200   1463224  31     31        yes
popular     /video?o=lg                           200   1463754  32     32        yes
old         /video?o=da                            404   997002    0      0        no        <title>Page Not Found</title>
old         /video?o=old                           404   999636    0      0        no        <title>Page Not Found</title>
genres      /categories/teen                       200   1493408  30     30        yes
genres      /categories/teen?page=2                200   1581284  42     42        yes        0
genres      /categories/teen?page=50               200   1571828  41     41        yes        0 (vs p1)
genres      /categories/teen?page=2000             404  1001758    0      0        no
genres      /categories/hentai                     200   1460384  30     30        yes
categories  /video?c=10                            200   1466186  30     30        yes
categories  /video?c=10&page=2                     200   1549610  42     42        yes        0
categories  /video?c=10&page=100                   200   1521441  40     40        yes        0 (vs p1)
categories  /video?c=10&page=2000                  404   998557    0      0        no
tags        /video/search?search=teen              200   1380360  31     31        yes
tags        /video/search?search=teen&page=2       200   1459319  43     43        yes        0
tags        /video/search?search=teen&page=10      200   1458730  43     43        yes        0 (vs p1)
```

Every sampled type paginates on the site's own `<link rel="next">` — the seam
`Parse.homeHasNext`/`Parse.searchHasNext` reads — page 2 is always fresh (**0 shared viewkeys**),
a deep page is fresh too, and past the last page (`?page=2000`) the route answers 404 with no
listing block, so the rows cannot walk into a 404 or re-serve page 1. The `/video` home row was
re-confirmed at the same time (`/video` 200, 31 cards, `/video?page=2` 200, 43 cards, **0
shared**).

### Geo surface (row for closed #1416)

`/video` answered **200 with `ul#videoCategory`** from this egress (Oregon, US, AWS) — the
`302 -> /` + `ph-redirect: 1041` + `isSfw: 1` gate recorded in
`docs/archive/providers/PornHub/FINDINGS.md` §Risks did **not** fire. No `Set-Cookie`-visible gate
marker, `isSfw` empty. So the #1416 regression row holds as written: the gate is egress-decided,
the four shipped row URLs and `Parse.cards` are correct where it is absent, and
`vpnStatus = VPNStatus.MightBeNeeded` remains the provider's only signal. Nothing is `Blocked`
this run.

---

## Video pages — 14 samples, ≥2 per listing type, both catalogue ends

One row per sampled watch page (`load()` surface: `flashvars` present, `h1.title span.inlineFree`,
`og:*`, JSON-LD `VideoObject`, categories/tags wrappers, related block).

```
viewkey         listing (sample position)      http  title (truncated)                                    dur         up    cats tags act rel self
6a9c889f879a6   new o=cm p1                    200   Slutty Stripper Shana Grimoire Redhead              2:30        2026  5    6    0   35  no
6b0264f58ef77   new o=cm p342 (deep)           200   Would you stay over if I asked nicely?              0:16        2026  5    9    0   35  no
6aee3ff58a230   popular o=mv p1                 200   Milf with Big ASS Touched & Fucked by a Stranger…   17:03       2026  11   16   1   35  no
65fb2ca2a358d   popular o=mv p427 (deep)        200   Eliza Ibarra Seduces & Uses her Tight Pussy…        37:02       2024  10   13   2   35  no
691ffed8dfcaa   popular o=tr p1                 200   Asian teen student gets spanked and railed…         4:11        2025  10   14   1   35  no
6acd1178754a9   popular o=tr p59 (deep)         200   Hedera Helix Reads Hot Porn To Have A Sexy Orgasm   9:00        2026  8    16   0   35  no
6a9ea9716042c   popular o=ht p1                 200   Friends Hot Mom Wants To See My Dick - Jesse Pony   9:34        2026  10   16   2   35  no
68e53098bcb06   popular o=lg p1                 200   OMG! STEPSISTER & STEPBROTHER HAVING A FUCKING…     6:48:01     2025  11   16   0   35  no
698d8b07bdc88   categories c=10 p1             200   Fit blonde Naughty Tinkerbell given deep…           15:04       2026  8    16   1   35  no
ph5d25d6de1d65a categories c=10 p100 (deep)    200   Femdom Mistress Lusinda facesitting…                8:55        2019  10   16   0   35  no
6add862f486ac   genres /categories/teen p1     200   ACCIDENTAL GANGBANG - Five Teen Scouts…             20:55       2026  5    8    2   35  no
6a7b8a7c0221b   genres /categories/teen p50    200   Mature stepmom and her stepdaughter have FFM…       23:27       2026  10   16   0   35  no
6ac144ca27c0d   tags search=teen p1            200   First Weiner for Lesbian Step Daughter - Myra…      9:53        2026  12   16   2   35  no
68bd5b7bda226   tags search=teen p10 (deep)    200   RIM4K. Threesome with a horny big-titted virgin      15:20       2025  11   16   1   35  no

distinct titles 14/14; distinct viewkeys 14; distinct poster asset paths 14/14;
related block 35 cards on every page, 35 distinct viewkeys, own viewkey never present
RESULT: PASS
```

`act` is the number of `div.pornstarsWrapper a.pstar-list-btn` anchors: **7 of 14** pages render
the wrapper (1–2 credited models); the other 7 render **no wrapper at all** — the documented
"no-credit upload" shape (`video-page-2.html`), not a selector drift. Confirmable from the
markup: the pages with the wrapper carry `Pornstars` labels with avatar+name (e.g.
`Bruce Venture`, `Eliza Ibarra` on `65fb2ca2a358d`), the others have no such row.

`/video/get_media` and `flashvars` both expose `duration`/`uploadDate` through the JSON-LD
`VideoObject`, so `duration`/`year` were asserted from there (verify.sh cannot take a DOM
selector for them — unchanged plateau).

---

## Stream resolution — `loadLinks` end-to-end

**No embed host, nothing for `HostRegistry`.** PornHub's player is first-party: the watch page's
`var flashvars_<id>` blob carries the HLS masters on `*.phncdn.com` and the provider emits them
directly (`ExtractorLinkType.M3U8`), with the `/video/get_media` mp4 array as fallback. There is
no iframe embed and no third-party host, so `shared/`'s `HostRegistry` is correctly uninvolved —
`grep -rn "phncdn\|pornhub" shared/src/main/kotlin/` returns nothing, and none is owed.

Per-leg probe on all 14 samples (page fetched fresh per video; every emitted HLS leg probed):

```
6a9c889f879a6  202609/05 61357235  legs [1080,240,480,720] -> all 200 application/vnd.apple.mpegurl
6b0264f58ef77  202611/22 70100685  legs [1920,426,852,1280] as reported; real ladder [1080,240,480,720] -> all 200
6aee3ff58a230  202611/06 68202355  legs [1080,240,480,720] -> all 200
65fb2ca2a358d  202403/20 449856371 legs [1080,240,480,720] -> all 200
691ffed8dfcaa  202511/21 30412285  legs [1920,426,854,1280] as reported; real ladder [1080,240,480,720] -> all 200
6acd1178754a9  202610/12 65563925  legs [1080,240,480,720] -> all 200
6a9ea9716042c  202609/07 61522795  legs [1080,240,480,720] -> all 200
68e53098bcb06  202510/07 25278795  legs [240,480,720] -> all 200
698d8b07bdc88  202602/12 39124035  legs [240,480] -> all 200
ph5d25d6de1d65a 201907/10 234452111 legs [240,480,720] -> all 200
6add862f486ac  202610/25 66922415  legs [1080,240,480,720] -> all 200
6a7b8a7c0221b  202608/11 58679735  legs [240,480,720] -> all 200
6ac144ca27c0d  202610/03 64564435  legs [1080,240,480,720] -> all 200
68bd5b7bda226  202509/07 21757235  legs [1080,240,480,720] -> all 200

51 HLS legs probed, 51 distinct paths, all 200 application/vnd.apple.mpegurl
RESULT: PASS
```

### CDN signing-shape lottery — still live, and the fallback still rescues it

The per-page assignment flips between **style A** (`ev-h.phncdn.com`, `validfrom…hash`) and
**style B** (`hv-h.phncdn.com`, `h…e…f=1`) → 410 `text/plain`. Captured on one video this run:

```
6a9ea9716042c  attempt 1..5  host=hv-h.phncdn.com  legs 1080/240/480/720 -> 410 text/plain (all four)
6a9ea9716042c  attempt 6..7  host=ev-h.phncdn.com  legs 1080/240/480/720 -> 200 application/vnd.apple.mpegurl
6a9ea9716042c  attempt 8     host=hv-h.phncdn.com  legs 1080/240/480/720 -> 410 text/plain
```

Page re-fetch counts to land a live shape across the samples were 1/1/2/3/1/3/1/1/1/4/6/2/1/2 —
so 6 of 14 pages started on a dead shape. `/video/get_media` (the documented fallback) answers
real bytes **when the page fetch landed on a dead shape**, with the watch page's own cookies:

```
6aee3ff58a230  page fetch #1: hv-h, all 4 HLS legs 410
               GET /video/get_media (watch-page cookies) ->
                 [(240, …/240P_1000K_68202355.mp4), (480, …), (720, …), (1080, …)]
               range 0-1023 on each -> 206 video/mp4 1024 B (all four)
```

The provider's per-leg `isPlayable` probe is what keeps a mixed page from losing live legs
(unchanged from the #1419 repair), and a fully dead page falls through to the mp4 array — proven
above, not inferred. `--stream-url` verification still resolves the *highest* leg of a fresh
fetch, which is why the S27 verify run re-fetched until style A (below).

### Quality — the confirmed condition (fixed in this run)

The `height` field is **not** the ladder height on every page. Measured here on 2 of 14 pages,
and previously on 2 of 5 (S3) and 1 of 5 (S4) pages:

```
6b0264f58ef77   .../1080P_4000K_70100685.mp4/master.m3u8   height=1920  width=1080  quality="1080"
                .../240P_1000K_70100685.mp4/master.m3u8    height=426   width=240   quality="240"
                .../480P_2000K_70100685.mp4/master.m3u8    height=852   width=480   quality="480"
                .../720P_4000K_70100685.mp4/master.m3u8    height=1280  width=720   quality="720"
691ffed8dfcaa   .../1080P_4000K_30412285.mp4/master.m3u8   height=1920  width=1080  quality="1080"
                .../480P_2000K_30412285.mp4/master.m3u8    height=854   width=480   quality="480"
(height-convention page, for contrast)
6a9c889f879a6   .../720P_4000K_61357235.mp4/master.m3u8    height=720   width=1280  quality="720"
```

`height` and `width` are **exchanged** on such pages (`height: 1920, width: 1080`), so
`Parse.qualityOf(height)` labelled a 1080p stream **1920p** and its 240p leg **426p** — a wrong
player label on every such page (the ordering within the page still sorted correctly, which is
why this read as a cosmetic observation in S3/S4). Two markers survive the swap and agree on
**all 51 legs** sampled this run: the entry's own `quality` label (`"1080"`) and the URL's
rendition token (`/1080P_4000K_`). `/video/get_media` carries both too
(`height: 426, width: 240, quality: "240"`), so the fallback inherited the same defect.

**Fix (v17 → v18, TDD red → green).** `Parse.ladderHeight(height, quality, url)` resolves, in
order: the entry's `quality` label → the URL's `/1080P_4000K_` token → the `height` field (only
when neither marker exists, so "no evidence" stays "no guess" and an absent height still maps to
`Qualities.Unknown`). `hlsStreams` and `getMediaStreams` run every leg through it, so
`Parse.qualityOf`/`Parse.linkName` see a real ladder height. New fixtures are the verbatim shape
from this run: `flashvars-width-convention.json` (the 5-entry `mediaDefinitions` of
`6b0264f58ef77`) and `get-media-widths.json` (its `/video/get_media` body).

Red first — with `ladderHeight` stubbed to return `height`, exactly the four new behavioural
assertions failed and the "no marker → keep the height" one passed:

```
$ ./gradlew PornHub:test
PornHubLoadParseTest > the url rendition token backstops a missing quality label FAILED
PornHubLoadParseTest > a swapped width-in-height page resolves to the real ladder heights FAILED
PornHubLoadParseTest > a quality label outranks the url token and the height field FAILED
PornHubLoadParseTest > a swapped get_media body resolves to the real ladder heights FAILED
250 tests completed, 4 failed
```

Green after the implementation — **250 tests, 0 failures** (66 PornHub + 184 spliced `shared/`),
and the width-convention fixture now emits `["PornHub 240p", "PornHub 480p", "PornHub 720p",
"PornHub 1080p"]`.

---

## `verify.sh` (live)

Five videos from five different listings, the tag/search page pair, and two home rows, with the
highest live HLS leg per video as the position-matched `--stream-url` (flashvars escapes slashes,
so the page-level m3u8 regex cannot see the emitted links — unchanged plateau):

```
.pi/skills/verify-provider/scripts/verify.sh \
  --search-url 'https://www.pornhub.com/video/search?search=teen' \
  --search-url 'https://www.pornhub.com/video/search?search=teen&page=2' \
  --search-selector 'ul#videoSearchResult li.pcVideoListItem' --search-title-selector 'span.title a' \
  --home-url 'https://www.pornhub.com/video' --home-url 'https://www.pornhub.com/video?page=2' \
  --home-url 'https://www.pornhub.com/video?o=mv' --home-url 'https://www.pornhub.com/video?o=mv&page=2' \
  --home-selector 'ul#videoCategory li.pcVideoListItem' \
  --video-url … (6a9c889f879a6, 6aee3ff58a230, 691ffed8dfcaa, 698d8b07bdc88, 6ac144ca27c0d) \
  --stream-selector 'div#player' --stream-url … (highest HLS per video) \
  --video-title-selector 'h1.title span.inlineFree' \
  --video-poster-selector 'meta[property=og:image]' \
  --video-plot-selector 'meta[property=og:description]' \
  --video-tags-selector 'div.tagsWrapper a[data-label=tag]' \
  --video-actors-selector 'div.pornstarsWrapper a.pstar-list-btn' \
  --provider-src PornHub --load-response 'recommendations,tags,plot,duration,year,actors'
```

What the run proved, and the documented instrument plateau:

```
── check 1: search pages (2)      → 200 / 200; global matches 38 / 50
   FAIL duplicate search cards    ← url_path strips ?viewkey= (plateau)
── check 1a: homepage pages (4)   → 200 ×4; global matches 45 / 57 on every pair
   FAIL duplicate home cards      ← same plateau
── check 1b: quick search (0)     → NOTE: no distinct endpoint (§Quick search), hasQuickSearch=false
── check 2: video pages (5)       → 200 ×5, div#player 2-3 matches each
   streams 5/5 → 206 application/vnd.apple.mpegurl (re-resolved and fetched the same minute)
   field 'tags': present on all 5
   FAIL actors 4/5               ← 698d8b07bdc88 credits no models (site content, video-page-2 shape)
   NOTE year/duration            ← script-embedded (JSON-LD), no DOM selector exists
── check 3: cross-video identity  FAIL — all five collapse to /view_video.php (plateau)
── check 5: search ↔ load        FAIL — whole search page compared to each load page (plateau)
── check 6: LoadResponse completeness → recommendations 1, tags 2, plot 3, duration 1, year 2, actors 3
RESULT: FAIL
```

Every `FAIL` except the actors note is the **single documented root cause** (`url_path` drops the
`?viewkey=` query, so every card/video normalises to `/view_video.php`), substituted here with
the same mechanics keyed on `viewkey` (precedent: §Verification (S3)/(S4) of the provider FINDINGS,
`PerverZija`, `Javmost`):

```
search p1 31 / p2 43 cards, all distinct within a page, 0 shared between pages
home p1 31 / p2 43, 0 shared (row URL /video);  o=mv p1 31 / p2 43, 0 shared
5 sampled videos: 5 distinct viewkeys, 5 distinct titles, 5 distinct poster asset paths
search card 6ac144ca27c0d title == load h1 ('First Weiner for Lesbian Step Daughter - …')  equal
   poster asset core /videos/202610/03/64564435/original_64564435.mp4 equal
   (card shard pix-fl rs:fit:320:180 vs load pix-egi rs:fit:640:360 — differ by design)
related block: 35 cards, 35 distinct viewkeys, own viewkey absent, on all 14 sampled pages
RESULT: PASS (substituted)
```

`--related-selector` was not passed, for the same plateau reason (its self/duplicate checks read
`/view_video.php`).

---

## Lifecycle — dedup before filing

`gh issue list --search "PornHub" --state all` → no open standing issue for this condition
(#1468 poster/thumbnail, #1472 poster Referer, #1406/#1408/#1409/#1410 new-site chain, #1490/#1495/
#1496 pagination, #1524/#1536/#1537 search pagination, #1574/#1576/#1577 query normalisation,
#1416 geo-gate docs — all closed; #1504 is the fleet-wide SonarCloud false positive). The quality
condition was already recorded as an *observation* in the provider FINDINGS and had no issue, so
this run **fixed it in place** per D1 and files **no** new issue (`findings_filed: 0`). No
`suspected_excluded` recorded for this provider (first write), `false_positives: []`.

---

## Gates

```
.pi/skills/audit-providers/scripts/check-findings.sh --block <registry payload>   → PASS
./gradlew bootstrapCloudstream (sha-pinned classes.jar)                           → BUILD SUCCESSFUL
./gradlew PornHub:test                                                            → BUILD SUCCESSFUL
                                                                                    250 tests, 0 failures
                                                                                    (66 PornHub + 184 shared)
./gradlew PornHub:make            → BUILD SUCCESSFUL, PornHub/build/PornHub.cs3 (175,181 B, v18)
```

`parseVideoCards`, `load()`, `search()` and the home rows are untouched by the fix — the only
behavioural delta is the `quality` int (and therefore the `PornHub <n>p` label) on pages whose
`height` field is swapped, which is now the rung the entry itself names.

---

## Observations carried forward (no new finding)

- `LoadResponse.score` is still exposed (`span.votesUp[data-rating]`) but not populated; channel/
  uploader exists in JSON-LD `VideoObject.creator` and has no `LoadResponse` field for a movie.
  Both already recorded in the provider FINDINGS §Video pages as documented non-populations.
- `plot` remains the generated `og:description` (`Watch <title> on Pornhub.com, …`) — the site
  exposes no owner-written synopsis.
- Mobile UA still gets the thinner layout (no `ul#videoCategory`, no `categoriesWrapper`); the app's
  UA gets desktop markup. Unchanged, recorded in the provider FINDINGS.
- Geo/SFW listing gate: not fired on this egress (Oregon/US, AS16509); unaffected by this run.
- `stop at past-the-end` for the home rows was re-confirmed only through `?page=2000` on the
  category/genre routes (404, no listing block) and `?page=9999` history in the provider FINDINGS;
  the `/video` catalogue itself still serves page 428.

---

## Registry payload (posted as a comment on #1465, never in the diff — ADR-0013)

```json
{
  "providers": {
    "PornHub": {
      "site": "www.pornhub.com",
      "verdict": "ok-drift",
      "reason": "S27 video-source pass 2026-11-26 (first audit of this provider): one confirmed condition, fixed in this run (v17 -> v18). Some watch pages exchange the entry's height/width fields (height: 1920, width: 1080 for the 1080P_4000K rendition; 2 of 14 sampled pages, also on 2 of 5 S3 and 1 of 5 S4 pages), so Parse.qualityOf labelled a 1080p stream 1920p and its 240p leg 426p on those pages. Fixed structurally: Parse.ladderHeight resolves the ladder from the entry's own quality label ('1080') then the CDN URL's /1080P_4000K_ token then the height field, so hlsStreams/getMediaStreams hand the player real ladder heights; red -> green in PornHubLoadParseTest against flashvars-width-convention.json / get-media-widths.json. Listing types: new /video?o=cm, old absent (/video?o=da and ?o=old 404 - the sort menu has no ascending order), popular /video?o=mv + ?o=tr (+ ?o=ht, ?o=lg), genres /categories/<slug>, categories /video?c=<id>, tags /video/search?search=<tag> (watch-page tags are search links). 14 sampled video pages (2+ per type, both catalogue ends) all 200 with flashvars, 51/51 HLS legs 200 application/vnd.apple.mpegurl, style-B 410 lottery still live (6 of 14 pages started dead -> per-leg probe + /video/get_media fallback 206 video/mp4 x4), no embed host so HostRegistry is correctly uninvolved. verify.sh FAIL is the url_path?viewkey plateau plus one no-credit actor page; stream/home/search surfaces pass and were substituted viewkey-keyed. Geo/SFW listing gate did not fire on this egress (Oregon US AS16509). No standing issue, no finding filed.",
      "tier": "plain-curl",
      "surfaces": {
        "home": "200 /video + /video?page=2 + o=mv|tr|ht|lg (31/43 cards, rel=next, 0 shared across pages)",
        "search": "200 /video/search?search=teen p1/p2/p10 (31/43 cards, 0 shared); no quick-search endpoint (hasQuickSearch=false)",
        "video": "200 14/14 with flashvars + h1 + og:* + JSON-LD; tags 14/14, actors wrapper 7/14 (rest credit nobody), related 35/35 distinct",
        "stream": "51/51 HLS legs 200 application/vnd.apple.mpegurl on ev-h.phncdn.com; style-B hv-h 410 -> /video/get_media 206 video/mp4 x4"
      },
      "issue": null,
      "standing_issue": null,
      "drift_confirmed": 1,
      "suspected_excluded": [],
      "chronic": false,
      "last_deep": "2026-11-26"
    }
  },
  "last_runs": [
    {
      "id": "run1465",
      "issue": 1465,
      "date": "2026-11-26",
      "kind": "video-source audit (S27 of #1354)",
      "verdict": "ok-drift",
      "tier": "plain-curl",
      "escalation_tier": "available",
      "canaries": "film1k-challenge-pair plain-curl 403 = recorded expectation, TLS 200 (curl_cffi 0.17.0 installed mid-run; #1435 live pair recorded, no environment-degraded branch); eporner-healthy plain-curl 200 match (315518 B); pandamovies-redirect-origin plain 302 -> pandamovies.org match, TLS 403 at the .org target (expectation-stale on that half, update proposed in the PR, canaries.json untouched); 0 Blocked",
      "findings_filed": 0,
      "standing_issue": null,
      "false_positives": []
    }
  ]
}
```
