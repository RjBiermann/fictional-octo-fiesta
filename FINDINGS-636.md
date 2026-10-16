# FINDINGS-636 — Cat3Film deep pass (issue #636, sub-issue of epic #631)

Run date: 2026-10-16. Tier: **plain-curl on every surface** (TLS-impersonated tier exercised for
canaries and the sssrr.org re-check; browser step never reached — no blocked surface).
Runner IP country: **US** (ipinfo).

## Phase 0 — calibration

Canaries **3/3 match**: film1k-challenge-pair (plain 403 / TLS 200 ✓), eporner-healthy
(plain 200 ✓), pandamovies-redirect-origin (plain 302 → pandamovies.org, TLS 200 ✓).
Instrument healthy — no environment-degraded downgrade; full verdict vocabulary in effect.

## Engine fingerprint

Unchanged from `Cat3Film/FINDINGS.md`: custom Go/template engine (not KVS/WP), server-rendered
`a.card` grids, `/api/v1/episodes/{id}/sources` JSON API, AJAX search `/_ajax/search?q=` → JSON,
JW Player on watch pages, Cloudflare in front (cf-ray everywhere, 200 — no challenge leg).

## Home pagination (getMainPage rows)

Provider rows: `/movies`, `/tv-series` — both plain-curl 200.

- **/movies**: page 1 **30 cards** / page 2 **30 cards**, href overlap **0** ✓
  (p1 head: colonel-redl, malombra, lady-of-the-camelias…; p2 head: sexus, too-late, slugs…).
- **/tv-series**: page 1 **14 cards** (hot-line, risque-business-japan, virgin…);
  page 2 → 200 with the full listing chrome (title, genre/country filters) and **0 cards** —
  the site serves an **empty page past the last page** (14 series total), not a duplicate flood
  and not a clamp. Site shape, not drift; a CloudStream row stops paginating on an empty list.
- Home rank page `/` (not a provider row): 60 `a.card` anchors, 48 distinct — rank-grid
  cross-listing repeats cards within the page; pre-documented site reality, unchanged.

## Search (deep-pass focus items 1–2)

`GET /_ajax/search?q={q}` → 200 `application/json`, contract **unchanged**:
`{"results":[{"format","slug","thumb","title","year"}]}`.

- **milf → 0 revisited** (issue-specified deep-pass focus): `milf` again returns
  `{"results":[]}` — but every term certain to match returns hits: `handmaiden`→1 (`the-handmaiden`),
  `the`→8, `teen`→8, `sex`→8 (mixed Movie/TV `format`), `anal`→2, `lesbian`→2, `the-handmaiden`
  as exact string. The endpoint is healthy; **a 0-result for `milf` is catalog reality on a
  niche site** (the 2026-09-29 registry note already framed it as such) — closed, not drift.
- **_ajax JSON contract re-verified**: shape identical, `format: "Movie"|"TV"` still carried
  per result, single page (no pagination key).
- Multi-word queries work (`hot line` → `hot-line Hot Line`); the live-typing box reuses this
  same endpoint (site.js: `fetch("/_ajax/search?q=" + …)`) — **no distinct quick-search
  endpoint** (absent surface, recorded as absent per spec, matching every prior pass).

## Video pages (≥3 required — 4 probed, all fresh slugs vs prior runs)

Plain-curl 200 ×4 detail + watch pages; `section#related a.card` = **4 cards each**:

| page | type | ep ids | notes |
|---|---|---|---|
| `/colonel-redl` | Movie (badges `HD, 1985, 2h 32m, Movie, EN Sub`) | 1816 | first card /movies p1 |
| `/too-late` | Movie (`1996, 1h 46m, Movie`) | 1780 | first card /movies p2 |
| `/hot-line` | TV (`1995, 30m, TV`) | 1009–1016 | 2 wserver panes `Season 1`/`Season 2`; `.epbtn[data-ep]` 8 buttons, `data-no` restarts per season (1,2,3,4,5,6,1,2) — `Parse.episodes` shape unchanged |
| `/sexus` | Movie (`1965, 1h 16m, Movie`) | 1781 | first card /tv-series-adjacent listing sample |

All detail pages expose `h1.info-title`, `og:image` (absolute `https://cat3film.com/uploads/…`),
`og:description` plot, Genre/Country/Director/**Cast** `.info-people` blocks (label-anchored,
provider picks by `.ip-label` text), year badge anchor `/year/…`, ld+json with
`aggregateRating.ratingValue` — `duration` in ld+json on the 3 movies (`PT152M`, `PT106M`,
`PT76M`); the series page has none (site shape, matches the 10-15 record).

**ep-id churn quirk (store-worthy)**: 1816 is Colonel Redl's **live** episode id today, but it
was the 2026-10-15 dead-ep sanity probe (`/api/v1/episodes/1816/sources` → `episode not found`).
Ids are recycled across titles over time; the "not found" error contract is real but any fixed
dead-id sentinel goes stale — recorded for future sanity-probe design.

## Stream / embed resolution (spec focus 4)

`GET /api/v1/episodes/{id}/sources` → 200 `application/json` ×4, shape unchanged:

    {"backup":"https://player.abyssplayer.com/<id>",            // present on 3/4
     "sources":[{"file":"https://master.abyssssss.top/<token>","type":"hls"}],
     "subs":[],"success":true}

- CDN host unchanged since the 2026-10-15 move: **`master.abyssssss.top`** (no regression to
  `abyssssss.top` apex or the dead `cat3.asuka-vod.site`). `Parse.streamUrl()` is host-agnostic
  — nothing to fix by design (the #618/#619 structural pinning covers exactly this drift family).
- **`<token>/index.m3u8` → 200 `application/vnd.apple.mpegurl` 4/4** (valid VOD `#EXTM3U`,
  `TargetDuration 4`, 2k+ segments; stream paths distinct across videos ✓). Reached also with a
  bare **okhttp UA and no Referer** ✓ — provider's plain-HTTP runtime matches.
- **Backup embed leg**: `player.abyssplayer.com/<id>` → 200 HTML, `<title>3028_01_001.mp4`,
  `const datas = "…"` (996 chars) → `POST enc-dec.app/api/dec-abyss` → 200, `status: 200`,
  `result.sources` with `url/size/type/codec/status` keys (`480p`, `status: true`, one source
  this sample) → shared `AbyssParse.sourcesFromResult` field names **match live shape**
  (`url`, `type`→quality, `status` gate).
- **Final CDN of the backup route — datacenter artifact**: the sora MP4 host
  `*.sssrr.org/sora/…` serves a **hard Cloudflare block ("Attention Required — You are unable
  to access sssrr.org… security solution")** on this runner's datacenter IP, plain curl **and**
  TLS-impersonated (403 both). No redirect hop: the 2026-10-15 `trycloudflare.com` mirror is
  gone this pass. Per the verdict rules this is a **Suspected datacenter-IP artifact — never a
  standalone finding; a residential differential decides**. Scope note: it can only affect the
  **backup/2nd route** — the primary token→m3u8 route is healthy 4/4 and CloudStream devices
  on residential IPs are outside this block class. Filed **no issue** (dedup + suspected rule);
  recorded here and in the registry reason.

## Search ↔ load agreement (check 5 compensation for the JSON-blind tool)

Re-proven by curl: `_ajax/search?q=sexus` → `[('sexus','Sexus')]` agrees with load page
`/sexus` (`h1.info-title = Sexus`, same slug); `_ajax/search?q=hot line` →
`[('hot-line','Hot Line')]` agrees with `/hot-line` (`h1.info-title = Hot Line`).

## verify.sh (pipeline Verification, live site)

`RESULT: FAIL` **only** on the pre-documented instrument limitation:

- **check 1 (search): FAIL-by-tool, site shape — third consecutive record (2026-09-11,
  2026-10-15, this pass).** The site's only search surface is the AJAX JSON endpoint
  `/_ajax/search?q=`; the script asserts HTML `a.card` and cannot count a JSON page.
  Provider uses exactly this endpoint; agreement re-proven by curl above. No provider defect.
- **check 1a home rows**: `/movies` 30 + `/movies?page=2` 30 — **PASS** (`/tv-series` page 2
  emptiness documented above; the row's page 1 is served by the same 200 listing shape).
- **check 2 video pages ×4**: 200, `a[href*="/watch/"]` next-leg selector 1/1, title
  `h1.info-title` 4/4 — **PASS**; no cross-video title/poster/plot collisions, no poster dupes.
- **check 3 streams**: positional `--stream-url` (FINDINGS chain:
  `.epbtn[data-ep]` → `/api/v1/episodes/{id}/sources` → `master.abyssssss.top/<token>/index.m3u8`)
  4/4 → 200 m3u8 playlist bodies, paths distinct within and across videos — **PASS**.
- **check 4 related**: `section#related a.card` = 4 on all four pages, non-empty titles, no
  self-references, no duplicates — **PASS**.
- **check 2a fields**: tags (`div.info-people` first block = Genre) 4/4, actors
  (`div.info-people` — Cast block presence verified per page by label grep: `Cast:` 1/1 ×4)
  4/4, year (`div.badges a[href^="/year/"]`) 4/4 — **PASS**. Duration intentionally
  un-asserted (ld+json Movie-object only; the series page has none — 3/4 all-or-none split is
  site shape, per the #619 record).
- **check 5**: NOTE (expected — JSON surface has no HTML cards); agreement proven by curl.
- **check 6 LoadResponse completeness**: recommendations 3, tags 2, plot 3, duration 3,
  year 3, actors 4, score 2, posters 4 assignments in `Cat3Film.kt` — all ≥1 — **PASS**.
- quick search: none passed (absent endpoint) — script NOTE noted, explicit in FINDINGS.

## Verdict

**`ok`** (retained), tier `plain-curl`, all surfaces healthy, **0 confirmed conditions**,
**0 findings filed / 0 false positives** (dedup: no standing issue for Cat3Film; recently
closed #617/#618/#619 merged 2026-10-16 carry no unaddressed condition; the only new
observation — sssrr.org datacenter block — is a suspected probe-IP artifact under the
residential-differential rule, recorded as such, not filed).

Fix policy: no confirmed condition → **no code change, no version bump** (Cat3Film stays 14).

## Gates

- `./gradlew Cat3Film:test` **PASS** (15/15)
- clean `./gradlew Cat3Film:make` **PASS** (BUILD SUCCESSFUL)
- `bash .pi/skills/audit-providers/scripts/check-findings.sh` **PASS** (semantic diff only:
  Cat3Film entry + run23 append)
- `verify.sh` — every provider-relevant check PASS (single FAIL line = documented
  FAIL-by-tool site shape, above)
