# FINDINGS-656 — Javbangers audit, probe, and fix (run of approved #631 spec)

Attempt 2 of #656 (attempt 1 = run 38994383950 "run23": full probe completed, verdict `ok`
retained, but its push died rebasing `audits/findings.json` onto a main that had moved —
fallback record #712). This run redid the audit against a fresh main and ships the same
registry delta re-based on it.

**Verdict: `ok` retained** — tier `plain-curl` on every surface; the escalation ladder never
escalated (no 403/challenge seen anywhere, plain curl reproduced everything).
`last_deep = 2026-10-17`. **No confirmed provider condition → no code change in the provider;
version stays 7.** Standing #475 contract re-confirmed (see below); nothing refiled, 0 new
finding issues, 0 false positives. `drift_confirmed` stays 1 (hardening rule not reached).

## Instrument calibration (canaries 3/3 — instrument healthy)

| canary | expected plain-curl | observed | verdict |
|---|---|---|---|
| film1k-challenge-pair | 403 | 403 | match |
| eporner-healthy | 200 | 200 | match |
| pandamovies-redirect-origin | 302 | 302 (→ .org follows) | match |

## Probe results (all plain-curl; IP region: runner datacenter, same as every prior pass)

| Surface | Result |
|---|---|
| home latest-updates p1/p2 | 200, 30+30 cards, 0 href-overlap ✓ |
| home most-popular p1/p2 | 200, 24+24, 0 overlap ✓ |
| home milf/uncensored p1/p2 | 200, 24+24, 0 overlap ✓ |
| home top-rated/creampie/teen p1/p2 | 200, 24+24, 1/4/2 overlap — site-side last-page wrap-pad (known, deterministic, async-identical; recorded, not drift, not filed) |
| home cosplay/hentai/chinese | 200, 24 cards each (all mainPage slugs live) ✓ |
| search p1 | `/search/japanese/` 200/75, `/search/anal/` 200/75, `/search/mdyd/` 200/3 (query-shape exact match to FINDINGS) ✓ |
| search p2 | `/search/{q}/2/` → 404 on japanese AND mdyd — standing #475 contract re-confirmed; provider `hasNext=false` correct; do NOT refile |
| quickSearch | absent — homepage form `data-url="…/search/%QUERY%/"` is an on-submit redirect template, 0 suggest/typeahead JS; async block probe (`mode=async&function=get_block&block_id=list_videos_search_videos_list&from=2`) returns 200 with 0 video links. Provider keeps hasQuickSearch=false ✓ |
| video pages | 5 distinct probed (3 latest-updates, 1 latest-updates p2, 1 category milf), all 200; all 5 carry `flashvars` with plain `video_url` + `video_alt_url` (480p+720p) ✓ — 0 placeholders in this sample (run23 observed ~40% file-less placeholder imports among 29 pages; site-side, varies by sample, recorded not filed) |
| card markup | `a.thumb[href,title]` + `img.cover[data-original]` + `p.inf a` unchanged; card URLs are absolute (`https://www.javbangers.com/video/{id}/{slug}`) ✓ |
| details surfaces | `h1` title, `og:image` poster (`jav.cdntrex.com/contents/...`), `div.block-details` categories links, `div.related-videos` block present on all 5; Submitted badges live (`5 hours ago`, `3 months ago`, `2 years ago`, `2 hours ago`, `4 weeks ago`) ✓ |
| stream chain | flashvars `video_url` → `get_file` → **302** → `origin13-direct.cdntrex.com/remote_control.php?file=...mp4&acctoken=...` → **206 video/mp4 with site referer**; **origin hop 403s without the referer** (re-probed 3/3). Provider always ships `referer = mainUrl` — evidence update only, zero behavioral change. Origin host rotates by probe date (origin11/origin25 in older FINDINGS, origin13 now) — never pinned in provider code, correct. |

## Site-side observations — recorded in FINDINGS, not drift, not filed

1. Last-page wrap-pad on small categories (top-rated 1/24, creampie 4/24, teen 2/24 p1∩p2)
   — mirrors what every client sees; unchanged from run23's 1–22 range.
2. File-less placeholder imports exist site-wide (run23: 12/29) though 0/5 in this sample —
   `loadLinks` correctly returns false for them; app shows "no streams" like the site's
   own empty player.
3. Origin hop requires the site referer (confirmed again this run) — provider already ships it.
4. Origin host id rotates (origin11 → origin25 → origin13 across probe dates) — chain is
   host-agnostic in the provider.

## Gates

- `./gradlew bootstrapCloudstream` ✓ (fresh mavenLocal fetch)
- `./gradlew Javbangers:test` ✓ green — **only after repairing shared/ (see below)**
- `./gradlew Javbangers:make` ✓ clean `.cs3`
- `.pi/skills/verify-provider/scripts/verify.sh` vs live site: **RESULT: PASS (exit 0)** —
  search 3 pages (75/3/74 cards), home p1/p2 30+30, 5 video pages 200 + `div.block-details`,
  tags present on all 5, streams **5/5 → 206 video/mp4** via `--stream-url` position-matched
  get_file chain + `Referer: https://www.javbangers.com/`. Same script-limitation NOTES as
  the 2026-09-11 precedent: year selector NOTE (first `em.badge` is the JS-filled empty
  Duration badge; direct probe shows the Submitted badge on all 5 — parsing is unit-tested);
  related-selector omitted (regex-DOM truncation fake-dups card-text titles — Porntrex
  precedent; direct probe shows the related block on all 5 pages); search↔load agreement
  NOTE (fuzzy search returns 74 cards for the `svvrt-003` query without the sampled video —
  same-shape agreement covered by card `a.thumb href` == load URL and shared og:image poster).
- `check-findings.sh` ✓ PASS

## Shared repair (root cause in shared/, per AGENTS.md)

`./gradlew Javbangers:test` was **red on arrival**: `shared/src/test/kotlin/com/kraptor/HostRegistryTest.kt`
came in syntactically corrupted with the #713 merge (sweeper-scoped PR) — the
`xmfy-served filehost families` test is missing its closing `}`, the vidara KDoc lost its
`/**` opener, and a stray un-commented attribution fragment sits after a `assertTrue`. Since
shared tests run with every provider's `test` task (splice coupling, ADR-0002), **every
provider's test was red on main** while `make` (which never compiles test sources) stayed
green — the pipeline could not see it. Repair is syntax-only (restore `}`, `/**`, comment the
fragment); no assertion, row, or behavior change; the vidara/xmfy tests' pinned registry rows
all exist and pass. No provider version bumps: no provider's runtime behavior changed.

## Registry delta

`audits/findings.json`: **Javbangers entry only** (verdict `ok` retained, tier, surfaces,
`last_deep = 2026-10-17`, `drift_confirmed` stays 1) + one `last_runs` entry (run25).
No fleet-wide touches — concurrent per-provider runs cannot clobber each other (the exact
failure that killed attempt 1's push).
