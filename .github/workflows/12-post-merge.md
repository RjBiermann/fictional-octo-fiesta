---
features:
  dangerously-disable-sandbox-agent: true
sandbox:
  agent: false
strict: false
description: Merge = human verdict → close spawned sub-issues, record closeout
on:
  push:
    branches: [main]
  roles: [admin, maintainer, write]
imports:
  - .github/workflows/shared/engine-lane.md
  - .github/workflows/shared/gate.md
permissions:
  contents: read
  issues: read
  pull-requests: read
timeout-minutes: 10
max-turns: 15
max-ai-credits: 300
safe-outputs:
  threat-detection: false   # engine:pi detection runs on Copilot CLI — not available (no Copilot); structural guardrails + human merge carry the boundary
  close-issue:
    max: 6
    state-reason: completed
    # This workflow triggers on push/pull_request, never on issues — the
    # default target "triggering" means gh-aw skips every close with
    # "not running in issue context" (#862). target "*" + explicit
    # issue_number per message is the only closing path here.
    target: "*"
  add-comment:
    target: "*"
    # 6, matching close-issue: every merged PR here uses `Fixes #n`, so an
    # already-closed issue is the NORMAL closeout path and each recorded
    # issue costs one add_comment. At max: 2 a push recording 3+ issues
    # silently lost the tail of the record (review #1332 finding 2).
    max: 6
---

# Post-merge closeout

The push to `main` IS the human's verdict (branch protection guarantees
it landed via merge — or an intentional direct push, which a collaborator
owns either way).

1. For each commit in the push, find the PR it landed through and the
   spec issue the PR body references (provenance section, `#refs`).
2. Close linked sub-issues whose PRs are now merged — execution of the
   human's merge, not your judgment (the same carve-out as `/retry`'s
   close). Issues without a merged PR stay open. Decide on each issue's
   **observed state**, never on the PR body text: still open → close it
   (`issue_number` field; without it the close lands nowhere, #862);
   already closed → skip the close. GitHub auto-closes `Fixes #n`/
   `Closes #n` at merge, so "already closed" is the normal case here, and
   a reopened issue must still get its close.
3. Record each such issue with exactly one `add_comment` call — one call
   per issue, that issue's number in `item_number`, body "PR #{n} merged
   → #issue recorded" — the issues the merge already closed included.
   Never pair a `close_issue` body with an `add_comment` for the same
   issue: one comment, one path. Nothing else; `create-pull-request` was
   deliberately not configured — add it only when a concrete need
   appears.
4. Never reverse-engineer the harness to answer "what does this tool
   do": no `grep`/`find` over `/home/runner/work/_temp`,
   `/home/runner/work/_actions`, `/opt`, `/usr/local`, and no reading of
   the gh-aw action bundle. Searching this repository is fine — that is
   step 1's job. An output-less scan of a system tree burns the whole
   10-minute step budget (run 40150049433 died on an 8m23s stall watchdog
   doing exactly that, 2026-10-25).
