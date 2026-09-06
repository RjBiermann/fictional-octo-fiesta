# FINDINGS — XVideos

Probed 2026-09-06 per site-probe skill. Engine: **custom** (xvideos-style), server-rendered
(SPA frontends like hclips were ruled out). SpankBang was probed first: Cloudflare challenge
(`cf-mitigated: challenge`, HTTP 403) — Blocked from datacenter IPs, skipped for this tracer.

## Engine fingerprint

- `html5player` JS var present in video pages; `xvideos-cdn.com` asset domains
- Server-rendered listing: 48 `div.frame-block.thumb-block` items on the homepage
- Not KVS (`kt_player`/`flashvars` absent), not WP (`wp-content` absent)

Evidence: `grep -o html5player /tmp/xv.html` → match; homepage item count above.

## Search

- **Works:** `https://www.xvideos.com/?k={query}` → HTTP 200, 27 `thumb-block` items for
  `?k=amber+leaves`
- **Dead:** `https://www.xvideos.com/search/{query}/` → HTTP 404
- Query separator: `+`. Pagination: `&p={n}` (see Pagination).

Transcript:
```
$ curl -s -w "%{http_code}" "https://www.xvideos.com/?k=amber+leaves" | grep -c thumb-block
200 … 27
$ curl -s -o /dev/null -w "%{http_code}" "https://www.xvideos.com/search/amber+leaves"
404
```

## Video page

- URL shape: `/video.{eid}/{slug}_` — eid is a short base-ish id (`video.uemmmcm1cc5`), slug
  trailing underscore is part of the canonical URL
- **JSON-LD block carries everything** (`<script type="application/ld+json">`, `@type:
  VideoObject`): `name`, `description`, `thumbnailUrl[]`, `uploadDate`, `duration` (ISO-8601
  `PT00H10M25S`), `contentUrl` (the stream)

Transcript:
```
$ python3 -c "json.loads(ld)" →
name : Amber Stark & Seth Brogan Fuck As Soon As His Wife Leaves The House!
thumbnailUrl : ['https://thumb-cdn77.xvideos-cdn.com/f1c023eb-0e7e-405b-be28-83d82d9af752/0/xv_27_t.jpg']
uploadDate : 2024-09-03T22:00:00+00:00
duration : PT00H10M25S
```

- Tags/categories: none found on the video page (`/tags/` links absent) — skip tags.
- Duration parse: ISO-8601 `PT00H10M25S` → 10 min.

## Listing item structure (search + homepage share it)

```
div.frame-block.thumb-block
  div.thumb-inside > div.thumb > a[href=/video.{eid}/{slug}_]
    img[data-src=<poster cdn url>]          ← lazyload; src is a blank gif
    span.top-right-tags > span.video-hd-mark  ← e.g. "1080p"
  div.thumb-under > p.title > a[href][title=<full title>]
```

Transcript: `grep -oE 'class="thumb-block' /tmp/xv_s1.html | wc -l` → 27; block dump in probe
log shows `data-src` + `p.title > a[title]` as above. Use `data-src` (not `src`) for posters.

## Stream source

- Direct MP4 from JSON-LD `contentUrl`: `https://mp4-cdn77.xvideos-cdn.com/{uuid}/{n}/video_{quality}p.mp4?secure={token}`
- Signed (`?secure=`) ⇒ URLs expire; always re-derive at load time, never cache across sessions
- HLS: none offered for this video (`"hls":` absent) — MP4 only
- Verified:

```
$ GET contentUrl (Range: 0-64)
STATUS: 206   Content-Type: video/mp4   Content-Range: bytes 0-64/34131732
```

## Headers / referer

- Stream serves **without** referer: `no-referer -> 200 video/mp4` — plain UA suffices
- Site pages: plain browser UA required (403-cloudflare only on SpankBang, not here)

## Pagination

- `?k={query}&p={n}`; `p=1` returns HTTP 200, 27 items, item ids differ from page 0:
  transcript: `diff` of first 5 `/video.{eid}/` hrefs → DIFFERENT
- Homepage listing presumably uses the same `&p=` — unconfirmed, search pagination confirmed

## Risks / blockers

- Signed stream URLs expire — resolve fresh on every `loadLinks`
- Age-consent wall can appear for some regions/IPs (not seen from this machine; watch for
  `disclaimer` interstitials in CI)
- Lower value from CI: sites sometimes serve degraded quality to bots — in-app test remains
  the final word
