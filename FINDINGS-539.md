# FINDINGS-539 — Javseen watch-page meta og:video:duration sentinel-zero (duration="0 min" leak)

Issue #539 (audit run 8 / #524 detail fix). Re-probe 2026-10-09, runner IP US datacenter,
plain curl (instrument tier matches the sweep's `ok (plain)` verdict for this site).
Fix branch: `devloop/issue-539`. Scope: duration field guard only.

## Reproduce — confirmed exactly as reported

`/287527/` (the page named in the issue) still serves the sentinel zero:

```
$ curl -sL https://javseen.tv/287527/ -A $UA | grep -o 'og:video:duration" content="[^"]*"'
og:video:duration" content="0"
```

The provider's old inline parse (`…attr("content")?.toIntOrNull()?.let { it / 60 }`)
accepts `0` → `duration = 0` → CloudStream renders a bogus "0 min" row instead of hiding it.

## Is the zero site-wide or per-page? — mixed: keep the field, gate > 0

Six fresh watch pages from different listings + the issue's page, all HTTP 200:

| page                                  | og:video:duration |
|---------------------------------------|-------------------|
| /287527/ (issued page; recent listing)| **0** (sentinel)  |
| /287521/ (search "teacher")           | 6840              |
| /287520/ (search "teacher")           | 11400             |
| /286500/ (search "teacher")           | 7080              |
| /286680/ (search "teacher")           | 9360              |
| /286818/ (search "teacher")           | 8700              |
| /286942/ (search "teacher")           | 9000              |

Per the issue's decision rule: values are real on 6/7 pages → **gate `> 0`, keep the
selector** (do not drop it). The shared `DurationParse.fromSeconds` (built red→green in an
earlier run, issue #302 lineage) already implements exactly this contract — converts seconds
to minutes, nulls junk/absent/zero ("junk/0 → null"). The root cause is that Javseen was the
one provider still carrying an inline copy instead of routing through it (same bypass MissAV
had; MissAVParse delegates — both shapes exist in shared test coverage).

## Fix

- TDD red: 4 new tests in `JavseenParseTest` pin the live-probed grammar — `"6840"` → `114`,
  `"11400"` → `190`, **`"0"` → null (#539 regression)**, `"59"` → null, junk/absent → null.
  Green: added `JavseenParse.parseDuration(seconds) = DurationParse.fromSeconds(seconds)`
  (MissAV precedent) and swapped the inline `toIntOrNull()/60` wiring in `load()` for it.
  Nonzero behavior is unchanged (identical floor division); only "0"/junk now yields null.
- `Javseen/build.gradle.kts` version 18 → 19. No shared-code change (module already carries
  the contract), so no other provider is affected.

## Verification (verify-provider skill)

`verify.sh` against live site, 6 video URLs sampled from search listing + issued page,
provider `Referer` header:

- video pages 6/6 → 200 with ≥1 `button[class=button_choice_server]` (9/5/7/7/9/4 matches);
  title/poster all-or-none consistent, no duplicate titles.
- `--video-duration-selector 'meta[property=og:video:duration]'`: **present on all 6 sampled
  pages** — exposure is consistent across the fleet, the fix only affects the zero value.
- streams 6/6 → **HTTP 200 `application/vnd.apple.mpegurl` with `#EXTM3U` body** via base64
  data-embed → mycloudz.cc unpack → per-video HLS host (streamvalora/acek-cdn/…
  `.urlset/master.txt` variants), `Referer: https://javseen.tv/` replayed. No stream path
  repeats across videos (distinct tokens/paths).
- LoadResponse completeness: recommendations/tags/plot/year/duration/actors all have
  population assignments in the provider Kotlin.
- quick search: **none on site** (`hasQuickSearch = false`, unchanged — Javseen/FINDINGS).
- related videos: **no related section** (commented-out placeholder; unchanged —
  Javseen/FINDINGS). Provider's `ul.videos.related li` still returns empty harmlessly.

Tool caveat (same as Javseen/FINDINGS 2026-09-09): search and homepage endpoints return
JSON-wrapped HTML (`{"status":1,"html":…}` with backslash-escaped markup); verify.sh matches
selectors on literal HTML and yields 0 matches even though the endpoints are healthy. Manual
curl transcripts this run:

- search p1 `/search/video/?ajax=search_results&s=teacher&o=recent` → 200, `status:1`,
  29 distinct cards (`video-287521` …); p2 `&page=2` → 200, new items.
- search ↔ load agreement: card 287521 title `Mosaic XRW-830 Naked Tying Teacher An Sasakura`
  == load `/287521/` h1 and og:title; poster `pics.javseen.tv/media/videos/tmb//000/287/521/1.jpg`.
- homepage p1 `/recent/?ajax=browse_videos` (Referer mainUrl) → 200, 30 cards (287511…);
  p2 `/recent/2/?ajax=browse_videos` → 200, 30 cards (287481…) — zero p1/p2 overlap.
- actors (description meta) 2026-10-09, /287521/: `... and pornstar ...` shape intact
  (`meta[name=description]` = "Mosaic XRW-830 ... with studio K.M.Produce and release
  2020-02-14 …"); tags `fa-th-list` 12 (/287527/) and 16 (/287521/); year regex
  `Release Day: 2019-10-12` (/287527/), `Release Day: 2020-02-14` (/287521/).

## Status

Fixed and verified on this branch for human review — not merged.
