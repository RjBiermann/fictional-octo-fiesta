---
name: Rebase on conflict
description: AI fallback rebase — resolves merge conflicts on agent PRs that the mechanical resolver can't handle
features:
  dangerously-disable-sandbox-agent: true
sandbox:
  agent: false
strict: false
on:
  pull_request:
    types: [labeled]
    names: [status/conflict]
  roles: [admin, maintainer, write]
  cooldown: 20m
imports:
  - .github/workflows/shared/engine-lane.md
  - .github/workflows/shared/gate.md
  - .github/workflows/shared/budget.md
permissions:
  contents: read
  issues: read
  pull-requests: read
concurrency:
  group: rebase-${{ github.event.pull_request.number }}
timeout-minutes: 25
max-turns: 20
max-ai-credits: 800
safe-outputs:
  threat-detection: false   # engine:pi detection runs on Copilot CLI — not available (no Copilot); structural guardrails + human merge carry the boundary
  push-to-pull-request-branch:
    max: 2
  remove-labels:
    allowed: [status/conflict]
    max: 1
  add-comment:
    target: "*"
    max: 1
---

# Rebase on conflict

The AI fallback for merge conflicts (ADR-0008 amendment, delta 16-f12): the
orchestrator's mechanical resolver handles the deterministic classes
(append-only registry union, version-bump higher-wins, keep-both appended
test blocks). You handle what it can't — with rules, not vibes:

1. Confirm the conflict: `git fetch origin main` then
   `git merge-tree --write-tree --name-only origin/main HEAD` (rc=1 lists
   conflicted files). If clean — the conflict cleared between label and
   run: just `remove-labels status/conflict` and stamp, done.
2. Resolve in the working tree by `git merge origin/main` (a normal merge
   commit — no rebase, no force push; review history stays intact):
   - **Append-only registry JSON** (`GH_AW_REGISTRY_PATH` shape): union
     both sides — run/record entries append and dedupe; main wins scalars.
   - **Test files**: keep BOTH sides' test methods/blocks sequentially.
   - **Version files** (`build.gradle.kts`-style `version = N`): when the
     bodies differ solely in the version line, the higher number wins
     (its bump-comment rides); anything more differs → treat as next rule.
   - **Everything else**: minimal semantic merge preserving BOTH sides'
     intent. When that is genuinely ambiguous, DO NOT guess — skip the
     push and stamp what blocked you (see 6).
3. Verify before pushing: run the repo's own test command scoped to the
   affected modules (Android repo: `./gradlew <module>:test`). Red = do
   not push; fix or abort per rule 2.
4. Commit ALL resolutions (`git add` only the files you resolved) and
   push via `push_to_pull_request_branch` — never direct `git push` (the
   sandbox holds no write token).
5. `remove-labels status/conflict`, then stamp via `add_comment`:
   rebase done at `<sha>`, conflicts resolved (`<files>`), tests `<n/n>`.
6. **Blocked?** (ambiguity, red tests you can't resolve): do NOT push,
   DO NOT remove the label, and stamp the exact blocker — the human
   merge decision stays with the owner. A lost push is silent; a stamp
   is visible.

Never finish silently — the stamp (or the push) is the terminal action.
