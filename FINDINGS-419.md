# FINDINGS-419 — build failed (CI: Setup Android SDK)

Issue: https://github.com/RjBiermann/fictional-octo-fiesta/issues/419
Run: https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/35045695055
Branch: `devloop/issue-419`, head at `b36b851` (same commit as the failed run).

## Probe

### 1. Which step failed?

`gh run view 35045695055` — job `build`, failing step:

```
{"conclusion":"failure","name":"Setup Android SDK"}
```

Gradle (bootstrap / test / make / makePluginsJson) **never ran**. The failure is in
`android-actions/setup-android@v2` (`.github/workflows/build.yml:50`), not in any
provider or shared code.

### 2. The actual error

From the run log:

```
[command]/usr/local/lib/android/sdk/cmdline-tools/7.0/bin/sdkmanager tools
Warning: Failed to find package 'tools'
Error: The process '/usr/local/lib/android/sdk/cmdline-tools/7.0/bin/sdkmanager' failed with exit code 1
```

`sdkmanager` can no longer resolve the package id `tools`. Google removed the obsolete
`tools` package (superseded by `cmdline-tools`) from the Android SDK repository, and the
runner image now ships `cmdline-tools 7.0` which enforces that.

### 3. Not a repo regression

- The last successful build, run 34741395291 (2026-09-13, commit `ee3e8a9`), used the **same
  `build.yml`** (no workflow commits between `ee3e8a9` and `b36b851`) and succeeded — its log
  shows `sdkmanager` resolving via `cmdline-tools/latest`.
- Between then and now the **runner image / SDK repository changed underneath us**
  (`ubuntu-latest` is mutable). The repo's own diff did not cause this.

### 4. Why not a plain version bump

Inspected `action.yml` of the upstream action at each ref:

- `v2` (pinned): has **no inputs at all** — its `dist/index.js` unconditionally runs
  `sdkmanager tools platform-tools`. Permanently broken against today's SDK repository; no
  input can fix it.
- `v3` (README-recommended, latest 3.2.2): default `packages: 'tools platform-tools'` —
  still installs the removed `tools` package by default → same failure.
- `v4.0.1` (latest): identical default `packages: 'tools platform-tools'` → same failure.

So any version bump alone reproduces the break. The upstream action's own README documents
the escape hatch:

> Default value is `tools platform-tools`, supply an empty string to skip installing
> additional packages.

### 5. Do we need any packages installed?

No. The GitHub runner image preinstalls the Android SDK (`ANDROID_HOME` set, platforms and
build-tools present) and the action still accepts SDK licenses (separate input, default
`true`). AGP auto-downloads anything missing when licenses are accepted. Nothing in this
repo invokes `sdkmanager`/`adb` directly; Gradle resolves the pinned `classes.jar` via
`bootstrapCloudstream`, not via the SDK manager.

## Conclusion (root cause)

Upstream infrastructure change: `android-actions/setup-android@v2` hardcodes installation of
the SDK package `tools`, which Google has removed from the SDK repository. The workflow's
setup step now fails before Gradle runs. Fix is CI plumbing only — no provider code affected.

## Fix (minimal)

`.github/workflows/build.yml`: bump `android-actions/setup-android@v2` → `@v3` and pass
`packages: ''` to skip installing the removed `tools` package (license acceptance is kept).

## Verification (this run)

The verify-provider skill (live-site probe) is provider-specific and not applicable — no
provider code changed; the failing surface is the CI setup step. Verification done:

- `actionlint` on the modified workflow (AGENTS.md pre-flight for `.github/**` changes).
- YAML parse check.
- Confirmed locally that the pinned `v3` action metadata (fetched from upstream) accepts the
  `packages` input and that `''` is its documented skip value.

In-app/CI validation happens when a human merges (build.yml runs on push to main); the
delivery of a workflow-touching change requires the `AGENT_PAT` secret per ADR-0004.
