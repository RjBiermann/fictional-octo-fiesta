# FINDINGS-646 — Eroticmv deep pass (2026-10-16)

One self-contained run of the approved #631 spec: audit, probe, and fix Eroticmv
(eroticmv.com). Registry state before this run: verdict `ok`, tier `plain-curl`,
last deep 2026-10-09.

## Phase 0 — canary calibration: 3/3 MATCH

| Canary | Expected | Observed | Result |
| --- | --- | --- | --- |
| film1k-challenge-pair | plain 403 / TLS 200 | plain 403 / TLS 200 (`HTTP 200 https://www.film1k.com/` via curl_cffi chrome) | match |
| eporner-healthy | plain 200 | plain 200 | match |
| pandamovies-redirect-origin | plain 302 → 200 @ .org / TLS 200 | plain 302 → `https://pandamovies.org/` / TLS 200 @ .org | match |

Instrument healthy. Browser tier never reached (no blocked surface after escalation).

## Instrument condition — NEW challenge wall on plain curl (this is the headlight)

- `curl https://eroticmv.com/` (desktop UA + eroticmv referer) → **403**, `cf-mitigated: challenge`,
  `server: cloudflare`, title `Just a moment...`, challenge CSP referencing `challenges.cloudflare.com`.
  Consistent: 3/3 plain fetches 403. **New since the 2026-10-09 last_deep** (which recorded plain-curl 200
  on every surface).
- Escalation step 2 (`scripts/impersonate.sh`, curl_cffi chrome fingerprint): **HTTP 200 on every
  surface probed** (home, facets, search, 5 video pages). Same signature as the film1k canary pair
  (a client-fingerprint gate, not an IP gate — the same runner IP passes at tier 2).
- Residential differential: unavailable this run (no `scripts/differential.sh`, no secret) → the
  wall's effect on the device's OkHttp client is **unadjudicated** → recorded `suspected`, not
  filed (`challenge from the probe is never a standalone finding`). No issue filed; PR flags it
  for maintainer in-app adjudication.

## Probe (full surfaces, tier-2 instrument)

### Homepage rows (getMainPage — "Latest")

- `/` → 200, **24** `article.post-item` cards; `/page/2/` → 200, **24** cards.
- **p1 ∩ p2 = 0 overlap** (href-keyed). Card grammar unchanged: `a.blog-img` (href + `title`
  attr) + lazy poster `img placeholder.png src` / real `data-src` — byte-identical to what
  `Eroticmv.kt` `toSearchResult()` parses.

### Facet rows (post-#497/#559 state)

- `/category/genre/classic-erotica/` → 200, 24 cards; `/category/genre/classic-erotica/page/2/`
  → 200, 24 cards, **overlap p1∩p2 = 0** — facet pagination (#559, merged 2026-10-10) live.
- `/category/decades/1970s/` → 200, 24 cards. Shares 10 hrefs with home p1 — **cross-section
  recency** (recent posts also listed under decade/genre facets), the same site reality prior
  runs recorded for Sexfilm/HQPorner; not pagination drift (within a row, pages are disjoint).

### Search

- `/?s=erotica` → 200, **17** unique cards (`article.post-item`), titles in `h3.entry-title a`.
- `/page/2/?s=erotica` → **404** — search has no pagination; provider's `if (page > 1) return
  empty` matches the site.
- Search ↔ load agreement (title): search card `Erótica: Luz de Luna (2008)` ↔ load og:title
  `Watch Erótica: Luz de Luna (2008) - Erotic Movies` → provider normalization strips the
  wrapper → equal. Title agreement passed mechanically (check 5).

### Quick search

- `/search/suggest/?q=sex` → HTTP 200 but **`text/html; charset=UTF-8`, 269 KB full HTML page**
  (theme's search-results fallback, `article.post-item` cards inside) — not a JSON/fragment
  suggest endpoint. Also `/wp-json/wp/v2/search` = generic WP search HTML. **No distinct
  quick-search endpoint** (absent, not drift). `hasQuickSearch = false` stays correct.

### Video pages (×5, varied listings: home p1, facet decades, search)

| page | og:title | og:image | og:description | tags (`.categories-elm a.category-item`) | actors (title attr) | related |
| --- | --- | --- | --- | --- | --- | --- |
| /night-has-a-thousand-desires-1984/ | ✓ | ✓ | ✓ | 1980s, Classic Erotica, Spain, Violence | 8 | 12 |
| /masahe-2026/ | ✓ | ✓ | ✓ | 2020s, Asian Erotica, Philippines | 1 | 12 |
| /abigail-lesley-is-back-in-town-1975/ | ✓ | ✓ | ✓ | 1970s, Cheating, Classic Erotica, Lesbian, MILF, USA | 8 | 12 |
| /erotica-luz-de-luna-2008/ | ✓ | ✓ | ✓ | 2000s, Mexico, Newage Erotica | 14 | 12 |
| /little-shop-of-erotica-2001/ | ✓ | ✓ | ✓ | 2000s, Newage Erotica, USA | 8 | 12 |

- All 5 titles, 5 posters, 5 plots pairwise distinct. Related (`.single-related-posts
  article.post-item`): 12 cards/page, distinct, no self-references.
- **#538 condition adjudicated live:** the standalone `VideoObject` JSON-LD carries **no
  `articleSection` on any of the 5 pages**; the yoast `@graph` `Article` node carries it on
  5/5 (e.g. tokyo-style values: `["1980s","Classic Erotica","Spain","Violence"]`). The
  intermittent VideoObject re-appearance seen at the 2026-10-09 run-9 audit is not present
  today. Either way the merged #538 fix (`parseTags` → `.categories-elm a.category-item`)
  is the provider's only tags path and it is live 5/5 — **the record stays closed (fixed)**;
  no reopen, no new issue (this is the same condition #538 is the standing record for).
- Actors block `.actor-element.single-element` unchanged (`a[href*='/actor/']` with `title`
  attr). Single-actor pages (masahe) are site variance, not parser drift.
- Year: exposed via og:title `(YYYY)` only — no dedicated field; provider regex covers it.
  Duration: not exposed in any parseable field (site has no duration markup) — NOTE'd in verify.

### Stream chain (re-resolved end-to-end, every video page)

**CDN host migrated since 2026-10-09: `vidcdn2.eroticmv.com` → `data.eroticmvs.com`**
(note the `s` in eroticmvs). Evidence: base64 token in `og:video:url` decodes to
`https://data.eroticmvs.com/dat1/<slug>/<slug>.m3u8` on 5/5 pages.

- 5/5 resolved m3u8s: **HTTP 206, `Content-Type: application/vnd.apple.mpegurl`, body starts
  `#EXTM3U`** — with the provider's referer AND without referer (both probed).
- Streams pairwise distinct across videos; ≤1 source per page, `source` count matches
  `meta[property=og:video:url]` presence 5/5.
- **Zero provider impact:** the host lives inside the decoded payload
  (`parseStreamUrl` → `decodeBase64(token)`); the provider hardcodes no CDN host. No
  extractor change needed, none made.

## verify.sh (mechanical gate, tier-2 instrument)

`verify.sh` fetches with plain `curl`, which the site now challenges — the run wrapped
`curl` in a shim routing through `curl_cffi` chrome impersonation (site-probe tier 2, the
same instrument the canary pair validates: plain HTTP semantics, only the TLS fingerprint
differs). Families: main (search + home p1/p2 + 5 videos) and facet (classic-erotica p1/p2
+ 1 video). Per-row facet verification needed because facet rows share recent posts with
home p1 (cross-section, see above).

| Check | Result |
| --- | --- |
| 1 search `/?s=erotica` | PASS — 200, 17 cards, no dups |
| 1a home `/` + `/page/2/` | PASS — 24+24, no dups |
| 1a facet `classic-erotica` p1/p2 | PASS — 24+24, no dups |
| 2 video ×5 (×1 facet run) | PASS — 200, stream selector 1/page, titles |
| 3 streams ×5 | PASS — 206 `application/vnd.apple.mpegurl`, distinct |
| 4 related `div.single-related-posts article.post-item` | PASS — 22–23 matches, distinct, no self |
| 5 search↔load agreement | title PASS; poster agreement FAIL ×3 — **instrument-limited**, adjudicated below |
| 6 load-response fields | PASS — recommendations/tags/plot/year/actors all assigned in Kotlin |
| 2a tags | PASS — present 5/5 |
| 2a actors | NOTE (matches nothing) — **instrument-limited**: pydom `field text` visits the first matched anchor, whose inner text is empty (the actor block's `a.blog-img` *image* anchor); the provider reads the `title` attr on that anchor, live-verified 8/1/8/14/8. Same pydom first-block artifact class documented in FINDINGS-538. |
| 2a year/duration | NOTE — no dedicated selector: year via og:title regex (exposed), duration not exposed on-site |

**Poster-agreement FAILs adjudicated raw (same 2026-09-11 artifact class):** search cards
carry the theme's crop renditions (`…-165x248.jpg`, sometimes a re-based crop file like
`vu5qphc8vxkr-165x248.jpg`), the load page og:image the full file — both fetch
`200 image/jpeg` live; path equality across surfaces is impossible, and this is the
*site's* rendition pipeline, not a provider regression (the provider parses each surface's
real markup byte-exactly).

Fix policy: **0 confirmed provider-code conditions → 0 code changes, version stays 11.**
Gates: `gradlew Eroticmv:test` (72/0), clean `Eroticmv:make` → `.cs3` green,
`check-findings.sh` PASS.

## Risks / blockers

- New Cloudflare fingerprint challenge on plain curl (see Instrument condition) — suspected
  for in-app OkHttp, unadjudicated from the runner. `vpnStatus = MightBeNeeded` already set.
- Probe region: runner IP (US datacenter); geo-gate not re-tested (region check was plain-curl
  healthy at last deep; tier-2 surfaces all reachable).
