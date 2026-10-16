# FINDINGS-632 — PornXP (pxp.news) deep pass (approved #631 spec, one provider)

Run: 2026-10-16. Issue #632 (sub-issue of #631). One-provider deep audit: full surfaces, per-tier instrument, standing #476 re-check. **Verdict: `ok`, tier `plain-curl`. 0 confirmed conditions — no code fix, no version bump.**

## Phase 0 — calibration

Canaries 3/3 match (`audits/canaries.json`, last_verified 2026-10-14):

| canary | expected | observed |
|---|---|---|
| film1k-challenge-pair | plain 403 / TLS 200 | 403 / 200 ✔ |
| eporner-healthy | plain 200 | 200 ✔ |
| pandamovies-redirect-origin | plain 302 / TLS 200 | 302 → 200 @ pandamovies.org ✔ |

Instrument healthy; no environment-degraded downgrade in effect.

## Probe evidence (escalation ladder: plain curl → TLS-impersonated; browser never needed)

**Instrument tier per surface: plain-curl everywhere (TLS tier confirmed home 200; browser step not reached — no blocked surface).**

### Home pagination (page 1 vs page 2 must be disjoint)

- `GET https://pxp.news/` → 200 (51,608 B), 36 `div.item_cont` cards, 36 unique data-ids
- `GET https://pxp.news/?page=2` → 200 (45,579 B), 36 cards
- href overlap p1∩p2 = **0** (36/36 each, disjoint)
- `GET https://pxp.news/?page=3` → 200, 36 cards, p1∩p3 overlap = 0
- One p1∩p2 **data-id** collision (`65258502`): two *distinct* mirror videos — `/videos/65258502612` vs `/videos/65258502792`, same title ("Daddy, I Only Want You"), same duration (31:05), same poster filename prefix, different hrefs/posters. Site-level mirror-duplicate content artifact (same class as the "Maria Alfonsina" pair in FINDINGS), not pagination drift.

### Search

- `/tags/ATKGirlfriends` → 200, 36 cards; `?page=2` → 200, 36 cards; **href overlap 0**; `#pages` carries the site's `>` next control
- `/tags/DaughterSwap` p1/p2 → 200, 36 cards each; **overlap 0**; no within-page title dups (FINDINGS's intended verify sample)
- Standing #476 (`https://pxp.news/search?query=anal` zero-byte / `?q=` ignored) — condition is the provider's known, *already fixed* behavior: provider searches `/tags/<q>`. The `?q=` form still serves the unfiltered homepage grid (site-level, unchanged since #330; provider does not use it). **#476 stays clear — re-confirmed live, no filing.**

### Quick-search surface check (deep-pass focus)

**No distinct quick-search endpoint — recorded as absent, not drift.**

- `2.js` only rewrites the search form submit to `/tags/' + encodeURIComponent(q)`; no keyup/autocomplete/suggest handler
- `/co/` ajax → 200, `text/html`, 0 bytes (click counter, not a suggest endpoint)
- Provider keeps `hasQuickSearch = true` with `quickSearch() = search(q, 1)` (full search fallback) — consistent with site reality

### Video pages (≥3 sampled, varied listings)

| URL | from listing | title source | streams |
|---|---|---|---|
| `/videos/268309051653` | home p1 | "Léa, 20ans, coquine toulousaine!" | 360p/720p/1080p |
| `/videos/17686704675` | `/tags/` | "Malibu 1/2" | 360p |
| `/videos/37955051792` | `/released/` | "AJ Classics 50yo Mature Redhead Dallas Gets Fucked" | 360p |

All 200 with `.player_details h1` title, `#player poster`, `#desc` plot/date, `div.tags a` tags, related `a[href^=/videos]` cards (25–36 per page). Identity fields differ across the 3 (Distinct bar met).

### Stream chain re-resolution

`#player source` mp4s (1–3 per page), scheme-relative `//xxx.pornxp.sh/…/360.mp4|720.mp4|1080p…`:

- Range-GET → **206 video/mp4** on all 3 sampled pages (both 360 and 1080/720 variants)
- **Bare request (no Referer) also 206** — no referer required; provider keeps `referer = mainUrl` anyway
- CDN subdomain rotates across rounds (`ce.` → `cdrn.` (#476) → `xxx.` today). Not drift: provider extracts `src` from the page itself, no hardcoded host in `PornXP/src/main/kotlin/` (grep-verified). Any future host rename is followed statically.

## Registry delta

`audits/findings.json` — only the PornXP entry (`verdict` stays `ok`, `tier` stays `plain-curl`, `surfaces` refreshed incl. quicksearch-absent note, `last_deep` → 2026-10-16) plus one `last_runs` entry (run15). No fleet-wide rewrite. `drift_confirmed` stays 0, `chronic` stays false.

## Issues

- **0 new findings.** Dedup against #476 (standing, closed, condition re-confirmed clear) ran before any filing decision.
- No fix branch needed: every surface healthy → fix-policy's "confirmed condition" term never fired; `gradlew PornXP:test` + build still required as the delivery gate and they pass on the unchanged tree.

## verify.sh (mechanical gate) — PASS

search ×2 (36 cards, 0 dups) · home ×2 (36 cards, 0 dups) · quicksearch absent (explicit FINDINGS note) · video ×3 with `video#player source` matches 3/1/1 · streams 3+1+1 → 206 video/mp4 · related `a[href^=/videos]` 36/25/36 · tags on 3/3 · LoadResponse tags/plot/duration/recommendations all populated. Known structural NOTEs (all pre-documented in `PornXP/FINDINGS.md`): no og: metas on this site, so check-5 search↔load agreement can't be exercised through the harness (title readable only via `<title>` suffix form), and poster/plot read paths confirm the site's real markup (`#player[poster]`, `#desc`).
