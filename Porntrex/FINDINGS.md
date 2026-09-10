# FINDINGS — porntrex.com (2026-10 drift probe, issue #171)

## Verdict: fixed — video pages now load streams via /embed/{id}/

## Drift (fresh, this run)
Direct video pages (`/video/{id}/{slug}/`) return 200 but render an **empty shell** to
guests (and to Googlebot): no `flashvars`, no `get_file`, empty `p.title-video`,
no `og:` metas, `<body class="white age_false video-page-not member-page">`.
Not geo/CI-specific shape — reproducible for every probed video and UA; search and
listing pages are unaffected.

Evidence (runner, 2026-10):
- `GET /video/2913997/... → 200, 0× flashvars, <title> is the generic site title`
- Same for 3258747, 3210808 (search/milf) and fresh latest-updates IDs
- `?mode=async&function=get_block&block_id=video_view_video_view` → **500**
- `Cookie: confirmed=true`, browser UA, session cookies, Googlebot UA → same empty shell
- Age gate is client-side JS only (sets `confirmed` cookie, server never reads it)

## Fix (probed)
`https://www.porntrex.com/embed/{id}/` still serves the full KVS `kt_player` flashvars:

```
GET /embed/2913997/ → 200
video_url: 'https://www.porntrex.com/get_file/28/58549f.../2913000/2913997/2913997.mp4/?embed=true'
title: 'Busty 38yo Redhead Milf Anna Maria ...'
preview_url: '//ptx.cdntrex.com/contents/videos_screenshots/2913000/2913997/preview.jpg'
```

Stream serves video: `GET get_file URL (Referer https://www.porntrex.com/) → 302 →
200 video/mp4` (249 MB body); ranged `206 video/mp4` on the final CDN URL
(pcdn.cdntrex.com). Only `video_url` (480p) is a direct stream — the
`video_alt_url*` entries carry `video_alt_url_redirect: '1'` and point back at the
page, not at media.

Provider change (version 4 → 5): `load()` and `loadLinks()` fall back to
`/embed/{id}/` (id from the URL) whenever the direct page has no title / flashvars.

## Search
- `/search/milf/` → 200, 85–106 `div.video-preview-screen.video-item` cards. Unchanged.

## Video pages
- Direct `/video/{id}/{slug}/` — empty shell (see above), kept for tags/models/duration
  when the site restores them.
- `/embed/{id}/` — title, preview poster, flashvars stream. Used as fallback.

## Related videos
- `https://www.porntrex.com/related_videos_html/2913997/` → 200,
  `a.player-related-videos-item.kt-api-related-item` items (23 KB). Existing shell-page
  selector (`div.video-list div.video-item`) returns nothing now; wired in v5: `load()`
  falls back to this endpoint when the shell yields no items (title from `span.title`,
  poster from `div.thumb` style `url(...)`).

## Risks / blockers
- None blocking. If guest shells ever regress to requiring login, /embed/ is the
  remaining guest surface.

## Duration field fix (issue #215, 2026-09-09 probe)

- The stats row on fully-rendered video pages carries the real duration
  (`<span><i class="fa fa-clock-o"></i> <em class="badge">6min 09sec</em></span>` in
  `div.block-details div.item`). The old selector (bare `i.fa-clock-o` + first parent) matched
  the **navbar "Latest" icon** first → `duration = null` on every video, and if the navbar
  icon disappeared it would have parsed "6min 09sec" as 6.
- Fix (version 6 → 7): `PorntrexParse.parseDurationSeconds()` parses `Nmin Nsec`, `M:SS` and
  `H:MM:SS` into **seconds**; selector scoped to
  `div.block-details div.item span:has(i.fa-clock-o) em.badge`. Duration is not present on the
  `/embed/{id}/` fallback page, so guest-shell videos legitimately return null duration.
- TDD: `Porntrex/src/test/resources/porntrex_video_page.html` fixture
  (transcript pattern from the issue) + `PorntrexParseTest` — red (unresolved `PorntrexParse`)
  → green (369s for "6min 09sec", 3969s for "1:06:09", null for "Latest"). `gradlew Porntrex:test`
  AND `Porntrex:make` clean.

## Verification (verify.sh, this run) — RESULT: PASS

- search `/search/massage/` + async page 2: 200, 85 cards each, no dupes across pages
- home `/categories/teen/` + async pagination page 2: 200, 120 cards each, no dupes
- video pages via `/embed/{id}/` (guest direct pages were shells at probe time, see #171):
  both 200, stream `get_file/.../{id}.mp4/` → 302 CDN → **206 video/mp4** (both sampled videos)
- NOTE (expected at the time): duration selector matches nothing on the sampled *guest shell*
  embed pages — the row only materializes on fully-rendered pages; parsing correctness is
  proven by the unit test on the real stats-row markup. Tags/actors/plot/poster likewise
  absent on shells and resolved by the #171 embed fallback.

## Reviewer re-verification (round 2, live site current state) — real run, results below

Direct video pages **now render fully again** (og:title/og:image/og:description metas,
`video_url:` flashvars, and the full stats row). The duration fix is therefore live-verifiable:

- 5 varied direct video URLs (3074115, 1231055, 3325230, 1182292, 1147023): all 200;
  `div#kt_player` matched 1 on each; stream `get_file/.../{id}.mp4/` → **206 video/mp4 on all 5**
- `--video-duration-selector 'div.block-details div.item span:has(i.fa-clock-o) em.badge'`:
  **field 'duration' present on all 5 sampled pages** (live badges: "9min 57sec",
  "6min 09sec", ... — parse → 597s, 369s, ...)
- related videos: site exposes an in-page `div.video-list div.video-item` block (24 items on
  each of the 5 pages) and a `related_videos_html/{id}/` endpoint (45 items) — both match
- search `/search/massage/` + async page 2: 200, 85 cards each; sampled videos 3074115 and
  1231055 appear on both search pages, hrefs identical to their load URLs
- NOTE: home `/categories/teen/` page 2 shares 20/120 hrefs with page 1 — verified **site-native**
  (the site's own `/2/` URL overlaps the same 20; async p2 ≡ native p2, 120/120).
  getMainPage is untouched by this PR; no action taken.

## Quality variants (issue #256, 2026-09-10 probe; corrected by Reviewer)

Direct `/video/{id}/{slug}/` pages still render the **empty shell** for guests (5 probed
IDs: 433943-byte body, no `og:title`, no `flashvars`, body class `video-page-not`). The
`/embed/{id}/` fallback is the only guest surface.

Embed flashvars do list quality labels, but the alternate URLs are **page redirects, not
media**:

- `video_url` (base) → real `get_file/.../{id}.mp4/` file
- `video_alt_url` ('720p HD'), `video_alt_url2` ('1080p FHD'), `video_alt_url3` ('2160p 4K')
  each carry `video_alt_url<n>_redirect: '1'` and point at
  `https://www.porntrex.com/video/{id}/{slug}` — a text/html page

Live check (5 videos): base `get_file/.../2491818.mp4` → 302 → `pcdn.cdntrex.com`
video/mp4; the alt URL returns 200 text/html; hand-built `..._720p.mp4` variants 404.
Emitting the alt URLs as ExtractorLinks yields 3 dead links per video. Fix: `qualityLinks`
skips variants flagged `video_<n>_redirect: '1'` (JUnit covers it); base 480p stays.

Verify notes: verify.sh run with `--home-selector p.inf` because the script's regex-DOM
card block for `div.video-preview-screen.video-item` truncates inner text to the quality
badge, producing false title dupes on the homepage check (script limitation, not a site
duplicate — href dedup on the full cards is clean). No quick-search endpoint exists
(site search is the only lookup); tags/actors/duration are exposed on full video pages,
year/score are not.
