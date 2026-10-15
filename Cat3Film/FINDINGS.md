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
- draft-1669 (ep 1796): first probe (2026-09-10): `https://cat3.asuka-vod.site/DHd_sdtq0NcRENMr0XvPaoymrl9VfrAIh4cEjTnyRLc` (hls) — host since moved, see re-probe note below
- hache ep 449 / impregnation-nation ep 886 / russian-lolita ep 307 / the-handmaiden ep 405 / jailhouse-wardress ep 1765: first probe returned the same `cat3.asuka-vod.site` bare-token shape — host since moved, see re-probe note below

Single server (`sv=1`, "Watch Online"). All hls, no qualities/alt urls.

**CDN host has moved**: re-probes (2026-09-16, see the re-probe section below) recorded
`cat3.asuka-vod.site` → `abyssssss.top`; 2026-10-15 re-probes (below) record a further move
to `master.abyssssss.top`. The `cat3.asuka-vod.site` URLs above are the original
first-probe record, superseded. Bare-token shape unchanged (no extension, site JS
appends `/index.m3u8`).

**Token URLs are bare** (`https://<cdn-host>/<token>`, no extension — host currently
`abyssssss.top`, formerly `cat3.asuka-vod.site`). The site
player's JS (`withSuffix()` in /static/js/site.js) appends `/index.m3u8` (or `/index.json`
per-UA) before loading. The bare URL serves Cloudflare 403 HTML — only the suffixed
`/token/index.m3u8` serves a real `#EXTM3U` playlist (segments are camouflaged as `.jpg`).

## Headers / referer
Source API worked with plain UA + referer. ~~Stream CDN `cat3.asuka-vod.site` is behind a
Cloudflare JS challenge for this runner~~ — **retracted 2026-09-16**: that early-probe 403
did not hold up; re-probes (below) found the CDN unchallenged (m3u8 200 with/without
Referer, okhttp UA) and the host has since moved to `abyssssss.top`. Main site
`cat3film.com` serves 200 normally.

## Pagination
Listings: `?page=N` query param. Page 2 of `/movies` returns different items (verified:
page1 starts draft-1669…, page2 starts conflict-of-emotions, jailhouse-wardress…).
Search: single page (no pagination).

## Risks / blockers
- ~~Stream CDN blocked~~: resolved — the bare token URL got Cloudflare 403; `/token/index.m3u8`
  (with browser UA + main-site referer) serves a valid VOD playlist (200, #EXTM3U, thousands
  of segments). Suffix appended in `loadLinks`.
- ~~Iframe allowlist on the main site includes `https://cat3.asuka-vod.site`.~~ Superseded:
  the CDN host moved to `abyssssss.top` (2026-09-16 re-probes below); treated as
  illustrative of the main site whitelisting its own CDN, whatever host it uses.

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

## 2026-09-16 re-probes (issues #410 / #416 — distilled from the per-issue probes)

**#410** ("video doesn't play"): the whole chain (detail → watch `?sv=1&part=1` →
`/api/v1/episodes/{id}/sources` → `<cdn>/token/index.m3u8`) re-verified 200 for 17
episodes, also over a raw okhttp/NiceHttp fingerprint — no site-side drift. Silent
"no links found" is the CF-challenge-on-client path: the sources fetch throws on
challenge HTML and `loadLinks` swallows → zero callbacks. Fix shipped: CloudflareKiller
interceptor on Cat3Film fetches (repo precedent FullPorner/Film1k), suffix logic moved
into pure `Parse.streamUrl()` (TDD).

**#416** (movies shown as TV shows): detail-page badges row is the type signal —
movies render `<span class="badge">Movie` (verified `/the-handmaiden`, `/russian-lolita`,
`/impregnation-nation`, `/conflict-of-emotions`), series render `badge>TV` with 0×
"Movie" (verified `/hache`, `/hot-line`, `/risque-business-japan`, `/virgin`).
Search JSON also carries `format: "Movie"|"TV"` per result (future tie-breaker).
CDN moved `cat3.asuka-vod.site` → `abyssssss.top` this run and is NOT CF-challenged
(m3u8 200 with/without Referer, okhttp UA) — old challenged-CDN note obsolete.

## 2026-10-15 re-probe (issue #617 — Story 1 of the approved #613 spec)

Supersedes the 2026-09-16 records where stale. Chrome-124 UA unless noted; Cloudflare in
front of `cat3film.com` (cf-ray) served 200 everywhere — no challenge encountered on any leg.

**Sample titles (failing-string sample set for the issue):**

- `/malombra` — movie, movie id 1690, ep id 1815, badges `[HD, Movie]` — first card on
  `/movies`, **newest upload found this run** (also appears on `/` rank grid)
- `/hache` — TV, movie id 394, ep ids 449–462 (S1 449–456 / S2 457–462, unchanged),
  badges `[HD, TV]`; ep 449 probed end-to-end
- `/impregnation-nation` — movie, movie id 778, ep id 886, badges `[Movie]` — first card on
  `/`
- dead ep id sanity: `/api/v1/episodes/1816/sources` → `{"error":"episode not found","success":false}`

**Listing legs all healthy**: `/` 200 (60 cards), `/movies` 200 (30 cards), `/movies?page=2`
200 (30 fresh cards: too-late, slugs, …), `/tv-series` 200 (14 cards), `section#related`
present. `/_ajax/search?q=handmaiden` → 200 JSON, same one-hit shape
(`format:Movie, slug, thumb, title, year`).

**Sources API — unchanged shape, new CDN host.**
`GET /api/v1/episodes/{id}/sources` (referer not enforced) → 200 `application/json`:

    {"backup":"https://player.abyssplayer.com/lD0RMD3Q_",
     "sources":[{"file":"https://master.abyssssss.top/<token>","type":"hls"}],
     "subs":[],"success":true}

- **CDN host moved 2nd time**: `abyssssss.top` → **`master.abyssssss.top`** (API now
  returns the `master.` host for all fresh tokens: malombra 1815, hache 449/462,
  impregnation-nation 886 all confirmed). The old apex host `abyssssss.top` still serves
  the same current tokens behind the suffix (200 m3u8) — the move is a new subdomain, not
  a token invalidation. Bare token (no suffix) → nginx 404 (both hosts), NOT a CF 403.
- **Suffix behavior re-proven**: `/token/index.m3u8` → 200 `application/vnd.apple.mpegurl`,
  VOD `#EXTM3U` (TargetDuration 4, ~1367 segments). `/token/index.json` → 200
  `application/json` but identical `#EXTM3U` body — both suffixes serve the playlist.
  `Parse.streamUrl()` behavior (always `/index.m3u8`) remains correct.
- **Not CF-challenged**: m3u8 200 with okhttp UA and **no Referer** at all.
- Segments live on `player.abyssssss.top/<token>/seg-N-v…` (`.jpg`-camouflage gone in
  segment naming, now generic `seg-N-v…`; irrelevant to the provider — the player fetches
  segment URLs straight out of the m3u8 body).
- **Backup embed healthy**: `player.abyssplayer.com/<id>` → 200 HTML, `<title>m_1690.mp4`,
  `const datas = "…"` (1528 chars) → `POST https://enc-dec.app/api/dec-abyss`
  (Origin: enc-dec.app) → 200 with `result.sources` 2× (`480p`, `720p`, status true)
  pointing at `*.sssrr.org/sora/…` MP4 links; those 302 → an ephemeral
  `*.trycloudflare.com` mirror that serves `video/mp4` (206 on range) — playable when
  redirects are followed. Shared `AbyssPlayer` adapter chain matches live shape.
- Observation for Story 2 (not re-verified as broken): `loadExtractor`'s primary match is
  `compareUrl.startsWith(extractor.mainUrl-stripped)` — `player.abyssplayer.com/…` does
  **not** prefix-match `AbyssPlayer.mainUrl = "https://abyssplayer.com"`; it can only match
  via the Levenshtein `partialRatio > 80` fallback loop.

**Conclusion (ground truth for Story 2)**: every leg of the chain is server-side healthy
today — 200 with plain nokogiri/plain-UA fingerprints, no drift in selectors, API shape, or
suffix behavior; the only live drift is the **CDN host subdomain** (`abyssssss.top` →
`master.abyssssss.top`), which `Parse.streamUrl()` handles host-agnostically. The issue's
"No links found on every title" symptom therefore does **not** reproduce against today's
site from this probe context — consistent with the `#410` client-side-CF-decision diagnosis
(challenge HTML on the sources fetch, swallowed by `loadLinks` → zero callbacks). Client-
side evidence carries nothing new this run; the interceptor path (`CloudflareKiller` +
`cfChallenge`) shipped by `#410` remains the matching in-app mechanism, and the subdomain
move is the one store-worthy change for Story 2's fix scope.
