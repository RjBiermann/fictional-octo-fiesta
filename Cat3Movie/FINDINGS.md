# FINDINGS — cat3movie.org (Cat3Movie)

## Engine fingerprint
WordPress + commercial "halimmovies" movie theme (Yoast SEO, `wp-content/themes/halimmovies/`,
`halim_cfg` JS config, `player.php` ajax player, jwplayer-8.9.3 / hls.js). Evidence:

```
$ curl -s https://cat3movie.org/ | grep -o 'halim_cfg = {[^,]*' | head -1
halim_cfg = {"act":"","post_url":"https:\/\/cat3movie.org\/watch-special-delivery-1959",...
$ grep -o 'themes/halimmovies[^"]*' (homepage) -> /wp-content/themes/halimmovies/halim-ajax.php
```

## Search
WP-style search: `/?s={query}` 302-redirects to `/search/{query}` which returns results.

```
$ curl -s -A "Mozilla/5.0" "https://cat3movie.org/search/delivery" | grep -c '<article' ; grep -o '<a href="https://cat3movie.org/[a-z0-9-]*" title="[^"]*"' | head -3
6
<a href="https://cat3movie.org/the-fourth-body-2004" title="The Fourth Body (2004)"
<a href="https://cat3movie.org/3-d-sex-and-zen-extreme-ecstasy-2011" title="3-D Sex and Zen..."
```

Tested and rejected: `/?s=` raw (302, use /search/), `/search/{q}/page/2` → **404 (no search
pagination)**. Empty/gibberish queries return 3 "suggestions" articles, not an error — acceptable.

## Video pages
Slug URLs, no numeric id in path. Probed (from home listing, a category listing, and related sections):

- https://cat3movie.org/joy-1983 (home) — 200
- https://cat3movie.org/heat-1986 (home) — 200
- https://cat3movie.org/baby-cat-1983 (home nav) — 200
- https://cat3movie.org/bamboo-house-of-dolls-1973 (classic-porn category) — 200
- https://cat3movie.org/the-sadist-of-notre-dame-1979 (related section of joy) — 200
- https://cat3movie.org/special-delivery-1959 (home) — 200 (streams broken, see Risks)

Movie page selectors (verified on joy-1983):

```
title    : h1.entry-title                      -> "Joy (1983)"
poster   : meta[property=og:image]             -> https://cat3movie.org/wp-content/uploads/2026/08/joy-1983-34545-300x450.jpg
year     : p.released a[href*=/release/] text  -> "1983"
tags     : p.category a                        -> "Classic Erotica"
country  : p.actors:containsOwn(Country) a
actors   : p.actors:containsOwn(Actors) a      -> Agnès Torrent, ...
plot     : div.entry-content article.item-content p (fallback og:description)
post_id  : halim_cfg "post_id":34545           (also data-post_id / body data-nonce)
nonce    : body[data-nonce="ee7f5efa13"]-style attr: data-nonce="[a-f0-9]{10}"
watch url: halim_cfg post_url -> https://cat3movie.org/watch-joy-1983 ; episode pages /watch-<slug>/full-svN.html
```

## Related videos
Present: `section.related-movies article.thumb` with `a.halim-thumb[href][title]` and
`img[data-src]`.

```
$ grep -o 'section class="related-movies"' joy.html ; grep -o '<a class="halim-thumb" href="https://cat3movie.org/[^"]*" title="[^"]*"' joy.html | head -2
<a class="halim-thumb" href="https://cat3movie.org/the-sadist-of-notre-dame-1979" title="The Sadist of Notre Dame (1979)"
```

## Stream sources (per video page)
There are **no direct `<video>`/mp4/m3u8 on any page**. Streams come from the theme's player
loader: `GET https://cat3movie.org/wp-content/themes/halimmovies/player.php?episode_slug=full&server_id=N&subsv_id=&post_id=<id>&nonce=<nonce>&custom_var=`
with header `Referer: https://cat3movie.org/watch-<slug>/full-svN.html`. It returns HTML:

```
$ curl -s -A "Mozilla/5.0" -H "Referer: https://cat3movie.org/watch-joy-1983/full-sv1.html" "https://cat3movie.org/wp-content/themes/halimmovies/player.php?episode_slug=full&server_id=1&subsv_id=&post_id=34545&nonce=89b086faf9&custom_var="
<div class="embed-responsive embed-responsive-16by9"><iframe class="embed-responsive-item" src="https://hlsfree.com/embed/hls/971" allowfullscreen></iframe></div>
```

Server→embed mapping (verified, GET; NOTE: server_id ordering varies per movie):
- sv1 (usually): `https://hlsfree.com/embed/hls/<id>` — **works**
- sv2/sv3: `https://cdn.loadvid.com/videos/play/<hash>` and `https://hlsfast.com/#<hash>` — see below.

**hlsfree (sv1) — verified end to end:**
1. embed page requires Referer `https://cat3movie.org/` else 403 "Access Denied / domain not authorized":
```
$ curl -s -A "Mozilla/5.0" "https://hlsfree.com/embed/hls/971" | grep -o 'defaultHlsUrl = "[^"]*"'
const defaultHlsUrl = "https://hlsfree.com/api/hls/serve?token=949554948cf4";
```
2. token endpoint → HLS playlist (**send `Referer: https://hlsfree.com/`** — without it the
   endpoint returns 500 `Proxy error`; the Kotlin sets it on the emitted link)
```
$ curl -s -A "Mozilla/5.0" -H "Referer: https://hlsfree.com/" "https://hlsfree.com/api/hls/serve?token=949554948cf4"
HTTP 200, content-type: application/vnd.apple.mpegurl
#EXTM3U / #EXT-X-VERSION:3 / segments at https://s1.cat3hls.com/...
```
3. segment fetch: HTTP 200, ~2.5 MB binary (mislabeled `image/png` content-type, plays fine).
Same flow verified for hls 741 (baby-cat), 984 (bamboo), 966 (heat), 971 (joy).

**2026-09-14 re-probe (issue #143):** server mapping surveyed across 10+ videos (home,
/classic-porn/page/8, related listings). Typical mapping is sv1=hlsfree, sv2=loadvid,
sv3=hlsfast (e.g. baby-cat-1983, bamboo-house-of-dolls-1973, blue-money-1972,
a-bloody-fight-1988, 3-d-sex-and-zen-extreme-ecstasy-2011, curse-of-the-dog-god-1977,
dannoura-yomakura-kassenki-1977) — the hlsfree path is alive and verified end to end.
Some older archive movies (e.g. carry-on-teacher-1959, dark-dreams-1971) have hlsfast on
**every** server: for those no stream is resolvable (see below) — a site-side limitation,
not a provider defect.

**hlsfast re-check (issue #143):** `hlsfast.com/#<id>` is an obfuscated Vue SPA
(`/assets/index-DqFBtoPY.js`, vidstack, player-version 16.5.3) that calls
`/api/v1/player?t=<encrypted>` / `/api/v1/video?id=` — raw ids return `{"error": "Token is invalid"}` /
`{"message": "Request is invalid"}`; the `t` token is generated inside obfuscated JS
(aes/crypto strings present) and is not reproducible server-side. **loadvid (sv2):** page exposes `videoToken` + csrf; stream is `POST /videos/resolve-token`
which returns the **m3u8 body** (no URL) for a blob player — no stable stream URL to emit;
skipped. **hlsfast (sv3):** `/api/v1/video?id=` returns encrypted hex blobs (obfuscated
vidstack player); not decryptable cheaply; skipped.

## Headers / referer
- cat3movie.org pages: plain requests, browser UA, no referer needed.
- player.php: needs `Referer: https://cat3movie.org/watch-<slug>/full-svN.html` (send it).
- hlsfree embed page: needs `Referer: https://cat3movie.org/` (403 without).
- hlsfree token/serve: needs `Referer: https://hlsfree.com/` (500 `Proxy error` without); segments: none needed.

## Pagination
Home/categories: `/{base}/page/{N}` suffix (page 1 = base). Verified different posts on
/classic-porn vs /classic-porn/page/2 and / vs /page/2. Search: **no pagination** (page 2 → 404).

## Risks / blockers
- **Cloudflare in front** caches player.php GET responses aggressively; some URLs are
  CF-cached 404 (e.g. special-delivery-1959 sv1/sv2/sv3 — movie appears broken site-wide,
  its player.php URLs return cached 404 even with fresh nonce). Retry logic: try sv1..sv3.
- hlsfree embed domain check can deny new referer domains → keep cat3movie.org referer.
- No duration/quality metadata anywhere on the site (movies, not tube clips).
