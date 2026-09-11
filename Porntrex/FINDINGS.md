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

## Quality variants (issue #256, 2026-09-10 probe; round-2 correction)

The site has **two page shapes** with different quality surfaces, and `qualityLinks` must
handle both:

**1. `/embed/{id}/` (guest embed — used as the loadLinks fallback).** Flashvars expose
only one direct stream: `video_url` (`video_url_text: '480p'`). `video_alt_url`
('720p HD'), `video_alt_url2` ('1080p FHD'), `video_alt_url3` ('2160p 4K') each carry
`video_alt_url<n>_redirect: '1'` and point at `https://www.porntrex.com/video/{id}/{slug}`
— a text/html page, not media (live check: base `get_file/.../2491818.mp4` → 302 →
`pcdn.cdntrex.com` video/mp4; alt URL → 200 text/html; hand-built `..._720p.mp4` on the
embed hash → 404). Emitting those would yield dead links.

**2. Fully-rendered `/video/{id}/{slug}/` (real-browser guests; CI shell windows hide it).**
Flashvars carry a real `get_file` URL per quality, each with its **own hash** — e.g. the
2024-10-10 Wayback capture of #2491818: `video_url` → `..._360p.mp4` ('360p'),
`video_alt_url` → `...mp4` ('480p'), `video_alt_url2` → `..._720p.mp4` ('720p HD'),
`video_alt_url3` → `..._1080p.mp4` ('1080p HD'), **no `_redirect` flags**. Old hashes
rotate (404 today), but the shape is what the provider parses.

Fix: `qualityLinks` skips any variant flagged `video_<n>_redirect: '1'` (embed stub) and
keeps the rest in flashvar order (full page: base 360p + 480p/720p/1080p). JUnit covers
both shapes. Direct guest pages are currently empty shells again (same as #171),
so in-app playback resolves through `/embed/{id}/` with the base quality; on devices
that get the full render the higher qualities now appear.

## Verification (verify.sh, this run, live) — RESULT: PASS

Search `/search/massage/` + async page 2: 200, 85 `div.video-preview-screen.video-item`
cards each, no dupes. Home `/categories/milf/` + async page 2: 200, 120 cards each, no
dupes. 5 varied video URLs (embeds — the guest surface while direct pages shell):
2491818, 2913997, 3074115, 1122515, 1231055 — all 200, `div#kt_player` matched 1 each,
stream `get_file/.../{id}.mp4/` → 302 → **206 video/mp4 on all 5**. Related videos:
`related_videos_html/{id}/` endpoint returns 45 `a.player-related-videos-item.kt-api-related-item`
items (fallback wired in `load()`). LoadResponse fields all populated in code (check 6).

Verify notes: `--home-selector p.inf` (the script's regex-DOM card block for the full-card
selector truncates inner text to the quality badge → false title dupes; `p.inf` is clean).
NOTES at run time: no poster/plot on the embed surface (excluded from distinctness); no
quick-search endpoint exists (site search is the only lookup); tags/actors/duration are
further exposed on fully-rendered pages only. `--load-response` lists
recommendations,tags,plot,duration,actors,posters — all present in the Kotlin.

## Tags from embed flashvars + quick-search verdict (issue #275, 2026-09-10 probe)

**Finding 1 — tags on guest-shell video pages.** Direct `/video/{id}/{slug}/` pages are empty
shells again (same shape as #171: 0 flashvars, empty Tags/Models holders). The
`/embed/{id}/` page exposes both keys in its flashvars (live, 2026-09-10):

```
video_categories: 'Milf, Hardcore, Red Head, Lingerie'
video_tags: 'Anna, maria, Busty Redhead, Reverse Cowgirl, ...'   (59 flashvar pairs total)
```

Fix (version 9 → 10): `PorntrexParse.embedTags(document)` parses `video_categories` +
`video_tags` (categories first, deduped); `load()` merges them when the page yields no tags.
The embed page is already fetched for title/poster on shells (#171), so no extra request on
the common path. TDD: new fixture `porntrex_embed_page.html` built from the live
/embed/2913997/ flashvars block (real 59 pairs) + `embedTags` tests in `PorntrexParseTest`
— red → green; `Porntrex:test` and `Porntrex:make` BUILD SUCCESSFUL.
NOTE: plot, duration and actors are NOT exposed anywhere on the embed response (no
`video_duration`, no description key, no model list) — they legitimately stay null on shell
pages, not chased.

**Finding 2 — quick search.** The site has a quick-search endpoint
`/search_results.php?q=...` (easyAutocomplete in main.min.min.js →
`url: "/search_results.php?q="+e`). Probed live with 6 queries (milf, teen, asian,
hardcore, big tits, redhead): HTTP 200 every time, but the returned `"search"` array is
**empty on every query** — the suggestions are albums (`"album"` key, `/albums/...` links),
categories (`"category"`) and models ("model"), i.e. no video links. Albums are not videos:
the provider's `load()` only handles `/video/{id}/` pages, so surfacing album suggestions
would produce dead entries. Verdict: album-suggestion only → `hasQuickSearch = false`
(explicitly set with the recorded reason in Porntrex.kt), no quickSearch override. Recorded
sample shape (q=milf): `{"search": [], "album": [{"text": "Blonde MILF riding cock",
"website-link": "/albums/31600/blonde-milf-riding-cock/", "videos": "1"}, ...]}`.

## Verification (verify.sh, this run, live) — RESULT: PASS

- search `/search/massage/` + async page 2: 200, 85 `p.inf` cards each, no dupes
- home `/categories/busty/` + async page 2: 200, 120 cards each, no dupes
- 5 video URLs via `/embed/{id}/` (2491818, 2913997, 3074115, 1122515, 1231055): all 200,
  `div#kt_player` matched 1 each; streams `get_file/.../{id}.mp4/` → **206 video/mp4 on all 5**
- related videos (separate endpoint surface, no per-URL flag in verify.sh — checked manually
  like the #256 run): `related_videos_html/2913997/` → 200, 45 `a.player-related-videos-item`
  items. NOTE: verify.sh applies `--related-selector` to the --video-url page itself; the
  embed page does not embed the related block, so the selector is omitted from the script run
  and the endpoint is verified by direct request instead.
- LoadResponse fields: recommendations,tags,plot,duration,actors,posters all assigned in Kotlin.
- quick-search check 1b: NOTE (no --quick-search-url) — recorded verdict above: endpoint is
  album-suggestion only, provider `hasQuickSearch = false` by issue-#275 decision.
- Site-native home churn (noted during probing, not a provider defect): the site's own
  pagination repeats 1–5 recent videos between page 1 and page 2 depending on the category
  and fetch moment (teen p1/p2 shared 1 href, teen p1 vs the site's own `/2/` page shares
  the same 1 — confirmed site-side; milf 2, blonde 3, hardcore 5, amateur 43; busty and
  webcam sampled clean this run). getMainPage is untouched by this PR.

## Issue #309 — duration selector missed the live .video-info stats row (2026-09-11 audit)

**Finding (audit, checked against code).** `durationOf` scoped the clock badge to
`div.block-details`, but on the fully-rendered video DOM the stats row lives in the
`.video-info` header block (calendar / eye / clock badges), and `div#tab_video_info >
.block-details` starts *after* it and contains Models/Categories/Tags/Description only —
no `fa-clock-o`. So duration was null on every real page; the unit test only passed because
the fixture put the badge inside a made-up `div.block-details`. Navbar-"Latest" guard
(issue #215) verified still safe: the parse still requires `em.badge`, absent from the
navbar `<a>`.

**Fix (version 10 → 11).** `durationOf` selector widened to
`div.block-details div.item span:has(i.fa-clock-o) em.badge, .video-info .item span:has(i.fa-clock-o) em.badge`
(both locations, block-details first — old behavior preserved). Fixture
`porntrex_video_page.html` regenerated to the audit's real stats-row DOM: badge under
`.video-info`, block-details = Models/Tags/Description only, navbar clock still present.
Tests: `duration parsed from video-info stats row` (red→green, 50min 55sec = 3050s),
inline block-details variant kept green, navbar-garbage test kept green.
`Porntrex:test` and `Porntrex:make` BUILD SUCCESSFUL.

**Live verification (2026-09-11, this run) — PARTIAL.**
- Direct `/video/{id}/…` pages currently serve the degraded ad shell to guests (the known
  Correctness+Drift issue): 5 probed (3324609 slug 404s, 1311225, 1449881, 1950308,
  1888890, 1888347) return 200 but the homepage-shaped shell — empty `p.title-video`,
  no stats row, no flashvars. Duration is null on shells regardless (stats row absent);
  the selector fix becomes visible the moment full-page serving resumes — unit-fixture
  per the audit's full-page snapshot is the proof of the DOM shape.
- verify.sh run on the embedded surface (per #275 methodology): search page 1+2 85 cards
  each no dupes; home 120 cards; 3 embeds `div#kt_player` 1×; all 3 `get_file` streams
  **206 video/mp4**; LoadResponse fields recommendations,tags,plot,duration,actors,posters
  all assigned (`duration` from both selector locations). RESULT: FAIL only on the
  documented **site-native home churn** (2 cards shared between front page and async page 2,
  site-side reshuffle — same finding as the #275 run) plus shell serving; everything
  else PASS.
- Endpoints used: search async `block_id=list_videos_videos&from=2` works (85 page-2
  cards); home async `block_id=list_videos_common_videos_list_norm&from=2` works.
