# FINDINGS — issue #366 (review #347 P1-10): lint.yml plugin-shape globs scan shared/ for legacy Plugin() but not for pass-through Provider.kt

## Probe (reality check)

- **Asymmetry confirmed** at `.github/workflows/lint.yml:76-82`: the legacy-shape
  `grep` roots are `./*/src/main/kotlin shared/src/main/kotlin`, but the
  pass-through `find` scanned only `./*/src/main/kotlin`. A legacy `Plugin()`
  class dropped into `shared/` fails the gate; a pass-through `*Provider.kt`
  file in `shared/` did not.
- **Exploited by `./*` alone**: `./shared/src/main/kotlin` is matched by the
  `./*/src/main/kotlin` glob (the glob finds all top-level dirs including
  `shared/`), so in the sandbox tree the pass-through check was already
  effective without the explicit root. On GitHub Actions, though, the checkout
  may not have a `.git` dir (then `*/` is not globbed by default: `failglob`
  off leaves the pattern literal, `failglob` on errors), so the explicit
  `shared/src/main/kotlin` root is the only guarantee that both halves of the
  gate scan `shared/`. The two halves must use the same root list.
- **Moot in-tree today, both halves**: `find . shared -name '*Provider.kt'`
  → empty; `grep -rnE '(:[[:space:]]*Plugin\(\)|plugins\.Plugin\(\))'`
  → empty. No legacy `Plugin()` class and no pass-through file exists anywhere
  (providers or shared/). The gate is purely defense-in-depth.
- **Sandbox replication** (fake tree with a planted `shared/src/main/kotlin/FooProvider.kt`):
  `find ./*/src/main/kotlin -name '*Provider.kt'` found it via the `./`-prefixed
  glob hit, but *not* under the no-`.git` checkout condition above — with
  `shared/src/main/kotlin` added as an explicit root it is always found. Same
  root list for both checks is the invariant worth enforcing.
- **Precedent from the same gate** (lint.yml header comment): WatchPorn/
  FullPorner keep `Plugin(context)` context-requiring constructors and are
  excluded from the legacy grep by name. No parallel exclusion is needed for
  the `find`: a `*Provider.kt` pass-through file under WatchPorn/FullPorner is
  still banned by AGENTS.md ("plugin class at the bottom of `<Provider>.kt`"),
  so the find has always correctly flagged any such file. No carve-out required.
- **Verification bar**: AGENTS.md requires `actionlint` before pushing `.github/**`
  changes; it was not on PATH in this environment, so it was installed via
  `go install github.com/rhysd/actionlint/cmd/actionlint@latest` (Go 1.24.13 present).
  The verify-provider skill's live-site script (`.pi/skills/verify-provider/scripts/verify.sh`)
  is for CloudStream providers and does not apply to a workflow-files change; the
  applicable mechanical checks are actionlint plus an end-to-end sandbox run of the
  edited gate script (below).

## Fix (minimal)

One file, one glob, one comment — the `find` root list now matches the legacy
`grep` root list exactly:

- `.github/workflows/lint.yml` `plugin-shape` step:
  `find ./*/src/main/kotlin -name '*Provider.kt'` →
  `find ./*/src/main/kotlin shared/src/main/kotlin -name '*Provider.kt'`,
  with a comment stating shared/ is deliberately in scope for both halves of
  the gate (review #347 P1-10).

## Verification

- **Sandbox, planted pass-through in shared/**: a tree with
  `shared/src/main/kotlin/FooProvider.kt` and a planted legacy
  `shared/src/main/kotlin/Legacy.kt` (`class X : Plugin()`) — the edited
  pipeline catches both (legacy via grep, pass-through via find, each printed
  with its path before the `::error::` line and `exit 1`). The same tree
  without the planted files exits 0.
- **Live repo**: the edited gate script, run as-is in this repo, exits 0 —
  no legacy `Plugin()` and no pass-through `*Provider.kt` anywhere (matches
  the probe).
- **actionlint**: clean (exit 0) on all workflow files after the edit.
