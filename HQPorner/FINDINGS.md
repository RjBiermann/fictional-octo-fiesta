# FINDINGS — hqporner.com (2026-09 audit)

## Verdict: OK

## Search
- `https://hqporner.com/?q=red` → 200; provider selector `div.row section.box.feature:has(span.icon)` matches 51 items; result links `/hdporn/127751-....html`.

## Video page / stream
- Video page 200, `iframe src="//mydaddy.cc/video/45ec40b01a59e8f1ca/"` present (selector `iframe[src*=mydaddy]` OK).
- MyDaddy embed lists `a href='//s29.bigcdn.cc/pubs/6a9e1ee772bce4.19677672/{360,720,1080}.mp4'`.
- Stream check: 360p → 404 (dead file), **720p & 1080p → 206 video/mp4** with `Referer: hqporner.com`. Player emits all qualities; 360 flakiness is cdn-side.
