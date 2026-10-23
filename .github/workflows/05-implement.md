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
  workflow_dispatch:          # ADR-0019: machine relay fires via dispatch (aw_context) —
                              # bot-applied label events are loop-dead, so the
                              # conveyor dispatches instead of stamping into the void
  bots: ["github-actions[bot]", "RjBiermann-machinery[bot]"]
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
  # issue events: the issue number; dispatch relay (ADR-0019): the item
  # restored from aw_context; anything else: per-run (no shared slot —
  # the bare group collapsed ALL dispatched implements into one slot,
  # cancelling sibling chains' builds).
  group: impl-${{ github.event.issue.number || (github.event.inputs.aw_context && fromJSON(github.event.inputs.aw_context).item_number) || github.run_id }}
timeout-minutes: 75
max-turns: 30
max-ai-credits: 1500
safe-outputs:
  threat-detection: false
  # ADR-0020 step 3: safe outputs act as the machinery App — its Workflows
  # rw permission is what lets push/create-PR patches carry workflow-file
  # commits (GITHUB_TOKEN is rejected for those since GitHub's
  # workflow-commit push restriction; #875 context). ignore-if-missing:
  # unregistered/fork context falls back to GITHUB_TOKEN instead of failing.
  github-app:
    client-id: ${{ vars.MACHINERY_APP_CLIENT_ID }}
    private-key: ${{ secrets.MACHINERY_APP_KEY }}
    ignore-if-missing: true
  add-comment:
    target: "*"
    # 3 slots: one work-attempt budget stamp + one work-report narration +
    # one anchored registry entry when the run audits a provider (ADR-0013).
    # max:1 deadlocked the ledger protocol — the narration comment consumed
    # the slot and the required stamp could never be posted (issue #716).
    max: 3
   # engine:pi detection runs on Copilot CLI — not available (no Copilot); structural guardrails + human merge carry the boundary
  create-pull-request:
    # workflows: write on the minted machinery-App token — without it the
    # remote rejects every push touching .github/workflows/** ("refusing
    # to allow a GitHub App ... without 'workflows' permission"; #1063).
    allow-workflows: true
    max: 1
    title-prefix: "[impl] "
    draft: false   # ADR-0017: ready PRs are the conveyor's currency; a session-side `gh pr ready` was bare chore, removed
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
4. **Transition immediately after the PR exists — before ANY
   `add-comment`** (work stamp, registry entry, verdict; #860): a late
   comment-payload error must not eat the turns before the state
   transition. `add-labels status/in-review`, then `remove-labels
   status/planned` (one state label at a time; partial failure is healed
   by the daily sweep). An Implement run that posts its verdict but skips
   this transition stalls the whole chain (observed S3 #812, ~13h).
5. Post one work-attempt stamp (`add-comment`) closing out what you did.
6. **Registry entries never travel in your diff** (ADR-0013): never edit
   `audits/findings.json` — a registry blob here dies rebasing onto moved
   main (#723 stranded-delivery class). If this run produced a registry
   update, post it as an `add-comment` whose body starts with
   `<!-- findings-registry-entry -->` followed by a fenced ```json
   object; the orchestrator unions it into main on its next sweep.

Never: apply trigger labels, label `status/planned`, merge, approve, or
close anything.

When this run is an audit story, the verdict comment's heading is
machine-parsed by the orchestrator's story closeout (#861) — use exactly
this heading shape, no prose inside the heading:
`## S<n> audit verdict — <Provider>: **clean**` (plain, spaced; the
verdict word in bold at the end. Bracketed or unnumbered variants are
tolerated by the closeout regex, but ship the canonical shape).

When this run's parent spec declares a **conditional fan-out** (#847:
probe → exactly one branch story), your deliverable verdict is
machine-parsed by the orchestrator's branch executor — post exactly:
`## Probe verdict — <chain>: **<fixable|not fixable>**` — the chain link
in parens after the word "verdict" (e.g. `(#846)`), the outcome bold,
`**not fixable**` checked before `**fixable**`. The executor fails
closed on any other shape.
