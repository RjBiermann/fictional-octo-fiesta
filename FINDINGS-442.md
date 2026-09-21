# FINDINGS-442 — site-by-site audit (probe + compile/test, no code changes)

Issue #442 is audit-only ("no need to fix", create issues for findings). Scope below.

## What was run

- `./gradlew bootstrapCloudstream && ./gradlew test` — **BUILD SUCCESSFUL**, all 25
  provider subprojects compile, all unit tests green.
- One subproject per provider dir (25 total incl. `ixiporn`; `shared` is spliced,
  not a subproject).
- Live probes with plain curl, desktop Chrome UA, `--max-time 15-20`:
  homepage + search-reality (provider-built search URL) for every site;
  doc/watch-page spot checks for Sexfilm and MissAV.

## Per-site results

| Provider | Site | Search probe | Verdict |
|---|---|---|---|
| AllClassicPorn | allclassic.porn | 200 /search/milf/ (201 KB) | OK |
| Cat3Film | cat3film.com | `/_ajax/search?q=`; empty for "milf" but **200 + results for exact titles** ("bella") | OK (tokenized/fuzzy search is title-only; not a break) |
| Cat3Movie | cat3movie.org | 200 (68 KB, cards) | OK |
| **EPorner** | eporner.com | **Age Verification wall on every page** (home, /search, after cookie-jar retry; no skip cookie; AV requires webcam liveness/photos + account: `/xhr/age_liveness_start`, `age_photo_verif`) | **BROKEN → issue filed** |
| Eroticmv | eroticmv.com | 200 (229 KB) | OK |
| **Film1k** | film1k.xyz | **403 nginx on homepage and search; http1.1 and every UA variant identical; server: cloudflare header but body is origin nginx 403 → datacenter/IP deny. No CloudflareKiller in provider. | **BROKEN → issue filed** |
| **FreePornVideos** | freepornvideos.xxx | 403 `cf-mitigated: challenge`, `__cf_chl` turnstile JS challenge on home + search. Provider code has **no** CloudflareKiller/interceptor (Cat3Film, Cat3Movie, FullPorner do). | **BROKEN → issue filed** |
| FullPorner | fullporner.com | 403 CF challenge to curl, **but provider has CloudflareInterceptor + CloudflareKiller** | pass curl, needs in-app check only |
| HQPorner | hqporner.com | 200 (301 KB) | OK |
| JavGuru | jav.guru | 200 (193 KB) | OK |
| Javbangers | javbangers.com | 200 (213 KB) | OK |
| Javmost | javmost.ws | `showlist2/<q>/1/0/` returns JSON results + cards (my initial `/mov/` guess was the wrong URL, not a site bug) | OK |
| Javseen | javseen.tv | 200 (63 KB) | OK |
| Javtiful | javtiful.com | 200 (106 KB) | OK |
| Mangoporn | mangoporn.net | 200 (256 KB) | OK |
| MissAV | missav.live | 200 search + watch page with packed-JS m3u8 (`eval(function(...)`) | OK |
| Neporn | neporn.com | 200 (112 KB) | OK |
| PandaMovies | pandamovies.pw | 200 (240 KB); server-side fuzzy-search brittleness already covered by ADR-ish provenance in FINDINGS-444 | OK (known note) |
| PerverZija | tube.perverzija.com | 200 (526 KB) | OK |
| PornXP | pxp.news | 200 (52 KB) | OK |
| Porntrex | porntrex.com | 200 (639 KB) | OK |
| Sexfilm | en.sex-film.biz | DLE search URL per code → 200, 24 cards; my earlier `search.php` 404 was a wrong guess | OK |
| WatchPorn | watchporn.to | async block search URL → 200 | OK |
| **XMoviesForYou** | xmoviesforyou.com | 403 CF `challenge-platform` on search; provider code has **no** CloudflareKiller | **BROKEN → issue filed** |
| Xhamster | xhamster.com | 200 (357 KB) | OK |
| ixiporn | ixiporn.org | 200 (80 KB) | OK |

## Common root cause signal (XMoviesForYou / FreePornVideos / Film1k)

Three providers stun against bot walls with **zero** Cloudflare handling while the
needed precedent (`CloudflareKiller` + `interceptor`) already exists in three sibling
providers and `shared/` is the sanctioned extension point. If any evolve to a fix,
prefer extending `shared/` (hardening clause, AGENTS.md) over per-provider copies.

## Not done (out of scope for the audit pass)

- In-app stream playback verification (maintainer-only per AGENTS.md); curl cannot
  pass CF JS challenges, so FullPorner/XMoviesForYou stream paths were not traced
  beyond the wall.
- `verify.sh` of `.pi/skills/verify-provider/` referenced in AGENTS.md **does not
  exist in this tree** — verification fell back to `gradle test` + live probes.
