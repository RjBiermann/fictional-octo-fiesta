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

## Issue #251 re-verification (2026-09-10, Builder)

Reported URL `/watch/draft-1665?sv=1&part=1` (Murder in Blue Light) re-probed end-to-end on master (v3, PR #246's fix intact):
`data-ep=1792` → `/api/v1/episodes/1792/sources` 200 → `{token}/index.m3u8` **200, #EXTM3U VOD, 1339 segments**; m3u8 also 200 with okhttp/exoplayer UA, HTTP/2, no referer, no cookies. Same full pipeline on the-handmaiden (2173), impregnation-nation (272), russian-lolita (1396), jailhouse-wardress (1261) — all playable. Search JSON, both home pages, `gradlew :make` all clean. No server-side or provider failure reproduces; classified duplicate of #245 (stale pre-v3 plugin suspected on the reporter side). No code change, no version bump.
