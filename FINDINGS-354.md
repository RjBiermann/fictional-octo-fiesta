# FINDINGS-354 — Issue #354: [review #347] P0-6: builds publishing race

## Probe (2024, branch devloop/issue-354, HEAD 40496b6)

### Claim

`.github/workflows/build.yml` runs the *publishing* build with
`concurrency: cancel-in-progress: true`, while its later steps copy fresh
artifacts onto a checkout of the `builds` branch and publish via
`git commit --amend` + `git push --force` (mirrored force-push to Codeberg).
Two rapid pushes to `main` can therefore interleave: run B cancels run A
*after* A has already copied its `.cs3` set + `plugins.json` into the builds
worktree but *before* A pushes — no wait, cancellation kills A's process, so
the actual interleaving is subtler but equally broken:

- A and B both start (B cancels A only at the moment B *enters* the group;
  GitHub cancels the in-progress run then). Between A's artifact copy and
  A's push there is a multi-minute gradle window — but the copy happens
  *after* the gradle build, so the race window is copy → commit → push.
- With `cancel-in-progress: true`, a B arriving during A's copy→push window
  cancels A mid-publish: A can be killed after `git commit --amend` but
  before `git push --force`, or worse, the two runs' worktrees are separate
  checkouts so the real hazard is: B cancels A, B completes, but A had
  already force-pushed a *partial* state (killed between `cp` of `.cs3`
  files and `cp` of `plugins.json`, or between `git add` and `git push`).
  Result: the published `builds` branch has a `.cs3` set that does not
  match `plugins.json` / `repo.json` — exactly the reported mismatch for
  repo.json consumers.
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
  `actionlint` is **not present** in this run environment (`command -v
  actionlint` → nothing; no binary on PATH). Validation done instead:
  YAML parses (`python3 -c "import yaml,…"`), and the change is a one-token
  boolean flip in an existing, previously actionlint-clean block. CI
  `lint.yml` remains the authoritative gate at push time.
- Verify-provider skill is provider-scoped (live-site stream checks); not
  applicable to a workflow-only change. Verification here = actionlint-equivalent
  YAML validity + the diff being exactly the concurrency flag.
