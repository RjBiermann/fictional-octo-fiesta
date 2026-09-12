# FINDINGS — issue #365 (review #347 P1-4): EPorner.kt stray brace / indentation artifact at class end

## Probe (reality check)

- **Confirmed** at `EPorner/src/main/kotlin/com/byayzen/EPorner.kt:219-222` (pre-fix):
  after `loadLinks`'s real close (`    }` at line 220), a second `    }` at the same
  4-space indent (line 221) closed the `EPorner` class, immediately followed by the
  `@CloudstreamPlugin` annotation with no blank line. Exactly the merge-artifact shape
  the review describes: brace count is correct (compiles, CI green), the *indentation*
  is wrong and the annotation glues to the class end.
- Artifact predates the current review: present in the #293-era Builder commit
  (`ef4d550`), so it has been latent through several green builds — cosmetic only,
  zero behavior delta.
- No other stray-brace artifacts in the file (brace balance checked over the whole
  file); the rest of the class end (plugin class at the bottom of `<Provider>.kt` per
  AGENTS.md) is the required shape.

## Fix (minimal)

One file, three lines of whitespace, plus the version bump the issue asks for:

- `EPorner/src/main/kotlin/com/byayzen/EPorner.kt`: dedent the class-closing `}` to
  column 0 and add the blank line before `@CloudstreamPlugin`:
  `    }\n    }\n@…` → `    }\n}\n\n@…`. No code tokens touched.
- `EPorner/build.gradle.kts`: `version = 14` → `15`.

## Verification

- **Unit tests (TDD bar)**: `EPorner:test` — 6/6 `com.byayzen.ParseTest` green plus the
  shared suites it pulls in (SearchCard 3, JsonLdParse 11, PackedJs 4, BysePow 3,
  HlsFreeParse 2, AbyssParse 4, DistinctBar 3 — 0 failures, 0 errors).
- **Build**: `EPorner:make` BUILD SUCCESSFUL → `EPorner/build/EPorner.cs3`.
- **Live-site verification** (verify-provider skill; selectors/URLs from
  EPorner/FINDINGS, Googlebot UA per its recorded age-gate workaround). Site reachable
  from this runner; stream chain reproduced end-to-end exactly as the provider does it:
  embed-page hash regex → md5 8-chunk base36 → `/xhr/video/{id}?hash=…` → 200 JSON with
  real `labelShort`/`src` mp4s (and `srcFallback` m3u8 present on 3 of 6 samples).
  - Search: `/search/milf/` 200 (100 cards), `/search/milf/2/` 200 (65 cards) via
    verify.sh; quick-search `/suggest/milf/video/` 200, 21 `li.qsliac`.
  - Video pages: 6 varied samples (2 from search page 1 for the agreement check + 4
    from earlier audits) all 200 with `video#EPvideo` present.
  - Streams: 5 distinct CDN mp4 URLs across 4 CDN nodes (fr/nl/ca/ca) → **206
    video/mp4**; one `srcFallback` master.m3u8 → **200 application/vnd.apple.mpegurl**.
    Provider's `labelShort/src` regex matches the live JSON verbatim (720p/1080p/…).
  - Tags (`li.vit-category`), duration (`span.vid-length`): present on all 6 pages.
    Check 6 LoadResponse completeness: all 6 fields have assignments in the Kotlin.
  - Related cards, real-DOM (stdlib HTMLParser, jsoup-equivalent) since verify.sh's
    regex DOM truncates the nested single-line card markup at the first `</div>`:
    7/5/9/10/9/10 cards, **0 empty titles, 0 within-page duplicate titles, 0
    self-references** on every sampled page. One cross-page overlap is the same
    recommended video (`/video-FwPkcPdhi4D/…`) legitimately listed on two different
    videos' related rails — not a defect.
- **Attributed residuals (none from this change; all pre-recorded or script/site
  ceilings)**:
  - `duplicate search cards` / `duplicate home cards` (search0↔search1, home0↔home1
    boundary overlaps): the site drift recorded in EPorner/FINDINGS (#293/#294) as
    covered by the Correctness/Drift issue — same class this audit, not repeated here.
  - `duplicate recommendations`: verify.sh's regex DOM truncates `div.mb` card inner at
    the first `</div>` (quality chip), so default card titles collapse to "1080p" etc.;
    the provider's own `searchCard(p.mbtit a)` drops these. Real-DOM re-check above is
    the actual bar and is clean. Same ceiling documented in #294-era FINDINGS.
  - `actors present on 5/6`: ground truth, not drift — `video-11PHqoqftMv` is the
    long-recorded no-actor negative case (EPorner/FINDINGS #181/#294); the provider
    no-ops gracefully (JSON-LD empty, no "Starring:" clause).
  - check 5 title mismatch on `video-R7ZATY8jOpO`: script extracts search-card title
    as '' from the single-line nested markup (ceiling documented in #293-era FINDINGS;
    live card title "Stepsons - Dee Williams" present via grep) and the load h1 text
    carries the site's embedded duration/quality spans. Pre-existing behavior, out of
    scope for a whitespace-only change; noting for the record.

## Verdict: OK

Cosmetic artifact removed (class end now `    }` / `}` / blank line / annotation),
tests green, build green, live site re-proven end-to-end on 6 videos including the
search↔load pair. Version 14 → 15.

---

# FINDINGS — issue #362 (review #347 P1-1): MissAV language = "jp" → should be "ja"

## Probe (reality check, this run)

`grep -n 'language\|version' MissAV/build.gradle.kts Javseen/build.gradle.kts`:

- `MissAV/build.gradle.kts:6` → `language    = "en"`  ← **discrepancy with the issue text**:
  the issue claims MissAV sets `"jp"`, but it actually sets `"en"`. Either way it is wrong —
  MissAV is a Japanese AV site (its own description: "Best Japan AV porn site"), and the
  issue's intent is ISO 639-1 `ja`. `"en"` excludes it from Japanese-language listings
  just the same as a bad code would.
- `Javseen/build.gradle.kts:6` → `language    = "jp"`  ← confirmed exactly as the issue says.
  `"jp"` is not an ISO 639-1 code; the correct code for Japanese is `ja`.
- Both providers were at `version = 16` before this change.

## Fix (minimal, metadata only)

- `MissAV/build.gradle.kts`: `language = "en"` → `"ja"`, `version` 16 → 17.
- `Javseen/build.gradle.kts`: `language = "jp"` → `"ja"`, `version` 16 → 17.

No Kotlin, extractor, or `shared/` changes — parsing/streams are unaffected, so no new
unit-test surface (TDD bar does not apply to build metadata).

## Verification

- `./gradlew MissAV:make Javseen:make` → both BUILD SUCCESSFUL
  (`MissAV/build/MissAV.cs3`, `Javseen/build/Javseen.cs3`).
- `./gradlew MissAV:test Javseen:test` → green (0 failures).
- Live-site verify-provider run not re-executed: this is a `build.gradle.kts` metadata
  change only; selectors, search/home/stream surfaces recorded in `MissAV/FINDINGS.md`
  and `Javseen/FINDINGS.md` are untouched, and their most recent verify evidence
  (#274/#302 and #145 re-probe) remains valid.

## Verdict: OK — left committed for human review
