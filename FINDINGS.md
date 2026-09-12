# FINDINGS — issue #369 (review #347 P1-7): lint.yml Jackson gate false-positives on comments

## Probe (reality check)

- Current gate: `.github/workflows/lint.yml` `jackson` job,
  `grep -rEhn --include=build.gradle.kts 'com\.fasterxml.*jackson.*:(2\.1[4-9]|2\.[2-9]|[3-9]\.)'`.
- Repo state today: **no false positive exists in-tree** (`grep` exits 1; the only
  Jackson reference is the legitimate `com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1`
  in root `build.gradle.kts:101`). The bug is latent.
- Fixture `/tmp/jprobe/build.gradle.kts`:
  - `// never bump com.fasterxml.jackson.core:jackson-databind to 2.17` → **does NOT match**
    (regex requires `:` immediately before the version).
  - `// e.g. com.fasterxml.jackson.core:jackson-databind:2.17 would break old devices`
    (comment quoting the exact coordinate) → **matches, exit 0 → gate fails**. Confirmed.
- `-h` confirmed harmful: on a failure no file/line is printed, so the author cannot
  tell which build file triggered it.

## Fix (minimal)

1. `-h` → `-n` so failures carry `file:line` (matches the `plugin-shape` job style).
2. Filter out lines whose content portion is a comment (`//`, `/*`, `*`), matching
   matches in comment-only lines can no longer fail the gate.

## Verification

- Fixture matrix (all against the new pipeline):
  - comment quoting coordinate (`:2.17`) → passes (no match after comment filter);
  - real `implementation(...jackson-module-kotlin:2.14.0)` → gate fails with file:line;
  - current `...:2.13.1` → passes;
  - block-comment quoting coordinate → passes.
- `actionlint` is not available in the local environment; `lint.yml`'s `actionlint`
  job covers this on push. Locally verified instead: YAML parses (js-yaml) and the
  embedded run script passes `bash -n`.
- The `verify-provider` skill's `verify.sh` is a live-site provider check and does
  not apply to a workflow change; equivalent mechanical checks are the fixture
  matrix + actionlint above.
