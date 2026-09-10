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

### hlsfree chain update (issue #247, 2026-09-10 re-probe)

- embed `https://hlsfree.com/embed/hls/<id>`: **403 without any referer** (200 with any
  Referer value) — dedup of FINDINGS above is still true for the page fetch.
- token playlist `https://hlsfree.com/api/hls/serve?token=<hex>`: 200 `#EXTM3U` with referer.
  Tokens rotate per embed-page load; 403/500/429 means dead/rate-limited token.
- segments: playlist entries point to `https://s1.cat3hls.com/<pair>/<item>.{js|css|png|jpg}`
  (574 segments, VOD). They are **MPEG-TS camouflaged as asset files** — Cloudflare WAF on
  the segment CDN 403s without `Referer: https://hlsfree.com/` and 200s with it; body is
  ts packets despite `Content-Type: image/png`.
- In-app symptom: handing the bare token URL to the player dies as ExoPlayer
  `ERROR_CODE_IO_BAD_HTTP_STATUS` (2004) whenever any leg returns non-2xx. Fix (provider):
  preflight the manifest in the extractor, retry once with a fresh embed token, emit the
  link with `referer` **and** an explicit `Referer` header map entry, and drop dead sources.

<details><summary>original stream-source notes (kept from earlier runs)</summary>
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

**hlsfast re-check (issue #143):** obfuscated Vue SPA — see issue #180 re-probe below for the
now-working extraction.

## Headers / referer
- cat3movie.org pages: plain requests, browser UA, no referer needed.
- player.php: needs `Referer: https://cat3movie.org/watch-<slug>/full-svN.html` (send it).
- hlsfree embed page: needs `Referer: https://cat3movie.org/` (403 without).
- hlsfree token/serve: needs `Referer: https://hlsfree.com/` (500 `Proxy error` without); segments: none needed.

## Pagination
Home/categories: `/{base}/page/{N}` suffix (page 1 = base). Verified different posts on
/classic-porn vs /classic-porn/page/2 and / vs /page/2. Search: **no pagination** (page 2 → 404).

## hlsfast extraction (issue #180, 2026-09-08 re-probe)

player.php now serves `https://hlsfast.com/#<hash>` embeds (typically sv3; some archive
movies on every server) alongside the still-working `hlsfree.com/embed/hls/<id>` (sv1)
and loadvid (sv2). Embed survey:

```
special-delivery-1959            sv1=hlsfree/993      sv2=loadvid  sv3=hlsfast/#iuba9o
3-d-sex-and-zen-...-2011         sv1=hlsfree/988      sv2=loadvid  sv3=hlsfast/#pn6yjv
blue-money-1972                  sv1=hlsfree/667      sv2=loadvid  sv3=hlsfast/#6khwsr
curse-of-the-dog-god-1977        sv1=hlsfree/953      sv2=loadvid  sv3=hlsfast/#3fu6z9
bamboo-house-of-dolls-1973       sv1/sv2 CF-cached 404, sv3=hlsfast/#o3iz8s (video deleted upstream)
```

hlsfast is a vidstack SPA (`assets/index-DqFBtoPY.js`, player-version 16.5.3). The SPA's
string-table decoder is trivial (`pe(i) = table[i-397]` after a fixed array rotation), so the
flow is fully reproducible server-side:

1. `GET https://hlsfast.com/api/v1/video?id=<hash>&w=<screenW>&h=<screenH>&r=cat3movie.org`
   with header `Referer: https://hlsfast.com/` (**required** — without it: 404
   `{"message":"Video not found or deleted"}`). Response is an AES-CBC encrypted **hex** blob.
2. Key/IV are constants generated in the obfuscated JS (`Z()`/`J()`), independent of video:
   key `kiemtienmua911ca`, iv `1234567890oiuytr` (AES-128-CBC, PKCS#5).
3. Decrypted JSON contains `cfNative` (proxied through hlsfast.com, **preferred**) and
   `source` (direct server IP). Also `player.restrictEmbed`: `["cat3movie.org","moviecat3.com"]`
   — the `r=` param must be the cat3movie.org referer domain.

```
$ node: decrypt(api/v1/video?id=iuba9o...) -> {"source":"https://94.131.217.174/v4/.../master.m3u8?...",
   "cfNative":"https://hlsfast.com/v4/pl/sn3k.evercresthospitality.space/3ae/iuba9o/master.1788349964.m3u8?k=...&kx=..."}
$ curl -s -A UA -H 'Referer: https://hlsfast.com/' '<cfNative>'
HTTP 200 application/vnd.apple.mpegurl
#EXTM3U / #EXT-X-STREAM-INF ... RESOLUTION=718x478
```

**End-to-end chain test (player.php → embed → m3u8, emulating the fixed Kotlin):**

```
special-delivery-1959:            sv1 hlsfree m3u8 200 mpegurl; sv3 hlsfast m3u8 200 mpegurl
3-d-sex-and-zen-...-2011:         sv1 hlsfree m3u8 200 mpegurl; sv3 hlsfast m3u8 200 mpegurl
blue-money-1972:                  sv1 hlsfree m3u8 200 mpegurl; sv3 hlsfast m3u8 200 mpegurl
curse-of-the-dog-god-1977:        sv1 hlsfree m3u8 200 mpegurl; sv3 hlsfast m3u8 200 mpegurl
the-sadist-of-notre-dame-1979:    sv3 hlsfast m3u8 200 mpegurl (sv1/sv2 CF-cached 404)
bamboo-house-of-dolls-1973:       sv3 hlsfast id o3iz8s → "Video not found or deleted"
                                  (video genuinely deleted upstream — site-side gap)
```

Implementation note: `/api/v1/player?t=` telemetry and P2P/WebTorrent delivery are not
needed — `cfNative` is a plain HLS master playlist served from hlsfast.com with
`Referer: https://hlsfast.com/`.

## Risks / blockers
- **Cloudflare in front** caches player.php GET responses aggressively; some URLs are
  CF-cached 404 (e.g. special-delivery-1959 sv1/sv2/sv3 — movie appears broken site-wide,
  its player.php URLs return cached 404 even with fresh nonce). Retry logic: try sv1..sv3.
- hlsfree embed domain check can deny new referer domains → keep cat3movie.org referer.
- No duration/quality metadata anywhere on the site (movies, not tube clips).

## 2026-09-09 re-probe (issue #203 — duplicated homepage cards)
The halimmovies homepage renders the 10 newest posts **twice** on `/` — a "Latest" top strip
(`article.thumb.grid-item post-N`, no col classes) plus the main archive grid
(`article.col-md-3 … post-N`) — and one legacy post (`post-15919`) **3×**. A handful of
articles carry no `a.halim-thumb` link (widget frames). Raw shape today:

```
$ curl -s -A "Mozilla/5.0 Chrome/124" https://cat3movie.org/ -o home.html
$ grep -c 'article\b[^>]* thumb' home.html        → 61 article.thumb
$ grep -o 'a class="halim-thumb" href="…"' → 58 hrefs, 48 unique
$ grep -o 'article\b[^>]* thumb' home.html | sort | uniq -c | sort -rn  → 10 posts ×2 (newest 10), post-15919 ×3
```

Kotlin fix (issue #203): `Parse.homeCards()` dedupes homepage cards by `a.halim-thumb` href
(fields without that link are dropped); search keeps `Parse.searchCards()` untouched —
`/search/delivery` has no duplicates (6 cards, 6 unique hrefs, re-checked today).

Homepage pagination: **homepage rows do not paginate today** — `/page/2` (and `/page/3`) served
by Cloudflare with `cf-cache-status: HIT` return the *same 51 grid items as `/`*;
category pages still paginate cleanly (`/classic-porn` vs `/classic-porn/page/2` grids differ).
Homepage page-2 duplication is a site-side cache/shape artifact, not a provider drift — noted
for verify.sh (`--home-url` page 1 only).

Stream chain re-verified live today (unchanged, post #180; player.php is JS/AJS-gated and
leaves no m3u8 in page HTML — verify.sh check 3 cannot mechanically resolve it here):

```
the-sex-killer-1965          post=34572 nonce=cec74c215e
  sv1 hlsfree/1007 → defaultHlsUrl token → GET /api/hls/serve?token → HTTP 200 application/vnd.apple.mpegurl #EXTM3U
  sv3 hlsfast/#3fn91p → api/v1/video AES-CBC(key=kiemtienmua911ca, iv=1234567890oiuytr) → cfNative m3u8 → HTTP 200 mpegurl
  sv2 loadvid (blob-gated, as before)
doctr-francoise-gailland-1976  sv1 hlsfree/991 → HTTP 200 mpegurl; blue-money-1972: sv1 200, sv3 cfNative → 200
```

NOTE: one openssl typo in the first chain sweep printed `000` for sv3 — the corrected run
(python JSON `cfNative`) fetches HTTP 200 `application/vnd.apple.mpegurl` for all sv1+sv3.

## Host-registry refactor verification (issue #169, this run)
Search: `https://cat3movie.org/?s=teacher` → 200, 6 articles (`div.thumb.grid-item.post-N`, `a.halim-thumb` hrefs, no trailing slash). curl (runner IP) gets CF-blocked empty body → urllib with full mobile UA gets 200 (69 KB).
Stream chain replicated manually (5 movies): player.php?episode_slug=full&server_id=1..3&post_id&nonce via XHR headers → iframe src = `https://hlsfree.com/embed/hls/875` / `/856` (2), hlsfast `#<hash>` (2; API 200/AES path intact — inline AES stays), 1 dead upstream.
HlsFree chain: embed page → `defaultHlsUrl … token=<hex>` → `GET /api/hls/serve?token` → **200 `application/vnd.apple.mpegurl` #EXTM3U, 2/2**. Shared `HlsFree` adapter in `com.kraptor.HostAdapters` — provider code replaced by `loadExtractor(embed, …)`.
</details>
