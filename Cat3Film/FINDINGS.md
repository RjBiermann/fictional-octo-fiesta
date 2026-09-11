# FINDINGS — cat3film.com

## Engine fingerprint
Custom Go/template engine (not KVS, not WP). Server-rendered HTML with `class="card"` grid,
JW Player (cdn.jwplayer.com) on the watch page, internal JSON API `/api/v1/...`,
AJAX search at `/_ajax/search`. cf-ray header ⇒ behind Cloudflare.

## Search
UI search is AJAX: `GET /_ajax/search?q={query}` → JSON. `/search?q=` and `/search/{q}/` are 404.
```
$ curl -s "https://cat3film.com/_ajax/search?q=handmaiden" -A "Mozilla/5.0"
{"results":[{"format":"Movie","slug":"the-handmaiden","thumb":"/uploads/672b550f7349ff8432a3.jpg","title":"The Handmaiden","year":2016}]}
```
Single page, no pagination on search.

## Video pages
Detail page: `/{slug}` (slug only, no numeric id in URL; internal id is in `data-movie`).
Watch page: `/watch/{slug}?sv=1&part=1` (movies) or `?sv=1&ss=1&ep=1` (series).

Probed pages:
- `/draft-1669` (Youtopia, from home rank grid) — 200, og:type video.movie, ld+json Movie with duration `PT94M`, genre Drama, year 2018, poster `/uploads/48dfc3a86d54bdb94ed7.jpg`
- `/impregnation-nation` (from /movies listing) — movie=778, ep=886
- `/russian-lolita` (from related-videos of draft-1669) — movie=294, ep=307
- `/the-handmaiden` (from search) — movie=350, ep=405
- `/jailhouse-wardress` (from /movies?page=2) — movie=1637, ep=1765
- `/hache` (TV series, from /tv-series) — movie=394, episodes 449..452+ (`data-ep` buttons)

Card selector (home, /movies, /tv-series, related): `a.card` with `href="/{slug}"`,
poster `img[src]` under `.poster-box` (lazyload uses plain `src` + srcset), title `.card-title`.
Detail metadata: `h1.info-title`, badges `.badges .badge` (year is `<a href="/year/..">`),
genres `.info-people` with label "Genre:" links, ld+json `application/ld+json` first block has
Movie object: description, duration (ISO-8601), genre[], datePublished, image, name.

## Related videos
`section#related .grid a.card` — present on detail pages (verified on /draft-1669: first card `/russian-lolita`).

## Stream sources (per video page)
All sources come from an API call the JW Player page makes:
```
GET /api/v1/episodes/{episodeId}/sources   (episodeId from .epbtn[data-ep] on the watch page)
Referer: https://cat3film.com/watch/{slug}
```
- draft-1669 (ep 1796): `{"sources":[{"file":"https://cat3.asuka-vod.site/DHd_sdtq0NcRENMr0XvPaoymrl9VfrAIh4cEjTnyRLc","type":"hls"}],"subs":[],"success":true}`
- hache ep 449: `https://cat3.asuka-vod.site/H7tLlX8xolDaa4YvPuXminlKqCLCw_qYT_rtEv9SAcI` (hls)
- impregnation-nation ep 886: `https://cat3.asuka-vod.site/3YSGlZGAR4vMLneedThMqHMO9VxkL-7fKSY2B-3dbzg` (hls)
- russian-lolita ep 307: `https://cat3.asuka-vod.site/sCJlSvPOu0JJfrdUElG_AQ` (hls)
- the-handmaiden ep 405: `https://cat3.asuka-vod.site/nxG4hhNy43ysiZnhvOslpZseNdnE7Q8cmIXE-OjCmGc` (hls)
- jailhouse-wardress ep 1765: `https://cat3.asuka-vod.site/4SqvP94CWnvZSho7SfjrvEEUe9RLENZgbac6rCtPy7k` (hls)

Single server (`sv=1`, "Watch Online"). All hls, no qualities/alt urls.

**Token URLs are bare** (`https://cat3.asuka-vod.site/<token>`, no extension). The site
player's JS (`withSuffix()` in /static/js/site.js) appends `/index.m3u8` (or `/index.json`
per-UA) before loading. The bare URL serves Cloudflare 403 HTML — only the suffixed
`/token/index.m3u8` serves a real `#EXTM3U` playlist (segments are camouflaged as `.jpg`).

## Headers / referer
Source API worked with plain UA + referer. **Stream CDN `cat3.asuka-vod.site` is behind a
Cloudflare JS challenge for this runner** (403 "Just a moment..." with/without referer,
multiple UAs, http1.1). Main site cat3film.com serves 200 normally. Content-type of the
m3u8 could therefore not be confirmed from this machine — playback may only work on an
unblocked client. See Risks.

## Pagination
Listings: `?page=N` query param. Page 2 of `/movies` returns different items (verified:
page1 starts draft-1669…, page2 starts conflict-of-emotions, jailhouse-wardress…).
Search: single page (no pagination).

## Risks / blockers
- ~~Stream CDN blocked~~: resolved — the bare token URL got Cloudflare 403; `/token/index.m3u8`
  (with browser UA + main-site referer) serves a valid VOD playlist (200, #EXTM3U, thousands
  of segments). Suffix appended in `loadLinks`.
- Iframe allowlist on the main site includes `https://cat3.asuka-vod.site`.

## Issue #292 re-verification (2026-09-11, audit-fix run)

- ld+json: single `application/ld+json` script per detail page, rating inside
  `@graph[0].aggregateRating` (`rratingValue` unquoted, 0–10). Handmaiden 8.1, count 199145.
- Multi-season: one `?sv=1&part=1` fetch contains a `.wserver` per season
  (server slots double as seasons; headers "Season 1"/"Season 2"; `data-ss` is a per-pane
  constant "1", the real number is in the `wserver-name` label / `data-sv`). `.epbtn` carries
  `data-ep` (source id), `data-no` (per-season episode number), `data-ss`. Hache: 14 epbtns =
  S1 ep1–8 (449–456) + S2 ep1–6 (457–462). Movies/one-season pages say "Server 1"/"Watch
  Online" in the same slot, one `.epbtn` ("Full").
- detail pages keep empty `og:title` (`h1.info-title` is the real title) — unchanged.

## verify.sh result (run 2026-09-11)

- homepage `/movies` + `?page=2`: 200, 30 cards each, page 2 fresh — PASS
- video pages 5× (the-handmaiden, hache, draft-1669, impregnation-nation, jailhouse-wardress):
  200, `h1.info-title`, tags/actors/year present on all 5, `section#related a.card` 4 recs each,
  no title/poster/plot/related duplicates — PASS
- streams: positional `--stream-url` (per chain in FINDINGS: `.epbtn[data-ep]` →
  `/api/v1/episodes/{id}/sources` → `/index.m3u8`) 5/5 → **200 application/vnd.apple.mpegurl** — PASS
- check 6 (code half): recommendations/tags/plot/duration/year/actors/**score**/posters all ≥1
  assignment — PASS
- check 1 search: **FAIL-by-tool** — the site's only search surface is the AJAX JSON endpoint
  `/_ajax/search?q=` (HTML `/search?q=` is 404); verify.sh asserts HTML card selectors, so the
  JSON page has no `a.card`. Provider uses exactly this endpoint; agreement re-proven by curl:
  `_ajax/search?q=hache` → `{"slug":"hache","title":"Hache","year":2019}` matching load page
  `/hache` (Hache S1 2019). Site shape, not a provider defect.
