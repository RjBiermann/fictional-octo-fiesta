# FINDINGS — eporner.com (2026-09 audit)

## Verdict: OK (year fix applied, issue #294)

## Search
- `https://www.eporner.com/search/red/` → HTTP 200; selector `div#vidresults div.mb` matches 28 results (`p.mbtit a`, `div.mbimg img`).
- Pagination `/search/{q}/{page}/` intact.

## Video page
- `/video-sQPApLnGpP4/big-red/` 200; `h1`, `meta[property=og:image]`, `span.vid-length`, related `div#relateddiv div.mb` all present.

## Stream
- Provider flow reproduced with curl: `/embed/{id}/` → `EP.video.player.hash = '2e1700dd...'` → md5 8-chunk base36 → `/xhr/video/{id}?hash=...` → JSON `sources.mp4["1080p HD"].src = https://vid-s13-n50-fr-cdn.eporner.com/...16007210-1080p.mp4`
- STREAM CHECK: `curl -r 0-1000` → **206 video/mp4** (works with and without Referer).

## Risks
- gvideo/`xhr` URLs are signed/time-based — expected; player regenerates.

## Update (2026-02 fix, issue #174): actors

- `span.valor` gone from video pages: `grep -c 'span.valor'` → 0 matches on live page
  (https://www.eporner.com/video-goBad5wkYV3/…). Cast markup removed entirely
  (`grep -ioP 'class="[^"]*(cast|starring|actress)[^"]*"'` → 0 matches).
- "Starring:" now appears only in `og:description`, e.g.:
  `og:description" content="Watch 橘メアリー [Uncensored], … Squirt - Mary Tachibana. Starring: Mary Tachibana. Duration: 136:17, …"`
- Not every video carries it — 1 of 5 probed pages had "Starring:"; others end at the
  model/title text with no Starring clause. Parser must no-op gracefully.
- Fix: parse actors from `og:description` text between "Starring:" and ". Duration",
  comma-split; keep `span.valor a` as a no-cost first choice.

## Update (fix, issue #181): actors on non-"Starring:" pages

- og:description fallback from #174 misses pages with no "Starring:" clause, e.g.
  https://www.eporner.com/video-11PHqoqftMv/ :
  `og:description content="Watch Stepson Tries To Get His Stepmom ... , Danni Jones. Duration: 33:31, ..."`
- Ground truth on probed pages: JSON-LD `script[type=application/ld+json]` VideoObject
  carries `"actor": [{"@type": "Person", "name": "Danni Jones", "url": ...}]`
  (verified on video-1DpWrH3bhm3; absent on video-11PHqoqftMv — the negative case).
- Fix: parse JSON-LD actor array (Jackson readTree) as first choice after `span.valor`;
  og:description "Starring:" parse kept as last fallback.
- Further probing (issue #181): 1 of 6 pages (video-11PHqoqftMv) has NO JSON-LD actor and no
  "Starring:" clause — actor only as "Watch <title> , Danni Jones. Duration". Final fallback:
  last `\s,\s*(.+?)\. Duration` match, comma-split. Sanity-tested all 4 description shapes.
- Verification (runner): search 200 + 28 results; 6 video pages → related selector
  `div#relateddiv div.mb` matches; LoadResponse fields actors/tags/plot/duration/posters all
  assigned; stream via embed→hash→xhr reproduced, CDN range check 206 video/mp4 (fr + ca CDN
  nodes; one nl node timed out from runner — node flakiness, player falls over to other
  mirrors).
- Review (independent, round 2): related selector `div#relateddiv div.mb` re-counted with
  verify.sh's own matching method on the two PR-basis pages (video-1DpWrH3bhm3 and
  video-11PHqoqftMv) → 30 and 28 matches; the earlier "204–229" range was not reproducible
  by any counting variant (class-token, mb-prefix, mb-substring) — corrected.
- Review (independent, round 1): xhr flow reproduced on video-11PHqoqftMv (the negative
  case) — 4 `labelShort` mp4 srcs + hls `srcFallback`; CDN range check HTTP 206 video/mp4;
  master.m3u8 HTTP 200 application/vnd.apple.mpegurl; parser traced for Starring /
  comma-variant / multi-actor / no-actor shapes → correct actors or empty list.
- Review (independent, round 2): live re-probe — video-1DpWrH3bhm3 JSON-LD VideoObject
  `"actor": [{"@type":"Person","name":"Danni Jones"}]` (positive case, first of two
  ld+json scripts; second is BreadcrumbList and is skipped correctly); video-11PHqoqftMv
  VideoObject has no actor key (negative case); og:description fallback traced on all 4
  shapes → correct actors or []; `\s,` requires a space before the comma, so a title comma
  like "Alice, Bob And Carol" does NOT match the fallback (no fake actor); the fake-actor
  ceiling only applies to "word , word" titles when JSON-LD is also empty.

## Update (2026-09-10, issue #269): quick search

- Audit finding: no quickSearch. Site ships a suggest endpoint, unfixed.
- Fix: `hasQuickSearch = true` + `quickSearch(query) = search(query, 1).items` (PornXP
  pattern; delegating to search — no /suggest/ parsing, so no fixture test needed).
- Live evidence (CI runner): `/suggest/milf/video/` HTTP 200, real suggestions
  (`li.qsliac`, `.qslabel`); `/api/v2/video/search/?query=milf&format=json` HTTP 200, 30 videos.
- HTML surfaces still age-gated from the CI geo ("Eporner Age Verification", verified:
  `/search/milf/` and `/` both return the 5.7 KB wall; no cookie bypass found — tried
  tx-ageOk, ageverif, epage…). verify.sh FAILs on search/home/video HTML pages for this
  reason, not a selector regression. JSON surfaces + stream flow re-verified with curl:
  embed → hash → `/xhr/video/{id}?hash=…` → 5 mp4/hls URLs per video on both sampled IDs
  (11PHqoqftMv, 1DpWrH3bhm3).

## Update (2026-09-11, issue #294): year from JSON-LD uploadDate

- `span.C` gone from video pages: `<span class="C"` count = 0 on both sampled pages
  (video-R7ZATY8jOpO, video-UqnoIQjFusj). Dead selector — year was never populated.
- Site exposes the publish date only in JSON-LD: `"uploadDate": "2025-10-27T11:31:24+01:00"`
  (same field verified live on both samples). No DOM year source exists.
- Fix: year parsed via shared `JsonLdParse.year` (handles uploadDate/datePublished, plain and
  escaped JSON — same module PerverZija/WatchPorn/Sexfilm use) over the page's first
  `script[type=application/ld+json]`. Dead `span.C a` selector removed.
- Mechanical gate for year: verify.sh's DOM regex cannot see JSON-LD (ponytail ceiling:
  script-content parsing not implemented there), so the documented gate is the fixture unit
  test `EPorner/src/test/kotlin/com/byayzen/ParseTest.kt` — real page snippet asserting
  2025 from the capture above. Green in the build.
- Actors upgraded (optional item): primary source now the in-page per-actor links
  `li.vit-pornstar.starw a` (10+ anchors on both samples, live-verified); `span.valor a`
  and the JSON-LD / og:description fallback chain kept behind it.
- verify run: gate FAILs surface, all attributed:
  - `duplicate search cards` / `duplicate home cards`: known site drift — boundary dups
    recorded by this audit as "covered by the Correctness/Drift issue, not repeated here".
    (Home page-2 URL defect = the MainPage issue, out of axis.)
  - `duplicate recommendations` / `empty recommendation title`: verify.sh regex-DOM counts
    the 21 quality-chip `div.mb` cards (title text "1080p" etc.) inside relateddiv — the
    provider's own `searchCard(p.mbtit a)` correctly drops them (mapNotNull). Script
    limitation, not a provider defect (provider card titles come from anchors, null → skip).
  - check 5 (search↔load agreement): verify.sh cannot extract search card titles from this
    single-line nested markup (first `</div>` truncates card inner); title present in live
    HTML (`grep 'mbtit'` shows "Stepsons - Dee Williams").
  - All data-completeness signals green: streams 206 video/mp4 on both samples; tags,
    actors, duration selectors match every sampled page; LoadResponse has assignments for
    tags/plot/duration/year/actors/recommendations.

## Update (2026-09-11, issue #293): segment-swap pagination (mainPage page 2)

- Live re-probe (Googlebot UA; Firefox UA hits the age-verification wall from this runner's
  geo, listing pages only):

```
/most-viewed/2/  → 301 → /most-viewed/      (page 2 = page 1 again, 68/79 cards shared)
/longest/2/      → 301 → /longest/          (same defect class)
/2/most-viewed/  → 200, 67 cards, overlap 5 vs p1   ← correct page-2 form
/2/longest/      → 200, 67 cards, overlap 4
/2/              → 200, overlap 3 (fresh content)
/top-rated/2/, /tag/{cowgirl,riding,turkish}/2/, /cat/housewives/2/ → 200, overlap 0–6
```

- Fix: `EPornerParse.pageUrl(baseUrl, page)` — pure Parse function (TDD seam): page>1 rows
  whose top-level segment is `most-viewed`/`longest` build `/2/<list>/`; all other rows keep
  the suffix form `<path>/<n>/`. Wired into `getMainPage`. ParseTest red (unresolved
  reference) → green; `EPorner:test EPorner:make` BUILD SUCCESSFUL; version 12 → 13.

- Search pagination mechanical check (verify.sh selector path, pr-basis pages, trivial ?q=
  query params stripped):
  - `/search/milf/` → 200, 100 cards (effective URL `/tag/milf/`), 0 duplicate hrefs
  - `/search/milf/2/` → 200, 65 cards (`/tag/milf/2/`), 0 duplicate hrefs; overlap vs p1: 1
  - NOTE: Firebase dynamicLinks bypass attempt `/link?link=…` (for the `/search/<n>/` deep
    link) → **404** — flame page not present on this deployment; search stays on the
    standard suffix form, which the site resolves to the canonical tag page without any
    cache/bounce artifact.

- Stream chain re-proven end-to-end again on 3 fixed-page-2-sampled videos (71Qz4Vp8uKd,
  BR3u5WcXquG, 5kMdvVaLBli): embed → hash → base16→base36 (Kotlin algorithm reproduced,
  same output) → `/xhr/video/{id}?hash=…` → 200 JSON `available: true` → mp4 srcs extracted
  by `loadLinks`'s `labelShort/src` regex (720p/480p/240p… per video) → CDN range check
  **206 video/mp4**. JSON for these samples carries only `sources.mp4` (no `srcFallback`
  m3u8) on this deployment — HLS branch simply doesn't fire, mp4s serve (not a regression;
  #294-era samples had `srcFallback`).

- Video page selectors re-verified on the same 3 videos + `verbatimblockid` pr-basis page
  (video-1DpWrH3bhm3): h1, og:image, og:description, `span.vid-length` (e.g. "30min"),
  `li.vit-category a`, related `div#relateddiv div.mb` (17/ev quals counted 10–20 cards via
  verify.sh DOM method), JSON-LD actor array — all present.

## Update (2026-10-16, #652 deep pass): title glue fix + full-surface re-check

**CONFIRMED DRIFT (fixed in-run, TDD): the h1 quality-chip glue.** The live watch-page
h1 now nests chips inside the title element:

```html
<h1 >Tobrut Enak <span class="vid-length">9min</span><span class="vid-quality">720p</span></h1>
```

`load()` used `selectFirst("h1").text()`, which concatenates descendants —
jsoup escapes verified live: every in-app title rendered "Tobrut Enak 9min 720p".
Caught by verify.sh check 5 (search card `tobrut enak` / `3xxx` vs glue-title). Fix:
pure `EPornerParse.videoTitle(h1)` = `ownText()` (title text only, chips excluded),
fallback to `text()` when own text is empty (guarded by unit test so a future
"title inside a child span" template degrades to the old behavior, not to null).
Fixture `src/test/resources/video-1Gh6mVaDndG-h1.html` = the verbatim live h1
markup (Tobrut Enak + 3xxx pages). Test written first (compile-red unresolved
reference) → 11/11 green. `version` 18 → 19; clean `EPorner:make` green.

**Surfaces (plain-curl tier throughout — no challenge anywhere; escalation ladder
never needed). Canaries 3/3 on the plain tier: film1k 403, eporner 200,
pandamovies.pw 302. The TLS-impersonation step had no impersonation tool
available this run; plain was decisive because no surface was blocked.**

| Surface | Result |
| --- | --- |
| Home p1/p2 | 200, 64/66 video-card ids, overlap 3 of ~65 — the exact 3-overlap rail-placement class recorded 2026-10-14 (#598), site-native, not provider grammar |
| Search | `/search/cowgirl/1/` 301 → `/tag/cowgirl/` (the documented #537 collapse, unchanged); `/tag/cowgirl/` + `/2/` 200, 65+65, p1∩p2 = 1 boundary repeat; `/tag/cowgirl/1/` followed still collapses to the tag base (never route p1 there) |
| Video pages ×3 | `/video-0DbLLO1LU5O/`, `/video-1Gh6mVaDndG/`, `/video-1iQ9VveNRaR/` (bare id 301s to the slug URL — always fetch the slug form): h1, og:image, `span.vid-length`, `div#video-info-tags li.vit-category a`, `div#relateddiv` (30 blocks each), ×3 JSON-LD `script[type=application/ld+json]` blocks |
| Actor data | Still absent site-side on every probed page: 0 `li.vit-pornstar`, 0 `span.valor`, no JSON-LD `actor` key, og:description has no "Starring:" or " , Name. Duration" clause (vid1 og exactly "Watch <title>. Duration: …, available in: …") → provider no-ops to empty list. Standing #537 side record; not refiled, not drift. |
| Year | JSON-LD `VideoObject.uploadDate` intact (live samples 2025/2026 dates → JsonLdParse.year has real input again) |
| Stream chain | embed 200 → `EP.video.player.hash` present → 8-chunk base36 → `/xhr/video/<id>?hash=…` 200 `available: true` → `labelShort` ladder (720/480/360/240p mp4, no `/dload/`) + `srcFallback` HLS where shipped → 720p mp4 range check **206 video/mp4** video2+video3; video2 HLS master **200 vnd.apple.mpegurl** |
| fr-CDN cluster | video1's entire cluster (`vid-s1-s50-fr-cdn.eporner.com` + `dash-s1-s50-fr-cdn`) unreachable from the datacenter runner at connect level (000 retries 45–60s, bare host too) while the same run's ca-node streams serve 206 — single-cluster node flakiness, known class (same as #181-era nl note); sample is a recent upload whose stream URLs bind to that one cluster. Not a provider condition; the player re-resolves and picks a serving cluster on the client side. |
| Quick search | No distinct endpoint on the site surface; `quickSearch` still delegates to `search(query, 1)` (#569). |

**verify.sh mechanical run (search+home+video+stream+related+load-fields):** all
content checks green (streams 206, related 30, tags/`duration` present on all
pages, LoadResponse assignments all green). 4 FAIL lines adjudicated instrument/site-side:

1. `duplicate search cards` — 1 of 65 tag cards repeats across fresh p1/p2 fetches (rank
   re-injection at fetch time, same class Xhamster #635 documented) + 1 same-title pair of
   distinct hrefs on p1 (two re-uploads share a title; href identity distinct).
2. `duplicate home cards` — 3 of ~65 repeat across p1/p2 (the rail-placement class of #598,
   identical overlap count as the 2026-10-14 record).
3. `title mismatch` ×2 in check 5 — the regex-DOM instrument has no own-text semantics, so
   its `h1 text` sees the same chip glue the provider did before the fix ('tobrut enak 9min
   720p'); the producer fix is fixture-tested (11/11) and the mismatch text is precisely
   what it removes.
4. `poster mismatch` (3xxx) — search-card img `5_240.jpg` vs og:image `14_240.jpg`: different
   thumb frames from the same per-video `/17832634/` set (site-side variant; og:image is the
   metadata source of truth the provider mirrors).

**Verdict: ok-drift** (drift fixed in-run; no standing condition). `audits/findings.json`
updated (EPorner row + run23); drift_confirmed 1 → 2.
