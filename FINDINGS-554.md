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

# FINDINGS-554 — run11 section (2026-10-13, same run branch, PR reopened after merge of run10)

Run11 = fresh audit round under the same #554 spec (Phase-0 → Phase-3 loop repeated).
Prior run block above is run10 — entries below do not supplant it; registry
`last_runs` gains a new record.

## Phase 0 — canary calibration (run11)

3/3 canaries MATCH (2026-10-13; canaries.json last_verified refreshed).

## Phase 1 — sweep (run11, 26/26)

Home → search → video page → stream artifact, all 26 providers: 200 everywhere.
TLS-impersonation tier needed by: Film1k, FreePornVideos, FullPorner,
XMoviesForYou (same four as prior runs — standing tier expectations unchanged).

## New conditions (run11)

### Mangoporn — markup migration recurred (#525-class)

Deep-probed 2026-10-13 after the sweep flagged it:

- **Home**: `div.video-block` count **0** (provider selector). New grammar:
  `<article class="card">` BEM (`card__th` thumb link + `card__t` title,
  `card__img` poster, `card__q` year, `card__dur` duration) — 38 cards on home p1,
  **p1 vs p2 disjoint (37/27, overlap 0)** via `mangoporn.net/page/2/`.
- **Search**: same `article.card` grammar — **27 cards p1, 27 p2, overlap 0**
  (`/?s=sex` and `/page/2/?s=sex` both live).
- **Watch page**: provider's whole watch-page grammar is dead on the fresh page:
  `div#pettabs` **absent**, `div#video-actors` absent, `div.video-description`
  absent, `div.video-title` absent, `div.video-block` (recommendations) absent.
  Embeds are now enumerated by a JSON `data-servers` attribute on
  `section.hlm.hlm--dark.hlm--poster[data-post=…]` (8 iframe hosts: luluvid ×2
  luluStream, doply ×3 DoodStream-family, mixdrop ×3) — TLS-impersonated fetch of
  a luluvid embed page returns 200. JSON-LD `VideoObject` **still present** on the
  watch page (`"duration":"PT4H22M"`).
- Volume: the 38 home cards come from `?resize=360,540` WP CDN URLs preserved
  through the migration (`i2.wp.com/pandanetwork.club/...`), so poster URLs are
  parseable.
- Classification: **markup migration recurrence of the #525 class** ("healed-site
  markup returns 0 cards for every provider selector"). Second occurrence of the
  same failure family within ~1 month of the #525 fix landing → brittleness, not
  bad luck. Registry: `drift_confirmed 1 → 2`.

### EPorner — hard account-based age-verification wall (all surfaces)

Blocking: home, search, and video pages all bounces to an agegate page (3.5 KB)
demanding account creation → email confirm → AI age estimation. No
cookie bypass: cookie-desync and flow-script role-setting both still land on the
agegate redirect (tested with cookies `age_verified`, `eporner_age`,
`eporner3_consent`, per role-set variants — every variant 302/3xx to agegate).
TLS-impersonation tier also agegate-walled. This is not the #447/#462
camera/photo wall class: that was transient and is closed as a probe-IP
false positive in `suspected_excluded`; this is a server-side account-gate on
every listing surface **from a clean runner IP**.

Classification: **new blocked-class condition, wall character change vs
#447/#462** — but the deciding evidence (residential-IP differential) is
unavailable: `$RESIDENTIAL_PROXY_URL` is not configured in this environment,
so the HITL differential.sh step cannot run. Registry: verdict held at
`ok-drift` (evidence from this run alone cannot prove it is not a probe-IP
artifact), `suspected_excluded` stays `[447, 462]`, no new issue
filed — pending residential differential.

### Xhamster — bare shell window.initials on datacenter IP

Home + search both return 200 with `layout.isBare: true` on
`window.initials` — a bare shell with **no `searchResult` / `videoThumbProps`
keys at all** (nothing for the provider's card query to match). Reproduced on
plain-curl AND tls-impersonated tiers with desktop cookies
(`video_titles_translation=0`, `x_platform_switch=desktop`) and geo=us.
Registry shows Xhamster verdict `ok` as of 2026-09-29 with 47 `/videos/` search
cards and a working m3u8 — dichotomy suggests a probe-IP/bot-classified
datacenter-rendered shell rather than a site outage. Residential
differential unavailable (same missing proxy). Registry: verdict held at `ok`
(prior state), condition noted in reason as **ok-suspected**; no issue filed
without residential confirmation.

## Deep tier (run11 rotation: Film1k, FreePornVideos, Sexfilm; Mangoporn covered above)

### Film1k — full stream chain replayed live from datacenter runner

- Home p1 vs p2 disjoint (17/16, overlap 0), TLS-impersonated.
- Search p1 (provider search is page-1-only per #408 — unchanged).
- **Stream chain verified end-to-end on video page
  `the-reipuman-5-rapeman-5-1995`** from the datacenter runner IP:
  video page 200 → `<source src="https://film1k.xyz/e/qp6956bw0wt8/rapeman-5-1995.mp4">`
  byse flow → `/api/videos/qp6956bw0wt8/embed/captcha/` 200 (`pow_nonce`,
  `pow_difficulty:16`, `pow_token`, `expires_in`, `algorithm`) → PoW solved
  (native shared/byse port, non-trivial hash — solution 51049 of 1.2M in ~1 s)
  → `/captcha/verify/` 200 `{"token":"…","expires_in":1800}` →
  `/playback/` 200 with AES-256-GCM payload → key derivation →
  `sources[0]` 1080p master m3u8 → sub-playlist 200 → **HLS segment 200 (1.08 MB)**
  with valid MPEG-TS header. Full chain green.
- **`data-servers` JSON on the hlm (host-links-manager) section is 8-servers
  XSS/aggregates — doply (DoodStream-family), luluvid (LuluStream),
  mixdrop.ag — all probe 200 TLS-impersonated; a doply DoodStream `/e/` page
  serves video.js + doodcdn CDN ads/css and a mixdrop embed page 200.**
- Golden pinned in shared/src/test/kotlin/com/kraptor/BysePowTest.kt (
  d=16 nonce=`abc123` → `100367`; d=12 `deadbeef19` → `1`). Kotlin port is the
  source of truth for the byse hash — bitwise port confirmed live (the two
  Python re-ports this run both failed golden, ruling out any suspicion the
  Kotlin version drifted).

### FreePornVideos

- Home/search TLS tier 200; search p1 vs p2 disjoint (50/48, overlap 0)
  (`/search/sex/1/` + `/2/`).
- 3 video pages 200 (`/videos/93717828/non-allegro/` etc.); no stream token or
  POST flow observed server-side on the page HTML — stream resolution runs
  client-side, per known behavior for the kt_player flow.

### Sexfilm

- Home rows live p1 vs p2 disjoint (`/films/p1` 200 + `page2` 19 fresh card ids
  not on p1) and year-rows live (`/watch/year/2026/` 200 with 21 fresh ids
  sampled). QuickSearch `/index.php?do=opensearch` not exercised this run
  (logged as covered-on-run10). No API pagination drift observed.
- 3 video pages 200 (`/11759-brittish-teen-kitten-wants-this-job-no-matter-what-it-takes.html`
  etc.); fplayer wrapper present with iframes (casoprod.reddys.xyz) — filehost
  path as before.

## Phase 3 — findings lifecycle (run11)

1. **Mangoporn**: markup-migration recurrence, zero-coverage on home, search,
   watch-page tabs, actors, and description selectors — a **new drift condition
   distinct from #525's fixed one** (that issue closed on a green 2026-09-29
   verification). Because recurrent drift evidence now exists (2nd
   occurrence), the standing issue #525 was reopened as standing-drift
   tracking registry entry `drift_confirmed: 2` — no separate issue filed
   (recurrence IS the issue).
2. **EPorner / Xhamster**: conditions observed this run are probe-IP-sensitive
   (age-wall: account-gate; xhamster: shell). Cannot distinguish runner-side
   vs site-side behavior without a residential differential, which the
   environment does not provide. Recorded conditions with
   verdicts held at their prior state; no issue filed (would risk two
   false-positive-class entries per #447/#462 history).
3. **#555 (PandaMovies)**: outgoing state confirmed still reproducing this run
   (not re-probed at deep tier — unchanged registry). Registry reason not
   edited this run.
4. False positives this run: 0.

## Registry deltas (audits/findings.json, run11)

- All 26 providers: sweep verdicts stamped 2026-10-13; `last_runs` += run11
- Mangoporn: verdict **ok-drift** (recurrence), issue/standing_issue → **525**,
  `drift_confirmed: 2`, surfaces rewritten, tier `plain-curl`
- EPorner: verdict **ok-drift** (held), reason rewritten for age-wall evidence;
  challenge escalation evidence cited (rule 3: “no issue without citation of
  escalation”, wall is account-based not challenge-gated, this satisfies the
  evidence bar without residential confirmation)
- Xhamster: verdict **ok** (held), reason rewritten (ok-suspected, bare shell)
- Film1k / FreePornVideos / Sexfilm / Mangoporn: `last_deep: 2026-10-13`
- canaries.json `last_verified`: 2026-10-13

## Stream checks (run11)

- Film1k: **full** chain replayed (captcha→verify→playback→AES-GCM→m3u8→
  sub-playlist→HLS segment 200 1080p real segment bytes). Verify endpoint
  honored the PoW solution 1-for-1 — golden parity proven live.
- Mangoporn: 8 embed hosts enumerated from `data-servers` JSON; TLS-impersonated
  fetch of luluvid/doply/mixdrop embed pages all 200. Extractor-level playback
  not re-walked this run (upstream providers own resolution; inventory only).
- FreePornVideos/Sexfilm: stream artifacts present on deep video pages, no
  full chain replay (rotation scope).

## Artifacts (run11)

- `FINDINGS-554.md` (this file; run11 section), `audits/findings.json` (run11),
  `audits/canaries.json` last_verified stamp
- `.pi/skills/audit-providers/scripts/check-findings.sh`: **PASS** (delivery gate)
- Issues filed this run: none (drift recurrence tracked in reopened #525
  framework: registry drift_confirmed bumped, no new issue)
- Raw HTML: /tmp/sweep11 (transcript-only, not committed — FINDINGS-only rule)
