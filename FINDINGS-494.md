# FINDINGS-494 — Fleet-wide audit: CloudStream API correctness (issue #494)

Audit-only run: probe every provider against live sites + the CloudStream API
contract, file data-population findings, update the registry. No provider code
changes. Probe run 2026-09-29, branch `devloop/issue-494`. 26 providers.

## Canary calibration (Phase 0)

| Canary | Expected | Observed | Outcome |
|---|---|---|---|
| film1k-challenge-pair | plain 403 / TLS 200 | plain 403 / TLS 200 | **match** |
| eporner-healthy | plain 200 | 200 (106 KB homepage) | **match** |
| pandamovies-dead-origin | 522 / 522 | plain **302** → pandamovies.org 200; TLS 200 | **mismatch, expectation-stale** |

PandaMovies origin **healed**: `pandamovies.pw` 302-redirects to a live
`pandamovies.org` (real 336 KB homepage). Per canary policy the expectation
update is **proposed in this PR** (`audits/canaries.json`: renamed
`pandamovies-redirect-origin`, expected plain 302 / TLS 200) — maintainer
confirms in review. No environment degradation: Blocked verdicts stand.

## Sweep (Phase 1) — verdict table

| Provider | Verdict | Tier | Evidence (2026-09-29) |
|---|---|---|---|
| AllClassicPorn | ok | plain-curl | search `/videos/` links; KVS get_file stream 206. Single-use tokens: a consumed token 403s, fresh replay 206 (explains earlier 403s) |
| Cat3Film | ok | plain-curl | `_ajax/search` JSON alive: q=sex→8, teen→8, anal→2; q=milf→0 = no matching titles on this niche site (search page itself 404s — provider uses `_ajax` directly) |
| Cat3Movie | ok | plain-curl | search 200 with `/movie` links (`/milf-2010`) |
| EPorner | ok-suspected | plain-curl | **full-page age-verification wall** from regulated-region probe IP (`geocc: ca`), camera-liveness unlock (`/age_liveness_start`) — no curl-reachable unlock; canary 200. Suspected probe-IP geo artifact (#447/#462 class); residential differential (HITL) decides |
| Eroticmv | ok (gap #497) | plain-curl | deep re-run: rows p1/p2 24/24 cards, 0 overlap; search `hasNext=false` correct (no site search pagination); og:video:url base64 → `vidcdn2.eroticmv.com/.../ilconfessionale1998.m3u8` → 200 `#EXTM3U`. Open gap: single homepage row vs 75 live facets → **#497** |
| Film1k | ok-drift | tls-impersonated | main site TLS 200 (home/search/video); byse embed `film1k.xyz/e/u5iq5hndmnok` 200; **turbovid embed CDN family DNS-dead** → **#496** |
| FreePornVideos | ok-suspected | tls-impersonated | TLS 200 home/search/video; get_file 403 bare = KVS session-bound anti-leech (extractor path) |
| FullPorner | ok-suspected | tls-impersonated | TLS 200 search (24 `video-card`), video 200; embed `xiaoshenke.net` JS quality map (extractor path) |
| HQPorner | ok | plain-curl | deep: see below; mydaddy.cc embed requires referer → mp4 206 (both handled by MyDaddyExtractor `requiresReferer=true`) |
| Javbangers | ok-drift | plain-curl | search 200, 150 `/video/` links; KVS kt_player extractor path. Standing #475 re-confirmed: `/search/<q>/1/`, `/2/` still 404 |
| JavGuru | ok | plain-curl | home/search/video 200; javmiku base64 iframe (extractor path) |
| Javmost | ok | plain-curl | **standing #480 CLEARED**: `/showlist2/{all,uncensor,censor,new}/p1|p2|p3` all distinct JSON, 0 URL overlap, 24 results each |
| Javseen | ok | plain-curl | AJAX search 200 escaped-JSON video links (#477 stays clear); video 200; embed 200 |
| Javtiful | ok | plain-curl | deep: see below; native `<video>` mp4 source |
| Mangoporn | blocked | residential-differential | **522 re-confirmed** on home (standing #464; #456/#472 history) |
| MissAV | ok | plain-curl | deep: see below; eval-packed surrit m3u8 (known flow) |
| Neporn | ok | plain-curl | search/video 200; get_file 403 bare = session-bound cookie flow (known, unchanged) |
| PerverZija | ok | plain-curl | search 200 (64 post blocks, root-level post URLs); video 200; `pervl2.xtremestream.xyz` player (extractor path) |
| PandaMovies | ok-drift | plain-curl | **origin healed at .org**: .pw 302→.org; home/search/video 200; embed-host streams (seekplayer/dood, extractor path). Standing #463/#471 522 condition cleared; canary update proposed |
| PornXP | ok | plain-curl | `/tags/<q>` 36 cards (#476 stays clear); stream 206 `cdrn.pornxp.sh/.../360.mp4` |
| Porntrex | ok | plain-curl | search 200 (85 cards); video 200; stream 206 on live videos (fresh single-use token); one old video (1743439) file 404s on CDN — single dead asset, not a listing defect |
| Sexfilm | ok | plain-curl | two-step cookie-jar flow: search 200, `div.short nl2` cards with video links |
| WatchPorn | ok | plain-curl | search 200 (`/video/<id>/` cards); fresh get_file → 200 `image/gif` 37 B = KVS anti-leech for non-session replay — session-bound flow **as recorded in run 3**, not drift |
| Xhamster | ok | plain-curl | search 200 (47 `/videos/` cards); video 200; m3u8 200 `video-nss.xhcdn.com` |
| ixiporn | ok-drift | plain-curl | `.org`→`.live` double-redirect re-confirmed (standing #468): final URL `ixiporn.live/page/1?s=milf`, 30 `video-block` cards |
| XMoviesForYou | ok-suspected | tls-impersonated | TLS 200 search: SSR anchor cards (`/faketaxi-...-needs-sperm`, posters, HD badges); video 200 |

Verdicts: 18 ok, 4 ok-drift (Film1k #496, Javbangers #475, ixiporn #468,
PandaMovies healed), 4 ok-suspected (probe-IP/session-bound artifact classes),
1 blocked (Mangoporn, dead origin). False positives filed this run: 0.

## Deep tier (Phase 2) — Eroticmv, MissAV, Javtiful, HQPorner

Method: every homepage row's pagination p1 vs p2 card-disjointness (scoped to
each provider's own card selector — naive href regexes over-count from
sidebars/footers), search p1 vs p2 disjointness, hasNext discipline from code +
live, ≥1 video page + stream resolution.

| Provider | Rows | Row pagination | Search pagination | hasNext | Stream |
|---|---|---|---|---|---|
| Eroticmv | 1 | 24/24, 0 overlap | none (site) — `hasNext=false`, page>1 returns empty | `home.isNotEmpty()` | m3u8 200 (b64 og:video chain) |
| MissAV | 21 | 20 disjoint; `/en/new` 1 pinned card both pages (benign sticky); Tokyo Hot p2 empty (8-item series, benign) | 12/12, 0 overlap | `home.isNotEmpty()` | surrit m3u8 via eval-packed flow |
| Javtiful | 4 | all disjoint | 24/24, 0 overlap | Next-link check | native mp4 |
| HQPorner | 67 | 55 disjoint; 12 rows with 1–3 rotating featured repeats (≤6%, site rotation, benign) | 50/47, 0 overlap | blanket `true` in search + single-arg `newHomePageResponse` (CloudStream default) — noted, not a defect: /top/999 returns distinct non-clamped content | mp4 206 via mydaddy (referer-gated) |

`last_deep` stamped 2026-09-29 for all four (Eroticmv re-stamped; its fresh
single-row evidence fed #497).

## Findings lifecycle (Phase 3)

New (unlabeled, dedup-checked via `gh issue list --search` over open+closed):

- **#496 Film1k: turbovid embed CDN family DNS-dead** — `turbovidhls.com`,
  `cdn{1,5}.turboviplay.com`, `turbosplayer.com` have no A record (local
  resolver + Google DoH + Cloudflare DoH agree; MX still answers, so deliberate).
  Videos sourced only via turbovidhls get zero streams, and `loadLinks`' turbovid
  branch (`app.get("https://turbovidhls.com/t/$code")`) raises an uncaught DNS
  exception instead of falling through. byse-path videos unaffected.
  Not a recurrence of #448/#465 (origin 403 challenge class).
- **#497 Eroticmv: single hard-coded homepage row vs 75 live browse facets** —
  `country/genre/decades` taxonomy (e.g. `/category/country/canada/` → 200, 24
  `article.post-item` cards) never surfaced by `mainPageOf("$mainUrl/" to "Latest…")`.
  Lens item 1 (single row when site exposes facets). Not a recurrence of #175
  (card metadata fields) or #290/#321 (loadLinks shape-B, re-verified healthy).

Standing conditions:

- **Javmost #480 CLEARED** — the byte-identical page-2 condition is gone (all
  rows paginate disjointly). Registry verdict → ok; maintainer may close out.
- **PandaMovies #463/#471 HEALED** — origin alive at .org; verdict → ok-drift;
  canary expectation update proposed in this PR. `chronic: true` retained
  (5 historical 522 recurrences — maintainer's retirement call).
- **Mangoporn #464 re-confirmed** — still CF 522.
- **Javbangers #475 re-confirmed** — search page suffix still 404s.
- **ixiporn #468 re-confirmed** — double-redirect still present.

False-positive registry untouched: #447 #448 #449 #450 #461 #462 #465 #466
#467 #473.

## Instrument notes

- Probe IP is datacenter (regulated region for adult content in some
  jurisdictions): EPorner's wall is the only new geo-artifact this run; all
  other challenge-class surfaces behaved per canary/registry expectations.
- KVS single-use tokens: get_file URLs are consumed on first fetch — sweep
  stream checks replay with a fresh token from a fresh page fetch; WatchPorn's
  37-byte `image/gif` response is the KVS anti-leech answer for non-session
  replay (session-bound, provider flows carry the cookie jar).
- TLS tier via curl_cffi chrome impersonation used where plain curl 403s
  (Film1k, FreePornVideos, FullPorner, XMoviesForYou).
