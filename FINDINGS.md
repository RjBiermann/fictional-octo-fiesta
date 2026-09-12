# FINDINGS — issue #373 (P2-5: issue-tracker.md blames build.yml for issue creation)

## Probing (evidence)

- Claim: `docs/agents/issue-tracker.md` **Pipeline** line says "CI (`.github/workflows/build.yml`) may also create/update issues with the same `gh` conventions, using `GITHUB_TOKEN`."
- `grep -n -i "issue" .github/workflows/build.yml` → **no hits**. build.yml only builds providers; it has no issue logic. The old Builder/Triage machinery that touched issues was removed from CI.
- Reality: writes to the issue tracker from automation come from **devloop agent runs** — `.github/workflows/devloop.yml` has `permissions: issues: write`, exports `GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}` (line 82), and runs `gh`-based devloop steps. (`stale.yml` also has `issues: write`, but only labels/close via the stale bot — not issue *creation*.)
- Issue premise is correct; fix is a docs reword, no code change.

## Fix

- Rewrote the **Pipeline** line in `docs/agents/issue-tracker.md` to attribute issue create/update to devloop agent runs (same gh conventions, `GH_TOKEN`), removed the false build.yml attribution.

## Verification

- Gate: this is a docs-only change; no provider compile/test gate applies (pipeline gate `pipeline.verify` is empty for docs). `actionlint` not required — no `.github/**` changed.
- Checked: no other file asserts CI/build.yml creates issues (`grep -rn build.yml docs/ CONTEXT.md FILE_PATHS` re-check after edit below).
