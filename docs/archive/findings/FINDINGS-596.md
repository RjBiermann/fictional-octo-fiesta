# FINDINGS-596 — Fleet sweep round 14 (S1 of the #593 spec)

Run date: 2026-10-14. Scope: Phase 0 canary calibration + uniform per-provider sweep
across all 26 providers (home → quick search → one video page → stream artifact).
No deep tier this run (spec scope). Verdict-migration rule honoured: **no verdict
changes** — every run-10 verdict re-confirmed on fresh evidence; no condition
confirmed/cleared in a way that warrants a re-verdict.

## Phase 0 — canaries (all match)

| Canary | Expected | Observed | Result |
| --- | --- | --- | --- |
| film1k-challenge-pair | plain 403 / TLS 200 | plain 403 / TLS 200 → film1k.com | match |
| eporner-healthy | plain 200 | plain 200 | match |
| pandamovies-redirect-origin | plain 302 / TLS 200@pandamovies.org | plain 302 → 200 @ pandamovies.org | match |

`last_verified` refreshed to 2026-10-14 in `audits/canaries.json`. Instrument
healthy — no degradation clause applies (Blocked verdicts would not be downgraded,
and none were recorded anyway).

## Sweep transcript (instrument tier per surface)

All listing surfaces plain-curl with UA; TLS-impersonated tier used for
Eroticmv (both surfaces), FreePornVideos/FullPorner/XMoviesForYou video+search
pages (known session/leech ok-suspected exclusion class, standing #449/#466).
Script-URL artifacts from the sweep harness corrected back to provider URLs:
Javbangers `/search/sex/` p1 200 (p2 404, standing), Javmost `/showlist2/new/1/release/`
JSON listing 200 (the `all` group's pending bucket stays null-meta per #332).

| Provider | Home | Search | Video page | Stream artifact tiers/markers |
| --- | --- | --- | --- | --- |
| AllClassicPorn | 200 | 200 | 200 `/videos/6073/...` | .mp4 embed player source |
| Cat3Film | 200 | 200 (`_ajax/search`) | 200 `/a-foreign-girl-in-paris` | player api/v1 source |
| Cat3Movie | 200 | 200 | 200 `/losing-ground-1982` | player.php embed |
| EPorner | 200 | 200 | 200 `/video-31ISvxUzK26/...` | .mp4 embed |
| Eroticmv | 200 (TLS; plain = CF challenge) | 200 (TLS) | 200 `/high-school-fantasies-1974/` | .m3u8 hls embed player |
| Film1k | 200 (TLS) | 200 (TLS) | 200 `/dr-fummel-...1970.html` | .mp4 hls embed player |
| FreePornVideos | 200 (TLS) | 200 (TLS) | 200 `/videos/93679370/...` | .mp4 hls embed player |
| FullPorner | 200 (TLS) | 200 (TLS) | 200 `/video/put-on-the-ladies-face/` | .mp4 player source |
| HQPorner | 200 | 200 | 200 `/hdporn/128234-under_some_spell.html` | player.php iframe |
| JavGuru | 200 | 200 (`/?s=sex` cards present via `/1062.../` slugs) | 200 `/1062789/...` | iframe player |
| Javbangers | 200 | p1 200 / p2 404 | 200 `/video/216671/...` | .mp4 embed player |
| Javmost | 200 | 200 (`/showlist2/new/1/release/`, 24 result entries) | 200 `/DLDSS-538-REDUCING-MOSAIC/` | og:title player m3u8 |
| Javseen | 200 | 200 (ajax JSON, cards present) | 200 `/287853/...` | embed player source |
| Javtiful | 200 | 200 | 200 `/video/114548/...` | .mp4 embed player |
| Mangoporn | 200 | 200 (p2 200) | 200 `/movies/brazilian-teacher/` | playmate embed .mp4 |
| MissAV | 200 | 200 | 200 `/dm33/en/naughty4610` | .mp4 embed |
| Neporn | 200 | 200 | 200 `/video/34227/...` | .mp4 embed player |
| PandaMovies | 200 (pw → .org) | 200 (@.org) | 200 `/watch-brazilian-teacher-movie-online-free` | playmate .mp4 embed |
| PerverZija | 200 | 200 | 200 `/private-bobbi-eden-and-donna-marie-.../` | iframe embedurl source |
| PornXP | 200 | 200 (`/tags/sex`) | 200 `/videos/626869681587` | .mp4 |
| Porntrex | 200 | 200 | 200 `/video/2879909/...` | castplayer embed .mp4 |
| Sexfilm | 200 | 200 | 200 `/11760-two-cougars-...-theirs.html` | player |
| WatchPorn | 200 | 200 (ajax mode=async) | 200 `/video/309/...` | player source |
| XMoviesForYou | 200 (TLS; plain ok via header UA) | 200 (TLS) | 200 `/sexmex-elizabeth-marquez-...` | postId embed player |
| Xhamster | 200 | 200 | 200 `/videos/10423906` | .m3u8 .mp4 hls embed |
| ixiporn | 200 | 200 (`/search/sex` @ .live) | 200 `/video/hindi-hot-web-series-.../` | source embed |

## Conditions observed this run

1. **Javbangers search page-2 404** — re-confirmed at provider URLs
   (`/search/sex/` 200, `/search/sex/2/` 404, both tiers). Standing, already
   closed with condition recorded; not re-filed.
2. **Eroticmv header CF challenge from probe IP** (plain 403, TLS 200) —
   same Suspected (challenge from datacenter probe IP) class, never a finding
   on its own. Site fully fine on the TLS tier.
3. **#538 Eroticmv articleSection healed** — `articleSection` array is
   populated again on live video pages (e.g. `["1970s","Classic Porn","School","USA"]`
   on /high-school-fantasies-1974/, 2026-10-14). Issue #538 is already closed;
   recorded here per the evidence-change rule — no re-open requested.
4. **#539 Javseen og:video:duration sentinel healed** — content="6600" observed
   live (run 10 already reported healed). No action.

## Findings lifecycle

- Search of open + recently-closed findings: all standing provider conditions
  are in closed issues; none re-confirmed in a state requiring a re-open.
- **New findings this run: none.** `false_positives: []`.

## Raw probe HTML

Raw probe HTML (surfaced from the run sandbox) deletes from the repo per the
FINDINGS-only ground-truth rule; tables above are the ground truth.
