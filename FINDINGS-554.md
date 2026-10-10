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
