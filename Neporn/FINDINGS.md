# FINDINGS — neporn.com (2026-09 audit)

## Verdict: OK

## Search
- `/search/red/` returns empty (no 'red' content — legit), `/search/anal/` fine: `div.list-videos div.item` ✓; async page-2 URL works.

## Stream
- video page flashvars `video_url: 'https://neporn.com/get_file/7/.../39025_720p.mp4/?v-acctoken=...'` → 302 → `https://data003.neporn.com/remote_control.php?...` → **206 video/mp4**.

## Update — issue #130 Data-completeness probe (video/39025/wright-for-anal)
- plot: `meta[property=og:description]` ✓ (transcript: content="The adorable Whitney Wright joins Hard...")
- year: schema.org ld+json `"uploadDate": "2026-03-18T17:35:00Z"` ✓ (regex on script[type=application/ld+json])
- actors: `div.info-content a[href*=/models/]` → Whitney Wright, Ramon Nomar ✓
- streams unchanged; verify.sh PASS (search 200/24 items, 2 video pages 200 + 206 video/mp4, all LoadResponse fields assigned).
