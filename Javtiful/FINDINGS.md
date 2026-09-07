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
