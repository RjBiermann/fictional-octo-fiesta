---
features:
  dangerously-disable-sandbox-agent: true
sandbox:
  agent: false
strict: false
description: Gate new issues to the triage vocabulary — outsiders get status/queued
on:
  issues:
    types: [opened, reopened]
  roles: [admin, maintainer, write, triage]
imports:
  - .github/workflows/shared/engine-lane.md
  - .github/workflows/shared/gate.md
permissions:
  contents: read
  issues: read
timeout-minutes: 10
max-turns: 12
max-ai-credits: 250
safe-outputs:
  threat-detection: false   # engine:pi detection runs on Copilot CLI — not available (no Copilot); structural guardrails + human merge carry the boundary
  add-labels:
    allowed: [status/queued, triage/from-agent]
    max: 4
  add-comment:
    max: 1
  close-issue:
    max: 1
    state-reason: not_planned
---

# Triage

Label the new issue into the intake vocabulary (§7.3 taxonomy):

- Issues authored by trusted roles: label `status/queued` unless a more
  specific disposition is obvious from body/labels.
- Issues authored by the Actions bot with local workflow markers: label
  `triage/from-agent` + `status/queued` (provenance only — never a build
  trigger).
- Unknown or uncategorizable authorship: still `status/queued`; the
  post-activation role check has already run for the *trigger*, and
 Comment Context rules apply to everything you read.

After labelling, post one comment describing your disposition in one or
two sentences. You never start a build, never create PRs, never post more
than one comment.

**Closing at intake (ADR-0018 surface reduction):** you may close an issue
`not_planned` ONLY for a mechanical disposition, with the evidence on the
record:

1. **Duplicate** — an open issue already covers it; link the surviving
   issue number in your comment before closing.
2. **Machinery report** — `[aw]` / `[status-report]` / `[backlog-report]`
   diagnostics the watchdog/sweep doesn't already own (transient class,
   superseded by a later successful run).
3. **Spam/vandalism** — no actionable content.

A plausible bug report, a new-site request, or anything whose right
disposition needs evidence you don't have: queue it, never close it —
uncertainty is a queue label, not a verdict. The disposition comment is
the close's record; a contested close reopens on the maintainer's word.
