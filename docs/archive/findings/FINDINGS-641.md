# FINDINGS-641 — MissAV deep pass (issue #641, sub-issue of epic #631)

Run date: 2026-10-16. Tier: **plain-curl on every surface** (TLS-impersonated tier used for
canaries and the surrit re-check; browser step never reached — no blocked surface).
Runner IP country: **US** (ipinfo). Canaries 3/3 match: film1k 403 plain / 200 TLS,
eporner 200, pandamovies.pw 302 → 200 @ pandamovies.org.

Fixtures: live captures saved as `MissAV/src/test/resources/en-new-page1.html` and
`en-new-page2.html` (real /dm539/en/new?page=1|2 responses, issue-#641 evidence).

## Engine fingerprint

MissAV-style custom app (Laravel/Tailwind + Alpine.js): `div.grid.grid-cols-2` listing grids,
`@click`/`x-cloak` Alpine attributes, eval-packed player JS
(`eval(function(p,a,c,k,e,d)…`, 2 packers per video page), surrit m3u8 behind the decode step.
In-repo reference: MissAV itself.

## Home pagination (getMainPage rows)

Provider rows sampled live this pass: `/dm539/en/new` (Newly Added), `/dm169/en/weekly-hot?sort=weekly_views`
(Weekly Hot), `/dm628/en/uncensored-leak` (Uncensored Leak). All 200 on plain curl; NiceHttp's
redirect-following preserves `?page=N` (`/en/new` 301 → `/dm539/en/new?page=1|2`, final URL query intact).

- **Card selector (raw site grammar)**: `div.content-without-search div.grid.grid-cols-2 > div`.
  The real listing grid is always inside `div.content-without-search`; a second
  `div.grid.grid-cols-2` (footer/nav links `new`, `contact`) sits outside it — the provider's
  coarser selector `div.grid.grid-cols-2 > div, div.thumbnail.group` matches the nav grid too,
  but every nav shell fails the poster-null/blacklist filters in `toMainPageResult`, so no card
  reaches the user. Card inner: link `<a>` wraps `video.preview` + `img` (poster in `data-src`,
  `src` is a 1×1 data: placeholder) and the title anchor is `a.text-secondary` inside `div.my-2`.
  Verify-compatible form (pydom has no `>` combinator or comma alternation — same tooling
  class as #302/#328): **`div.content-without-search div.thumbnail.group`** — scope + the card
  thumbnail class; one real card per match, nav shells carry no thumbnail. Title sub-selector
  `a.text-secondary`; poster = card img `data-src`.
- **Each card matches the provider selector twice** (grid cell nests a `div.thumbnail.group`);
  `distinctBy { it.url }` in `getMainPage`/`search`/`quickSearch` handles this (12 raw → 12
  after url-dedupe + nav filtering... 26 raw elements → 12 real cards + 2 nav shells).
- **weekly-hot p1/p2: 12 + 12 cards, overlap 0** ✓. **uncensored-leak p1/p2: 12 + 12, overlap 0** ✓.
- **/dm539/en/new p1/p2: 12 + 12 cards, overlap 5 — site-side, stable, structural.** p1's tail
  (musume-101626_01, pow-096, hhl-163, hjmo-742, mucd-362) = p2's head, byte-identical order.
  Stability proof: p1 and p2 refetched 3× over ~20 min — p1 12/12 self-overlap, p2 12/12
  self-overlap (no fetch-time churn), overlap stays 5. p2∩p3 = 0, p1∩p3 = 0, p10∩p11 = 0 — the
  repetition is scoped to the p1/p2 boundary. The `?sort=published_at` variant (which #327 found
  duplicating 6 cards in September) is now disjoint — the skew migrated to the unsorted URL.
  Mechanism consistent with a stale p2 cache: p2's window predates the 7 newest publications,
  so its head shows p1's then-tail. **Site-side condition; the provider cannot fix the site's
  cache.** Drift-recurrence rule: MissAV has 4+ closed drift/broken issues (#34, #302, #327,
  #328) → hardening, not a URL swap: `getMainPage` now keeps a per-row seen-set and drops
  already-served URLs on page ≥ 1... (page 1 re-seeds; deeper pages filter via pure
  `MissAVParse.filterSeen`, TDD-tested against the two live captures).
- **Pinned-card exception re-verified**: the 2026-09-29 registry note "1 pinned card on /en/new"
  — no separate pinned markup exists; the p1/p2 boundary repeat is that same condition, now
  5 cards. Recorded as the site-native boundary skew above; no separate pinned-card mechanism.

## Search

`GET https://missav.live/en/search/milf?page=1|2` → 200, 12 real cards each
(`div.content-without-search div.grid.grid-cols-2 > div`), **p1∩p2 video overlap = 0** ✓
(only `new`/`contact` nav shells repeat across pages; excluded from cards by the chained
selector, and by provider filters in-app). Title: `a.text-secondary` inside the card; poster:
card `img` `data-src` (`src` is a placeholder).

## Quick search — PRESENT (distinct endpoint, newly recorded)

`GET https://missav.live/en/search/suggest?q=milf` → 200, full HTML page titled
"Search result of suggest - MissAV | Watch HD JAV Online", **13 distinct video cards**, same
card grammar as search. The path variant `/en/search/suggest/milf` degenerates (title
"Search result of suggest/milf", 4 cards) — the `?q=` form is the real endpoint. The
`?page=2` form serves disjoint items (12 + 12, overlap 0) but CloudStream quick search is
single-call by contract. **The provider did not implement quickSearch** (absent from MissAV.kt;
prior FINDINGS never recorded this surface — recorded present as of this probe). Fixed
in-run: `quickSearch(query)` now parses `/en/search/suggest?q=` through the same card pipeline.

## Video pages (3 sampled, different listings)

All 200 on plain curl, same page grammar:

| page | listing | title (h1.text-base) | og:video:duration | year (time) | actress | actor | genre |
|---|---|---|---|---|---|---|---|
| /en/mxgs-1450 | /en/new p1 | MXGS-1450 A young wife dissatisfied with her sex life betray… | 7285 s | 2026-10-16 | — | — | — |
| /en/milf-091 | search milf p1 | MILF-091 Let yourself go and be treated like a toy… | 7024 s | 2026-06-01 | — | — | 4 tags |
| /en/mimk-288 | weekly-hot p1 | MIMK-288 The series has sold over 400,000 copies… | 7285 s | 2026-09-24 | Rena Miyashita | — | 6 tags |

Additional sample: /en/mudr-407 (the /en/new row's head card) 200, full grammar, but the site
itself carries no synopsis — `og:description` content="" and the plot paragraph absent. Site
per-video data variation, not a page-shape defect: the plot source (og:description / the
`div.mb-1.text-secondary` paragraph) is the same everywhere; the provider ships plot only
when the site has one (its load() reads og:description content, null-safe). Same class as
#328's people-row omission.

Notes: og:image poster on all 3; og:description plot present on all 3 (the mudr-407 case above
shows the site itself omits the synopsis per-video — plot exposure stays og:description);
people rows (`div.text-secondary` + label span, Actress:/Actor:/Genre:) render only where the
site has them — the sampled pages carry Actress+Genre where the site has them (milf-091 has
none, same site behavior #328 recorded). Exposure inventory: title ✓, poster ✓, plot ✓
(og:description; site-dependent content), tags ✓, actors ✓ (site-dependent rows), year ✓
(`<time>` on all 3), duration ✓ (og:video:duration, seconds). Main `<video class="player">`
element present on all 3.

## Stream sources (per video page)

Every sampled page carries the same chain, byte-identical to the known flow:
eval-packed JS → `getAndUnpack` → `source='https://surrit.com/<uuid>/playlist.m3u8'`
(+ `source842`/`source1280` variants of the same UUID). Unpacked live on all 3 pages
(probe-side Dean-Edwards decode mirroring `getAndUnpack`):

- /en/mxgs-1450 → `18ee1328-ceea-4c80-8935-ca0ee5a34f02`
- /en/milf-091 → `f2e36563-2c44-4d63-beda-f06dbb1ed149`
- /en/mimk-288 → `6346777f-97a8-4a4e-bbb7-87e51103a2c2`

Distinct UUIDs per video ✓ (no shared stream path). **surrit serving from runner: HTTP 403
text/html — Cloudflare "Attention Required!" challenge page — on plain curl AND
TLS-impersonated curl (chrome fingerprint), with and without the Referer header, on playlist
and 64kbps-mono paths.** Consistent with every recorded run since #302: runner-IP gate,
extraction schema proven correct (3 distinct live UUIDs), **verify stream playback in-app**.

## Recommendations

recombee RAPI `client-rapi-missav.recombee.com/missav-default/recomms/items/mudr-407/items/`
POST with the embedded HMAC token → **200, 12 recomms** (sample: ebwh-296-uncensored-leak).
Provider flow unchanged and live.

## Headers / referer

No unlock needed: every surface (home rows, search, suggest, video pages) serves 200 to a
plain UA with no cookies. surrit requires nothing beyond what the runner never gets (IP gate).

## Pagination

- Search: `?page=N`, p1/p2 disjoint ✓ (video cards).
- Home rows: `?page=N` (dm-redirect preserves the query), weekly-hot/uncensored-leak p1/p2
  disjoint ✓; /en/new boundary skew documented above.
- Quick search: single-call surface (site does offer ?page=2, disjoint, not used by contract).
- Past-the-end: /en/new serves pagination links up to page=1999+ (deep pages remain 200).

## Risks / blockers

- surrit.com 403 Cloudflare challenge from this runner IP (plain + TLS) — stream playback
  must be verified in-app (maintainer-only, per repo policy).
- /en/new p1/p2 boundary skew (5 cards) — site-native cache condition; provider-side
  cross-page dedup shipped this run (v18 → 19). Re-probe note: if a future pass sees the
  sorted variant skewed again, that is the same site cache class, not provider drift.

## Verdict deltas

- Verdict: **ok retained** — every surface healthy on plain-curl; the /en/new skew is a
  site-side cache condition, hardened provider-side; no user-facing duplicate cards after fix.
- drift_confirmed: 0 → 1 (the #327 duplication class re-confirmed live on the sibling URL;
  fixed in-run per fix policy, TDD v18 → 19: `MissAVParse.filterSeen` + per-row seen-set,
  `quickSearch` added against the newly-recorded suggest surface).
