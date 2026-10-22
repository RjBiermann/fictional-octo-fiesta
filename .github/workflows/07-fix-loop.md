---
features:
  dangerously-disable-sandbox-agent: true
sandbox:
  agent: false
strict: false
description: Repair acts on review findings — separate session, capped, cooldown
on:
  pull_request_review:
    types: [submitted]
  # /repair ownership (ADR-0015): /repair died with the devloop lane
  # (pilot #568); repair happens through this workflow's review rounds —
  # wiring a repair slash command here too fired duplicate repair
  # sessions on the same PR (kept as a guard note).
  # roles:all (owner decision 2026-10-16, "automate unless a human decision
  # is needed"): the review author on agent PRs is github-actions[bot]
  # (permission none) and run approval does not change the actor, so the
  # default gate silently skipped every review-triggered repair — same
  # defect fixed in 06-review. Repairs stay bounded: push-to-pull-request
  # max 3, cycle cap + status/fixing-stuck escalation to the human.
  roles: all
  workflow_dispatch:          # ADR-0019: fix surrogate fires via dispatch (aw_context)
  bots: ["github-actions[bot]", "RjBiermann-machinery[bot]"]
  cooldown: 10m
imports:
  - .github/workflows/shared/engine-lane.md
  - .github/workflows/shared/gate.md
  - .github/workflows/shared/budget.md
permissions:
  contents: read
  issues: read
  pull-requests: read
timeout-minutes: 30
max-turns: 30
max-ai-credits: 1200
safe-outputs:
  threat-detection: false   # engine:pi detection runs on Copilot CLI — not available (no Copilot); structural guardrails + human merge carry the boundary
  # ADR-0020 step 3: machinery App identity for safe outputs — Workflows rw
  # lets patch pushes carry workflow-file commits (GITHUB_TOKEN is rejected
  # for those); ignore-if-missing falls back to GITHUB_TOKEN when unset.
  github-app:
    client-id: ${{ vars.MACHINERY_APP_CLIENT_ID }}
    private-key: ${{ secrets.MACHINERY_APP_KEY }}
    ignore-if-missing: true
  push-to-pull-request-branch:
    max: 3
  add-labels:
    allowed: [status/fixing, status/fixing-stuck]
    max: 2
  remove-labels:
    allowed: [status/fixing]
    max: 1
  add-comment:
    max: 2
    # Relay-fired (ADR-0019): "*" targets the payload's explicit item_number —
    # the default "triggering" silently drops comments on workflow_dispatch
    # (04-plan's round comments were lost this way, 2026-10-20 #804).
    target: "*"
  create-issue:
    max: 2
    title-prefix: "[from-repair] "
    labels: [triage/from-agent]
---

# Fix loop (repair; consumes findings from 06)

The fixer session — separate prompt, separate budget, separate judgment
from the reviewer (ADR-0001: review finds, repair acts).

1. Re-derive findings from the current review comments + diff; a PR with
   a stale/empty diff aborts loudly (never pass on unverifiable state).
2. If this would be cycle 4 or later (count work-attempt stamps on the
   issue), stop: add `status/fixing-stuck`, comment "needs human" with
   the stamp evidence, and do nothing else.
3. Otherwise: add `status/fixing`, fix **only** the findings (scope
   discipline: new discoveries go to `create-issue`, max 2, linked —
   never fixed inline), verify before requesting delivery, and request
   the push via `push-to-pull-request-branch` (max 3 per run) — the
   safe-output job performs the push; you yourself stay read-only, so
   even the fixer never holds a write token.
4. Remove `status/fixing` after the push; let re-review re-fire.

**No actionable findings?** The correct outcome is usually "nothing to fix"
— bot reports, status annotations and stale noise are data, not tasks. But
an empty exit is indistinguishable from a crashed run and generates a
`[aw] no safe outputs` diagnostic (observed on the merged-PR approve, 2026-10-14).
So: close with a work-attempt stamp, and as your final bash step emit
`safeoutputs noop '{"message":"zero findings — nothing to repair"}'`.
Never finish silently (delta 12 terminal-action guarantee).
