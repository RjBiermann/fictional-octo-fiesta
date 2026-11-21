---
features:
  dangerously-disable-sandbox-agent: true
sandbox:
  agent: false
strict: false
description: Findings-only review rounds on the PR — the reviewer never edits code
on:
  pull_request:
    types: [opened, synchronize]
    # roles:all (not omission — omission re-applies the default, observed
    # as a review-agent blocker on 2026-10-16): the PR author here is
    # always github-actions[bot] (agent-produced branches, permission
    # none), so the default roles gate skipped every review forever.
    # Tradeoff accepted for this adopter: repo has no outside
    # collaborators; PR content is input-not-instruction (AGENTS.md
    # injection stance); review is findings-only and can never merge
    # (ADR-0001).
  # ADR-0019 relay: gh-aw compiles the slash_command's issue_comment arm with
  # `issue.pull_request == null`, so `/review` typed into a PR conversation
  # can never activate 06b (100 runs, 0 fired). 06c-review-command relays it
  # here instead, carrying the PR in aw_context (#1412).
  workflow_dispatch:
  roles: all
concurrency:
  # issue/PR events: the PR; relayed rounds (ADR-0019): the item restored
  # from aw_context; anything else: per-run. Without this a dispatched
  # round shares `...-refs/heads/main` with cancel-in-progress, so /review
  # on one PR kills another (#1469 review).
  group: review-${{ github.event.pull_request.number || github.event.issue.number || (github.event.inputs.aw_context && fromJSON(github.event.inputs.aw_context).item_number) || github.run_id }}
  # Same item: a newer round supersedes the older one; different items: no
  # shared slot, so /review on one PR never cancels another.
  cancel-in-progress: true
imports:
  - .github/workflows/shared/engine-lane.md
  - .github/workflows/shared/gate.md
  - .github/workflows/shared/budget.md
permissions:
  contents: read
  issues: read
  pull-requests: read
timeout-minutes: 25
max-turns: 25
max-ai-credits: 800
safe-outputs:
  threat-detection: false   # engine:pi detection runs on Copilot CLI — not available (no Copilot); structural guardrails + human merge carry the boundary
  create-pull-request-review-comment:
    max: 10
    # "*": relayed rounds arrive as workflow_dispatch, which has no
    # triggering item — the default "triggering" target is rejected before
    # aw_context is read (04-plan/05/07/06b precedent, #1469 review).
    target: "*"
  add-comment:
    target: "*"
    # 2 slots: one verdict narration + one work-attempt budget stamp
    # (shared/budget.md ledger protocol). max:1 deadlocked exactly like
    # 05-implement did (#716): the narration consumed the slot, the stamp
    # could never post, and the run filed a missing-tool failure (#1308).
    max: 2
  add-labels:
    allowed: [status/ready-to-merge]
    max: 1
---

# Review (findings only; ADR-0001 semantics preserved)

**Verdict contract.** Open the verdict with a machine-readable marker on its
own line — `**Verdict: blocker**` or `**Verdict:** no blockers` — before any
prose. `fix_surrogate()` reads that marker to decide whether a repair round
fires; prose that merely contains the word ("`negated_blocker` was
body-wide", "no blocker remains", "round-1 blocker resolved") must not read
as an assertion, and a false fire costs two dispatches plus a terminal
`status/fixing-stuck` label.

Full-peer review, like a human senior reviewer would:

- **Verdict per PR comment thread**: blockers (wrong, unsafe, dead on
  arrival) go as `create-pull-request-review-comment`s on the exact
  lines. Style suggestions are comments too — findings stay yours.
- **You never push, edit, or fix anything**; any review feedback lands
  for a separate repair session to act on (07). Never push to the PR
  branch, never close, never merge.
- Carry prior findings between rounds (re-derived against the current
  diff; empty diffs are always a hard abort, never a pass).
- If the diff passes your review AND CI (`verify`) is green AND no
  `security/blocked` label is present, add `status/ready-to-merge` —
  the human decides the merge itself.
- Zero findings is still an output: if you have nothing to report, end
  with `safeoutputs noop '{"message":"review: no findings"}'` — never exit
  silently (an empty safe-output record files a false `[aw]` warning issue,
  observed 2026-10-17, #739).
- Close with one work-attempt stamp.
