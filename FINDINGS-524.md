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
- FullPorner: `ok-suspected` → `ok` (challenge-artifact tier history; fresh site healthy), #526 linked
- HQPorner: verdict was already `ok` in the committed registry (deep 2026-09-29) — no verdict change; tags gap #527 linked
- Cat3Movie, JavGuru, Neporn, WatchPorn: `last_deep` = 2026-10-09

## Artifacts
- `FINDINGS-524.md` (this file), `audits/findings.json` (run7 + run8 entries + provider deltas), `audits/canaries.json` (Phase 0 `last_verified` = 2026-10-09), `audits/sweep524.sh` (sweep transcript script, incl. Mangoporn search + /page/2 and Javbangers p1/p2 probes)
- `audits/check-findings` gate: PASS (run before commit)

---

## Run 8 — same-day re-fire (2026-10-09, branch `devloop/issue-524`, after PR #528 merge)

Run 7 had already landed and merged; this pass widened the details-field audit. **No provider code changed on this run — artifacts only.** Probe IP: US datacenter (CI runner), same as run 7. Canaries re-checked same day: 3/3 match (film1k pair 403/200, eporner 200, pandamovies 302→.org 200) — instrument healthy.

Live-probed this run (plain curl unless noted; TLS = curl_cffi chrome impersonation, `impersonate.sh` + curl_cffi installed):
Javtiful, XMoviesForYou, FreePornVideos, EPorner, Eroticmv, Javmost, Neporn, WatchPorn, Sexfilm, Mangoporn, Javbangers, ixiporn, FullPorner, Cat3Film, Cat3Movie, Javseen, Xhamster, PerverZija, PornXP, Porntrex spot-checks.

### Findings filed (all unlabeled — humans apply trigger labels)

| # | Provider | Condition (live-verified) |
|---|---|---|
| #534 | **Javtiful** | Full site redesign: every `front-*` selector (`article.front-video-card`, `a.front-video-title`, `div.front-watch-title h1`, `div.front-watch-detail`, `a.front-watch-actor-card`, `a.front-pagination-link`) dead. Live: `article.video-card` / `a.video-card__title` / `div.watch-title h1` / `div.watch-detail` («Added on» `<time datetime>`) / `a.watch-actor-card` / `a.pagination__link`. JSON-LD VideoObject (duration, uploadDate) + og metas still healthy; stream path unchanged. Home/search/related return 0 cards; `load()` nulls at title gate. |
| #535 | **XMoviesForYou** | `a.group.flex.flex-col` dead on home (33 new cards: `a[data-video-card].flex-none.w-64…`) and search (24: `a.card`, title `h3.title`); watch-page selectors all alive (h1, calendar chip date, `/category/`, `/pornstar/`, `div.prose p`). Plain curl still 403 CF (canary-consistent); TLS 200. |
| #536 | **FreePornVideos** | Details block rebuilt: `Description:` / `Models:` / `Duration` xpaths dead; live now `Channel: / Network: / Categories: / Pornstars:` inside `div.hidden_tags`; duration only in `meta[property=video:duration]` (10995 s) + JSON-LD (PT3H3M, uploadDate 2015-07-17); title-tail date branch stale. Listings still healthy (24 `div.item`). Verdict stays `ok` (FullPorner pattern), issue linked. |
| #537 | **EPorner** | `/search/sex/<N>/` (N=1..3) all 301/302 → base `/tag/sex/` — numbered search pages collapse to **page-1 results** while provider reports `hasNext`. `/tag/sex/<N>/` paginates correctly (≈59/58/59 cards, 0 p1/p2 overlap) and carries the provider's expected card markup (`div#vidresults div.mb`, `p.mbtit a`). Runtime probe found no age-wall from this runner IP (200s across home/tag/watch). Site-side note: no actor exposure anywhere on watch pages (`li.vit-pornstar`, `span.valor`, JSON-LD `actor`, og:description comma-variant all gone) — empty `actors` is not provider-parseable. |
| #538 | **Eroticmv** | JSON-LD `articleSection` absent on 3/3 sampled watch pages (`tokyo-nights-2025`, `any-and-every-which-way-2010`, `a-road-to-viabra-s1-ep-1-2020`) → provider `tags` = empty. Watch pages still expose 156 `/category/` anchors, `/actor/` stars anchors (8/page, `parseActors` alive), og:title year regex alive. Distinct from standing open #497 (homepage facet breadth). |
| #539 | **Javseen** | Watch-page `meta[property=og:video:duration] content="0"` (sampled `/287527/…` 200) → `duration = 0` sentinel populates a bogus "0 min". One-guard fix; `Release Day: 2019-10-12`, AJAX search, listing cards all fine. |

### Standing issues updated (evidence comments, no duplicates)

- **#525 Mangoporn** — fresh transcript posted: home/`?s=sex`/`/page/2/?s=sex` all 200 with 48 `div.video-block thumbs-rotation` cards (`a.thumb`/`a.infos`); provider's `article`-based search and `div.items > article` getMainPage match 0; load-page `div.data`/`span.textco`/`div.persons`/`og:image`-class markup replaced by `video-content-row` (`video-about`, `video-actors`, `/pornstar/`, `/year/2015`, JSON-LD duration/uploadDate, `#pettabs` doodstream/doodapi/lulustream embed tables). Drift-recurrence note (closed #464 ≙1, #456/#33 drift-class, now #525) re-flagged — edge of Chronic per AGENTS.md.
- **#475 Javbangers** — drills re-read this run: search pages carry 75 `video-item` cards (earlier 400 report superseded); watch-page details showed **Duration `<em class="badge"></em>` empty site-side** (Views and Submitted badges filled; no absolute date, only "5 years ago"), `div.videodesc` description present, Categories links present, href-less tag anchors. Provider sets tags/year/plot; site exposes no duration/actor data → not provider-parseable. #475 (p2+ 404) stays standing, untouched.
- **#496 Film1k** — not re-tested at stream level this run; sweep rows already healthy.

### Re-verified healthy (no findings, no drift)

- **Javmost** — `/showlist2/search/1/sex/` JSON fine (full metadata case `length/genre/release/maker`, `star:null`); watch page `div.card-block` present, `card-text` carries Release date / Time minutes / Genre `/category/` links — `Parse.cardBlock` fields (year/duration/tags) all match live. No `/star/` links exist on the watch page (nav-only pornstar links) → `actors` empty is site-side, not a gap.
- **Neporn** — home 200 3 video links sane; async search 24 items; watch `/video/40231/…` 200: JSON-LD VideoObject uploadDate 2026-05-29 + `duration PT0H25M12S`, `div.added`/`div.views (2.0K)`. Provider covers year/actors/tags.
- **WatchPorn** — `/video/111410/…` 200: JSON-LD VideoObject uploadDate 2024-11-06 + duration PT1H14M11S; `single__info-row` cells Studio/Categories/Models/Tags all alive; provider covers categories/models/plot/duration/year (=JSON-LD uploadDate year). Observation (not filed): a live `Tags:` row sits unharvested alongside Categories — cosmetic dup field at most.
- **Sexfilm** — home 200; `/movies/` 200 with 24 `div.short nl nl2` cards (`a.short-poster`, `a.th-title`, `img[data-src]`); watch `11755-sexual-healing.html` 200: `h1#s-title`, `div#s-desc`, `meta[itemprop=duration]` PT4279S, `span.gv a[href*=/watch/year/1994/]` (year alive), Casting `watch/name/` (9 links), `meta[itemprop=genre]` "HD porn movies , Vintage", 25 `div.short` cards incl. `div.sect-c` recommendations — every provider field parses live. Deep-checked, clean.
- **ixiporn** — `.org` → `.live` double redirect still live (standing #468); watch page 200: `#video-tags` present, `meta[itemprop=duration]` P0DT0H19M48S, `meta[itemprop=uploadDate]` 2026-10-09T08:41:13+05:30 — provider fields (tags by id, JSON-ld grammar minutes, year from uploadDate) all match live.
- **FullPorner** — TLS 200; `/search?q=sex` 24 cards; watch page `tag-link` + `single-video-info-content` (empty `Pornstar:` cell as documented in open #530).
- **Cat3Film** — `/_ajax/search?q=sex` JSON fine (`results` with year 1998/1997 — no actor keys, site-side). **Cat3Movie** — `/search/erotic` 200 real `/movie` links; watch page `a-thousand-and-one-erotic-nights-1982` 200: JSON-LD `@type Movie` (name carries title-year, datePublished = WP posting date), no duration/og:video:duration/live key — provider covers all exposed fields, consistent with run-7 stamp.
- **Xhamster** — watch page 200; `window.initials` `videoModel` (id/duration/title, no year) + `videoEntity` (title/desc/duration/dateAgo), `pornstarModels` present. `year` Lead re-proven — still unfiled (needs field-shape proof before it's a finding).
- **PerverZija / PornXP / Porntrex / Eroticmv listings** — 200 with expected card markup (spot-check).

### Verdict deltas vs run-7 registry

- Javtiful: `ok` → `ok-drift` (full redesign — #534; drift_confirmed 1). Listing-level parse is dead → drift-class, not a field-polish gap.
- XMoviesForYou: `ok-suspected` → `ok-drift` (listing selectors dead home+search — #535; drift_confirmed 1; watch page fields live).
- EPorner: `ok-suspected` → `ok-drift` (search pagination redirect collapse — #537; drift_confirmed 1; no age-wall this run, Geo suspected class narrowed).
- FreePornVideos: `ok-suspected` → `ok` (site healthy TLS-wide, KVS anti-leech class retired à la FullPorner); #536 linked.
- Eroticmv: `ok` stays; #538 linked. Javseen: `ok` stays; #539 linked. Mangoporn/ixiporn/Film1k/Javbangers/PandaMovies: unchanged drift states re-confirmed.
- `last_deep`: no stamps moved this run — deep-tier **stream resolution** was not executed (field/selector deepening only); rotating deep remains due from the next run.
