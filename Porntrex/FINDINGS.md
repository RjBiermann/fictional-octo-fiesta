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
  `a.player-related-videos-item.kt-api-related-…` items (23 KB). Existing shell-page
  selector (`div.video-list div.video-item`) returns nothing now; recommendations may be
  empty until/unless re-wired to this endpoint (not done — out of scope for this fix).

## Risks / blockers
- None blocking. If guest shells ever regress to requiring login, /embed/ is the
  remaining guest surface.
