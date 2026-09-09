# FINDINGS — javtiful.com (2026-09 audit; updated for issue #128)

## Verdict: OK

## Search
- `https://javtiful.com/search?q=red` → 200; `article.front-video-card:not(.front-partner-card)` matches; `/video/112318/mngs-075`.

## Video pages
- Watch page carries JSON-LD block with ISO-8601 duration:
  `"duration":"PT1H58M21S"` (video/112318/mngs-075). Player config `#frontWatchConfig`
  itself has NO duration field — the JSON-LD block is the only per-video duration source.
- Related-card durations (`span.front-duration-tag`) belong to *other* videos, not the loaded one.

## Stream
- Video page `id="frontWatchConfig" type="application/json">` JSON `playerSources[0].src=https://fast-stream.jav.si/p/<hex>` size 720 → **206 video/mp4**.

## Duration fix (issue #128)
- `load()` now regex-parses `"duration":"PT{h}H{m}M{s}S"` from the JSON-LD block
  (`PT1H58M21S` → 118 min) and sets `this.duration`.

## Sorted-row pagination fix (issue #207, probed 2026-09-09)
- Correct URL form when a row URL already carries a query: `&page=N` (not `?page=N`).
  New `pagedUrl()` top-level helper; unit-tested (ADR-0005) in `JavtifulPagingUrlTest`.
- Evidence: `https://javtiful.com/videos?sort=most_viewed?page=2` (broken) → 200 but page
  title "Latest JAV Videos" (site ignores the malformed sort param). Fixed form
  `...&page=2` → title "Most Viewed JAV Videos … - Page 2"; `sort=top_rated&page=2` →
  "Most Liked … - Page 2". Both verified 2026-09-09.
- Malformed-URL curl transcript (site quirk, kept for the record): broken URL returns 200
  with default Newest listing — silent drift, no 4xx.
- Site data note: listings contain the censored and reducing-mosaic releases of the same
  JAV code under one identical title (e.g. `/video/107163/fit-007-reducing-mosaic` and
  `/video/107052/fit-007` both on search page 1 for "tokyo"). verify.sh's duplicate-title
  check therefore reports title collisions although hrefs/posters/streams are distinct;
  the provider now dedupes by title (`distinctBy { it.name }`) in search, getMainPage and
  recommendations as mitigation.
- No distinct quick-search endpoint (hasQuickSearch = false).
- Year: from "Added on:" `<time datetime>`; duration from the JSON-LD `PT…H…M…S` regex.
- Stream verification: all 5 sampled playerSources srcs → 206 video/mp4 from
  fast-stream.jav.si (2026-09-09 run).
