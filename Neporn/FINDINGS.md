# FINDINGS — neporn.com (2026-09 audit)

## Verdict: OK

## Search
- `/search/red/` returns empty (no 'red' content — legit), `/search/anal/` fine: `div.list-videos div.item` ✓; async page-2 URL works.

## Stream
- video page flashvars `video_url: 'https://neporn.com/get_file/7/.../39025_720p.mp4/?v-acctoken=...'` → 302 → `https://data003.neporn.com/remote_control.php?...` → **206 video/mp4**.

## Verify.sh transcript (prior audit, corroborates OK verdict — no code change)
Ran `.pi/skills/verify-provider/scripts/verify.sh` with 6 varied video URLs (search + home listings), `--search-selector 'div.list-videos div.item'`, `--stream-selector 'div.player'`, `--related-selector 'div.list-videos div.item'`:

```
── check 1: search page
GET https://neporn.com/search/wet/ → 200; 'div.list-videos div.item' matches: 24
── check 2: video pages + streams (6 URLs)
GET https://neporn.com/video/43371/presley-maddox3/ → 200; 'div.player' matches: 3
GET ... → 'div.list-videos div.item' matches: 22; GET stream → 206 video/mp4
GET https://neporn.com/video/43347/angela-white-blowbang/ → 200; 'div.player' matches: 3 → 206 video/mp4
GET https://neporn.com/video/43349/alexis-texas-jayden-jaymes-drowning-in-big-booty/ → 200; → 206 video/mp4
GET https://neporn.com/video/33918/sexy-flawless-horny-chick-vibrates-her-creamy-pussy/ → 200; → 206 video/mp4
GET https://neporn.com/video/34087/big-oily-ass-brunette-riding-her-fake-cock/ → 200; → 206 video/mp4
GET https://neporn.com/video/34117/horny-babe-tease-seductive-self-love/ → 200; → 206 video/mp4
RESULT: PASS
```

Related-videos check: every probed video page contains a `list_videos_related_videos_items` block wrapped in `div.list-videos` (22 `div.item` cards each), so the `div.list-videos div.item` related selector is justified.
