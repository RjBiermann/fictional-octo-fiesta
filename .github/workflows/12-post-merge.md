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
    max: 2
---

# Post-merge closeout

The push to `main` IS the human's verdict (branch protection guarantees
it landed via merge — or an intentional direct push, which a collaborator
owns either way).

1. For each commit in the push, find the PR it landed through and the
   spec issue the PR body references (provenance section, `#refs`).
2. Close linked sub-issues whose PRs are now merged — execution of the
   human's merge, not your judgment (the same carve-out as `/retry`'s
   close). Issues without a merged PR stay open. Pass each issue's
   number explicitly in the close_issue call (`issue_number` field);
   without it the close lands nowhere (#862).
3. Post one closeout comment per issue closed ("PR #{n} merged → #issue
   recorded"). Nothing else; `create-pull-request` was deliberately not
   configured — add it only when a concrete need appears.
