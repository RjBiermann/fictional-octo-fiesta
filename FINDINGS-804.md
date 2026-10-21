# FINDINGS-804 — CloudStream API review: baseline (S0 of #804)

Run: 2026-10-21 (`ai-fix`, implement of the finalized S0 story — parent #804, shared baseline
for the S1–S26 per-provider audit chain). **Deliverables:** live bootstrap + API-diff verdict on
the `pre-release` classes.jar, the API-conformance scan instrument the per-provider sub-issues
consume, and the first scan run against `shared/`.
**No provider or shared/ code changed in this story.**

## 1. Bootstrap + API diff — verdict: **zero delta**

`./gradlew bootstrapCloudstream` fetched the current `pre-release` release asset
(`https://github.com/recloudstream/cloudstream/releases/download/pre-release/classes.jar`,
run 2026-10-21) and installed it into mavenLocal.

| artifact | sha256 |
|---|---|
| fresh bootstrap jar (2026-10-21) | `16278fc26dc9b6205b43d20cd3a0bbf5a51eb39c614a395354f484343115ccda` |
| pinned jar (FINDINGS-767 bytecode inspection, 2026-10-20) | `16278fc26dc9b6205b43d20cd3a0bbf5a51eb39c614a395354f484343115ccda` |

**The fresh jar is bit-for-bit identical to the FINDINGS-767 pinned artifact.** The
`pre-release` tag has not moved since the S1 baseline inspection (2026-10-20). Therefore:

- **API change: none.** No bytecode delta to diff (a moving-tag overwrite would have produced a
  distinct sha; it did not).
- **Affected call sites: none.** The FINDINGS-767 ground truth carries over 1:1 — including the
  `getQualityFromName` suffix-label trap the #759 chain already ships a decided mapping for
  (`PorntrexParse.qualityOf`).

Spot-verification on the fresh jar (`javap`, same class layout as FINDINGS-767):

- `com.lagradost.cloudstream3.utils.ExtractorLink` — `private int quality` + `getQuality()/setQuality(int)` (plain Int, no enum, no null) ✔
- `com.lagradost.cloudstream3.utils.Qualities` — enum: `Unknown`, `P144`…`P2160` ✔
- `com.lagradost.cloudstream3.utils.ExtractorApiKt.getQualityFromName(String): int` ✔
- `MainAPI` async surface: `getMainPage(int, MainPageRequest, Continuation<HomePageResponse>)`,
  `search(..., Continuation<SearchResponseList>)`, `load(..., Continuation<LoadResponse>)`,
  `loadLinks(String, boolean, C1<SubtitleFile>, C1<ExtractorLink>, Continuation<Boolean>)` ✔
- `LoadResponse.Companion` completeness DSL: `addActors`/`addActorsRole`/`addActorsOnly`,
  `addDuration`, `addScore`, `addTrailer` (+ plain `posterUrl`/`recommendations` setters) ✔
- DSL builders live in `ExtractorApiKt` (`newExtractorLink`) and `MainAPIKt`
  (`newMovieLoadResponse`, etc.) ✔

## 2. The scan instrument — `audits/scan-api-conformance.sh`

The grep/AST pass the S1–S26 per-provider sub-issues consume. Committed at
`audits/scan-api-conformance.sh` (precedent: `audits/sweep524.sh`).

### Usage contract

```bash
audits/scan-api-conformance.sh --signatures      # tier 2: jar-side reference surface (javap, mavenLocal jar)
audits/scan-api-conformance.sh <ProviderDir>     # tier 1: provider-side grep pass (repeatable args; also: `shared`)
```

Stable output line format for quoting into audit comments:
`[PASS|INFO|FINDING|WARN] <check-id> <file>:<line>: <detail>` —
**PASS** = mechanical contract holds; **INFO** = inventory item for the auditor's verdict;
**FINDING** = mechanically-detected breach candidate (quote file:line as evidence);
**WARN** = risky pattern, verdict is the auditor's.

### Check tiers (mapped to the #812-style sub-issue checklist)

| id | tier | checklist item | what it checks |
|---|---|---|---|
| G1 | grep | build.gradle.kts metadata | `version`/`language`/`status`/`description`/`iconUrl` present; `tvTypes = listOf("NSFW")` exact |
| R1 | grep | registration | one plugin class; `registerMainAPI(...)`; `registerHostExtractors()`; flags `registerExtractorAPI` / local `: ExtractorApi()` subclasses outside `shared/` (ADR-0002) |
| M1 | grep | MainAPI contract | reports which of `search`/`load`/`loadLinks`/`getMainPage` are overridden (absence = framework default applies, INFO not breach) |
| H1 | grep | homepage data | flags raw `HomePageResponse(...)`; DSL path (`newHomePageResponse`/`HomePageList`) = PASS |
| C1 | grep | constructors | flags raw `ExtractorLink(...)` (repo norm is `newExtractorLink { }`); raw `Episode(...)` = INFO (raw is common in NSFW providers) |
| L1 | grep | LoadResponse completeness | uses/not-uses inventory for `addPoster`/`addDuration`/`addActors`/`addScore`/`addTrailer`/`recommendations` — the not-used side is the auditor's judgment ("does the site provably expose it?") |
| Q1 | grep | quality mapping (FINDINGS-767 standard) | PASS: `Qualities.P<res>.value` or `qualityOf` decided-table idiom; INFO: `getQualityFromName(` label-source trace points; WARN: raw ints outside the resolution set (400 ⇒ empty tag) and string-expression quality assigns |
| — | javap | (AST tier) | `--signatures` mode dumps the reference surface from the mavenLocal jar — the conformance answer for signature questions |

### Calibration fixes applied during this baseline run

Three grep-pattern false positives were found and fixed **while calibrating** on
MissAV/Porntrex/EPorner (no provider behavior changed — instrument-only fixes):

1. L1 matched import lines (`import ...Companion.addActors`) — now excludes `^\d+:\s*import` and
   matches call-form so unqualified extension calls inside `load()` (`addActors(actresses)`) count.
2. L1 `recommendations` was dot-anchored — now catches `this.recommendations = ...`.
3. Q1 flagged the **already-fixed** #759 idiom (`this.quality = PorntrexParse.qualityOf(label, url)`)
   as a WARN — the `qualityOf` decided-table idiom is now a distinct PASS; and a source set with
   **no** quality assignment at all gets an explicit `NO quality set anywhere` INFO (app renders
   "Auto"; verdict is the auditor's).

### Fleet smoke (instrument stability, not verdicts)

All 26 providers + `shared` scan with rc=0; zero FINDINGS, zero WARNs fleet-wide. S1–S26
per-provider runs are expected to produce INFO-driven verdicts, not mechanical failures.

## 3. shared/ scan (spec step 4 — splice coupling, ADR-0002)

Full scan:

```text
===== SCAN shared (src=shared/src/main/kotlin) =====
[INFO]    G1 shared:0: no build.gradle.kts (shared/ splice source — G1 n/a)
[INFO]    R1 shared/src/main/kotlin:0: no plugin class here (shared/ splice source or registered elsewhere)
[INFO]    M1 shared/src/main/kotlin:0: no MainAPI subclass (shared/ splice source)
[INFO]    H1 shared/src/main/kotlin:0: no homepage construction in this source set
[PASS]    Q1 shared/src/main/kotlin/com/kraptor/CloudWish.kt:136: enum-Int quality (Qualities.P1080.value)
[PASS]    Q1 shared/src/main/kotlin/com/kraptor/CloudWish.kt:137: enum-Int quality (Qualities.P720.value)
[PASS]    Q1 shared/src/main/kotlin/com/kraptor/Extractorlar.kt:915: enum-Int quality (Qualities.P1080.value)
[INFO]    Q1 shared/src/main/kotlin/com/kraptor/HostAdapters.kt:62: getQualityFromName call — VERIFY label source ...
[INFO]    Q1 shared/src/main/kotlin/com/kraptor/HostAdapters.kt:306: getQualityFromName call — VERIFY label source ...
[INFO]    Q1 shared/src/main/kotlin/com/kraptor/Extractorlar.kt:543: getQualityFromName call — VERIFY label source ...
[INFO]    Q1 shared/src/main/kotlin/com/kraptor/Extractorlar.kt:619: getQualityFromName call — VERIFY label source ...
[INFO]    Q1 shared/src/main/kotlin/com/kraptor/Extractorlar.kt:959: getQualityFromName call — VERIFY label source ...
```

G1/R1/M1/H1 are structurally n/a for `shared/` (no plugin, no MainAPI — providers own those); the
shared/ exposure is Q1 and the L1-equivalent link fields. **Mechanical verdict: clean.**

### getQualityFromName call-site trace (the only shared/ risk surface)

| site | adapter (owner class) | label source | suffix-label risk (`'720p HD'` ⇒ 400) |
|---|---|---|---|
| `HostAdapters.kt:62` | `Lanjia`-style adapter | video filename via `substringAfterLast("/").substringBefore(".")` | low — filename-derived; junk degrades to 400 gracefully (empty tag) |
| `HostAdapters.kt:306` | Abyss-family adapter | JSON `s.quality` Int → `"\${q}p"` | none — whole-number+`p` form |
| `Extractorlar.kt:543` | **Filemoon** (`emitFromArray`) | site JSON `label` field | **live-data risk** — site-controlled label |
| `Extractorlar.kt:619` | VidHidePro family | literal `""` | none — deliberate Unknown fallback (renders empty tag) |
| `Extractorlar.kt:959` | **VidNest** | JS label regex (`label\s*:\s*["']([^"']+)["']`) | **live-data risk** — site-controlled label |

### Disposition (rules-compliant)

- All five call sites route through the API-sanctioned `getQualityFromName` — **API-conformant as
  written**; the suffix-label degradation is upstream API semantics, not a conformance breach.
- The two **live-data risk** sites (Filemoon `emitFromArray`, VidNest) become watch items for the
  per-provider stories: if a provider's audit confirms a suffix-bearing label live
  (`'720p HD'`/`'1080p FHD'`/`'2160p 4K'` patterns), the *shared-root-cause* fix is a
  suffix-stripping normalization in `shared/` (e.g. pre-strip `\s*[hf]{0,2}d?\b`-style suffix
  tokens before `getQualityFromName`, mirroring the `PorntrexParse.qualityOf` decided-table
  approach), affecting every provider via the whole-table registry (ADR-0002 splice: all 26 are
  potentially affected; live exposure is what the per-provider audits determine).
- No shared/ code change in S0 — baseline only; scope discipline kept (`ponytail`).

## 4. Handoff to S1–S26

Per-provider run order: `audits/scan-api-conformance.sh <Provider>` → judge the INFO lines
against the live site where they matter (L1 completeness, Q1 label sources) → verdict comment on
the sub-issue (`clean` or finding-with-evidence file:line + jar symbol). The jar ground truth is
`--signatures` mode; the pinned sha is this file's section 1.
