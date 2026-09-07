# FINDINGS — neporn.com (2026-09 audit)

## Verdict: OK

## Search
- `/search/red/` returns empty (no 'red' content — legit), `/search/anal/` fine: `div.list-videos div.item` ✓; async page-2 URL works.

## Stream
- video page flashvars `video_url: 'https://neporn.com/get_file/7/.../39025_720p.mp4/?v-acctoken=...'` → 302 → `https://data003.neporn.com/remote_control.php?...` → **206 video/mp4**.
