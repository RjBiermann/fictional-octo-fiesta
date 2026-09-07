# FINDINGS — javtiful.com (2026-09 audit)

## Verdict: OK

## Search
- `https://javtiful.com/search?q=red` → 200; `article.front-video-card:not(.front-partner-card)` matches; `/video/112318/mngs-075`.

## Stream
- Video page `id="frontWatchConfig" type="application/json">` JSON `playerSources[0].src=https://fast-stream.jav.si/p/<hex>` size 720 → **206 video/mp4**.
