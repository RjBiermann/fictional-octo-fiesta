# FINDINGS — xhamster.com (re-probe 2026-09-09, fix for issue #216)

## 2026 re-probe for issue #242 (masked card titles + load poster not loading)
- Masked/starred card titles: **not reproducible server-side from this runner** — desktop fetches
  of search/home/video pages across `?geo=us`, no-geo, `geo=de`, de/fr Accept-Language, and
  cookie on/off all return full unmasked titles (grep `\*{3,}` = 0 hits on every page).
  Hypothesis: guest "safe/censored-title" variant served to age-verification locales
  (UK Online Safety Act pattern). Defensive fix: the search/home card mapping now prefers the
  server-rendered `title` attribute of `a.video-thumb-info__name` (always the real title on
  every probed page across locales) and falls back to inner text; the recommendations mapping
  keeps inner text because related-video inner text is verified present on video pages.
  Note: related-video inner text IS present on video pages; the verify script's regex-DOM
  truncates nested-div cards — verify uses the anchor itself
  (`a.video-thumb-info__name`) as the related card.
- Load poster: old parse (`style` → substringAfter "https:") stripped the scheme. Fix: poster
  comes from `window.initials.videoModel.thumbURL` (2560x1440 webp, live-verified on 4 video
  pages: `https://ic-vt-nss.xhcdn.com/a/.../030/711/276/v2/2560x1440.201.webp`), fallback
  `parsePreloadPoster` regex extracts the full `https://...` URL from `div.xp-preload-image`
  style. `VideoModel` data class gained the `thumbURL` field.
- og:image meta remains populated on video pages (used by verify). Stream side unchanged;
  4/4 sampled pages serve HLS 206.

## Context: the SPA-shell window closed
The issue reported every surface returning a contentless `isBare:true` SPA shell
(41 KB HTML, 0 cards, 0 m3u8). Re-probing on 2026-09-09 shows the site now
**server-renders full desktop HTML again** (same shell ALSO appeared transiently at the
start of this run — pages shrank to ~41 KB, then grew to 250–380 KB with content; the
shell is served intermittently at the edge, not permanently). All rewrites against a
JSON API were shelved; the DOM path is alive again.

## Engine fingerprint
Custom xHamster platform, "XH New Design" template. `window.initials = {...}` JSON blob
on every page (verified present and content-bearing on desktop search/home/video pages).

## Search (mobile-UA trap)
`https://xhamster.com/search/{query}?geo=us`, **desktop UA** → 200, 379 KB, 51
`data-video-id` cards. Card markup: `div.container-c9dbd.thumb-list__item.video-thumb` …
`a.video-thumb-info__name` + `img.thumb-image-container__image` — **the provider's existing
selectors still match** (46 title links on page 1).

- Desktop UA: 51 cards. Mobile UA: only 5 cards (rest are client-hydrated
  `renderPlaceholder` divs). **Provider must fetch with default (desktop) UA** — it does.
- Transcript: `curl -A <desktop UA> "https://xhamster.com/search/teacher?geo=us" | grep -c data-video-id → 51`.

## Quick search
No distinct quick-search endpoint: legacy `quick_search.php` → 404 (reproduced); provider keeps `hasQuickSearch = false`.

## Homepage
Rows: `/newest/1?geo=us`, `/most-viewed/weekly/1?geo=us`, `/videos/teacher/1?geo=us`, `/categories/big-ass/1?geo=us` — each returns `a.video-thumb-info__name` (7–9 grep hits; one line of HTML). Page 2 (`/newest/2?geo=us`) → 246 KB, unique `data-video-id`s with 0 overlap to page 1.

## Video pages
Desktop page now exposes everything to guests:
- `window.initials.videoModel`: title, duration, description, pageURL, thumbURL, views.
- `window.initials.xplayerSettings.sources.standard.{h264,av1}` — **populated for guests on the DESKTOP page** (this was mobile-only in Feb 2026). Same hex-obfuscated `url`/`fallback` values; decode algorithm (algId 1–7) verified unchanged with live hex.
- `div.xp-preload-image` (poster bg URL) still present.
- Tags: `div[data-role='video-tags-list'] a[href*='/categories/']` — /categories/ hrefs confirmed (also /pornstars/, channel links in same block — provider filter handles this).
- Actors: `a.entity-author-container__name` present (uploader).
- Related: `div[data-role="related-item"]` ×11, each with `a.video-thumb-info__name` + `a[data-role='thumb-link']` + `img` — provider selectors unchanged and matching.
- Deletions observed: `div.with-player-container h1` (title, gone) and `div.controls-info div.ab-info p` (plot, replaced by `controls-info__description`) — **provider load() now reads title/plot/duration from `videoModel` JSON** with `<h1>` fallback. og:title / og:image / og:description meta tags remain valid mechanical verification selectors (verify passes with them).

Note: the issue's sample video `teacher-bangs-her-desk-14125383` now returns **410 Gone** (deleted video, not a site defect). Fixtures/tests use live video `xh6Aty0` (id 30358422).

## Stream sources
From `window.initials.xplayerSettings.sources.standard` (desktop page, guest):
- h264 auto fallback → master HLS m3u8, decoded live:
  `https://video-h.xhcdn.com/key=...,end=1789002000/data=.../media=hls4/multi=.../030/358/422/_TPL_.h264.mp4.m3u8`
  → curl 200, body `#EXTM3U #EXT-X-STREAM-INF ... RESOLUTION=256x144, avc1.4d4015`. No Referer needed (desktop UA).
- h264 240p/480p/720p direct MP4s decode to `https://video-h.xhcdn.com/key=...,limit=3/...` (IP-bound, 403 from CI runner — known artifact, works in-app per session IP).
- av1 auto → m3u8 on video-nss-h.xhcdn.com.
- Mobile-UA page also still serves the same populated source set (decode verified live this run: `04bd00…720p` hex → `https://video-h.xhcdn.com/key=piy2lSXwIEJV+Q7rxsJtsA,end=1789002000/...720p.h264.mp4` prefixed URLs stable). `loadLinks` keeps the mobile-UA fetch (verified working 2026-09-09) and its `link[rel=preload] m3u8` path (also present on desktop).

## Headers / referer
Search/home/load: desktop UA + `Cookie: video_titles_translation=0`. loadLinks stream fetch: mobile UA (unchanged). Decoded CDN m3u8 needs no Referer.

## Pagination
Search `?page=N` (`search/teacher?page=2` → 276 KB, 0 shared video ids with page 1). Homepage rows `/{row}/{page}?geo=us` — NOTE: `/newest/N` cards include a `video-thumb__date-added` header (per-day date label) and the title lives in `a.video-thumb-info__name` (same as search cards); `/most-viewed` cards truncate to empty inner text in the verify regex-DOM, so the homepage distinctness check passes on the title anchor as the card (`--home-selector 'a.video-thumb-info__name'`, href = video path, text = unique title). Related videos: single page per video page (no pagination) — provider never paginated them. Year/upload-date is not exposed as a page field — only `videoModel.created` (unix, JSON); provider does not populate a year.

## Risks / blockers
- SPA shell is served intermittently at the edge (this run saw it flip within minutes). When the shell wins, a fetch returns ~40 KB with no `videoModel`/cards and the provider yields empty results that page. There's no client workaround (no fetchable JSON surface; XHR re-fetch 404). If the shell mode becomes permanent, this provider dies again — at which point reversing the `/x-api` endpoints (`uploadHost` on `u.xhamster.com`) is the upgrade path.
- Direct MP4s are IP-keyed; mobile-UA loadLinks path untested in-app this run (m3u8 verified by curl).
- Deleted videos return 410 — an old provider cache entry can dead-end; not a provider defect.

## 2026-09-11 re-probe (issue #339 — data-completeness)
Desktop video pages' `window.initials.videoModel` no longer carries title/description/thumbURL
(keys observed: author, channelModel, duration, id, sponsor — duration in seconds still present).
Verified on two live videos with mobile-UA Chrome/130 + Cookie `video_titles_translation=0`:

- `videoModel.duration` ✓ (492 / 643)
- `window.initials.videoEntity` now carries: title, description (186 / 164 chars), duration,
  thumbBig (xhcdn webp poster), pornstarModels [{id,name,...}]. NOTE: pornstarModels carry no
  usable avatar URL (thumb.avatar1/avatar2 are filename fragments, not URLs) — actors are
  name-only; no videoEntity.pageURL key exists (related-video pageURLs live on each thumb).
  - v1 "Two Cougars on the Prowl" (29238872): aud Desifilmy45, Karla Insatiable, Jason Pierce, Madame D
  - v2 "My MILF Stepmom Gives Me A Laundry Lesson" (29212231): Jax Slayher, Hailey Rose, Kera Bear
- `window.initials.videoPageComponent.relatedVideos.videoTabInitialData.videoListProps.videoThumbProps`
  → 11 related items on BOTH pages, each with absolute pageURL (https://xhamster.com/videos/…)
  and thumbURL (xhcdn image).
- DOM: `div[data-role='related-item']` → 0; `a.entity-author-container__name` → 0 (both pages).
- DOM: `div[data-role='video-tags-list'] a[href*='/categories/']` still matches (9 links on both pages).
  NOTE: the tags block no longer matches when replayed strictly inside the raw block in some
  pages — but on the live pages the selector-matched links are identical to the list above,
  so the DOM tags path stays untouched.
- og:description meta carries "Starring: …" (corroborates pornstarModels).
- Quick search: `/xhr/search/suggest?q=…` → 404 (no suggest endpoint; provider correctly has none).
- h1 present server-side on both videos (title fallback intact); div.xp-preload-image present
  (poster css fallback intact).
- Stream side unchanged; duration (seconds) semantics unchanged; no absolute date/year field
  (only relative dateAgo) → year correctly not populated.

Fix (version 17 → 18): title from videoEntity.title, poster from videoEntity.thumbBig,
description from videoEntity.description, actors from videoEntity.pornstarModels,
recommendations from videoThumbProps. Dead DOM selectors removed for related-item and
entity-author; tags selector kept. Fresh fixtures xhamster-video-v1/v2.html carry the raw
window.initials payload from the two probed videos; Parse tests assert plot length ≥164,
11 recs with absolute pageURLs, and pornstar name lists.

---

# FINDINGS — Xhamster (issue #338) — probed 2026-09-11
UA `Mozilla/5.0 (Linux; Android 13; Pixel 7) … Chrome/130 Mobile`, `Cookie: video_titles_translation=0`, `?geo=us`.

## Finding 1 — listing drift: SSR cards gone, cards JSON-only in window.initials (confirmed, fixed)
- Every listing surface returns HTTP 200 (~230–280 KB) with **0** `div.thumb-list div.thumb-list__item`
  nodes (desktop UA, `x_platform_switch=desktop`, cookie jar, HTTP/1.1 all tried — no SSR cards anywhere).
- Card data lives only in `window.initials`:
  | surface | path | cards/page |
  |---|---|---|
  | search | `searchResult.videoThumbProps` | 46 |
  | /newest, category pages | `layoutPage.videoListProps` | 46 |
  | /4k | `layoutPage.trendingVideoListProps` | 50 |
  | /most-viewed/*, /hd/2?quality=1080p | `layoutPage.videoListProps` | 46–50 |
- Titles in the JSON are NOT masked; `{pageURL,title,thumbURL}` per card.
- Pre-existing dead row: `/categories/complilation` → 404 → fixed to `/categories/compilation` (46/46 cards).
- Provider's exact page-1/page-2 URL forms verified for all 24 rows (incl. the `/hd/2?quality=1080p/2` form
  getMainPage builds — 46/46 cards).

## Finding 2 — decodeXhUrl algo-6 broken (confirmed, fixed)
Site JS (xplayer-mobile.js) algoId 6 keystream: `return 255 & (i ^ i>>>18) >>> (i>>>27 & 31)` with
`i = O(i,0x2c9277b5)+0xac564b05|0` (O = imul) — the XOR result is **not** masked before the logical shift.
The old Kotlin port masked the xor before the shift AND used an arithmetic `shr` → keys diverged from frame 1.
Live samples (algos rotate per fetch — first byte of each hex tells the algo):

```
hex 06e06077…f9979 (video /videos/xhpglku, h264 240p url)
site-decode → https://video-h.xhcdn.com/key=l2na9rOxunUJpEq6cVwT-Q,end=1789110000,limit=3/data=20.118.214.103-mv/speed=0/029/334/118/240p.h264.mp4
hex 06e0c5e2…db408f (video /videos/xhuTa4g, h264 auto url)
site-decode → https://video-nss-h.xhcdn.com/N3u4fvec2BPOm6TRA7Il1g==,1789110000/media=hls4/multi=256x144:144p,…
Kotlin-semantics decode of both → garbage (non-URL bytes)
```

## Not affected (verified live, do not regress)
- `getInitialsJson()` regex captures `window.initials` on video and listing pages.
- Video-page `?geo=us` → `xplayerSettings.sources.standard` decodes: algo-5 auto / algo-6 hls4 masters →
  HTTP 200/206 `application/vnd.apple.mpegurl`. Existing algo 1/2/5/7 decodes verified byte-for-byte
  against player JS semantics (python trace matched the provider's algo-5 keystream frame-for-frame).
- Per-quality `key=`-style 720p/480p direct MP4 links intermittently 403 `text/plain "Wrong key"` from this
  runner even session-cookie'd — CDN key appears IP/token-bound at render time; the algo-1/2/3/4 entries the
  provider decodes depend on which algos the site assigns that fetch. HLS auto master covers all qualities,
  so playback is unaffected (issue #338 agrees: "the defect is lost per-quality links").
