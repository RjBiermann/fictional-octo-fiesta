# FINDINGS-494 — Fleet-wide audit: CloudStream API correctness (issue #494)

Audit-only run: probe every provider against live sites + the CloudStream API
contract, file data-population findings, update the registry. No provider code
changes. Probe run 2026-09-30, branch `devloop/issue-494`, third sweep of this
issue (runs 5 and 6 both on #494; run 5 delivered PR #498, merged). 26 providers.

## Canary calibration (Phase 0)

| Canary | Expected | Observed | Outcome |
|---|---|---|---|
| film1k-challenge-pair | plain 403 / TLS 200 | plain 403 / TLS 200 | **match** |
| eporner-healthy | plain 200 | 200 | **match** |
| pandamovies-redirect-origin (.pw → .org) | plain 302 / TLS 200 | `.pw` 302 → `pandamovies.org` 200 both tiers | **match** |

All three canaries match the (run-5-updated) expectations — no drift in the
instrument this run.

## Sweep (Phase 1) — verdict table

| Provider | Verdict | Tier | Evidence (2026-09-30) |
|---|---|---|---|
| AllClassicPorn | ok | plain-curl | search `/videos/` links; KVS get_file stream 206 (single-use tokens) |
| Cat3Film | ok-suspected | plain-curl | `_ajax/search` alive; sources API 200: backup `abyssplayer` 200 (encrypted SoTrym media, `window.SoTrym` + obfuscated `iamcdn.net` bundle — not decryptable in probe), source `abyssssss.top` hls 404; embed chain live, stream unresolved this run |
| Cat3Movie | ok (deep) | tls+plain | deep: see below — hlsfast AES-CBC chain verified end-to-end |
| EPorner | ok-suspected (deep) | plain-curl | deep: full-page `/agecheck/` wall (5,743 B, `EP.account.login.openModalAgeVer`, camera-liveness unlock, no static bypass) = regulated-region probe-IP artifact (`geocc=ca`); canary matches; residential differential decides |
| Eroticmv | ok | tls-impersonated | facet p1 200 (46 cards); `/category/genre/milf/2|3/` 404 = 1-page facets **by design** (code clamps `hasNext=false`, `hasNext` covered by the same clamp; #497 gap already filed for the row surface); og:video base64 → `vidcdn2.eroticmv.com/...m3u8` 200 `#EXTM3U` |
| Film1k | ok (deep) | tls-impersonated | deep: see below — Byse PoW/AES-GCM playback chain verified end-to-end; turbovid branch removed (#500/#501) confirmed current |
| FreePornVideos | ok-suspected (deep) | tls-impersonated | TLS 200 home/search/video; search p2 TLS 24 cards, 0 overlap; KVS `get_file` 403 bare (session-bound anti-leech, extractor path) |
| FullPorner | ok-suspected | tls-impersonated | TLS 200 search (24 `/watch/<hex>` cards); video 200 → xiaoshenke embed live (JS quality map, extractor path); probed video's direct `vid/<id>/<q>` 404 = per-video content death, not a provider defect |
| HQPorner | ok | plain-curl | deep run 5 (67 rows, streams verified); no drift this run |
| Javbangers | ok-drift | plain-curl | home 75 cards; **stream VERIFIED**: `get_file/4/...103436.mp4` 5.6 MB downloaded before truncation; standing #475 re-confirmed: `/search/<q>/2/` 404 |
| JavGuru | ok-suspected | plain-curl | home/search/pagination 200, p1∩p2 = 0; embed chain live: shortcode → `/searcho/?xd=<token>` → cfg decode → `/searcho/?xr=<reversed>` = **CF 520** (16 B) transient — stream unverifiable this run |
| Javmost | ok | plain-curl | **#480 stays CLEARED**: `/showlist2/{group}/{page}/{type}/` distinct JSON per page; watch-page 404 template AJAX-references `www5.javmost.com` host (harmless residue) |
| Javseen | ok (deep) | tls-impersonated | deep: see below — home is AJAX-only **by site design** and the provider already fetches the AJAX rows (verified 30 items); stream end-to-end via savedvids/streamhg |
| Javtiful | ok | tls-impersonated | 3 `/video/...` home links; video 200 (preview mp4s on `/videos/previews/...` — real source path per run-5 deep tier) |
| Mangoporn | blocked | residential-differential | **522 both tiers** (`mangoporn.net` TLS, plain DNS fail/.co 522; standing #464; #456/#472 history) |
| MissAV | ok (deep) | tls-impersonated | deep: see below — eval-packed surrit chain unpacked and verified live |
| Neporn | ok | tls-impersonated | video `/video/<id>/` 200; KVS `video_url` `get_file/7/...720p.mp4` **session-bound 200, 15.4 MB flowing** (bare = 403 anti-leech, known) |
| PerverZija | ok | tls-impersonated | stream VERIFIED: `index_cf.php` (referer/session CF tier) → `xs1.php?data=…` 200 `#EXTM3U` (854x480 variant) → `pervl5.xspcdn01.click/cdn/.../4800.html` segments 200 TS-bytes |
| PandaMovies | ok-drift | plain-curl | `.pw` 302→`.org` re-confirmed (healed; standing #463/#471 stay cleared); home 87 cards; `/genre/movies` 200 (heal condition) |
| PornXP | ok | tls-impersonated | **deep**: `pornxp.com/videos/<id>` 301→`youporn.com/channel/brazzers/` = channel vanity redirects (not provider path — `mainUrl=pxp.news`); `pxp.news/videos/<id>` 200 with `<source>` 360p/1080p `xxx.pornxp.sh/...mp4` → **20.8 MB flowing**; #476 stays clear |
| Porntrex | ok | tls-impersonated | video 200; KVS `video_url` `get_file/15/...1311225.mp4` in-session **7.1 MB flowing** (`/video/<id>/` card shape) |
| Sexfilm | ok | plain-curl | two-step cookie-jar flow re-confirmed |
| WatchPorn | ok | tls-impersonated | video 200 (`/video/<id>/<slug>/` shape); KVS `video_url` `get_file/9/...21344_720p.mp4` in-session **5.3 MB flowing** |
| Xhamster | ok | tls-impersonated | 50 home cards; video 200 → signed `video7.xhcdn.com ...m3u8` → **200 `#EXTM3U`** |
| ixiporn | ok-drift | plain-curl | `.org`→`.live` double-redirect (standing #468); home 32 cards via `.live/...`; video 200 with `.mp4` |
| XMoviesForYou | ok | tls-impersonated | provider's `?q=$query&page=$page` pagination **verified correct** (24 cards p2, 0 overlap); dedi node `/api/stream/<vid>?target=dedi_1` needs `X-Requested-With` (matches code) → node m3u8 403 at CDN = probe-IP block; **streamtape fallback 200** — multi-host contract intact (`XMoviesForYou.kt:169`) |

Verdicts: 17 ok (4 newly deep-verified: Cat3Movie, Film1k, Javseen, MissAV;
stream smokes confirm 9 more), 4 ok-drift all standing (Javbangers #475,
ixiporn #468, PandaMovies healed), 5 ok-suspected
(probe-IP artifact classes: Cat3Film, EPorner, FreePornVideos, FullPorner,
JavGuru), 1 blocked (Mangoporn #464). **Findings filed this run: 0.**
False-positive set untouched: #447 #448 #449 #450 #461 #462 #465 #466 #467
#473.

## Deep tier (Phase 2)

### Cat3Movie (new deep)

- Watch mechanics: `player.php?episode_slug=full&server_id=N&subsv_id=&post_id=…&nonce=…` +
  `X-Requested-With` + referer `/full-sv1/` + cache-buster → `<iframe src="https://hlsfast.com/#<hash>">`.
  Nonce is **unvalidated** server-side (code comment confirmed live); CF-cached
  404 dodged via referer + cache-buster — code matches site.
- Stream chain VERIFIED (All About Anna, hash `5z6s5u`): `/api/v1/video?id=<hash>&w=1280&h=720&r=cat3movie.org`
  → AES-CBC blob; openssl decrypt with key `kiemtienmua911ca` IV
  `1234567890oiuytr` (= `HLSFAST_KEY`/`HLSFAST_IV` in code) → JSON `{cf,
  player, title, thumbnail, poster, swarmId}` → direct-IP `94.131.217.177/v4/…`
  master.m3u8 200 → `index-f1-v1-a1.m3u8` (720x366) → `seg-1-f1-v1-a1.m4s`
  **200 video/mp4 231 KB**.
- Per-movie dead hashes (`putipx`, `lslxrt`, `tyxlmz`) → 404 "Video not found
  or deleted" = content-level, not provider bug.
- Search: `/search/milf/` 12 cards; `/search/milf/2` 200-but-empty → **no
  search pagination** — provider comment (line ~67) matches live.
- Static admin endpoints (`halim-ajax.php`, admin-ajax) → `0`. Dead.

### Film1k (new deep — full playback chain)

- Home/search/video TLS 200 (`www.film1k.com` canonical; `film1k.xyz` plain-tier 403).
- Home p1∩p2 = 9/33 shared = site's own pagination markup rotation (same pages
  the provider would render) — benign.
- Byse embed chain **verified programmatically end-to-end**: `/api/videos/<code>/embed/captcha/`
  200 (`pow_nonce`, difficulty 16, `pow_token`) → shared `solvePow` port answers
  verify 200 (`SOL=12872`, JVM classes, goldens green) → `/playback/` 200 with
  AES-GCM payload → key assembly (`key_parts[version-1]` ‖
  `key_parts[31-version-1]`) decrypts → JSON `{sources:[{url:"https://edge2-waw-sprintcdn...master.m3u8?t=…"}]}`
  → master 200 → `index-v1-a1.m3u8` 200 → `seg-1-v1-a1.ts` **200 5.9 MB**.
  Matches `Film1k.kt:118–150` exactly.
- `Film1kV.html` options are all abyssplayer/byse variants (no other hosts);
  turbovid (#496) branch already removed by #500/#501 — confirmed current.

### EPorner (deep attempted — wall-limited)

Full-page age-verification wall for the datacenter probe region. `/agecheck/`
5,743 B, `#ageverifybox`, AI age-estimation unlock, `EP.user.agever` gate; no
static bypass markers (no liveness/bltoken in served HTML). Canary matches —
instrument fine, verdict stays ok-suspected pending residential differential.

### FreePornVideos (deep)

TLS 200 video page (186 KB) KVS flashvars with `get_file/8512/..._480m|720m`
(bare get_file 403 = session/referer anti-leech, extractor-path). og:image
medium@2x poster. Search TLS p1∩p2 = 0.

### Javseen (deep — resolves run-5 candidate)

Run-5 flagged live `/recent/` serving **skeleton markup** (`fp-sk-card`,
`id="sk-browse"`, zero `li[id^=video-]`) as a potential home-rows defect.
Resolved: the site is AJAX-only by design, and the provider's `getMainPage`
already fetches `"/recent/?ajax=browse_videos"` and
`"/<cat>/?ajax=category_videos"` → JSON `{html}` → 200 with **30** video items
per row (verified for browse + category). Search path likewise AJAX (30/page,
p1∩p2 = 0). Stream end-to-end: video page `data-embeds` b64 → 7 embeds →
`worker5.savedvids.com` streamhg `var FIRST` → m3u8 200 → segment **200
video/MP2T 144 KB**. **Candidate dismissed — provider is correct; nothing filed.**

### Stream smokes (Phase 1.5, this run)

End-to-end bytes-verified this run: Neporn get_file 15.4 MB, WatchPorn 5.3 MB,
Porntrex 7.1 MB, Javbangers 5.6 MB, PornXP pxp.news 20.8 MB, Xhamster m3u8
200, MissAV surrit playlist 200 (`eval`-pack p,a,c,k,e,d unpacked manually —
matches provider flow), PerverZija xs1 200 + segments 200, Film1k segment
5.9 MB, Cat3Movie segment 231 KB, Javseen segment 144 KB, XMFY streamtape
fallback 200. Eroticmv og:video m3u8 200.

## Findings lifecycle (Phase 3)

- **New findings: none.** Every candidate triaged: Javseen home (provider
  correct, dismissed), XMFY node 403 (CDN probe-block, fallback verified),
  FullPorner vid 404 (per-video content death), JavGuru CF 520 (transient),
  Cat3Film abyssssss 404 (per-video/source-level; backup live), Cat3Movie
  per-movie 404s (content-level), PornXP .com 301 (channel vanity redirect,
  provider uses pxp.news).
- Standing conditions re-confirmed: Mangoporn #464 (522), Javbangers #475
  (search p2 404), ixiporn #468 (double-redirect). Javmost #480 stays cleared;
  PandaMovies #463/#471 stay healed.
- Open gaps from run 5 unchanged: #496 (Film1k turbovid — fix merged, current),
  #497 (Eroticmv facet rows).
- False-positive registry untouched: #447 #448 #449 #450 #461 #462 #465
  #466 #467 #473.

## Instrument notes

- Probe IP is datacenter (regulated region in some jurisdictions): EPorner's
  age-wall and JavGuru's searcho-520 are the two surfaces where the probe IP
  plausibly degrades the verdict; canaries show no other instrument drift.
- Session-based confirmations (KVS get_file, PerverZija xs1, XMFY api) require
  the chrome-impersonated `curl_cffi` session with probe-referer — bare fetches
  403 is the anti-leech expected class, not drift.
- `solvePow` was validated live via the compiled JVM classes (golden test suite
  green; live solve → verify 200 → playback 200 → segment 200).
