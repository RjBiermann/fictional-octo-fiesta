# FINDINGS-442 — site-by-site audit (probe + compile/test, no code changes)

Issue #442 is audit-only ("no need to fix", create issues for findings). Scope below.

**Revision 2 — this file was revised in review.** The first pass probed with plain
curl (desktop UA, `--max-time 15-20`), which produced three "BROKEN" verdicts whose
provider/PR thread review flagged as a probe-tooling artifact: FINDINGS-408 (repo
standard, 2026-09-13) documents that accurate probing needs curl_cffi with chrome
TLS impersonation precisely because plain curl produces false CF/403 verdicts. All
sites were re-probed with curl_cffi `chrome` impersonation (same Firefox/130.0 UA)
at the FINDINGS-408 depth — homepage + search + page-2 for the sites that paginate
— plus one watch page with the provider's decisive stream step replayed for
FreePornVideos, FullPorner and XMoviesForYou. Film1k was additionally retried with
`firefox`, `safari` and `chrome110` impersonation.

## Validation

- `./gradlew bootstrapCloudstream && ./gradlew clean && ./gradlew test` — **BUILD
  SUCCESSFUL** (2026-09-xx, this review branch): all 25 provider subprojects compile,
  all unit tests green. A starting-from-clean `test` build is the AGENTS.md bar.
  (An explicit `./gradlew build` additionally fails on pre-existing lint errors in
  `shared/` — `startsWith`-era `Iterable#forEach` desugaring, unrelated to this
  audit-only change; noted, not fixed.)
- One subproject per provider dir (25 total incl. `ixiporn`; `shared` is spliced,
  not a subproject).

## Per-site results (impersonated re-probe, this run)

Search query `milf` unless noted. watch = provider's decisive stream step replayed.

| Provider | Site | Home | Search | P2 | Watch / stream step | Verdict |
|---|---|---|---|---|---|---|
| AllClassicPorn | allclassic.porn | 200 | 200 | 200 | — | OK |
| Cat3Film | cat3film.com | 200 | 200 (JSON, exact-title search; `milf` empty is site semantics) | n/a (JSON) | — | OK |
| Cat3Movie | cat3movie.org | 200 | 200 | 404 on guessed `/page/2/?s=` | — | OK (pagination pattern mistrusted, not re-verified) |
| **EPorner** | eporner.com | 200 (5 KB **"Eporner Age Verification"** wall) | 200 (same AV wall) | 200 (same wall) | blocked by wall | **AV WALL — geo/IP-conditional, not confirmed site-wide break.** FINDINGS-408 (8 days earlier) had full 200/OK chain incl. stream, from the same kind of egress. HTTP 200 + wall body persists under chrome impersonation and cookie retries; wall demands camera/photo account verification. Issue #447 downgraded to *unverified — AV-wall pattern, likely egress/IP-conditional*, not "site broken". |
| Eroticmv | eroticmv.com | 200 | 200 | 404 on guessed `/page/2/?s=` | — | OK |
| **Film1k** | film1k.xyz | **403 nginx** | **403 nginx** | 200 but only client-rendered "Byse Frontend" SPA shell (1 KB) | — | **BROKEN → issue #448 stands.** 403 on home and search with chrome/firefox/safari/chrome110 impersonation — diverges from FINDINGS-408, where chrome impersonation passed. Genuine change since 2026-09-13, not a probe artifact. |
| **FreePornVideos** | freepornvideos.xxx | 200 | 200, `/videos/{id}/{slug}/` cards | 200 | `/videos/93774858/...` → 200, `get_file/8512/.../93774858_2160m.mp4/` source present | **OK — NOT BROKEN.** Plain-curl 403/turnstile verdict was a probe-tooling artifact (chrome impersonation passes). Issue #449 downgraded. |
| FullPorner | fullporner.com | 200 | 200, `/watch/{id}` cards | 200 | (not replayed this run; provider has CloudflareInterceptor + CloudflareKiller and 408's 2026-09-13 chain via impersonation was OK) | OK (in-app check only) |
| HQPorner | hqporner.com | 200 | 200 | 200 | — | OK |
| ixiporn | ixiporn.org | 200 | 200 | 200 | — | OK |
| JavGuru | jav.guru | 200 | 200 (`page/1/?s=`) | 200 | — | OK |
| Javbangers | javbangers.com | 200 | 200 | n/a (provider matches p1-only) | — | OK |
| Javmost | javmost.ws | 200 | 200 (`/showlist2/milf/1/search/` JSON) | envelope handles pages | — | OK |
| Javseen | javseen.tv | 200 | 200 (JSON envelope) | envelope handles pages | — | OK |
| Javtiful | javtiful.com | 200 | 200 | 200 | — | OK |
| Mangoporn | mangoporn.net | 200 | 200 | 200 | — | OK |
| MissAV | missav.live | 200 | 200 | 200 | watch page 200 with packed-JS m3u8 (first pass) | OK |
| Neporn | neporn.com | 200 | 200 | 404 on guessed `/search/milf/2/` | — | OK |
| PandaMovies | pandamovies.pw | 200 | 200 | n/a | — | OK (server-side fuzzy-search brittleness, provenance in FINDINGS-444) |
| PerverZija | tube.perverzija.com | 200 | 200 | 200 | — | OK |
| PornXP | pxp.news | 200 | 200 | 200 | — | OK |
| Porntrex | porntrex.com | 200 | 200 | 200 | — | OK |
| Sexfilm | en.sex-film.biz | 200 | 200 (DLE search URL) | n/a | — | OK |
| WatchPorn | watchporn.to | 200 | 200 (async block) | n/a | — | OK |
| Xhamster | xhamster.com | 200 | 200 | 200 | — | OK |
| **XMoviesForYou** | xmoviesforyou.com | 200 | 200, 24 `a.group.flex.flex-col` cards | 200, 24 cards | watch page 200; `/api/related/{postId}` replay → 200 JSON envelope with results | **OK — NOT BROken in cloudstream sense; plain-curl 403 `challenge-platform` was a probe artifact. Issue #450 downgraded.** |

## Issue evidence corrections (review round)

- #447 (EPorner): downgraded to *unverified — IP/geo-conditional AV wall*; the
  2026-09-13 OK baseline (FINDINGS-408:13-15) negates "site dead". Single-egress
  probe; no second-region retry was made.
- #448 (Film1k): **stands**, evidence strengthened — 403 persists through all
  impersonation variants, a genuine drift vs FINDINGS-408.
- #449 (FreePornVideos): false "broken provider" issue from plain-curl evidence —
  downgraded; search + watch chain verified live via impersonation.
- #450 (XMoviesForYou): false "broken provider" issue — downgraded; search,
  pagination and `/api/related/` step verified live.

## Common signal

Film1k is the only site that fails even under the repo-standard probe. Note: since
three of the four first-pass "broken" verdicts were probe artifacts, any future
sweep must use curl_cffi chrome impersonation from the start (FINDINGS-408 header).

## Not done (out of scope for the audit pass)

- In-app stream playback verification (maintainer-only per AGENTS.md).
- Watch-page/stream-chain replay for the 22 sites whose home+search+pagination came
  back clean (first pass did spot checks for Sexfilm and MissAV; deep chain replays
  for FreePornVideos, FullPorner and XMoviesForYou this round).
- Single egress (datacenter IP). No second-region retry — relevant to the EPorner
  AV-wall verdict specifically.
- `verify.sh` of `.pi/skills/verify-provider/` does not exist in this tree;
  verification fell back to the documented `clean` + `gradlew test` bar.
