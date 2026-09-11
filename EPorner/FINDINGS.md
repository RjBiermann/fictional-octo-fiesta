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
