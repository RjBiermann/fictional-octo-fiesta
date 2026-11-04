# FINDINGS — run23, issue #656 — Javbangers (www.javbangers.com)

Self-contained audit+probe+fix run of the approved #631 spec (2026-10-16).
Escalation ladder: **every surface resolved on plain-curl** — TLS-impersonated and browser
steps never needed. Canaries 3/3 match (film1k plain 403 / TLS 200, eporner 200,
pandamovies 302→200@.org) → instrument healthy, no environment degradation.

## Verdict: `ok` (re-affirmed) — tier plain-curl, last_deep 2026-10-16

No confirmed provider condition surfaced → per fix policy, no code change and no version
bump (stays 7). Standing #475 contract unchanged (verified live). 0 finding issues filed
(dedup: nothing new matches a standing condition — #475's search-p2 404 is the honored
by-design contract and still holds).

## Per-surface results (all plain-curl)

| Surface | Result | Disposition |
|---|---|---|
| home latest-updates p1/p2 | 200, 30+30 `div.video-item`, 0 id-overlap | record ✓ |
| home most-popular p1/p2 | 200, 24+24, 0 overlap | record ✓ |
| home top-rated p1/p2 | 200, 24+24, 1 overlap | new: last-page wrap-pad (site-side, recorded) |
| home milf/hentai/chinese p1/p2 | 200, 24+24, 0 overlap | record ✓ |
| home uncensored p1/p2 | 200, 24+24, **22/24 overlap** | last-page wrap-pad (site-side, recorded) |
| home creampie/cosplay/teen p1/p2 | 200, 24+24, 4/2/2 overlap | same wrap-pad mechanism |
| search p1 | `/search/japanese/` 200/75, `/search/anal/` 200/75, `/search/mdyd/` 200/3 | record ✓ |
| search p2 | `/search/<q>/2/` → **404** on japanese and mdyd | standing #475 contract, unchanged |
| quickSearch | no distinct endpoint (form's `data-url` = on-submit redirect to `/search/<q>/`; zero suggest/typeahead JS; `/search/suggest/?q=` is literally the search page for query "suggest") | absent — recorded, not drift; provider `hasQuickSearch=false` correct |
| video pages | 11 distinct probed from latest/most-popular/teen/milf/uncensored-p2/search: all 200; 7 carry flashvars, 4 file-less | see below |
| stream chain | flashvars `video_url` → get_file → 302 → `origin13-direct.cdntrex.com` → **206 video/mp4** with `Referer: https://www.javbangers.com/` (5/5 in verify.sh; origin hop 403s without referer) | record ✓ + evidence update (referer now required) |

## New site-side observations (recorded, not drift, not filed)

1. **Last-page wrap-pad on small categories.** Categories with < 2×24 videos pad their final
   page with wrap-around repeats of page 1. Deterministic (identical across 3 rounds and a
   later re-probe), identical body on the async endpoint (`?mode=async&function=get_block&
   block_id=list_videos_common_videos_list&from=2`). uncensored: 26-video catalog → p2 = 2
   real (408465, 408466) + 22 wrapped; creampie stable 4/24 across re-probes (20 min apart).
   No honored record ever claimed category-p2 disjointness. Provider change would only lose
   the real new items; no fix.
2. **File-less placeholder entries (~40% of sampled cards).** 12/29 distinct probed video
   pages serve 200 with NO `flashvars`/`kt_player`/`get_file`. Slugs are planted-looking
   (`txvlog2`, `ktv-amateur-drunk`, `3519`, bare hex); og/description/Tags/details/related all
   parse; listing cards carry real thumbnails and are indistinguishable from file-bearing
   cards → no pre-filter exists. `load()` fine, `loadLinks` returns false (correct: 0 sources).
   Clustered in 401xxx/404xxx/407xxx bands and the whole chinese sample (3/3). Site content
   hygiene; app shows "no streams" exactly like the site's own empty player. No provider defect.
3. **Origin hop now requires the site referer.** get_file 302s with or without it; the
   resulting cdntrex origin URL serves 206 video/mp4 **only with
   `Referer: https://www.javbangers.com/`** — 403 text/html without. FINDINGS 2026-09
   recorded the referer as optional; the provider has always shipped `referer = mainUrl`, so
   nothing changed behaviorally for the app. Evidence update only.
4. **Same-title distinct-video re-uploads on high-volume search pages.** `/search/japanese/`:
   75/75 unique hrefs, 59 distinct titles / 75 cards (same-scene spam re-uploads; one title on
   7 different ids). Only a verify.sh pairwise-title FAIL; not card duplication. Provider
   renders 75 distinct videos, same as the site.

## verify.sh invocation (2026-10-16)

5 videos from varied listings (latest p1 ×2, most-popular, teen, search-mdyd anchor 393873),
`--stream-url` position-matched per page, `Referer: https://www.javbangers.com/` header.

- search 2 pages ✓, home 2 pages ✓ (30+30, no dups), 5 video pages 200 ✓
  (`div.block-details` presence proxy — flashvars is a JS assignment, no CSS selector;
  FINDINGS-precedent), related `div.related-videos div.video-item` = 10 cards/page ✓,
- **streams 5/5 → 206 video/mp4** (+ manual chain trace: 302 → origin13 → 206; origin-hop
  referer requirement pinned).
- tags present on all 5 ✓; code-completeness: recommendations 2 / tags 4 / plot 1 / year 3
  assignments in `Javbangers/` ✓.
- Instrument-limited formal items (all evidence-backed above): duplicate-title FAIL = same-title
  re-uploads (0 href dups); poster NOTE = `data-original` attr not in the script's fallback set
  (direct parse: real cdntrex thumbs); year NOTE = script takes first `em.badge` (empty
  Duration badge; direct parse: Submitted badge present); quick-search NOTE = endpoint absent,
  explicit in FINDINGS; actors/duration NOTEs = site does not expose server-side (unchanged).

## Gates

- `./gradlew bootstrapCloudstream` ✓ (sha-pinned fetch)
- `./gradlew Javbangers:test` ✓ (31 tasks green)
- `./gradlew Javbangers:make` ✓ → `Javbangers/build/Javbangers.cs3`
- `check-findings.sh` ✓ (run after registry edit)
- `.pi/skills/verify-provider/scripts/verify.sh` ✓ on every mechanically expressible surface

## Registry delta

`audits/findings.json` — Javbangers entry only (verdict ok, tier plain-curl, surfaces
refreshed, last_deep 2026-10-16, drift_confirmed stays 1) + one `last_runs` entry (run23).
No fleet-wide touches.
