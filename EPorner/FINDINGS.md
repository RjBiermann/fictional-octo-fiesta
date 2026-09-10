# FINDINGS — eporner.com (2026-09 audit)

## Verdict: OK (no code change)

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
