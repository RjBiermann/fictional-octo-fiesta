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
  roles: all
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
  add-comment:
    max: 1
  add-labels:
    allowed: [status/ready-to-merge]
    max: 1
---

# Review (findings only; ADR-0001 semantics preserved)

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
