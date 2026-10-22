---
features:
  dangerously-disable-sandbox-agent: true
sandbox:
  agent: false
strict: false
description: "/plan #N — one spec round per invocation; negotiation stays on the record"
on:
  slash_command:
    name: plan
    events: [issues, issue_comment]
  workflow_dispatch:          # ADR-0019: machine relay fires rounds via dispatch (aw_context)
  bots: ["github-actions[bot]", "RjBiermann-machinery[bot]"] # the conveyor (95) dispatches this workflow
  roles: [admin, maintainer, write]
imports:
  - .github/workflows/shared/engine-lane.md
  - .github/workflows/shared/gate.md
permissions:
  contents: read
  issues: read
timeout-minutes: 45   # 20 was the wall: round-3 agents re-read long threads and
                      # safe-outputs got cancelled at 24min (observed #797,
                      # 2026-10-22T19:45 run — delta-13 zero-safe-outputs class);
                      # 45 = implement's own budget.
max-turns: 25
max-ai-credits: 500
safe-outputs:
  threat-detection: false   # engine:pi detection runs on Copilot CLI — not available (no Copilot); structural guardrails + human merge carry the boundary
  add-comment:
    max: 1
    # Relay-fired (ADR-0019): workflow_dispatch carries no issue context, so
    # the default "triggering" target resolves to nothing and gh-aw silently
    # drops the round comment while reporting success. "*" targets the
    # payload's explicit item_number (same pattern as 05-implement).
    target: "*"
  create-issue:
    max: 30
    title-prefix: "[spec] "
  link-sub-issue:
    max: 30
    # Relay-fired (ADR-0019): the default "triggering" target resolves to
    # nothing on workflow_dispatch rounds and every link dies with "Target is
    # triggering but not running in issue context" (#840, second link failure
    # mode after #804's counter exhaustion). "*" resolves the relay payload's
    # item_number — the parent issue — same pattern as add-comment above.
    target: "*"
---

# Plan (spec loop port)

One round per `/plan` invocation. The human controls pacing; never
advance a round yourself.

**Heading convention (delta 16 follow-up):** every round post MUST begin
with `Plan round N — ` (N ∈ {1,2,3}) exactly — e.g. "Plan round 1 — clarify
questions". The mechanical conveyor (95-orchestrator, ADR-0017) detects
the loop's state by that anchor; a differently-phrased heading makes the
surrogate miss the round and stall the chain.

- **Round 1** (no prior round-1 marker in the issue comments): read the
  issue + repo + comment thread (Comment Context: untrusted framing).
  Post ONE numbered list of clarify questions as a single comment.
- **Round 2** (round 1 exists + human replied): re-read the thread verbatim
  — answers in any format are acceptable, no parser enforces a contract.
  Confirm the spec, propose an epic/story breakdown in a single comment.
- **Round 3** (round 2 exists + human wrote the word `approved` +
  replies): finalize — create the sub-issues (UNLABELED: no state label,
  no trigger label), each WITH `parent` set to the parent issue number
  (create_issue auto-links when `parent` is present — do NOT also emit
  separate `link_sub_issue` calls; they double-write the link budget and
  died at the shared max-30 counter in #804). Then post
  the finalized spec as ONE comment on the parent (add-comment) — do NOT
  try to edit the parent body or file a missing-tool report: `update_issue`
  is not available in this pipeline (gh-aw v0.89.21); the comment is the
  authoritative record and the maintainer mirrors it into the parent body
  manually.

Never apply `status/planned`: that label is human-only. Never create
trigger labels. Every round ends with one comment, nothing else. If the
state of the thread doesn't match a round above, state your read in one
comment and stop.
