# FINDINGS — neporn.com

## Engine fingerprint
KVS (Kernel Video Sharing), "metal" theme — evidenced by `list_videos_most_recent_videos_items` block ids, `kt_player`, `flashvars` JS object with `license_code`/`lrc`/`video_url`, and `get_file/...` stream URLs. Identical layout to watchporn.to (existing WatchPorn provider).

## Search
- `https://neporn.com/search/{query}/` → 200, 24 video links. WORKS.
- `https://neporn.com/search/{query}/2/` → 404. Does NOT work.
- Pagination of search results uses KVS async pattern (same engine as WatchPorn): `/search/?q={query}&mode=async&function=get_block&block_id=list_videos_videos_list_search_result&from_videos={page}` — same block_id naming family as homepage (`list_videos_most_recent_videos`).

```
$ curl -s -A "Mozilla/5.0" https://neporn.com/search/wet/ | grep -oE '/video/[0-9]+/[a-z0-9-]+/' | head -3
/video/33918/sexy-flawless-horny-chick-vibrates-her-creamy-pussy/
/video/33917/hot-blue-eyed-babe-rubbing-her-clit-into-sensation/
/video/33922/pawg-latina-masturbating-with-her-big-dildo/
```

## Video pages
Item cards (home, listings, related): `div.item` → `a[href*=/video/]` with `title=` attr, `strong.title`, `img.thumb` (`src` / `data-webp`), `div.duration`.

Video page `https://neporn.com/video/{id}/{slug}/` probed (5): 41865 (home), 515 (home), 42779 (home), 33918 (search), plus related links on each page.
- Title: `<h1>Video: {title}`
- Poster: `meta[property=og:image]` → `https://cdn.neporn.com/contents/videos_screenshots/41000/41865/preview.jpg`
- Categories: `div.info-content a[href*=/categories/]`
- Tags: `div.info-content a[href*=/tags/]`
- Duration: `div.info span:contains(Duration:) em` → "30:49"

## Related videos
`#list_videos_related_videos_items` present on all probed video pages (same `div.item` cards). Not "no related" — it exists.

## Stream sources (per video page)
Single source per page, KVS `flashvars.video_url` — plain text in the raw HTML (no JS obfuscation, no WebView needed):
`video_url: 'https://neporn.com/get_file/5/{hash}/41000/41865/41865_720p.mp4/?v-acctoken=...'`
No `video_alt_url` on any probed page (single 720p quality; postfix `_720p.mp4`).

Verification (follows 302 → data00N.neporn.com/remote_control.php):
```
$ curl -s -o /dev/null -A "Mozilla/5.0" -L -r 0-1000 "$URL" -w "%{http_code} %{content_type}\n"
206 video/mp4
```

## Headers / referer
Plain `Mozilla/5.0` UA sufficed for get_file and redirect target; no referer required (302→206 without `-e`).

## Pagination
Home listings: `/{path}/{page}/` suffix (e.g. `/latest-updates/2/` → 200, different items 43319/43323/43324).

## Risks / blockers
None. No Cloudflare, no age wall; runner IPs work.
