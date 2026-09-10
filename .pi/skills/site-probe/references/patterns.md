# Engine patterns

Fingerprints to check on the homepage HTML, most common first. Same engine ⇒ same
search/stream layout; a positive fingerprint tells you which patterns to try in steps 2–4 of
the probe.

## KVS / Kernel Video Sharing (most common tube engine)

Evidence (any two = near-certain):
- `kt_player` or `kvs_player` in inline JS, a `flashvars` object on video pages
- Container ids like `list_videos_videos_list_search_result_items`, `#list_videos_common_videos_list_items`
- Asset paths under `/contents/videos_screenshots/`, `/contents/sources/`
- `<meta name="generator" content="Kernel Video Sharing">` (sometimes present)

Usual layout:
- Search: `/search/{query}/` (HTML, `.item` blocks) — also try `/{search}/{query}/`
- Video page: inline `flashvars = {...}`; stream in `video_url` (URL-encoded or encrypted —
  `video_url_text`/`license_code` + `video_skipon`/`video_timeline` hint at encrypted KVS,
  decode with the known KVS algorithm), fallback `video_alt_url`
- Categories/duration: `.item .title`, `.item .duration`, thumbnails `data-src` (lazyload)

In-repo reference: FreePornVideos, Porntrex, PornHits.

## WP video themes (WP-Script family: Retrotube, Plus, Retro)

Evidence:
- `/wp-content/`, `/wp-json/` in HTML
- `.video-thumb`, `.post-thumbnail`, `.entry-title` blocks
- Search: `/?s={query}` (plain WP), pagination `/page/N/?s=…`

Streams usually arrive as embed iframes to an aggregator domain — record the embed pattern and
check the repo's extractors for that domain before planning anything new.

In-repo reference: Cat3Movie, JavGuru.

## Big custom engines

These are their own engines — do not force them into KVS/WP patterns:

- **xvideos/xvideos-style**: inline `video-data` / `setVideoUrlHigh(...)` JS, `html5player` var;
  search `/search/{query}/{page}/` (or `/?k=`); streams often direct mp4 with mobile variants.
- **spankbang-style**: Laravel app; `csrf-token` meta; video data in a JS object near the player
  (`<video>` + `source` tags or a JSON blob); search `/{query}/search/` or `/s/{query}/`.
- **pornhub-style**: `flashvars` with `mediaDefinitions` JSON (URL-encoded), age/consent walls,
  aggressive bot detection.
- **missav-style**: packed JS (`eval(function(p,a,c,k,e,d)…`), m3u8 behind a decode step;
  in-repo reference exists (MissAV) — read its Kotlin before probing a similar site.

## Universal checks

- `curl -sIL <url>` before parsing: 403/challenge ⇒ Cloudflare or bot wall; record under
  Risks/blockers, don't fight it.
- `og:video`, `og:image`, `og:title` meta tags are the cheapest title/poster/preview source.
- Stream URLs appearing URL-encoded or base64: decode, then verify with a real request +
  content-type check. A player URL that returns HTML is not the stream.
- Lazyload images: real URL lives in `data-src` / `data-original`, not `src`. `data:` URIs and
  placeholder pixels are not posters — grab the lazyload source or omit the poster.
- Age/consent walls: try the unlock cookie/param (`age_verified`, `ageGate=passed`) before
  declaring Blocked; record the unlock — an unpassable wall goes under Risks/blockers.
- Record the runner's IP country once (`curl -s https://ipinfo.io/country`): catalogs and
  streams may be geo-gated, and FINDINGS must say which region it describes.
- A stream that 30x-redirects: record both the original and final URL; send referer/UA to the
  host you call, not the redirect destination.
