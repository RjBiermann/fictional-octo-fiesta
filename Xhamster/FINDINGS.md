# FINDINGS — xHamster (re-probe 2026-09-07 for issue #119)

## Engine fingerprint
Custom xHamster desktop engine. Server-rendered HTML **is** served from this runner
(issue #119's 41 KB "JS shell" did not reproduce here; likely bot-tier/geo dependent and
transient). Markup confirmed:
`curl -sL -A "$UA" -b video_titles_translation=0 "https://xhamster.com/newest/?geo=us"` →
200, 395 KB, 46 × `thumb-list__item video-thumb video-thumb--type-video`.

## Search
`GET /search/big?page=2&x_platform_switch=desktop&geo=us` → 200, 355 KB,
46 × `thumb-list__item video-thumb video-thumb--type-video`, 46 `/videos/<slug>` hrefs.
Existing selector `div.thumb-list div.thumb-list__item` still matches. Unchanged.

## Video pages
Probed 2 pages (probe scope: stream extraction is the broken part):
- `https://xhamster.com/videos/xhJGXaA?geo=us` → 200, 296 KB. `with-player-container`,
  `controls-info`, `ab-info`, `video-tags-list`, `related-item` (23) all present —
  load() selectors unchanged and valid.

## Stream sources (per video page)
- `window.initials` JSON `xplayerSettings` is now **null** for anonymous guests (and
  likely for bot-tier requests): no HLS, no `standard` player sources, no
  `link[rel=preload][as=fetch]` m3u8 (0 matches). This is the actual drift —
  the provider's only stream paths (preload m3u8 + xplayerSettings) both dead.
- **New working source**: `window.initials.downloadDropdownComponent.sources.mp4` —
  map quality → signed xhcdn MP4:
  `"144p":"https://video7.xhcdn.com/key=...,end=...,limit=3/data=.../030/041/584/144p.h264.mp4"`
  (144p/240p/480p/720p on this video).
  Verification: `curl -sIL` → 302 → 200, `content-type: video/mp4`,
  `content-length: 27197828`.

## Headers / referer
No special referer required for the mp4 fetch (verified with plain curl, no referer).

## Pagination
`?page=N` on search and listings, unchanged (search page 2 verified above).

## Risks / blockers
- Site serves different shells per bot-tier/IP (issue #119 evidence vs this re-probe).
  Selectors were not actually broken; stream extraction was. The MP4-fallback fix covers
  both tiers: xplayerSettings when present, downloadSources otherwise.
- Signed URLs expire (`end=...`) — expected, per-request extraction.

## Re-probe 2026-09-07 (issue #134 — duration)
- Goal: capture duration evidence from a full video page.
- Runner now receives bot-tier JS shells on every fetch (search AND video pages):
  `curl -sL .../videos/xhJGXaA?geo=us` → 200, 42 KB, `window.initials` contains only
  layout/bot keys (`layoutPage`, `pk`, `recaptchaKeyV2`, ...), zero `thumb-list__item`,
  zero `with-player-container`, no player metadata. Googlebot UA, mobile UA, sec-fetch
  header sets and retries all produce the same shell. This matches the transient
  bot-tier drift seen in issue #119 (full pages were served earlier the same day).
- Fix shipped defensively without a fresh full-page capture: `parseDurationSeconds()` in
  load() extracts duration from (1) LD-JSON VideoObject ISO-8601 `"duration":"PT12M34S"`,
  then (2) plain seconds in player metadata `"duration":754`. Both are the standard
  xHamster desktop page durations sources; in-app verification recommended.
- verify.sh: FAIL on all live checks (bot-tier shell, 0 selector matches);
  static LoadResponse check PASS (duration, plot, tags, actors, recommendations).
