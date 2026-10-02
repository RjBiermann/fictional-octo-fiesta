# FINDINGS-511 — fleet audit run 6 (2026-10-02)

Audit-only run: probe reality, record evidence, update
`audits/findings.json`. **No provider code changed.**

Branch: `devloop/issue-511`. Instrument: plain curl → TLS-impersonated
(curl_cffi chrome impersonation) per the site-probe escalation ladder.
Probe IP: 20.49.61.55 (Microsoft Azure, Boydton VA — regulated US state).

## Phase 0 — canary calibration

| Canary | Expected | Observed | Outcome |
|---|---|---|---|
| film1k-challenge-pair | plain 403 / TLS 200 | 403 / 200 | MATCH |
| eporner-healthy | plain 200 | 200 | MATCH |
| pandamovies-redirect-origin | plain 302 / TLS 200 | 302 / 200 | MATCH |

All three MATCH — instrument healthy. `last_verified` stamps bumped to
2026-10-02 in `audits/canaries.json`.

## Phase 1 — sweep (26 providers)

| Provider | Verdict | Tier | Evidence (2026-10-02 unless noted) |
|---|---|---|---|
| AllClassicPorn | ok | plain | search/video 200; stream 206 fresh single-use token |
| Cat3Film | ok | plain | `_ajax/search?q=` JSON 200 (slug/format/year); home 200 |
| Cat3Movie | ok | plain | search 200 (21 cards p1 / 10 p2, 0 dup); video + stream verified (deep) |
| Eroticmv | ok | plain | rows/search healthy; #497 gap unchanged |
| Film1k | ok-drift | tls | main site 200 TLS; turbovid embed family still NX (#496) |
| FreePornVideos | ok-suspected | tls | search 200, 48 cards |
| FullPorner | ok-suspected | tls | search 200 (2 cards `/watch/<id>`); **stream now verified**: `xiaoshenke.net/vid/<reversedId>/<360\|720\|1080>` → 206 video/mp4 (Range), reverse-of-embed-id + `quality=parseInt("13")` (=360/720/1080 bit-flags) confirmed live |
| HQPorner | ok | plain | home/search/stream spot 200 |
| JavGuru | ok | plain | deep-verified stream (see below) |
| Javbangers | ok-drift | plain | search 200 (64 post ids); `/search/<q>/1/` + `/2/` still 404 — #475 re-confirmed |
| Javmost | ok | plain | showlist2 search 24 results; AJAX `ri3123o235r` POST 200 → `turbonewvid.com/t/<hash>` 200 returning `data[]` embed (turbovidhls successor host resolves; plain-curl connect-timeout on that host is probe-IP egress related) |
| Javseen | ok | plain | AJAX search 200 (escaped JSON, 31 video hrefs) |
| Javtiful | ok | plain | search 200 (24 cards, provider correctly drops 1 partner card); video 200; **stream 206 video/mp4** (`fast-stream.jav.si/p/<64-hex>`, signed URL serves ftypisom) |
| MissAV | ok | tls | search 200 grid markup; AV wall only on regulated-IP sessions |
| Mangoporn | blocked | tls | CF 522 all surfaces — #464 re-confirmed |
| Neporn | ok | plain | deep-verified (see below) |
| PandaMovies | ok-drift | tls | canary 200 via .pw→.org 302 — #463 stays healed |
| PerverZija | ok | plain | deep-verified (see below) |
| PornXP | ok | plain | tags/milf 200 (36 cards); stream 206 mp4 highest quality — #476 stays clear |
| Porntrex | ok | plain | search/video 200 spot |
| Sexfilm | ok | plain | search/photoset pages 200 |
| WatchPorn | ok | plain | search/video 200 (one sampled stream URL 0-byte = probe artifact) |
| Xhamster | **ok-suspected** (downgraded from ok) | tls | see "Xhamster downgrade" below |
| XMoviesForYou | ok-suspected | tls | search 200, 36 cards; video 200 |
| EPorner | ok-suspected | plain | AV wall persists from this probe IP |

### Xhamster downgrade: ok → ok-suspected

Regulated-region (Virginia) probe IP receives the **age-verification
wall SPA shell on every surface**: pages return 200 but
`window.initials` contains only chrome keys (`layoutPage.pageType =
default`) — `videoEntity`, `videoModel`, `searchResult`,
`videoPageComponent` are all absent, and
`age-verification-wall.css`/`.js` are served. Verified across:

- plain curl (200, empty-shell body, 39.6 KB)
- curl_cffi chrome impersonation, desktop UA
- curl_cffi with mobile UA + age cookies (`ageVerified=1` etc.)
- with the provider's exact query (`?geo=us&x_platform_switch=desktop`,
  `video_titles_translation=0` cookie)
- fresh session with prior homepage visit

No curl-reachable unlock path (camera-liveness flow per EPorner's
#447/#462 precedent). The 2026-09-26/29 `ok` verdicts recorded
search cards + m3u8 — presumably from a different egress IP (run 3/5
notes cite `geocc=ca` for EPorner, i.e. IP varied between runs). Verdict
moves to `ok-suspected` with the same `suspected_excluded` treatment
(probe-IP geo artifact, not a provider defect). Differential/HITL
decides whether to keep `ok`.

## Phase 2 — deep tier (4 providers, oldest `last_deep=None` stamps)

### Cat3Movie (cat3movie.org)

- **Rows**: all 5 homepage rows probed p1 vs p2 (`grid-item post-<id>`
  sets):
  - New Movies 21/21, Classic Porn 21/21, Classic Erotica 21/21,
    Asian Erotica 21/21 — **0 overlap** on each (1 sticky post `15919`
    repeated on every page = benign).
  - **Latest (= homepage) row: `/page/2/` serves an identical card
    grid to page 1 — 45/45 post-id overlap, canonical
    `https://cat3movie.org/page/2`**. 61 carousel/marquee cards in the
    same order. Site-side carousel duplication (mirrors the run-3
    Javmost home-dup class, cleared there as site behavior; recorded
    here as a note, not a finding: provider emits both rows verbatim —
    users see the same cards on row page 2).
- **Search** `/search/test`: 21 cards; `/page/2` 10 cards; 1 overlap
  (same sticky `15919` = benign). `/search/<slug>` variant 200.
- **Video**: watch page 200 (`post_id=34693`, `data-nonce` present);
  player.php sv1 200 with the provider's param set →
  `https://hlsfree.com/embed/hls/1133` iframe.
- **Stream**: embed page 200 exposing
  `defaultHlsUrl = ".../api/hls/serve?token=<hex>"`; serve endpoint
  → **200 `application/vnd.apple.mpegurl`, 909-line master playlist**.
  Provider's HlsFree chain intact.

### JavGuru (jav.guru)

- Home p1/p2 disjoint (24/24); Uncensored row p1/p2 disjoint; search
  (`/?s=`) 200 with 24 unique `/NNNNNN/` video links.
- **Stream chain verified end-to-end live**:
  1. video page → `wp-btn-iframe` base64 seed →
     `jav.guru/searcho/?<seed>=&bg=`
  2. searcho loader 200: `cfg cid/keys` → 3 `data-*` attrs
     concatenated + reversed → `?xr=<token>`
  3. 302 → `javclan.com/e/<id>` → packed JS `links={hls2,hls3,hls4}`
  4. **hls4 fetched against `javclan.com` with the searcho referer →
     200 `application/vnd.apple.mpegurl` (live playlist)**.
     hls2: 403 + TLS SAN mismatch; hls3: 404/timeout; stale hls4
     tokens: 404/502 — hls4 is the only live leg and matches the
     shared `Extractorlar.kt` Javclan class the provider dispatches
     through.

### Neporn (neporn.com)

- **Rows**: `latest-updates/`, `most-popular/`, `top-rated/` all p1/p2
  disjoint (24/24, 0 overlap) using the provider's `/page/2/` path.
- **Search**: 32 cards on `/search/?s=`; the provider's
  `mode=async&block_id=list_videos_videos_list_search_result&from_videos=<n>`
  search pagination returns distinct sets live (4-card block verified
  on the async fast path).
- **QuickSearch**: KVS async block
  (`mode=async&function=get_block&block_id=list_videos_...&q=may&from_videos=1`)
  → 200, 4 cards (provider has no quickSearch override — note only).
- **Stream**: video page 200; `/embed/<vid>` 200 exposes
  `video_url = https://neporn.com/get_file/9/<hash>/43…/43821_720p.mp4/?v-acctoken=<b64>`
  (+ a 480p alt in `video_alt_url`); **same-session curl with the
  embed cookies + Range → 302 → 206 `video/mp4`** (`ftypisom`).
  Bare `get_file` 403 without embed session = token binding, exactly
  what the provider's extractor emits (session-bound KVS flow).

### PerverZija (tube.perverzija.com)

- **Rows**: `featured-scenes/`, `full-movie/`, `tag/4k-quality/` all
  p1/p2 disjoint (64/64 h2-title sets and 64/64 `post-<id>` sets, 0
  overlap). Search p1 64 / p2 64 post ids — 1 shared id (`184346`,
  sticky, benign).
- **Video**: watch page 200 (`rel=1364474` class posts);
  `j2.xtremestream.xyz/player/index.php?data=<32-hex>` iframe +
  per-page `<source>` tag confirmed.
- **Stream**: player JS defines
  `m3u8_loader_url = "…/player/xs1.php?data="` —
  **`/player/xs1.php?data=<hash>` with the player referer → 200
  `#EXTM3U` master playlist with `#EXT-X-STREAM-INF` variants**.
  Matches shared `PerverZijaExtractor` (`index.php` → `xs1.php`
  replace, referer derived from the player URL). The earlier
  sub-root `xs1.php` (no `/player/`) 404s — the provider already
  targets the correct path.

## Phase 3 — findings lifecycle

- **Filed this run: 0.**
- Standing re-confirmed with fresh evidence: #464 (Mangoporn 522),
  #475 (Javbangers search-page suffix 404), #468 (ixiporn
  .org→.live redirect), #496 (turbovid family NX), #497 (Eroticmv
  facet gap).
- Stay clear: #463 (PandaMovies healed), #476 (PornXP), #477 (Javseen),
  #480 (Javmost row dup).
- New conditions recorded as notes, not issues:
  - **Cat3Movie Latest-row p2 duplication** — site-side carousel;
    mirrors the run-3 Javmost home row behavior that was recorded
    without a finding and later self-cleared.
  - **Xhamster AV-wall verdict downgrade** — verdict bookkeeping in
    this registry, not a site defect.
- Duplicate handling: the Javbangers age-wall scrape retrieved this run
  was exactly the page already quoted in FINDINGS-494 (run 5) — note
  only, not a new surface, and cited there rather than re-quoted or
  filed.

## Artifacts

- `audits/findings.json` — run6 entry + per-provider reason/surface
  updates + Xhamster verdict flip. `check-findings.sh`: PASS.
- `audits/canaries.json` — `last_verified: 2026-10-02` on all three.
- Probe transcripts in `/tmp/probe/*.html` (ephemeral; key numbers
  quoted above).
