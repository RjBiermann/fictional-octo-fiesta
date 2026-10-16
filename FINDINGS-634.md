# FINDINGS-634 — Sexfilm (en.sex-film.biz) deep pass (approved #631 spec, one provider)

Run: 2026-10-16. Issue #634 (sub-issue of #631). One-provider deep audit: full surfaces, per-tier instrument, the 2026-09-29 two-step cookie-jar handshake re-tested, standing limitations re-checked. **Verdict: `ok`, tier `plain-curl`. 0 confirmed conditions — no code fix, no version bump (stays 12).**

## Phase 0 — calibration

Canaries 3/3 match (`audits/canaries.json`, last_verified 2026-10-16):

| canary | expected | observed |
|---|---|---|
| film1k-challenge-pair | plain 403 / TLS 200 | 403 / 200 ✔ |
| eporner-healthy | plain 200 | 200 ✔ |
| pandamovies-redirect-origin | plain 302 / TLS 200 | 302 → 200 @ pandamovies.org ✔ |

Instrument healthy; no environment-degraded downgrade in effect.

## Two-step cookie-jar handshake (2026-09-29 registry note) — NOT REPRODUCED

A single fresh cookie-less request (`curl -A <Chrome-116 UA> https://en.sex-film.biz/`) serves the full homepage: **200**, 24 `div.short` cards, DLE fingerprints intact. `Set-Cookie` (PHPSESSID + language=en) is emitted on the first response but nothing downstream requires replaying it — home p2, all section rows, search, and video pages all fetch 200 on independent fresh requests with no cookie jar. The provider needs no cookie plumbing; the handshake was either a transient session state or a probe-time artifact.

## Probe evidence (escalation ladder: plain curl → TLS-impersonated; browser never needed)

**Instrument tier per surface: plain-curl everywhere. TLS tier confirmed home 200 (`impersonate.sh`); browser step not reached — no blocked surface.**

### Home pagination (all 6 `getMainPage` rows, page 1 vs 2 must be disjoint)

Per row: p1 200, p2 200, 24 `a.short-poster` cards each, **href overlap 0** on every row:

| row | p1 | p2 | overlap |
|---|---|---|---|
| `/movies/` | 200 / 24 | 200 / 24 | 0 |
| `/porno-video/` | 200 / 24 | 200 / 24 | 0 |
| `/movies/hd-porno-movies/` | 200 / 24 | 200 / 24 | 0 |
| `/movies/fullhd-porn-movie/` | 200 / 24 | 200 / 24 | 0 |
| `/movies/porno-parodies/` | 200 / 24 | 200 / 24 | 0 |
| `/movies/vintage/` | 200 / 24 | 200 / 24 | 0 |

Cross-section card sharing (e.g. Movies ∩ HD, FullHD p2 ∩ Vintage p2) observed again — pre-documented site reality (sections overlap), not per-row duplication (Sexfilm/FINDINGS.md #276 section).

### Search

- `GET /index.php?do=search&subaction=search&story=tarzan` → 200, 10 cards (includes `/6760-tarzan-x-shame-of-jane.html` — the sampled video, feeding the search↔load agreement check)
- `story=milf` p1/p2/p3 (`&search_start=2`, `&search_start=3`) → 200, 24/24/24 cards, **p1∩p2 = 0, p1∩p3 = 0**
- Provider shape unchanged: `div.short` cards, `a.th-title` titles, `img[data-src]` posters

### Quick-search surface check (deep-pass focus)

**No distinct quick-search endpoint — recorded as absent, not drift.**

- `GET /index.php?do=suggest&story=mil` → 200 `text/html`, a **full unfiltered HTML page** (24 cards, full template chrome) — not a JSON/AJAX suggest payload, and the `story` param is ignored
- No suggest/autocomplete/keyup handler anywhere in the homepage HTML (grep: 0 hits)
- Provider does not set `hasQuickSearch` (vendored default applies) — consistent with site reality

### Video pages (6 sampled, varied listings)

| URL | from listing | fields |
|---|---|---|
| `/11775-xxx-super-stars-tarra-white.html` | home p1 | all live |
| `/6760-tarzan-x-shame-of-jane.html` | search `tarzan` | all live |
| `/11733-gorgeous-blonde-nicole-nichols-first-dp-double-vaginal.html` | `/porno-video/page/2/` | all live |
| `/11587-anal-island.html` | `/movies/vintage/page/2/` | all live |
| `/11669-car-wash-angels-2.html` | `/movies/hd-porno-movies/page/2/` | all live |
| `/10556-stars-french-touch.html` | related section of `/11669` | all live |

All 200 with unified shape; per-page extracted values: `h1#s-title` + `og:title` (e.g. "XXX Super Stars Tarra White (2026, HD) watch porn movie online"), `og:image` on `sex-empire.org` (`.../uploads/posts/2026-10/xxx-super-stars-tarra-white.webp`), `div#s-desc` plot, `ul.flist-col` tags anchors (4–8) + Casting anchors, `span.gv a[href*=/watch/year/]` → 2026/1996/1999/2025 (the nav-menu "Premieres" anchor also points at `/watch/year/` but sits outside `span.gv` — the provider's scoped selector is correct), `meta[itemprop=duration]` PT8742S/PT6888S/PT2437S/PT6184S/PT9360S/PT17277S. Identity fields distinct across the 6 (Distinct bar met). Related `div.sect-c div.short` present on every page (6 titles each).

### Stream chain re-resolution (two-hop, per video page)

Click-injected embeds enumerated per page (unchanged grammar): `filmcdm.top/e/<key>`, `morencius.com/embed/<key>`, `s2.filmcdn.top/e/<base64>`, `playmogo.com/e/<key>`.

- **filmcdm.top** (4/4 pages): Dean-Edwards packed JW8 config unpacks to `links={hls4,hls3,hls2}`; `hls4` relative `/stream/.../master.m3u8` resolves against the embed host → **200 `application/vnd.apple.mpegurl`** on every sampled page. **Bare request (no Referer) also 200** — no referer required; provider keeps `referer = mainUrl` harmlessly.
- **morencius.com** (3/3 pages where present): same packed-JW grammar; `hls4` → 200 m3u8; `hls2` acek-cdn → 200 today (prior "token-bound hls2" note relaxed; hls4 remains the reliable leg).
- **s2.filmcdn.top** (`/6728-avengers-vs-x-men-xxx-parody.html`, `/10556`): page carries the IP-locked signed `cfglobalcdn.com` m3u8 (base64 requester IP matches probe IP) — **`cfglobalcdn.com` still TCP-times-out from this runner** (exit 28). Standing site reality, not drift.
- **playmogo.com**: **403** bot wall to the runner (standing, intentionally unmatched by `Parse.embeds`).
- One hls3 alternate host (11587 `careercoachingtips.site`) 403'd while that video's hls2/hls4 served 200 — per-host CDN artifact, not a condition (provider emits all legs; app tries them).

## Registry delta

`audits/findings.json` — only the Sexfilm entry (verdict stays `ok`, tier stays `plain-curl`, surfaces/reason refreshed incl. quicksearch-absent + handshake-not-reproduced, `last_deep` → 2026-10-16) plus one `last_runs` entry (run18). No fleet-wide rewrite. `drift_confirmed` stays 0, `chronic` stays false.

## Issues

- **0 new findings.** Dedup: no standing issue for Sexfilm (`standing_issue: null`); no recently closed Sexfilm drift issues; nothing to file — every surface healthy.
- No fix branch content: every surface healthy → fix-policy's "confirmed condition" term never fired; `gradlew Sexfilm:test` + clean `gradlew Sexfilm:make` pass on the unchanged tree (version stays 12).

## verify.sh (mechanical gate) — PASS ×6

Main run (search ×3 + home row-1 p1/p2 + 6 videos) + one run per remaining home row (p1+p2 each) — per-row because cross-section card overlap is a pre-documented site reality that trips the run-wide dedupe by design:

- search milf p1/p2 24+24 disjoint, tarzan 10; home rows 24+24 each, 0 per-row overlap
- video ×6: `div#video_container` ≥1 each; two-hop embed streams 200/206 `application/vnd.apple.mpegurl`
- tags/actors/year present on 6/6; related 6 per page; LoadResponse recommendations/tags/plot/duration/year/actors all assigned
- NOTEs, understood: no `--quick-search-url` (FINDINGS records no distinct endpoint); no `--video-duration-selector` (pydom void-meta limit; duration assigned in `load()`); search/home card selector is the leaf anchor `div.title-box a.th-title` (pydom nested-div truncation, #521 note)
