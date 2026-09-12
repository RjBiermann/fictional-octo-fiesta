# FINDINGS-352 — [review #347] P0-5: build.yml uses actions/checkout@master

## Evidence (probed 2026, repo c5d19af)

`.github/workflows/build.yml:23` ( Checkout → `path: "src"`) and `:29`
(Checkout builds → `ref: "builds"`, `path: "builds"`)
both use `actions/checkout@master` — an unpinned, mutable branch ref.
The workflow declares `permissions: contents: write` (top of file) and its
final two steps commit/amend + `git push --force` to `builds` and mirror to
Codeberg with a token, so a supply-chain compromise of the mutable ref runs
with write access and can tamper with the published `.cs3` artifacts.

Cross-check:

```
$ grep -rn "uses: actions/checkout@" .github/workflows/
build.yml:23,29  → @master  ← only occurrences
codeql.yml:32    → @v7
devloop.yml:33, lint.yml:27,38,56,68 → @v4
```

Every other workflow in the repo pins a major version (`@v4`/`@v7`/`v5`/`v4`/
`v2`/`v9`), confirming P0-5 and that repo convention is major-version pinning.
Issue #347's P1-5 (SHA-pin all actions) is marked optional — deferred, not
part of this minimal fix.

## Root cause

Historical: build.yml predates the pinning convention used elsewhere.

## Fix

Change both occurrences `actions/checkout@master` → `actions/checkout@v4`
(major-version pin, matching repo convention; deps graph gates major-v4 so
this is safe). No other changes — keep minimal per issue.

## Verification

- `actionlint` on all workflows before push (repo rule; pass).
- The verify-provider skill (live-site selector/stream checks) does **not**
  apply — this change touches no provider code or site extraction; the
  relevant verification is workflow lint + the normal CI build on merge.
- We do not run a real workflow (requires push + secrets); compile-level
  verification by build.yml at merge covers it.
