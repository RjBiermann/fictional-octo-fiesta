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

# FINDINGS — issue #388: CodeQL Kotlin extractor unsupported: root build.gradle.kts pins kotlin-gradle-plugin 2.4.20

## Probe (reality check)

Question the tracking issue asks: has CodeQL added support for Kotlin 2.4.20, so the
sed-downgrade block in `.github/workflows/codeql.yml` (Build for scan step) can be deleted?

**Answer: NO — reversal condition not met as of 2026-09-12.** Evidence:

- **Latest bundle is 2.27.0** (codeql-action release `codeql-bundle-v2.27.0`, 2026-09-09;
  action changelog 4.38.0 bumped the default to it). No newer bundle exists; changelog
  UNRELEASED section has no bundle bump.
- **CodeQL CLI changelog** (github/codeql-cli-binaries CHANGELOG.md, releases 2.25.1 →
  2.27.0) has **zero entries** mentioning Kotlin version-support increases; only Kotlin
  mentions in the whole file are the old 1.6/1.7 deprecation (removed in 2.24.1) and
  beta-era notes.
- **Decisive: decompiled the actual agent jar from bundle 2.27.0.** Downloaded
  `codeql-bundle-linux64.tar.zst` (v2.27.0), extracted
  `codeql/java/tools/codeql-java-agent.jar`, `javap -c` on
  `com.semmle.extractor.java.interceptors.KotlinInterceptor`:
  - `defaultAcceptableVersionLimitStr = "2.4.20"`, `minimumAcceptableVersionStr = "1.8.0"`.
  - Check semantics (bytecode 112–118 of `getExtractorJarPath`): throw
    `KotlinVersionTooRecentError` when `executingVersion.compareTo(limit) >= 0` — the
    limit is **exclusive** ("CodeQL currently supports versions below 2.4.20"). So
    **Kotlin 2.4.20 itself is rejected even by the newest bundle**; 2.4.19 and below pass.
  - Bundled extractor plugins: `codeql/java/tools/` ships
    `codeql-extractor-kotlin-{embeddable,standalone}-*.jar` only up to **2.4.0**. The
    agent selects the highest plugin jar ≤ the executing version (bytecode 545–597),
    which is why 2.4.10 → 2.4.0 jar works.
  - Escape hatch found but deliberately **not** used: env
    `CODEQL_EXTRACTOR_KOTLIN_OVERRIDE_MAXIMUM_VERSION_LIMIT` overrides the limit —
    rejected because it bypasses a vendor guard (the 2.4.0 plugin may mis-extract
    2.4.20 sources → silently degraded analysis), and the issue's reversal condition
    is "once CodeQL supports 2.4.20", not "force it through".
- Cross-check: the only public report of this error class (bitfireAT/icsx5#777) shows
  the same message shape ("Kotlin version 2.3.0 is too recent. CodeQL currently supports
  versions below 2.2.30"), confirming "below X" = X is the first unsupported version.

## Fix (minimal)

No logic change — the downgrade block is still required. One comment refreshed:

- `.github/workflows/codeql.yml`: comment cited stale evidence (CodeQL 2.26.4) and no
  exact threshold. Rewritten with the current facts (bundle 2.27.0 / action v4.38.0,
  limit still exactly 2.4.20 exclusive, newest plugin 2.4.0) plus a concrete re-check
  recipe (strings the new bundle's codeql-java-agent.jar for
  `defaultAcceptableVersionLimitStr`) so the next probe is one command. `sed` +
  `./gradlew assemble` untouched; `build.gradle.kts` untouched (real builds keep 2.4.20).

## Verification

- `actionlint` v1.7.7 (freshly installed; repo runner had no binary) on
  `.github/workflows/codeql.yml`: **PASS** (AGENTS.md mandates actionlint for
  `.github/**` changes — the lint.yml check runs only post-push, so this is the gate).
- YAML parse of the workflow: **PASS**.
- sed dry-run against root `build.gradle.kts`: line 22
  `kotlin-gradle-plugin:2.4.20` → `kotlin-gradle-plugin:2.4.10` exactly as the
  workflow expects (regex still matches the pinned line).
- verify-provider skill: **N/A — no provider code touched**; it validates live-site
  selectors/streams, and this change is CI-workflow-only.
- Full in-CI verification of the scan run is maintainer-only: next scheduled
  `codeql.yml` run (push to main / weekly cron) must keep the `java-kotlin` leg green
  with the unmodified `build.gradle.kts` build — exactly the reversal check in #388.
