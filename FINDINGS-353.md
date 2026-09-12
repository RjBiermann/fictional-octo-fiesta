# FINDINGS-353 — HostRegistry ordering contract (P0-10 / P0-11, review #347)

Issue #353 asks for ADR-0005-shaped unit tests pinning: (a) unique mainUrls, (b) the
StreamTape supersession invariant, (c) intentional naming collisions.

## Probe evidence

### 1. Framework matching semantics confirmed from the shipped jar (not assumed)

`loadExtractor` was disassembled from the vendored/resolved framework jar
(`~/.gradle/caches/cloudstream/cloudstream/cloudstream.jar`,
`com/lagradost/cloudstream3/utils/ExtractorApiKt.class`):

- Iteration is **last → first**: `getLastIndex` down to 0 over the static
  `extractorApis` list; the FIRST adapter whose normalized `mainUrl` is a
  `startsWith` prefix of the (unshortened, schema-stripped) target URL wins and
  returns.
- `BasePlugin.registerExtractorAPI` (same jar) is `extractorApis.add(element)` —
  registration order = list order.

⇒ "last-registered-wins" is real, and the ordering contract is: **among rows whose
mainUrl is a prefix of the same target URL, the later row wins; an earlier row with
an identical mainUrl is dead code.**

### 2. The issue's invariant (b) as literally stated does NOT hold of the current table

`StreamTAPE()` (mainUrl `https://streamtape.com`, line ~158) sits **between** the
streamtapeMirror rows: `stape.fun` before it, `shavetape.cash` and
`watchadsontape.com` after it. So "StreamTAPE() after the streamtapeMirror rows" is
already false today — and it is also *not* the invariant dispatch depends on
(mirror URLs are not prefixes of `streamtape.com`, so their relative order is
dispatch-irrelevant). The invariant the comment at HostRegistry.kt:39-42 actually
states is:

> Do not re-register the framework StreamTape alongside it: last-registered wins
> and the row would be dead.

i.e. the real supersession contract is:

- the **framework** `StreamTape` / `StreamTapeNet` / `StreamTapeXyz` classes are
  never registered raw (their `getUrl` handling must not serve this repo's
  streamtape traffic), and
- the row answering `https://streamtape.com` is the **custom** `com.kraptor.StreamTAPE`
  (subclass instances of it are fine — every `streamtapeMirror(...)` row is one).

Confirmed today: framework mainUrls are `https://streamtape.com` /
`…streamtape.net` / `…streamtape.xyz` (javap on the jar); the custom StreamTAPE is
the only row with mainUrl `https://streamtape.com`, and every mirror row is an
anonymous subclass of `com.kraptor.StreamTAPE` (Extractorlar.kt factory).

### 3. Table data census (regex over the registration literal, 2026-09 current HEAD 6a51069)

- 100 factory-call rows + 29 bare-constructor rows ≈ 129 rows.
- **Zero exact duplicate mainUrls** and **zero prefix-shadowed mainUrls** today
  (python scan; the unit test now asserts both permanently).
- Name collisions (user-facing `name`) across all rows incl. class defaults:
  Byse 16, dood 7 + DoodStream 1, Filemoon family, Player4Me family, VidHidePro
  family, EarnVids **5** (REVIEW.md said 6 — against an older revision; current
  table has smoothpre/dhtpre/peytonepre/movearnpre/dintezuvio), Streamwish **2**
  (base + filesimMirror swhoi; REVIEW.md said 3, same staleness), LuluStream 2 +
  Lulustream 6 (factory default is `Lulustream`, LULUBASE default `LuluStream` —
  case-sensitive distinct names), Voe 4, Streamtape 7, MixDrop 3, Vidstack 2,
  HlsFree 2, Javhdz… all counted at runtime by the new test.

### 4. Unit-test classpath reality (decides the build change)

`shared/src/test/kotlin` compiles today with only junit on `testImplementation`;
framework classes (`com.lagradost.cloudstream3.extractors.*`) are on the
`cloudstream` configuration only. To assert "the row is the *custom* StreamTAPE,
not the framework StreamTape", the framework jar must be on the test classpath.

- Framework extractor constructors are pure field assignments (javap checked:
  Voe, VidStack, MixDropAg, EmturbovidExtractor, Filesim, MixDrop, StreamTape) —
  safe to instantiate on the JVM; no android.util / app access at construction.
  The same is true of every bare-class row in shared/ (field-init only).
- Minimal build change in the root `build.gradle.kts` subprojects block:
  `testImplementation(files(configurations.getByName("cloudstream")))` (the
  framework jar) plus the two kotlinx-serialization jars (core + json 1.6.3) that
  satisfy the `@Serializable` companions referenced by framework extractors
  (Voe's parsing path). No provider build.gradle.kts touched.
  **Environment note (UGH-3-class runner quirk, resolved in-session):** this
  runner's `~/.gradle/caches` had no `cloudstream3:pre-release` artifact and
  jitpack 404s that coordinate; the vendored cs gradle plugin resolves that
  dependency by downloading `classes.jar` from the GitHub release asset
  `https://github.com/recloudstream/cloudstream/releases/download/pre-release/`
  (disassembled `CloudstreamConfigurationProvider.provide` / `ApkInfo`), *not*
  from a Maven repo. During the probe some transform/cache state was
  invalidated, so to restore a resolvable baseline the exact same pre-release
  `classes.jar` (sha256 e76bc931…bb58ea5) was fetched from that official URL and
  installed under `~/.m2` (plus the two serialization jars from Maven Central);
  `mavenLocal()` was appended LAST in `allprojects.repositories` so it can only
  backfill, never shadow. The plugin still fetches its own jar when a cache
  exists; this is runner repair only, not a dependency change 🔁 upstream —
  on a normally warmed runner `mavenLocal()` and the extra jars are inert.

### 5. Consequence for the fix shape

- ADR-0005 seam = the table itself (pure data). `registerHostExtractors` keeps its
  signature; the table moves behind an `internal fun sharedHostRegistry()` seam
  (behavior-identical refactor — same list, same registration call, all 24
  providers call `registerHostExtractors()` with no `first` arg, verified by grep).
- Tests instantiate the rows and assert: unique mainUrls; no mainUrl prefix-shadows
  another (the "reorder/mirror-kill" failure mode made impossible to miss);
  framework StreamTape family never registered + streamtape.com answered by the
  custom adapter (the *real* supersession invariant, per probe §2); name
  collisions exactly equal to a documented intentional allowlist (P0-11 made
  explicit — REVIEW.md's counts were stale, reality is pinned instead).
- Red→green: the pinning test for name collisions was run against reality before
  its allowlist was filled in (its first run fails while reporting the actual
  counts); the mutation check (revert-to-broken reorder) is recorded below.
- No provider version bumps: the plugin bytecode produced per provider is
  behavior-identical (same rows, same registration call) and no user-visible
  behavior changes; bumping 24 providers would ship 24 empty updates. Test-only +
  behavior-identical-refactor diff, like the issue-#360 root-build change.

## Gate

- `./gradlew HQPorner:test` (runs shared tests incl. HostRegistryTest) — **BUILD
  SUCCESSFUL, 4 tests / 0 failures / 0 errors** (`HostRegistryTest` plus the
  pre-existing shared tests). Output tail in PR body.
- Mutation check (proves the tests bite; each mutation committed temporary,
  suite run, file restored, re-run green):
  1. duplicate a `dood("https://vide0.net")` row → `every registered mainUrl is
     unique` FAILS ("expected:<130> but was:<129>") + collision-count test FAILS.
  2. replace `StreamTAPE()` with framework `StreamTape()` → `custom StreamTAPE
     supersedes…` FAILS ("framework StreamTape rows registered raw …
     [https://streamtape.com]") + collision-count test FAILS.
  3. rename one `lulu(…)` mirror to `"LulustreamX"` → `name collisions are
     exactly the intentional allowlist` FAILS (documented=… actual=…).
  Red→green for the allowlist itself: before reconciliation the collision test
  failed reporting `{Lulustream=6, HlsFree=2}` (and DoodStream 9 ≠ REVIEW.md's
  guess) — counts now match runtime reality exactly.
- Census correction vs the probe's static regex: runtime counts include the
  bare-class rows the regex missed — DoodStream is **9** (DoodStream class at
  myvidplay.com + 8 dood defaults), MixDrop **3** (Ag + .my + .is mirror),
  HlsFree **2**, and the lowercase `dood/lulu/player4me` pseudo-entries were a
  probe artifact (rows use the `$nm` factory param; correct names are
  `DoodStream/Lulustream/Player4Me`). Allowlist comment updated accordingly.

## What the gate cannot see (reviewer should know)

- The tests pin registration *data*, not runtime dispatch; live `loadExtractor`
  behavior remains owned by pipeline Verification per ADR-0002/0005.
- `filesimMirror("https://swhoi.com", "Streamwish")` resolving via the Filesim
  code path under the Streamwish name (P0-11's diagnosability tax) remains as-is —
  the issue says intentional; the allowlist documents it.
- Supersession note: because the shared table deliberately **interleaves**
  streamtape mirror-hosts (net/xyz/turboplayers) around `StreamTAPE()`, the
  "framework never registered" invariant is asserted per-FUNCTIONAL-HOST: any
  row answering a framework streamtape host must be inside the custom
  `StreamTAPE` family (it is, for all three — streamtape.com by `StreamTAPE`
  itself, .net (trailing slash) and .xyz by its mirror rows). Raw-URL-equality
  is NOT the correct check and would mis-fire on the trailing-slash row.
