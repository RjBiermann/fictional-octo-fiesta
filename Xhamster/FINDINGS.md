# FINDINGS — xhamster.com (2026-09 audit)

## Verdict: OK

## Search
- `/search/red` → 200; `div.thumb-list div.thumb-list__item` ×62; `a` hrefs `/videos/...`.

## Video page / stream
- `window.initials` present; `"sources":{"mp4":{"720p":"https://video7.xhcdn.com/key=.../720p.h264.mp4"}}` → 302 → follow → **206 video/mp4**.
