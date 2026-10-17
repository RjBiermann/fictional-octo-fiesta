# FINDINGS-655 — ixiporn (ixiporn.org) deep pass + in-run shape-B guard fix

Run: attempt 2 (2026-10-17). Attempt 1 (https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/38994351736)
completed the full probe, filed finding #709, and reported identical results — its PR push died
rebasing `audits/findings.json` against the freshly-merged #652; this run re-delivers with a
conflict-free base (same probe conclusions, independently reproduced, plus the code-hazard fix
attempt 1 noted but did not apply).

## Verdict: `ok-drift`, tier `plain-curl`

Browser tier never reached; canaries matched 3/3 (film1k 403 plain, eporner 200, pandamovies 302).

## Deciding fact — redirect chain UNCHANGED

`ixiporn.org → 301 ixiporn.info → 301 ixiporn.live → 200` (`num_redirects=2`, effective URL
`https://ixiporn.live/`). Byte-for-byte the standing #468 condition (2026-09-24, re-confirmed
2026-10-14). Per the approved spec: **standing update, not a re-filed condition; no fix** for the
redirect; #468 stays a closed record; `drift_confirmed` stays 0.

## Full-surface re-check (all healthy on the `.live` origin, plain-curl)

| surface | result |
|---|---|
| home | 10 mainPage rows × p1/p2 = 20 fetches, 200 × 30 `div.video-block` cards each, 0 `data-post-id` overlap within every row (row1 latest p1∩p2 = 0 — #217 fix holds) |
| search | `/page/1?s=amateur` ×3 pages 200, 30 cards, fully disjoint; legacy `/search/<q>/page/N/` grammar also 200/disjoint; `/page/2/?s=` (slash form) returns the identical set |
| quickSearch | absent — theme `ftt_ajax_var` lazyload var only (`action=ftt_search` → 400); recorded absent, not drift |
| video | 6 shape-A pages (latest ×2 / tag-ullu / search / related ×2) 200, `meta[itemprop=contentUrl]` ×1, og:* + `duration`/`uploadDate` metas, `#video-tags` on 5/7 (page variance), related 12/page |
| stream | 6/6 `contentUrl` mp4s → **206 video/mp4** with `.live` referer (`ixifile.xyz`, `cdn2.ixifile.xyz`) |

verify.sh: **RESULT: PASS** (28 listing pages, 6 varied videos, stream serving + distinctness,
search↔load agreement, LoadResponse completeness). Harness NOTEs: quickSearch absent (explicit in
FINDINGS); actors unasserted — site does not expose actors (#127); card poster is a `data-src`
lazyload attr the regex-DOM instrument cannot attribute (posters empty = "not exposed" note;
provider's `jsoup` `a.thumb > img` unaffected).

## New record (independent reproduction of the failed attempt's #709 finding)

**shape-B pages (2021–2023 hub-embed era)** — 2/3 of searched-`amateur` video pages here; 3/7 in
attempt 1's sample — carry **no** `contentUrl` meta. The player is a base64-wrapped iframe:

```
<iframe src="https://ixiporn.live/wp-content/plugins/clean-tube-player/public/player-x.php?q=<b64>">
```

whose decoded payload (`post_id=…&type=iframe&tag=<iframe src="https://embed.redtube.com/?id=…">`)
frames an external **hub embed — hub varies per video** (xvideos in attempt 1's sample, redtube
here). Their duration meta is a site-side placeholder (`P0DT18H0M0S`). Getting streams for this
tier needs a b64→hub-resolver chain and is scoped to **#709** (1/3 sampled embed targets live
upstream there).

## Confirmed code hazard — fixed in-run, TDD (version 26 → 27)

`loadLinks` built stream URLs with
`selectFirst("meta[itemprop=contentUrl]")?.attr("content")?.trim().toString()` — on shape-B pages
`selectFirst` returns null, so `.toString()` produced the literal `"null"` and the provider
emitted `fixUrl("null")` — a **garbage dead stream entry** on every hub-embed video page.

Fix (minimal, in-provider): `IxiPornParse.contentUrl(element) → String?` pure Parse function
(trim, jsoup-decode, blank/`null`-guarded; null with no meta), `loadLinks` skips blocks without a
URL and returns `emitted` instead of unconditional `true`. JUnit4 `IxiPornParseTest` (6 tests)
written red (`Unresolved reference`) → green against fixtures copied verbatim from live blocks
(`/player-direct-newest-eyes.mp4block.html` shape-A, `/player-embed-redtube.block.html` shape-B).

## Gates

- `gradlew ixiporn:test` — 126 test executions (incl. 6 new), 0 failures (debug+release)
- clean `gradlew ixiporn:make` — green, `ixiporn/build/ixiporn.cs3` produced
- `scripts/check-findings.sh` (audit-providers lint) — PASS
- verify.sh — PASS (above)

## Registry & lifecycle

- `audits/findings.json`: only the `ixiporn` provider entry (verdict `ok-drift`, tier
  `plain-curl`, surfaces, `last_deep` 2026-10-17) + one `last_runs` entry (`run24`,
  `false_positives: []`). No fleet-wide rewrite.
- Standing #468: unchanged condition → not reopened, no new redirect finding.
- Embed-tier condition: already filed as **#709** by attempt 1 — dedup honored, no duplicate
  filed. This run's added evidence (redtube hub variant, `fixUrl("null")` hazard + fix) is
  recorded in `ixiporn/FINDINGS.md` and this file.
