---
features:
  dangerously-disable-sandbox-agent: true
sandbox:
  agent: false
strict: false
description: Implement one status/planned spec issue into a scoped PR
on:
  issues:
    types: [labeled]
    names: [status/planned]
  roles: [admin, maintainer, write]
imports:
  - .github/workflows/shared/engine-lane.md
  - .github/workflows/shared/gate.md
  - .github/workflows/shared/budget.md
permissions:
  contents: read
  issues: read
  pull-requests: read
concurrency:
  group: impl-${{ github.event.issue.number }}
timeout-minutes: 75
max-turns: 30
max-ai-credits: 1500
safe-outputs:
  threat-detection: false
  add-comment:
    target: "*"
    # 3 slots: one work-attempt budget stamp + one work-report narration +
    # one anchored registry entry when the run audits a provider (ADR-0013).
    # max:1 deadlocked the ledger protocol — the narration comment consumed
    # the slot and the required stamp could never be posted (issue #716).
    max: 3
   # engine:pi detection runs on Copilot CLI — not available (no Copilot); structural guardrails + human merge carry the boundary
  create-pull-request:
    max: 1
    title-prefix: "[impl] "
    draft: false   # ADR-0008: ready PRs are the conveyor's currency; a session-side `gh pr ready` was bare chore, removed
    allowed-base-branches: [main]  # per-provider audit runs share one default branch; per-run base overrides stay rejected
  add-labels:
    allowed: [status/in-review]
    max: 1
  remove-labels:
    allowed: [status/planned]
    max: 1
  create-issue:
    max: 2
    title-prefix: "[from-impl] "
    labels: [triage/from-agent]
---

# Implement

Build this issue's spec exactly, nothing else. Scope discipline: unrelated
findings belong in the backlog, not in your diff — record them via
`create-issue` (`triage/from-agent`, max 2) or link the existing issue,
never fix inline.

1. Read the parent spec body and its linked sub-issues fully.
2. Implement; run the repo's own test command (from AGENTS.md/README).
3. `create-pull-request` — the PR body MUST include a "## Provenance"
   section: this workflow run URL, the trigger issue number, and your
   stamp (`gh-aw budget:` line per the budget protocol).
   **Never set the `base` field.** The safe-output layer fills the
   default (main); an explicit `base` — including habit-typed `master` —
   is rejected and the code push is lost (observed twice, 2026-10-16:
   runs 38957962887, 38961910251). Omit `base` entirely.
4. Transition state in one pass: `add-labels status/in-review`, then
   `remove-labels status/planned` (one state label at a time; partial
   failure is healed by the daily sweep).
5. Post one work-attempt stamp (`add-comment`) closing out what you did.
6. **Registry entries never travel in your diff** (ADR-0013): never edit
   `audits/findings.json` — a registry blob here dies rebasing onto moved
   main (#723 stranded-delivery class). If this run produced a registry
   update, post it as an `add-comment` whose body starts with
   `<!-- findings-registry-entry -->` followed by a fenced ```json
   object; the orchestrator unions it into main on its next sweep.

Never: apply trigger labels, label `status/planned`, merge, approve, or
close anything.
