# FINDINGS-354 — Issue #354: [review #347] P0-6: builds publishing race

## Probe (2024, branch devloop/issue-354, HEAD 40496b6)

### Claim

`.github/workflows/build.yml` runs the *publishing* build with
`concurrency: cancel-in-progress: true`, while its later steps copy fresh
artifacts onto a checkout of the `builds` branch and publish via
`git commit --amend` + `git push --force` (mirrored force-push to Codeberg).
Two rapid pushes to `main` can therefore interleave destructively:

- A and B both start; when B enters the shared `"build"` group, GitHub
  cancels the in-progress run A. The race window is A's copy → commit →
  push sequence (the multi-minute gradle build happens *before* the copy,
  so it is not part of the window).
- With `cancel-in-progress: true`, a B arriving during A's copy→push
  window kills A mid-publish. A can be killed between the `cp` of `.cs3`
  files and the `cp` of `plugins.json`, or between `git add` and
  `git push` — leaving the builds worktree, and in the worst case the
  published branch, with a `.cs3` set that does not match `plugins.json`
  / `repo.json`. That is exactly the reported mismatch for repo.json
  consumers.
- Even without a kill-in-flight, amend+force-push means the second run
  rewrites the first run's commit; a cancelled-but-already-pushed run leaves
  a half-refreshed branch until the next successful run.

### Evidence gathered

- `.github/workflows/build.yml:4-6`:
  ```
  concurrency:
    group: "build"
    cancel-in-progress: true
  ```
  Single shared group `"build"` for the publishing pipeline; in-progress run
  is cancelled, not queued.
- `.github/workflows/build.yml:56-63` ("Build Plugins"): `./gradlew test make
  makePluginsJson` then copies `*/build/*.cs3`, `build/plugins.json`,
  `repo.json` into the builds worktree — the state that must stay consistent.
- `.github/workflows/build.yml:65-72` ("Push builds"):
  `git commit --amend -m "Build $GITHUB_SHA"` + `git push --force`.
- `.github/workflows/build.yml:74-83` ("Mirror builds branch to Codeberg"):
  second `git push --force` of the same HEAD — a second window in which a
  cancellation strands GitHub and Codeberg out of sync.
- Contrast — `.github/workflows/devloop.yml:15-17`:
  ```
  concurrency:
    group: devloop-pipeline    # serial by design; a scheduled run preempts nothing, waits instead
    cancel-in-progress: false
  ```
  The devloop pipeline is already serial for the same reason.

### Verdict

Confirmed. `cancel-in-progress: true` on the publishing build is wrong; the
pipeline must queue instead. Minimal fix: set `cancel-in-progress: false` in
`build.yml` (keep group `"build"` so pushes to main serialize against each
other). Nothing else in the amend/force-push mechanics needs to change for
this finding — once runs serialize, the copy→amend→push sequence is atomic
per run from the branch's point of view.

### Notes

- AGENTS.md requires `actionlint` before pushing `.github/**` changes.
  `actionlint` was **not present** in the original build run environment
  (`command -v actionlint` → nothing; no binary on PATH), so YAML-parse
  validation was substituted and the deviation recorded here. Closed out
  in the repair round: actionlint v1.7.12 was fetched and run against
  every workflow under `.github/workflows/` — **exit 0, no findings**
  (includes the `cancel-in-progress: false` change). CI `lint.yml`
  remains the authoritative gate at push time.
- Verify-provider skill is provider-scoped (live-site stream checks); not
  applicable to a workflow-only change. Verification here = actionlint-equivalent
  YAML validity + the diff being exactly the concurrency flag.
