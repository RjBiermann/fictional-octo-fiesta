---
features:
  dangerously-disable-sandbox-agent: true
sandbox:
  agent: false
strict: false
description: Weekly spend + ledger report from run history (weekly, cheap)
on:
  schedule: weekly on monday
  workflow_dispatch:          # canary dispatch: ADR-0016 makes this the one
                              # cheap, side-effect-free run every lane change
                              # is validated against before the conveyor resumes
imports:
  - .github/workflows/shared/engine-lane.md
  - .github/workflows/shared/gate.md
permissions:
  contents: read
  issues: read
  pull-requests: read
timeout-minutes: 20          # canary slack: the first lane canary (39994868917)
                            # used 13m19s of 15m — too thin for a run whose job
                            # is to fail loudly, not to be cut off
max-turns: 15
max-ai-credits: 250
safe-outputs:
  threat-detection: false   # engine:pi detection runs on Copilot CLI — not available (no Copilot); structural guardrails + human merge carry the boundary
  create-issue:
    max: 1
    title-prefix: "[cost-report] "
    close-older-issues: true
---

# Cost tracker

Weekly accounting against the Actions API, for the org's single spend
lane: tokens/credits consumed per workflow (this repo + rolled-up per-repo
totals when run from the pipeline repo), run counts, crashes/timeouts,
budget-stamp-based attempt histograms, and chronic flappers the sweeper
report flagged but a human hasn't visited. One issue; the next week's run
closes it (`close-older-issues`). No advice, no fixes — numbers only.
