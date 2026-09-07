# FINDINGS — HQPorner (fix probe, 2026-09)

## Root cause of the drift
Cards DO still contain `span.icon` — but only in the **desktop** layout. With a mobile
UA (what CloudStream/NiceHttp sends), the site serves a variant without the hover
`span.icon.fa-circle-o` elements, so `section.box.feature:has(span.icon)` matches 0.

Evidence (same URL, different UA):
```
UA=curl/8.0                                   → 50 cards with span.icon
UA="Mozilla/5.0 … Android 13 … Mobile"        → 0 cards with span.icon, 50 cards with a.image
```
Cards in the mobile layout still carry `<a href="/hdporn/…" class="image featured …">`
and `<img …>` — stable across both layouts.

## Fix
`div.row section.box.feature:has(a.image)` for home (`getMainPage`) and `search`.
`div.row` ancestor verified present (51 sections inside `div.row`, 50 with img).

## Search
```
GET https://hqporner.com/?q=big&p=1 → 200
section.box.feature matches: 51 (50 video cards)
href="/hdporn/127759-anxiety_extracted_through_the_dick.html" …
```
Pagination `?q=…&p=N` unchanged.

## Video pages (probed ≥5, varied listings: search/top/category)
- /hdporn/127757-daddys_little_career_pusher.html (search) → 200
- /hdporn/101302-twos_cumpany_threesomes_a_crowd_part_two.html (top) → 200
- /hdporn/124376-have_you_milked_before.html (category) → 200
- /hdporn/127759-anxiety_extracted_through_the_dick.html (search) → 200
- /hdporn/102362-fucking_the_new_maid_mommy_got_boobs.html (top) → 200
(load() selectors verified: `h1` ✓, `li.icon.fa-clock-o` → "39m 20s" ✓,
`div.4u section` (recommendations) → 47 matches ✓; `div.extra span.C` (year) no longer
present on the page — pre-existing optional field, returns null.)

## Related videos
`div.4u section` on video pages (47 matches on 4/5 pages; one top-page listing had 30) —
unchanged, works.

## Stream chain (unchanged, verified end to end)
1. Video page → `iframe src="//mydaddy.cc/video/<id>/"` ✓ (all 5 pages)
2. mydaddy page requires `Referer: https://hqporner.com/` (without it: "This domain has
   been blocked" stub — this also makes verify.sh's built-in stream check structurally
   N/A here, since the stream is not embedded in the hqporner page HTML)
3. MyDaddyExtractor regex `a href='([^']*)'` → `//sXX.bigcdn.cc/pubs/…/1080.mp4`
4. `GET https://s47.bigcdn.cc/…1080.mp4` with referer → **206 video/mp4** (all 5 videos)

## Risks / blockers
- mydaddy.cc blocks referer-less requests (stub page) — extractor already sends referer.
- Some `/hdporn/…html` URLs from the /top listing are 404 (dead entries on the site
  itself, not a provider bug). No Cloudflare on hqporner.com for the runner.
