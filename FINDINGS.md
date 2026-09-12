# FINDINGS — issue #361 (P1-8: orphaned composite actions)

## Probe

- `.github/actions/{agent-run,agent-runtime,deliver-pr}/action.yml` all exist
  (60 / 65 / 42 lines).
- Grep across the repo for `actions/agent-run|actions/agent-runtime|actions/deliver-pr`
  in all `*.yml`/`*.yaml` files: **0 references**. Only remaining callers were the
  removed Builder/Task/Reviewer workflows (`AGENTS.md`: "The old pipeline's
  Builder/Reviewer/Triage/Monitor workflows have been removed").
- `docs/adr/0004-agents-never-push.md` already carries a historical-banner blockquote
  (added in review #347 follow-ups) stating the `deliver-pr` action "belonged to the
  removed pre-devloop pipeline; no workflow invokes it today."
- `CONTEXT.md` historical-machinery note (top of file) already covers `deliver-pr`
  and the Model chain as pre-devloop vocabulary.
- P0-7 model-config divergence: `agent-runtime/action.yml` writes
  `"$OPENCODE_API_KEY"` unexpanded into models.json (heredoc unquoted-free string but
  no envsubst in that action) — divergent from `devloop.yml`, which writes a quoted
  heredoc plus `envsubst` (P0-7 fix). Moot once the action is deleted.

## Decision (per the issue's "decide" fork)

**Delete all three actions.** Rationale:
- Nothing invokes them; keeping dead token-handling code (`deliver-pr`) is an audit
  liability, and the stale model-config heredoc is a drift trap against devloop.yml.
- Wiring the actionlint pre-flight into `devloop.yml` is redundant: CI `lint.yml`
  already runs actionlint (reviewdog) on push, and per AGENTS.md agents run
  `actionlint` locally before pushing `.github/**` changes.
- ADR-0004's banner needs only a small amendment to record that the actions were
  deleted, not just orphaned.

## Change

1. `git rm -r .github/actions/` (all three composite actions).
2. `docs/adr/0004-agents-never-push.md`: banner updated to note the action files were
   deleted (the decision record stays; only the dead code is gone).

## Verification

- Repo-wide grep: 0 remaining references to the three actions.
- `actionlint` clean on all workflow files (local pre-flight per AGENTS.md).
- No Kotlin/provider code touched; no need to bump provider versions or run
  `verify.sh` (this is CI plumbing only, not a provider change).

# FINDINGS — issue #360 (P0-15: vendored jars have no integrity record)

## Probe

- `build.gradle.kts:19-31` (buildscript) loads two committed binaries:
  - `classpath(files("gradlelibs/cs-plugin-facade.jar"))` — the vendored CloudStream
    Gradle plugin (jitpack -SNAPSHOT no longer resolves under Gradle 8.12).
  - `maven("$rootDir/vendor")` repo serving `com/github/recloudstream/gradle/gradle/-SNAPSHOT/`
    (`gradle--SNAPSHOT.jar` + `.pom`) to the buildscript classpath.
- Every provider build depends on these; no checksum/provenance anywhere in the repo
  beyond inline comments (repo-wide grep for sha256/integrity/checksum in *.kts/*.md: none,
  apart from an unrelated PoW comment in Film1k/FINDINGS.md).
- Measured SHA-256 (sha256sum, 2024 snapshot checkout):
  - `5358ef5bfe9ea8fad162fe9c4895e5706a92659c34a38bbd91457d902fadcb21`
    — `gradlelibs/cs-plugin-facade.jar`
  - `5358ef5bfe9ea8fad162fe9c4895e5706a92659c34a38bbd91457d902fadcb21`
    — `vendor/com/github/recloudstream/gradle/gradle/-SNAPSHOT/gradle--SNAPSHOT.jar`
  - `203e5d3055690ccc65ff1eb47d16f9d4f71984ea02f51b8509c217fe5f7c8fbd`
    — `vendor/com/github/recloudstream/gradle/gradle/-SNAPSHOT/gradle--SNAPSHOT.pom`
- Probe note: jar and facade jar are **byte-identical copies** (same hash) — the facade
  jar *is* the vendored plugin jar, duplicated at a stable path for `files(...)` classpath
  loading. Worth flagging to reviewers but not deduplicating here (issue scope is the
  integrity record, not restructuring the classpath).

## Decision (minimal fix)

- Add a committed, diffable integrity record `gradlelibs/INTEGRITY.txt` (all three files
  above, one `SHA256 <path> <hash>` per line, provenance comments inline).
- Add a root-Gradle `verifyVendoredJars` task that recomputes each hash and fails on
  mismatch/absence; every provider subproject's `make`/`test`/`check` task depends on it,
  so the invariant runs with the repo's existing commands (no CI change — per AGENTS.md,
  workflows only change when the issue spec names them).
- Amend AGENTS.md Commands section with one line documenting the invariant.

## Verification

- `./gradlew verifyVendoredJars` → exit 0 on the untouched tree.
- Tamper test ( appended byte to `gradlelibs/cs-plugin-facade.jar`, restored after):
  task fails with the expected/actual SHA-256 diff, exit 1 — red → green.
- `./gradlew EPorner:test` → exit 0 (provider test path runs the gate as a dependency).
- No CI workflow change (issue spec doesn't name one; review remains the gate).

# FINDINGS — issue #358 (P0-8: document shared/ splice coupling)

## Probe

- `build.gradle.kts` (root, allprojects block) splices `shared/` into every subproject:
  - main kotlin: `sourceSets.getByName("main").kotlin.srcDir(rootDir.resolve("shared/src/main/kotlin"))` (comment: "Host registry + shared adapters (ADR-0002) compile into every provider")
  - test kotlin: `sourceSets.getByName("test").kotlin.srcDir(rootDir.resolve("shared/src/test/kotlin"))`
  - test resources: `sourceSets.getByName("test").resources.srcDir(rootDir.resolve("shared/src/test/resources"))`
- `shared/` has no `build.gradle.kts` and is absent from `settings.gradle.kts` auto-include — it is not a Gradle subproject (matches AGENTS.md).
- `shared/` currently holds 14 Kotlin files across `src/main` and `src/test`.
- `build.yml` runs `./gradlew test make` at the root → `test`/`make` execute for every included subproject (24 directories with `build.gradle.kts`); shared sources/tests are compiled/run once per provider.
- Existing docs mention the splice only in passing: AGENTS.md ("providers get it via `sourceSets` splicing", "shared/src/test/kotlin runs with every provider's test task") and ADR-0005. The **failure modes** are documented nowhere.

## Consequences (the uncovered facts to record)

1. A syntax error in any one shared file fails **every** provider's `gradlew <Provider>:test` / `:make` (and CI's root `gradlew test make`) — there is no provider-scoped blast radius for shared/ edits.
2. Shared unit tests execute once per provider test task (N× CI wall time; 24 subprojects today).

## Decision (per the issue's "decide" fork)

**Document in ADR-0002** (it already owns "shared/ compiles into every provider" via the HostRegistry context) plus a one-line pointer in AGENTS.md. No code change — the coupling is by design (single-seam host coverage, ADR-0002; TDD splice, ADR-0005).

## Change

- `docs/adr/0002-loadextractor-is-the-only-stream-dispatch-seam.md`: appended "Shared splice coupling" section recording the mechanics and both failure modes.
- `AGENTS.md`: pointer line on the shared/ bullet to ADR-0002's coupling section.

## Verification

- Docs-only change: no Kotlin/provider code touched, no version bumps, `verify.sh` (live-site provider check) not applicable. Gradle wiring unchanged; `gradlew` still answers (config sanity only).
