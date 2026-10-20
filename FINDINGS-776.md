# FINDINGS-776 — verify-provider S3 proof: poster-variant normalization green offline + live (S3 of #680)

Run: 2026-10-20 (S3 of the approved #680 spec chain — depends on S1 #774, S2 #775; both merged).
**Deliverable:** proof, offline and live, of the #680 fix. **No code changed in this story.**
Diff is instrument-evidence-only: no provider directory touched, no provider version bumps
(MissAV stays at v19, the #641 closeout value; `git status` clean except this file).

## 1. Offline gates

- `bash -n` on `verify.sh`: clean.
- `bash .pi/skills/verify-provider/scripts/verify_selfcheck.sh` → **RESULT: PASS** (exit 0),
  including the S1 red→green poster-variant cases: `-t`/`-n` collapse to one artwork value,
  at most ONE variant token stripped (`cover-t-2.jpg` → `/covers/cover-t.jpg`), different
  artwork stays distinct, href/stream semantics keep variant tokens, and the real 4-field
  check_listing row shape collapses the poster column via the cross-video COLSPEC.

## 2. Red → green on the live #680 evidence (offline engine, live URLs)

The `milf-091` pair observed in the #641 pass, re-fetched live this run
(search card `img data-src` = `https://fourhoi.com/milf-091/cover-t.jpg`; load page
`meta[property=og:image]` = `https://fourhoi.com/milf-091/cover-n.jpg`):

| | search side | load side | outcome |
|---|---|---|---|
| OLD check-5 equality (`py path`) | `/milf-091/cover-t.jpg` | `/milf-091/cover-n.jpg` | **FAIL poster mismatch for /en/milf-091** (the #680 defect) |
| NEW equality (`py poster_path`) | `/milf-091/cover.jpg` | `/milf-091/cover.jpg` | PASS; differing raw URLs emit the variant NOTE |

## 3. Live re-run — verify.sh with the FINDINGS-641 arguments recapitulated

Invocation (every surface FINDINGS-641 records; selectors byte-equal to its recorded grammar;
stream overrides from its unpacked surrit UUIDs, position-matched; related-videos omitted —
FINDINGS-641 records recommendations as the recombee RAPI POST, not a DOM selector):

    --search-url   https://missav.live/en/search/milf?page=1|2
                   --search-selector 'div.content-without-search div.thumbnail.group'
                   --search-title-selector 'a.text-secondary'
    --home-url     /dm539/en/new?page=1|2, /dm169/en/weekly-hot?sort=weekly_views&page=1|2,
                   /dm628/en/uncensored-leak?page=1|2  (same selector)
    --quick-search-url  /en/search/suggest?q=milf  (same selector)
    --video-url    /en/mxgs-1450, /en/milf-091, /en/mimk-288
                   --video-title-selector h1.text-base --stream-selector 'video.player'
    --stream-url   surrit UUID playlists (18ee1328… / f2e36563… / 6346777f…)
    (runner UA default; no cookies — FINDINGS-641: every surface 200 on plain UA)

Transcript (abridged GET lines, complete for the checked bars):

    ── check 1: search pages (2)
    GET https://missav.live/en/search/milf?page=1 → 200; …div.thumbnail.group' matches: 12
    GET https://missav.live/en/search/milf?page=2 → 200; …div.thumbnail.group' matches: 12
    (no duplicate-card FAIL — p1∩p2 = 0, as FINDINGS-641)
    ── check 1a: homepage pages (6)
    GET …/dm539/en/new?page=1 → 200; matches: 12      GET …page=2 → 200; matches: 12
    GET …/dm169/en/weekly-hot…page=1 → 200; matches: 12  …page=2 → 200; matches: 12
    GET …/dm628/en/uncensored-leak?page=1 → 200; matches: 12  …page=2 → 200; matches: 12
    FAIL duplicate home cards …: href /en/htb-015 home0,home1 · href /en/spsf-069 home0,home1
    NOTE: repeated poster on home across different videos… (poster /htb-015/cover.jpg …)
    ── check 1b: quick search (1)
    GET https://missav.live/en/search/suggest?q=milf → 200; matches: 12   (no dupes)
    ── check 2: video pages (3 URLs)
    GET /en/mxgs-1450 → 200; 'video.player' matches: 1
    GET /en/milf-091  → 200; 'video.player' matches: 1
    GET /en/mimk-288  → 200; 'video.player' matches: 1
    (title/poster/plot extracted on all 3; exposure NOTEs — selectors omitted, recorded in
     FINDINGS-641 as site-dependent rows)
    ── check 3 (streams, --stream-url overrides)
    GET stream surrit 18ee1328… → 403 text/html – FAIL ×3 → recorded **Blocked**:
    standing runner-IP gate (FINDINGS-641/Appendix: Cloudflare challenge from runner IP on
    plain AND TLS tiers; SKILL.md Blocked rule; "verify stream playback in-app"). Stream
    paths remain distinct across videos (no shared-path FAIL).
    ── check 5: search ↔ load agreement
    NOTE: poster variant (https://fourhoi.com/milf-091/cover-t.jpg vs
    https://fourhoi.com/milf-091/cover-n.jpg) — same artwork
    (title agreed; no FAIL lines)

### Deliverable line (scope item 3) — CONFIRMED

Before #680: `FAIL poster mismatch for /en/milf-091: search='…/cover-t.jpg' load='…/cover-n.jpg'`
Now: **PASS with `NOTE: poster variant … — same artwork`.** The instrument no longer
false-FAILs the site's per-surface cover-art filenames.

### Dispositions of the two remaining FAIL lines (both pre-existing, pre-verdicted, out of S3 scope)

1. **check 1a home duplicates** — /dm539/en/new p1∩p2 (2 cards this run: htb-015, spsf-069;
   FINDINGS-641 recorded 5 at the same boundary). Same site-side stale-cache boundary skew
   FINDINGS-641 verdicted "the provider cannot fix the site's cache"; the provider dedupes
   in-app since v19 (`MissAVParse.filterSeen`, TDD-tested) — the instrument reads raw site
   pages, so the site condition re-appears by design. weekly-hot and uncensored-leak rows:
   disjoint ✓. Card identity drifted since #641 (different videos at the p1/p2 boundary) —
   same condition class, not new drift.
2. **check 3 surrit 403 ×3** — the standing runner-IP gate recorded in findings-641's
   riser block and MissAV/FINDINGS.md Appendix ("verify stream playback in-app,
   maintainer-only per repo policy"). Extraction schema proven by 3 distinct live UUIDs.

## 4. Instrument-only diff asserted (scope item 4)

`git status --porcelain` before commit: empty except this evidence file.
`git diff --stat`: one file, `FINDINGS-776.md`. No provider directory touched; no provider
version bump (MissAV `build.gradle.kts` untouched at `version = 19`); `verify.sh` and
`verify_selfcheck.sh` untouched by this story (S1/S2 already merged their changes).
