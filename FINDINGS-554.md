# FINDINGS-554 — fleet audit run10 (all 26 providers)

Issue #554: *"run full cloudstream audit"*. Audit-only run (audit-providers skill):
artifacts only, no provider code changes. Operator: pi agent, run branch
`devloop/issue-554`, 2026-10-13. Probe IP: US datacenter. Raw probe HTML is
transcript-only, not committed (FINDINGS-only ground-truth rule).

## Phase 0 — canary calibration

All 3 canaries **MATCH** (2026-10-13):

| Canary | plain-curl | tls-impersonated | expected | verdict |
|---|---|---|---|---|
| film1k-challenge-pair | 403 | 200 | 403 / 200 | match |
| eporner-healthy | 200 | — | 200 | match |
| pandamovies-redirect-origin | 302 → pandamovies.org | 200 (via 302) | 302 / 200 | match |

Instrument healthy — no degradation, no Blocked downgrades. `curl_cffi` was
missing in the run environment and was installed before the run proceeded.

## Phase 1 — sweep (all 26 providers, 2026-10-13)

Home → search → one video page → stream-artifact check per provider.

| Provider | home | search | video | stream artifact | tier | verdict |
|---|---|---|---|---|---|---|
| AllClassicPorn | 200 | 200 | 200 | flashvars/get_file/kt_player/sources | plain | ok |
| Cat3Film | 200 | 200 (`/_ajax/search?q=sex`) | 200 | (JSON API page; standard selector) | plain | ok |
| Cat3Movie | 200 | 200 | 200 | iframe (halim_cfg player) | plain | ok |
| EPorner | 200 | 200 (cards via `/hd-porn/` — age-wall SPA class, #447/#462 excluded) | 200 | iframe | plain | ok-suspected |
| Eroticmv | 200 | 200 (`/?s=sex`) | 200 | jwplayer/m3u8/video_url | plain | ok |
| Film1k | 200 TLS | 200 TLS (`/?s=sex`) | 200 | iframe | tls | ok |
| FreePornVideos | 200 TLS | 200 TLS (`/search/sex/1/`) | 403 plain / **200 TLS** | get_file/iframe | tls | ok (video-page 403 = known session/leech class, #449/#466 suspected-excluded) |
| FullPorner | 200 TLS | 200 TLS (`/search?q=sex`) | 200 | iframe | tls | ok |
| HQPorner | 200 | 200 (`/?q=sex&p=1`) | 200 | iframe | plain | ok |
| JavGuru | 200 | 200 (`/?s=sex`) | 200 | iframe (javmiku base64) | plain | ok |
| Javbangers | 200 | 200 p1 (provider URL `/search/sex/`) | 200 | flashvars/get_file/kt_player/video_url | plain | ok |
| Javmost | 200 | 200 (`/showlist2/search/1/all/` JSON) | 200 | iframe/m3u8 | plain | ok |
| Javseen | 200 AJAX | 200 AJAX (30 cards) | 200 | data-embeds → javhdz embed 200 m3u8 | plain | ok |
| Javtiful | 200 | 200 (`/search?q=sex`) | 200 | iframe/sources | plain | ok |
| Mangoporn | 200 (probe 000 once — transient; retry 200) | 200 + `/page/2/?s=sex` 200 | 200 | doply/lulustream/mixdrop iframe hosts | plain | ok (#525 fix holding) |
| MissAV | 200 | 200 (`/en/search/sex` p1, `?page=2` live) | 200 | iframe/m3u8 | plain | ok |
| Neporn | 200 | 200 (24 `div.item`) | 200 | flashvars/get_file/kt_player | plain | ok |
| **PandaMovies** | 200 (markup migrated) | 200 (markup migrated) | 200 | doply/doodstream/mixdrop/lulustream | plain + tls | **ok-drift — parser dead, filed #555** |
| PerverZija | 200 | 200 (`/?s=sex`) | 200 | pervl*.xtremestream player iframe | plain | ok |
| PornXP | 200 | 200 (`/tags/sex`) | 200 | (player page) | plain | ok |
| Porntrex | 200 | 200 (`/search/sex/` — cards on `/latest-updates/`) | 200 | flashvars/get_file/kt_player | plain | ok |
| Sexfilm | 200 (two-step cookie flow) | 200 | 200 | iframe | plain | ok |
| WatchPorn | 200 | 200 (async block) | 200 | flashvars/get_file/kt_player | plain | ok |
| XMoviesForYou | 200 TLS | 200 TLS (`/search?q=sex`) | 200 | + site `api/stream/<id>?target=dedi_1` → 200 JSON m3u8 node | tls | ok (listing drift #535 stands) |
| Xhamster | 200 | 200 (`/search/sex`) | 200 | iframe/sources | plain | ok |
| ixiporn | 200 (.org) | 200 (.live `/search/sex`) | 200 | iframe | plain | ok |

Probe-tier notes (script artifacts, not findings):
- Sweep script `audits/sweep524.sh` probe URLs for Javmost (`/showlist2/all/1/`) and
  Javbangers (`/search/sex/1/`) trailed the evolved provider paths → 404s in the raw
  script output; provider-as-coded URLs return 200 (verified directly).
- EPorner raw home HTML carries no video cards client-visible server-side (known SPA
  age-wall class); `/hd-porn/` serves full card lists → ok-suspected as before.

## Phase 2 — deep tier (4: PandaMovies, XMoviesForYou, Javseen, PerverZija — oldest unstamped)

### PandaMovies (plain + tls cross-check)
- Rows: `/genre/18-teens` p1 vs p2 disjoint (WP `rel=next` serves). Search p1/p2 200.
- **FINDING (filed #555)**: listing grammar migrated `div.ml-item` (PsyPlay) →
  `div.card` BEM (`card__th/card__t/card__q/card__m/card__dur`); `ml-item` count **0
  on every listing page, both plain and TLS-impersonated tiers** → provider
  `Parse.cards` matches 0. Home/search/genre URLs themselves 200; the Latest row URL
  `/movies` → **404** ("Page not found – PandaMovies"). Video pages healthy
  (3 sampled 200; embeds doply/doodstream/mixdrop/lulustream present). Site-side
  pagination works — pure parser drift, not a block.
- Drift history: 4 closed confirmed-drift records (the counter counts closed
  issues only — #555 is open and not yet counted) → already at the ≥4 Chronic
  threshold, flagged for maintainer
  (hardening fix vs `ai-remove-site`) in #555.

### XMoviesForYou (tls)
- Rows: `/category/anal` p1 vs p2 (`?page=2`) 200, disjoint card sets.
- Search: `/search?q=sex` p2 200 with fresh result slugs.
- 3 video pages 200 (`/allanal-harley-love-…`, `/analbeauty-monika-akai-…`,
  `/sexandsubmission-charlie-forde-…`).
- Streams: loadLinks host anchors present on every video page (streamtape/mixdrop/
  dood v1–v3), extractor path as before. Bonus observation: the site's own HLS player
  resolves `api/stream/<videoId>?target=dedi_1` → 200 JSON `{"node":…,"url":"…index.m3u8"}`;
  the CDN node itself 403s a bare probe (needs player session) — provider uses the
  filehost path, unaffected.
- Standing listing drift #535 (home/search card grammar) unchanged, still stands.

### Javseen (plain)
- Rows: `/recent/?ajax=browse_videos` and `/solowork/?ajax=category_videos`
  (exactly what `JavseenParse.homePageUrl` builds — the provider **always** appends
  `?ajax=`) → 200, 30 `video-` cards each; p1 vs p2 disjoint
  (287551… vs 287514…). Non-AJAX bare listing URLs serve skeleton shimmer pages —
  irrelevant to the provider, recorded to preempt a false future suspicion.
- Search: AJAX endpoint page-2 200 with fresh ids.
- 3 video pages 200 (`/287567/…`, `/287566/…`, `/287560/…`).
- Stream resolution: `data-embeds` base64 → javhdz `/embed.php?p=…` 200 with m3u8/mp4
  markers (matches the known extractor chain).
- **#539 condition healed**: `og:video:duration` now carries a real value (6540), not
  the `"0"` sentinel; #539 already closed — no action.

### PerverZija (plain)
- Rows: `/featured-scenes` p1 vs p2 200; page-2 adds distinct content links
  (carousel repeats deduped by `PerverZijaParse` as designed).
- Search: `/?s=sex` p1 + `/page/2/?s=sex` 200.
- 3 video pages 200, each with a per-page `pervl{N}.xtremestream.xyz/player/index.php`
  iframe (extractor path unchanged).

## Phase 3 — findings lifecycle

1. **PandaMovies** (#555): standing #463 (522 origin-down ×5, Chronic) is closed and
   cleared — today's condition (healed origin + markup migration + dead `/movies`
   row) is genuinely new. Searched open+closed (`PandaMovies`): no standing open
   issue matches → filed **#555**; `drift_confirmed` stays 4 until #555 closes.
2. **Javseen #477**: clear — provider's ajax-always URL strategy verified at deep
   tier; condition "bare page is a shell" is the provider-independent site behavior,
   not a defect. Updated evidence via this FINDINGS + registry reason.
3. **#539**: already closed; sentinel observed healed this run — recorded.
4. **#535 (XMoviesForYou)**: standing, still reproduces, no re-file.
5. False positives this run: **0** (no challenge-exclusive verdicts used for findings).

## Registry deltas (`audits/findings.json`)

- PandaMovies: verdict `ok-drift` (kept), issue → **555**, reason rewritten, `last_deep` 2026-10-13, reason states the counter stays 4 until #555 closes
- XMoviesForYou / Javseen / PerverZija: `last_deep` 2026-10-13, reasons refreshed
- Javseen: reason notes #539 sentinel healed (duration 0) — already reflected
- `last_runs` += run10 (findings [555], false_positives [])
- `audits/canaries.json`: `last_verified` = 2026-10-13

## Stream checks this run

- Javseen: full chain replayed (video page → base64 data-embeds → javhdz embed 200
  with m3u8/mp4 markers).
- XMoviesForYou: site-player HLS api/stream endpoint resolved live (JSON m3u8 node);
  filehost anchors present on all 3 deep video pages; node CDN 403 bare probe (not
  the provider path).
- PerverZija: per-video xtremestream player iframes confirmed on 3 pages.
- PandaMovies: embed hosts enumerated on 3 video pages (doply/doodstream/mixdrop/
  lulustream) — moot for in-app playback until #555 listings are fixed.

## Artifacts

- `FINDINGS-554.md` (this file), `audits/findings.json` (run10), `audits/canaries.json`
- `.pi/skills/audit-providers/scripts/check-findings.sh`: **PASS** (delivery gate)
- Finding issue filed this run: **#555** (PandaMovies)

---

# run13 — 2026-10-10 (round 4 under #554)

Note on run numbering: round 2 (run11) and round 3 (run12) shipped no merged
artifacts — run11's PR #557 was closed unmerged and run12 opened no PR — so the
merged evidence stream jumps from run10 to run13. Everything re-probed here
supersedes those unmerged claims; where run11 observed Mangoporn/Xhamster
conditions, this run re-derived them from scratch (see below).

Operator: pi agent, branch `devloop/issue-554`. Probe IP: US datacenter.

## Phase 0 — canary calibration

All 3 canaries **MATCH** (2026-10-10):

- film1k-challenge-pair: plain 403 / tls-impersonated 200
- eporner-healthy: plain 200 (status-match; caveat below — the body is the
  agegate, the canary is body-blind to that)
- pandamovies-redirect-origin: plain 302 → `pandamovies.org` 200 / tls 200

Instrument healthy. Blocked verdicts stay eligible (none needed).

## Phase 1 — sweep (26/26)

Every provider: home → search → video page → stream-artifact check, one pass,
plain-curl tier with tls escalation per ladder (records: /tmp/sweep13,
transcript-only). "Cards" = occurrences of the provider's own card grammar.

| Provider | tier | home | search | video | stream artifact |
|---|---|---|---|---|---|
| AllClassicPorn | plain | 200 (84 `th item`) | 200 (60) | 200 | kt_player + get_file (KVS) |
| Cat3Film | plain | 200 | 200 (`_ajax/_search` JSON) | 200 (`data-slug` page) | JS player API (extractor path) |
| Cat3Movie | plain | 200 (58 `halim-thumb`) | 200 (4 — narrow result set) | 200 (`geranalmo-1994`) | WP player iframe |
| EPorner | plain | 200 — **Age-verification sheet** (0 `vidresults`) | 200 — agegate | agegate |  n/a (walled) |
| Eroticmv | plain | 200 (48 `post-item`) | 200 (18) | 200 | `.m3u8` ×3 + 53 iframes — merged #560 facet fix live |
| Film1k | tls | 200 (24 `loop-post`) | 200 (24) | 200 | `film1k.xyz/e/…` embed 200 — merged #558/#561 scoping live |
| FreePornVideos | tls | 200 (74 `item`) | 200 (50) | 200 ×3 | get_file + kt_player (session-bound, known) |
| FullPorner | tls | 200 (72 `video-card`) | 200 (72) | 200 ×3 | `xiaoshenke.net` iframe (extractor path) |
| HQPorner | plain | 200 (50 `image`) | 200 (50) | 200 | mydaddy embed |
| JavGuru | plain | 200 (`inside-article` 12+) | 200 (12) | 200 | `javmiku` iframe (extractor path) |
| Javbangers | plain | 200 (60) | 200 (75) | 200 (kt_player/get_file on public; newest uploads flagged **private** by the site, no player rendered) |
| Javmost | plain | 200 | 200 (24 `"cover"` JSON) | 200 | mixdrop/streamtape/dood anchors |
| Javseen | plain | 200 | 200 (31 `li id="video-…"` via AJAX JSON) | 200 | `data-embeds` → javhdz |
| Javtiful | plain | 200 (24 `article.video-card` — post-#534 grammar healthy) | 200 (24) | 200 | mp4 markers |
| Mangoporn | plain | 200 (0 `video-block`, 38 `card__th`) | 200 (0, 27 `card__th`) | 200 | `section.hlm[data-servers]` — **new drift, #564** |
| MissAV | plain | 200 (11 `grid-cols-2`) | 200 (video cards `/en/<code>`) | 200 | surrit m3u8 (known flow) |
| Neporn | plain | 200 (32 `div.item`) | 200 (24) | 200 | kt_player + get_file (KVS) |
| PandaMovies | plain | 200 (0 `ml-item`, 49 `card__th`) | 200 (0, 27) | 200 | `section.hlm[data-servers]` — #555 re-confirmed |
| PerverZija | plain | 200 (69 `col-md-3`) | 200 | 200 | pervl.xtremestream iframe |
| PornXP | plain | 200 | 200 (36 `item_dur`) | 200 | cdrn mp4 markers |
| Porntrex | plain | 200 (89 `video-preview-screen`) | 200 (104) | 200 | kt_player |
| Sexfilm | plain | 200 (96 `short`) | 200 | 200 (cookie-jar 2-step) | filmcdm/morencius embeds (provider regex matches) |
| WatchPorn | plain | 200 (57 `thumb item`) | 200 (40) | 200 | kt_player + get_file |
| XMoviesForYou | tls | 200 (30 `data-video-card`) | 200 (24 `a.card`) | 200 | streamtape/mixdrop/dood anchors |
| Xhamster | plain | 200 (`videoListProps` present — home initials fine) | 200 (37 `searchResult`) | 200 | m3u8 markers |
| ixiporn | plain | 200 via .org → `.live` double redirect (#468 stands) | 200 (`video-block` 31 `a.infos`) | 200 | get_file (session-bound) |

Verdict changes this run: **Mangoporn ok → ok-drift** (new condition #564).
Everything else keeps its registry verdict. EPorner's agegate is recorded as a
suspected probe-IP condition (below), not a Blocked — Phase 0 is healthy, but
the wall's honesty requires the residential differential to decide.

## Phase 2 — deep tier (rotation)

Oldest missing `last_deep` stamps: Film1k, FreePornVideos (stamped but from the
unmerged run11), FullPorner, Javbangers. EPorner was also under-deep but is
agegate-walled on every surface from this probe IP — deep effort degraded,
replaced by Javbangers to keep N=4.

- **Film1k (tls)**: Latest `/` vs `/page/2` 24/24 cards, overlap 0; `/category/action/`
  p1 vs p2 24/24, overlap 0. The 9-overlap seen with an unscoped raw grep
  is the classic-featured *sidebar strip* repeated on every listing page — the
  provider parses only `article.loop-post` cards (API dedupe via `distinctBy url`),
  so it is benign. Search page-1 only (known; search 404s past p1). 3 video pages 200
  (`aunt-pegs-fulfillment-1981`, `barbed-wire-dolls-1976`, `caligula-2-the-untold-story-1982`),
  each embedding a live `film1k.xyz/e/<hash>` iframe (200) — the merged #558/#561
  embed-scope fix parses on live markup. Full byse playback chain (captcha PoW →
  playback AES) NOT replayed this run: no golden-parity harness rerun, Kotlin
  solver and upstream ResolveURL byse.py unchanged since run11's replay.
- **FreePornVideos (tls)**: `/latest-updates/1|2`, `/most-popular/week/1|2`,
  `/networks/brazzers-com/1|2` — all 24/24, overlap 0. 3 video pages 200 with
  get_file/KVS pipeline artifacts. kt_player client-side resolution not replayed
  (same practice as previous runs).
- **FullPorner (tls)**: `/home/1|2`, `/category/hd-porn/1|2`, `/category/amateur/1|2`
  all 24/24 overlap 0. 3 watch pages 200, each with the `xiaoshenke.net/video/…`
  quality-map iframe (extractor path unchanged).
- **Javbangers (plain)**: `/latest-updates|/2`, `/most-popular|/2`,
  `/categories/milf|/2` all 24/24, overlap 0 except **1** informal repeat
  (`/video/406770/…`) between milf p1/p2. 3 video pages 200—but two of the
  newest-updates entries render **"This video is a private…"** placeholders
  instead of the KVS player (site-side privacy on fresh uploads; the older
  public video page keeps kt_player/get_file). Not a listing defect.

## Phase 3 — findings lifecycle

Per the skill: open+recently-closed issue search per condition before filing.

1. **Mangoporn BEM migration** — genuinely new condition (the post-#525 markup
   this replaced was itself the 1st confirmed drift). Filed **#564** (unlabeled).
   Registry: verdict `ok-drift`, `drift_confirmed` 1 → 2 (2nd confirmed
   occurrence; a 3rd triggers the hardening rule). The grammar is identical in
   kind to PandaMovies' #555 (same `card__th/card__t/card__dur`, same `section.hlm`
   `data-servers` watch grammar, same `pandanetwork.club` image CDN), so the
   open #555/#563 fix is partially reusable.
2. **PandaMovies** — standing **#555** exists (pr-563 open): reposted evidence as a
   comment on #555 (per-site sweeps re-confirm, no new issue).
3. **EPorner age-wall** — all surfaces serve the agegate from the datacenter runner
   (plain AND tls). This matches run11's observation exactly; no refile under the
   excluded #447/#462 camera/photo class (this is an account-based wall), no new
   issue (suspected, deciding residential differential unavailable:
   `$RESIDENTIAL_PROXY_URL` unset in this environment). #537 is otherwise
   unverifiable this run behind the same wall.
4. **Xhamster — no finding** — the bare-shell `window.initials` condition
   from run11 did **not** reproduce: search carried 37 `searchResult` cards and home
   `videoListProps` on plain tier this run. No issue filed; no false positive
   created (was never merged as a finding).
5. False positives this run: **0**. Findings filed: **1** (#564). Standing-issue
   updates: **1** (#555 comment).

## Registry deltas (`audits/findings.json`)

- Mangoporn: verdict `ok-drift`, `drift_confirmed` 2, issue/standing 564/525
- PandaMovies: 2026-10-10 re-confirmation appended to reason
- EPorner: reason now records the age-gate condition + a canary body-blindness
  caveat (the eporner-healthy canary checks status code only, so it matches even
  when the served body is the agegate)
- `last_deep` stamped 2026-10-10: Film1k, FreePornVideos, FullPorner, Javbangers
- `updated` 2026-10-10; `last_runs` += run13 (findings [564], false_positives [])
- `audits/canaries.json`: `last_verified` 2026-10-10 ×3

## Stream checks this run

- Film1k: `film1k.xyz/e/<hash>` embed reachable 200 from the runner (chain endpoint
  confirmed live; PoW+AES playback chain replay not re-run — unchanged since run11).
- FreePornVideos / Javbangers / Neporn / WatchPorn / AllClassicPorn: KVS
  kt_player/get_file artifacts on video pages (session-bound; bare get_file 403 is
  known leech protection, not a defect).
- FullPorner: `xiaoshenke.net` quality-map iframe on 3/3 watch pages.
- Direct-media spot checks all healthy: Eroticmv m3u8, PornXP cdrn mp4,
  Xhamster m3u8, Javtiful mp4 markers, Javseen javhdz chain (data-embeds).
- EPorner / PandaMovies / Mangoporn streams moot on current listings (listings
  themselves are the open conditions).

## Artifacts

- `FINDINGS-554.md` (this file), `audits/findings.json` (run13), `audits/canaries.json`
- `.pi/skills/audit-providers/scripts/check-findings.sh`: **PASS** (delivery gate)
- Finding issues filed this run: **#564**; standing issue updated: **#555**
