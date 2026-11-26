---
features:
  dangerously-disable-sandbox-agent: true
sandbox:
  agent: false
strict: false
description: Security scan + secret-leak check merged (one PR-triggered pass)
on:
  pull_request:
    types: [opened, synchronize]
    # roles:all (same defect class fixed in 06-review; 07-fix-loop keeps the
    # explicit gate because its trigger actor is the REVIEW author, not the PR
    # author — do not "fix" this one the same way): the PR author/actor here
    # is github-actions[bot] (permission none), so the default roles gate
    # skipped every security scan on agent PRs — PRs could reach
    # status/ready-to-merge with no security/blocked label simply because this
    # workflow never ran. Findings-only and cheap by cap design; it can never
    # merge (ADR-0001).
  roles: all
  # Dependabot PRs (#1530 and every bump since): GitHub withholds repository
  # secrets from runs triggered by dependabot[bot], so activation fails
  # "Secret Verification Failed" before the agent starts — the review can
  # never run, and each bump filed a [aw] failure issue (#1531, #1532).
  # The bump is covered by CI + CodeQL; this lane adds nothing there.
  skip-bots: [dependabot]
imports:
  - .github/workflows/shared/engine-lane.md
  - .github/workflows/shared/gate.md
permissions:
  contents: read
  issues: read
  pull-requests: read
timeout-minutes: 10
max-turns: 8
max-ai-credits: 200
safe-outputs:
  threat-detection: false   # engine:pi detection runs on Copilot CLI — not available (no Copilot); structural guardrails + human merge carry the boundary
  add-labels:
    allowed: [security/blocked, security/flagged]
    max: 4
  add-comment:
    max: 1
---

# Security scan (08+09 merged)

Cheap, focused pass over the PR diff only:

- Look for: injected secrets/tokens/keys, credential-shaped strings,
  unsafe subprocess/eval/SQL interpolation, dependency red flags visible
  in the diff.
- `security/blocked` for definite findings with the line reference in a
  single comment; `security/flagged` for suspicion worth human review.
- No findings → label nothing, but DO NOT exit silently: end with
  `safeoutputs noop '{"message":"security scan: no findings"}'`. An empty
  safe-output record files a false `[aw]` warning issue (observed 2026-10-17,
  #734). This workflow exists to be boring; every run is cheap by cap design.
