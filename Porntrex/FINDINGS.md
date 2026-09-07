# FINDINGS — porntrex.com (2026-09 audit)

## Verdict: OK

## Search
- `/search/red/` → 200, `div.video-preview-screen.video-item` items (85 matches incl. CSS, cards included).

## Stream
- flashvars `video_url:https://www.porntrex.com/get_file/.../1477878.mp4/` → follows with Referer → **206 video/mp4**.

## Actors (2026 fix, issue #132)
- Video page `#tab_video_info div.block-details` has `div.item` with `span.title-item "Models:"` →
  `div.items-holder a` (e.g. `https://www.porntrex.com/models/katee-owen/` → "Katee Owen").
  Verified on /video/3292121/... (transcript in verify log). Extracted via
  `div.block-details div.item:has(span.title-item:contains(Models:)) div.items-holder a`.
- No upload date / year on the page (confirmed absent in audit #97); year remains unset.
