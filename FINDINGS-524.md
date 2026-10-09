# FINDINGS-524 — fleet sweep + details-field gap audit (all providers)

Issue #524: *"audit and probe all providers / find gaps in details from the site /
cloudstream missing fields gaps / Create GitHub issue for the all the issues found
during this audit and probe."*

Audit-only run (audit-providers skill): artifacts only, no provider code changes.
Operator: pi agent, run branch `devloop/issue-524`, 2026-10-09. Probe IP: US
datacenter (CI runner). All timestamps 2026-10-09.

Note: run 6 (issue #511, PRs #513/#514) was closed **unmerged** — its
findings.json/canaries.json updates never landed on master; the committed registry
on HEAD is still at run-5 state (2026-09-29). This run re-verified the load-bearing
run-6 claims live and folds the surviving facts into this registry update
(cross-checked against `FINDINGS-511.md` in git history).

## Phase 0 — canary calibration

All 3 canaries **MATCH** (2026-10-09, same runner IP):

| Canary | plain-curl | tls-impersonated | expected | verdict |
|---|---|---|---|---|
| film1k-challenge-pair | 403 | 200 | 403 / 200 | match |
| eporner-healthy | 200 | — | 200 | match |
| pandamovies-redirect-origin | 302 → pandamovies.org | 200 (via 302) | 302 / 200 | match |

Instrument healthy — no degradation, no Blocked downgrades needed.

## Phase 1 — sweep (all 26 providers, 2026-10-09)

Instrument tier per surface recorded (`probe()` lines in
`audits/sweep524.sh`; TLS tier = curl_cffi chrome impersonation).

| Provider | home | search | tier | verdict |
|---|---|---|---|---|
| AllClassicPorn | 200 | 200 (`/search/sex/`) | plain | ok |
| Cat3Film | 200 | 200 (`/_ajax/search?q=sex`) | plain | ok |
| Cat3Movie | 200 | 200 (`/search/sex`) | plain | ok |
| EPorner | 200 | 200 (`/search/sex/`) | plain | ok-suspected (age-wall SPA class, #447/#462 excl.) |
| Eroticmv | 200 | 200 (`/?s=sex`) | plain | ok |
| Film1k | 200 TLS | 200 TLS (`/?s=sex`) | tls | ok-drift (standing #496) |
| FreePornVideos | 200 TLS | 200 TLS (`/search/sex/1/`) | tls | ok-suspected (session/leech class, #449/#466 excl.) |
| FullPorner | 200 TLS | 200 TLS (`/search?q=sex`) | tls | ok — tags gap filed (#526) |
| HQPorner | 200 | 200 (`/?q=sex&p=1`) | plain | ok — tags gap filed (#527) |
| JavGuru | 200 | 200 (`/?s=sex`) | plain | ok |
| Javbangers | 200 | 200 p1 / **404 p2+** (standing #475 re-confirmed) | plain | ok-drift |
| Javmost | 200 | 200 (`/showlist2/uncensor/1/category/` JSON) | plain | ok (480 clear re-verified) |
| Javseen | 200 | 200 (AJAX search_results) | plain | ok (477 clear) |
| Javtiful | 200 | 200 (`/search?q=sex`) | plain | ok |
| Mangoporn | **200** | **200** (`/?s=sex` + `/page/2/?s`) | plain | **ok-drift — provider parser matches 0 cards (filed #525)** |
| MissAV | 200 | 200 (`/en/search/sex`) | plain | ok |
| Neporn | 200 | 200 (24 items async block) | plain | ok |
| PandaMovies | 200 (.pw 302→.org) | 200 (.org `/search/sex`) | plain | ok-drift (463/471 clear) |
| PerverZija | 200 | 200 (`/?s=sex`) | plain | ok |
| PornXP | 200 | 200 (`/tags/sex`) | plain | ok (476 clear) |
| Porntrex | 200 | 200 (`/search/sex/`) | plain | ok |
| Sexfilm | — | 200 (two-step cookie flow) | plain | ok |
| WatchPorn | 200 | 200 (async block) | plain | ok |
| XMoviesForYou | 200 TLS | 200 TLS (`/search?q=sex`) | tls | ok-suspected (450/467/473 excl.) |
| Xhamster | 200 | 200 (`/search/sex`) | plain | ok |
| ixiporn | 200 (.org) | 200 (.live `/search/sex`) | plain | ok-drift (standing #468) |

## Phase 2 — deep tier (4: Cat3Movie, JavGuru, Neporn, WatchPorn — oldest unstamped)

Same choices run 6 made (its stamps were never merged, so effective `last_deep` unchanged) + WatchPorn.

### Cat3Movie (plain)
- video page `https://cat3movie.org/3-d-sex-and-zen-extreme-ecstasy-2011` → 200
- exposure: og:title/og:image/og:description/og:url present; rating/views present; **no JSON-LD VideoObject, no duration** — provider (year/tags/actors/plot populated) covers everything the site exposes. No gap.
- runtime pagination note (run 6): Latest-row /page/2 site-side carousel duplication — recorded, benign.

### JavGuru (plain)
- 3 video pages 200: `/1059257/`, `/1059292/`, `/1059615/`
- exposure: `Release Date` 1, `rel="tag"` 19–20 per page, javmiku base64 iframe (known extractor path), description block present; **no og: meta tags at all** — provider parses markup, consistent. No gap.

### Neporn (plain)
- search async block 200, 24 `div.item` cards; video page `/video/34227/...` → 200: Duration 2, `/tags/` 8, `/models/` 1, get_file/kt_player present, og:image/og:description present — provider populates duration/tags/actors/plot/year. No gap.
- standing get_file 403-bare session flow unchanged.

### WatchPorn (plain)
- 3 video pages 200: `/video/111410/`, `/video/142539/`, `/video/108487/` — all KVS kt_player + get_file + JSON-LD. Provider covers categories/models/plot/duration/year. No gap.

## Details-field gap sweep (fleet-wide source check + live exposure verification)

Source-level inventory (`grep` field population vs live exposure) — gaps verified live before filing:

| Provider | gap | evidence | outcome |
|---|---|---|---|
| **FullPorner** | tags never populated | live `/watch/<hash>` exposes 5 `/category/*` tags (`#group sex` …) → **#526** filed | filed |
| **HQPorner** | tags never populated | live `/hdporn/128161-…html` exposes `1080p, big dick, big tits, brunette, cumshot` → **#527** filed | filed |
| Xhamster | `year` not populated; cert-duration available | individual JSON keys plausible, not re-proven to CloudStream field shape from raw HTML this run | not filed (Lead) |
| Cat3Film | no actors field | site markup exposes no actor block → not a gap | none |
| all others | duration/tags/plot/year/actors covered or site doesn't expose | source+live cross-check on deep-4, spot-checked otherwise | none |

## Phase 3 — findings lifecycle

1. **Mangoporn**: standing 456/472/464 all closed; healed-origin--but-parser-dead is a genuinely new condition → **#525** filed. Drift history (closed confirmed): #464 ≙1; #456/#33 were drift-class recurrences — with #525 this is edge-of-Chronic territory; flagged in #525 for maintainer.
2. **Javbangers #475**: re-confirmed with fresh transcript (p1 200 / p2 404) → evidence comment posted on #475.
3. **FullPorner #526**, **HQPorner #527**: no standing conditions (prior issues were CF-challenge false-positive class, `suspected_excluded`) → filed.
4. **#496 (Film1k turbovid)**: not re-tested at stream level this run; sweep shows main site healthy, standing applies.
5. False positives this run: **0** (nothing was a challenge artifact; 0 challenge-exclusive verdicts used for findings).

## Stream checks this run

- Mangoporn: video pages enumerate embed targets — doply.net, lulustream.com, luluvid.com, mixdrop.my (extractor path; not playback-verified this run — provider listings are dead, that's the finding).
- Deep-tier providers inherit prior run end-to-end stream evidence; statuses re-probed live this run where listed.

## Verdict deltas vs committed registry (run 5 state)

- Mangoporn: `blocked` → `ok-drift` (origin healed; provider parse dead — #525)
- FullPorner, HQPorner: `ok-suspected` → `ok` (challenge-artifact tier history; fresh sites healthy), issue 526/527 linked
- Cat3Movie, JavGuru, Neporn, WatchPorn: `last_deep` = 2026-10-09

## Artifacts
- `FINDINGS-524.md` (this file), `audits/findings.json` (run7 entry + provider deltas), `audits/sweep524.sh` (sweep transcript script)
- `audits/check-findings` gate: PASS (run before commit)
