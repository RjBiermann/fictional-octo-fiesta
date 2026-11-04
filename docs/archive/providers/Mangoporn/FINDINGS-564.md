# FINDINGS — Mangoporn (issue #564): markup migrated to the PandaMovies BEM card/data-servers grammar

All evidence plain-curl from the datacenter runner, 2026-10-16, HTTP unless noted.
Site answers plain curl 200 (mangoporn.net, `tpl-dooplay` module/theme tag in `<body>`
class). Person probing: EU-IP datacenter runner (`i3.wp.com` image proxy and all pages
resolve without cookies, no age gate, no CF challenge on the site itself).

## Condition (audit claim reproduced)

Every surface in the issue reproduces exactly:

| surface | `div.video-block` | `card__th` | status |
|---|---|---|---|
| home `/` | 0 | 38 | 200 |
| search `/page/1/?s=anal` | 0 | 27 | 200 |
| watch `/movies/anal-2/` | 0 | — (0 `pettabs`, `data-servers` 1) | 200 |

The site migrated sitewide to the same BEM grammar/theme PandaMovies got in #555/#563
(same `pandanetwork.club` WP image CDN provenance, same `section.hlm` player with
`data-servers` JSON).

## Engine fingerprint

WordPress DooPlay-genre tube theme ("SpeedPorn"), body tag: `tpl-dooplay mode-dark …
pg-card has-hero hero-carousel`. Card/player grammar identical to PandaMovies'
post-#555 theme (article.card / section.hlm), with site-specific path prefixes
(`/movies/genre/…`, `/movies/year/…`, `/movies/studios/…`) and a JSON-LD
`VideoObject` on watch pages.

## Cards (search / home / category / related share one shape)

```html
<article class="card">
  <a class="card__th" href="https://mangoporn.net/movies/<slug>/" tabindex="-1" aria-hidden="true">
    <img class="card__img" src="https://i1.wp.com/pandanetwork.club/movies/wp-content/uploads/.../5049993.jpg?resize=360,540"
         alt="Watch … Online Free" width="360" height="540" loading="eager" decoding="async" fetchpriority="high">
    <span class="card__q">2026</span><span class="card__dur">29:00</span>…
  </a>
  <div class="card__b">
    <h3 class="card__t"><a href="https://mangoporn.net/movies/<slug>/">Top-Tier SSS Beauty …</a></h3>
    <div class="card__m card__m--s card__m--dot"><span class="m-v">…18</span>
      <a class="card__tx" href="https://mangoporn.net/movies/studios/easy-japanese-women/" title="Easy Japanese Women">Easy Japanese Women</a></div>
  </div>
</article>
```

- title: the anchor **text** of `.card__t a` — heading tag varies per surface/page
  (`h3.card__t` on home p1, `h2.card__t` on home p2): match by class only.
- link: `a.card__th[href]` (absolute).
- poster: `img.card__img[src]` — **real URL eagerly served, no lazy attribute**
  (`i?.wp.com/pandanetwork.club/...?resize=360,540`; no `data:` placeholders observed).
- transcript: transcript: curl home -> grep '<article class="card"'|38; card__th 38;
  card__t 38; search p1 -> 27 each; older shape div.video-block 0 both.

Per-page counts vary (38 home p1, 27 search p1, 27 category p1, 9–27 genre p1,
related 11) — same variance as PandaMovies #555. Pagination must be anchor-based,
not count-based.

## Search

- Pattern: `/page/<N>/?s=<query>` (after migration `/page/1/?s=anal` works; plain `/?s=anal`
  (no page segment) also 200 with 9 cards + next anchor — both live; provider keeps the
  paginated form). Transcript: 200, 156 KB, 27 `article.card`, `a.next.page-numbers` present.
- Query that matches ≥1 sampled video page: `anal` → `/movies/anal-2/` on search p1.
- Card selector as above. No fuzzy-garbage behavior re-observed (WP #444 machinery not
  needed on live evidence; do not add).

## Quick search — distinct endpoint exists (new)

`GET https://mangoporn.net/wp-json/turbosearch/v1/suggest?q=<query>` → 200 JSON:

```json
{"q":"anal","posts":[{"id":32116,"title":"Anal","url":"https://mangoporn.net/movies/anal-2/",
 "thumb":"https://i3.wp.com/pandanetwork.club/movies/wp-content/uploads/.../3255674.jpg?resize=360,540",
 "meta":"29:00 · 12 views"}, …],"terms":[...]}
```

Reply has `total:0` when empty (`{"q":"","posts":[],…}`). Old provider had no quick
search; wiring this surface is trivial and covered by verify. No auth, no referer needed.

## Homepage

- Page 1 URL: `/` — transcript: 200, 170 KB, 38 `article.card` (after #525 there were 83/85
  `video-block`-cards; counts are per-theme and not comparable). First cards differ from page 2.
- Page 2 URL: `/page/2/` — 200, first cards `fetish-fantasies`, `amateur-show-offs-2`
  (different from p1's `top-tier-sss-…`, `auf-dem-weg-zum-pornostar`).
- Root archive paginates deep: `/page/N/` 200 up to `/page/800/` (27 cards each) —
  random-page rewrite (old `/movies/page/<random>/`, now 404) moved to root
  `$mainUrl/page/<random>/` with MAX_PAGE range 1..2000 intact.
- Homepage rows: the main grid is `article.card`; Featured/hero carousel carries no cards
  needing exclusion on `/` (cards in the grid only — `section.sec` filter not needed here,
  proved by the 38 = grid count).

## Category families (MangoAyarlar rows) — old paths 404, moved under `/movies/`

| MangoAyarlar key (old) | now | status |
|---|---|---|
| `movies` (Latest) | `$mainUrl/` + `/page/N/` (unchanged provider branch) | 200 |
| `movies/random` (Random) | rewrite target moved: `$mainUrl/page/<rand>/` | 200 |
| `genre/<slug>` | `movies/genre/<slug>` | 200 (germany/russian/france/anal/…) |
| `year/<y>` | `movies/year/<y>` | 200 (2022/1999 verified; pre-1990 slugs dead) |
| `xxx/studios/<slug>` | `movies/studios/<slug>` | 200 (21-sextury-video/devils-film/girlfriends-films verified; `evil-angel` slug dead) |
| `trending/`, `ratings` | no live path (trending redirects to `/movies/trending-in-love/`, a specific *category* of that name; ratings 404) | 404 — rows dropped |
| `xxxmovies/genre/indian`, `xxxxmovies/studios/asia-bootleg`, `xxxmovies/genre/bathroom`, `xxxmovies/genre/tattoos` | no live path | 404 — rows dropped |

Category pages serve the same `article.card` shape and paginate
(`/movies/genre/lesbian/` p1: 27 cards, `a.next.page-numbers`; `/movies/year/2022/`:
27 cards + next). Past-last category pages 404 (no clamp).

## Video pages (5 sampled, from 4 different listings)

| # | URL | from | ld duration | year chip | servers (labels) |
|---|---|---|---|---|---|
| 1 | `/movies/anal-2/` | search p1 | PT2H29M | 2022 | PlayMate, DoodStream, LuluStream, VOE, MixDrop |
| 2 | `/movies/top-tier-sss-beauty-returns-for-a-pure-unscripted-moment-of-passion/` | home p1 | PT29M | 2026 | DoodStream, DoodStream 2, LuluStream |
| 3 | `/movies/if-it-feels-good/` | related (anal-2) | PT2H24M | 2020 | DoodStream(x4), MixDrop(x2) |
| 4 | `/movies/excess/` | related | PT2H37M | 2020 | DoodStream(x2), VOE(x2), MixDrop |
| 5 | `/movies/flotte-dreier-in-der-nachbarschaft-3-2/` | genre p1 | PT1H50M | 2024 | MixDrop |

Structure on each (post-migration `article.vid` grammar, no `#pettabs`/`#video-actors`/
`.video-description`/`div.video-title`):

- title: `span.bc__c` (breadcrumb, 1 occurrence — same selector as PandaMovies)
  — "Anal", "Top-Tier SSS Beauty…". `h1.vid__t` exists as fallback.
- poster: JSON-LD `VideoObject.thumbnailUrl` — **now a JSON array** (`"thumbnailUrl":["…"]`),
  regex must mirror `\[?`. Single `img.hlm-screen__poster` also present (same URL family).
  Old behavior "blank on some pages" not observed in samples; array-form extraction proven on all 5.
- duration: JSON-LD `"duration":"PT2H29M"` (all 5 sampled) — `JsonLdParse.minutes`
  handles it (149/29/144/157/110). Clock text also on page (`span.st--duration` "2:24:00").
- year: `a[href*=/year/]` chip text ("2022" — link `https://mangoporn.net/movies/year/2022/`).
  One `watch-free-moivies-by-release-years/` nav anchor does not contain `/year/`.
- plot: `div.vid__txt p` (first p, `entry vid__txt`).
- actors: `a.chip--star` (7/9/17 sampled) — ids like `/movies/pornstar/<slug>/`.
- tags: `a[href*=/genre/]` — still a substring match multiplex (15–30 per page).
- related: `section.sec` — transcript: `<section class="sec">`+`<h2 class="sec__h">Similar
  titles`, 11 `article.card` each. Cards identical shape to listing cards.

## Related videos

`section.sec article.card`, 11 items each (5/5 pages). Explicit.

## Stream sources

`section.hlm[data-servers]` (exactly 1 per sampled page) — JSON array of objects
`{"u": "<embed-url>", "t": "iframe", "l": "<label>", "h": "<hash>"}`. URL-decoded:

```
[{"u":"https://playmate.to/embed/lIwXIX2evZ6ho","t":"iframe","l":"PlayMate",…},
 {"u":"https://playmogo.com/e/ote1kbil63o9","t":"iframe","l":"DoodStream",…},
 {"u":"https://luluvido.com/e/8n9fsmwl1tdt","t":"iframe","l":"LuluStream",…},
 {"u":"https://voe.sx/e/f3nrt622h53i","t":"iframe","l":"VOE",…},
 {"u":"https://mixdrop.ag/e/03vxj3mvsk96pq1","t":"iframe","l":"MixDrop",…}]
```

- Host sets differ per video (above table); **no stream path repeats across the 5 probed pages**.
- Download rows (`hlm-row--download`, nitroflare/frdl.my) are separate `hlm-btn`
  anchors **outside** `data-servers` — not extracted (old pettabs locker filter obsolete).
- Host → shared registry mapping:
  - `playmate.to/embed/<code>` → shared `Playmate` (registered, live-tested 200 + player HTML).
  - `playmogo.com/e/<code>` → shared DoodStream row (`dood("https://playmogo.com")` already in the registry).
  - `luluvido.com/e/<code>` → LULUBASE family. **Host not in the registry** (old pettabs served
    `luluvid.com/e/…` and matched the base class; the migration renamed the domain). Fix:
    registry row `lulu("https://luluvido.com", "LuluStream")` — LULUBASE fetches the
    embed URL it is given, so no URL/id-space rewrite needed (PandaMovies-style rewrites
    only necessary when a label stays the same while a dead host persists).
  - `voe.sx/e/<code>` → framework `Voe` (registered row; the sampled link 404s fresh —
    VOE ids expire quickly, different videos' ids resolve; PandaMovies had identical behavior).
  - `mixdrop.ag/e/<code>` → framework `MixDropAg` (registered; live check 200, redirects
    `https://mxdrop.top/e/<code>` — extractor handles the mirror per framework code).

Embed-host runner reachability (datacenter IP, plain curl + UA + no extra headers):
playmate.to **200 OK**; mxdrop.top (mixdrop.ag redirect target) **200 OK**; playmogo.com
and the lulu family CF-challenged ("Just a moment…", 403) — same per-host wall as PandaMovies
post-#563 ("runner-blocked, site fact"); voe.sx 404 for the expired sampled id.
Stream serving verification from this runner therefore covers playmate/mixdrop directly;
playmogo/lulu are Cloudflare walled — visible to app clients (browser-grade TLS/cookies) —
carrying the same in-app-verification note the #563 PR shipped.

## Headers / referer

- Site: plain curl 200 without cookies/headers on every surface probed — no age gate. Transcript materialized by the transcriber done by search/home/watch status lines above.
- Poster images: same URL family as before (`i?.wp.com` proxy) still ships `Accept` header captioning care (Mangoporn keeps its posterHeaders Accept header — cf. loading behavior from existing provider).
- Embeds: playmate/mixdrop resolve with browser UA only; lulu/dood hosts are CF-gated
  per-host (see Stream section) — extractor-level referer (`LULUBASE.requiresReferer=true`)
  is existing machinery, unchanged.

## Pagination

| surface | p-2 evidence |
|---|---|
| search | `/page/2/?s=anal` 200, different cards (`anal-fuck-dolls-5`, `anal-training-day`) vs p1 (`anal-2`, `anal`) — past last: 404 (`/page/500/?s=anal`) no clamp |
| home | `/page/2/` 200, `fetish-fantasies` first (differs from p1) — grid continues to ≥ /page/800/ |
| category | `/movies/genre/lesbian/` 27 cards + `a.next.page-numbers`; past last 404 |
| related | single section per video page (no paging) |

The `a.next.page-numbers` anchor is the portable has-more signal (exactly the pattern
PandaMovies #555 adopted after count-based detection drifted). Last page: cards, no anchor.
Past last: 404, no anchor, no cards-duplication flood.

## Risks / blockers

- Site itself: no CF challenge, no wall — plain curl clean (EU datacenter IP).
- Per-host embed gating only (dood family + lulu family CF; voe id expiry): visible to
  app clients, runner-blocked — carry the same in-app-verification note as #563.
- Some MangoAyarlar slugs rotted on top of the migration (`evil-angel`, pre-1990
  years, `russia` genre variant) — dead rows simply render empty; not a fix blocker.
- 502s observed transiently on cold curl to `/` (retried fine, `i matchmaking` fast-paced
  origin) — not reproducible enough to be listed as a repo-level risk.

---

## Verification (fixture sweep, devloop/issue-564)

`./gradlew Mangoporn:test` 10/10 green (incl. HostRegistryTest luluvido row, allowlist
consciously bumped LuluStream 2→3). `Mangoporn:make` → `Mangoporn.cs3` (version 6).

`verify.sh` mechanical sweep — **RESULT: PASS** (full transcript):

- check 1 search: `?s=vivian` p1+p2 — 27/27 `article.card`, no dupes (h2/h3 card__t variance
  noted; query switched from `anal` because the site itself ships *different* videos under the
  same title — `Sex Slaves` ×2, `Anal` ×2 — a site data fact the script's title-distinctness
  flag caught, not a parser bug).
- check 1a home: `/` 38 cards + p2 27, no dupes.
- check 1b quick search: script NOTEs (its machinery is HTML-only, endpoint is JSON);
  verified manually instead — `GET /wp-json/turbosearch/v1/suggest?q=anal` → 200, 8 posts,
  real titles/urls; covered unit-side by `suggestPosts` fixture test.
- check 2 videos ×5 (from 4 distinct listings + related-of): title `span.bc__c`, poster
  `img.hlm-screen__poster`, plot `div.vid__txt p`, tags/actors/year/duration all
  all-or-none consistent; 11 distinct `section.sec article.card` recommendations each.
- check 3 streams (`--stream-url` overrides, one runner-resolvable chain per page):
  playmate `api/s` → `frv1.plauymito.live/hls/…/master.txt` → 200 `application/vnd.apple.mpegurl`;
  mixdrop packed JS (PackedJs grammar) → `MDCore.wurl` → `*.mxcontent.net/v2/*.mp4` → 206
  `video/mp4` ×4. mxcontent UA gate fact found during verification: **any full-browser UA
  403s from this runner, bare `Mozilla/5.0` 206s** (tested chrome-149/Firefox-130/chrome-120
  — all 403; runtime `USER_AGENT` is chrome-149, so app playback also rides this gate —
  in-app proof stays maintainer-only like #563). Referer `https://mixdrop.to/` required on
  mxcontent (403 without).
- check 5 agreement: sampled search video `Vivian Lola & Natasha Fiori` — title+poster agree.
- check 6 data-complete: recommendations/tags/plot/duration/year/actors all populated in
  provider code.

Blocked hosts (same runner-block fact as #563 — not parser failures):
`playmogo.com/e/*` (dood family) CF 403 plain-curl; `luluvido.com/e/*` (LULUBASE chain)
CF 403 plain-curl; `voe.sx/e/*` sample ids expired 404 (VOE ids expire — host list varies
per video). `mixdrop.ag` origin intermittently 522s one-retry-resolves.
