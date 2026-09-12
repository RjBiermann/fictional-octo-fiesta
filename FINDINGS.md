# FINDINGS — issue #367 (review #347 P1-6): CodeQL Kotlin-downgrade sed block has no tracking issue; build.yml lists dead master branch

## Probe (reality check)

- **Sed block confirmed untracked**: `.github/workflows/codeql.yml:55-62` ("Build for scan")
  runs `sed -i 's/kotlin-gradle-plugin:2\.4\.20/kotlin-gradle-plugin:2.4.10/' build.gradle.kts`
  before `./gradlew assemble`, with an inline comment explaining the KotlinVersionTooRecentError
  workaround (CodeQL 2.26.4 vs pinned Kotlin 2.4.20) — but no tracking-issue reference. The pin
  lives at `build.gradle.kts:22`
  (`classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")`).
- **No tracking issue existed**: `gh issue list --state all --search "CodeQL"` returned only
  this issue (#367) and the review parent (#347). Created **#388** as the tracking issue,
  stating the reversal condition (CodeQL supports Kotlin 2.4.20 → delete the downgrade block)
  and where to check (codeql-action releases / CodeQL changelog).
- **Dead `master` branch confirmed**: `git ls-remote --heads origin` has only
  `refs/heads/main` (f7e4336…); no `master`. Yet `.github/workflows/build.yml:13-14` lists
  `- master` (with a stale "# choose your default branch" comment) under `on.push.branches`.
  Harmless (the branch just never matches — `gh run list --workflow=codeql.yml` confirms
  main pushes do trigger workflows; build.yml runs on main pushes too), pure hygiene.
- **Verification bar**: AGENTS.md requires `actionlint` before pushing `.github/**` changes;
  it was not on PATH in this environment, so it was installed via
  `go install github.com/rhysd/actionlint/cmd/actionlint@latest` (Go 1.24.13 present).
  The verify-provider skill's live-site script (`.pi/skills/verify-provider/scripts/verify.sh`)
  is for CloudStream providers and does not apply to a workflow-files change; the applicable
  mechanical checks are actionlint plus a dry-run of the sed against the real pin.

## Fix (minimal)

Two workflow files, comments/branch-list only — no behavioral change to either workflow:

1. `.github/workflows/codeql.yml` — inline comment now ends "Tracked in #388 — revert this
   whole block once CodeQL supports the pinned version."
2. `.github/workflows/build.yml` — `on.push.branches` reduced to `- main`; the stale
   "# choose your default branch" comment removed with it.

## Verification

- `actionlint` (exit 0) across all workflow files after the edits.
- Sed dry-run (`sed -n 's/…/p' build.gradle.kts`) prints line 22 with `2.4.10` — the
  CodeQL-downgrade pattern still matches the real pin, so #388's reversal instructions
  stay accurate.
