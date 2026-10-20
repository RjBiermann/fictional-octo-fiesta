---
features:
  dangerously-disable-sandbox-agent: true
sandbox:
  agent: false
strict: false
description: Daily sweep of open issues — re-check vocab, flag chronic flappers
on:
  schedule: daily
  workflow_dispatch:
imports:
  - .github/workflows/shared/engine-lane.md
  - .github/workflows/shared/gate.md
permissions:
  contents: read
  issues: read
timeout-minutes: 30
max-turns: 50
max-ai-credits: 800
safe-outputs:
  threat-detection: false   # engine:pi detection runs on Copilot CLI — not available (no Copilot); structural guardrails + human merge carry the boundary
  add-labels:
    allowed: [status/queued, status/in-review, status/fixing, status/fixing-stuck, status/ready-to-merge, triage/from-agent]
    max: 20
    target: "*"            # scheduled runs have no triggering issue — every call targets an explicit number
  remove-labels:
    allowed: [status/queued, status/in-review, status/fixing, status/fixing-stuck, status/ready-to-merge]
    max: 20
    target: "*"
  close-issue:
    max: 5
    state-reason: not_planned
    target: "*"
  add-comment:
    max: 10
    target: "*"
  link-sub-issue:
    max: 10
  create-issue:
    max: 1
    title-prefix: "[backlog-report] "
---

# Backlog triage

Daily corpus sweep (replaces the cron-Sweep's queue-review duties beyond
what the sweeper can do deterministically):

- Scan open issues; normalize state labels to the one-state-at-a-time
  invariant (this is the **healer** for non-atomic safe-output batches —
  add the correct label, remove stale ones, same pass).
- **Scheduled runs have no triggering issue** (observed 2026-10-16:
  `Target is "triggering" but not running in issue context`): every
  safe-output call MUST carry an explicit target issue number
  (`itemNumber` for label/comment calls; explicit parent & sub for
  `link-sub-issue`). Never rely on context.
- Add `triage/from-agent` when you discover or classify work; link
  discovered sub-work to parents via `link-sub-issue`.
- Flag chronic flappers (issues cycling fix/work states repeatedly, per
  budget-stamp history in comments) with a short comment naming the
  stamp evidence.
- **Close stale machinery records (ADR-0018 surface reduction)**:
  superseded weekly/duplicate `[status-report]`/`[backlog-report]` issues,
  `[aw]` diagnostics a later successful run already outranked, and exact
  duplicates of open work (link the survivor in the closing comment).
  Same rule as 02-triage: a plausible bug report or finding never closes
  here — uncertainty stays on the record as an issue. Cap 5 per sweep;
  the disposition comment IS the record.
- Post nothing on issues that need no change; your caps are shared with
  every other workflow in this repo.
- If nothing needed attention, say nothing anywhere.
