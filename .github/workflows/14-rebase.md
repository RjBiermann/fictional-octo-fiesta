---
name: Rebase on conflict
description: AI fallback rebase — resolves merge conflicts on agent PRs that the mechanical resolver can't handle
features:
  dangerously-disable-sandbox-agent: true
sandbox:
  agent: false
strict: false
on:
  pull_request:
    types: [labeled]
    names: [status/conflict]
  # explicit dispatch: the orchestrator fires this directly after applying
  # the label — pull_request.labeled events proved unreliable here
  # (2 label applications, 0 runs, 2026-10-17); the PR context is restored
  # via pr_number input when labeled did not fire.
  workflow_dispatch:
    inputs:
      pr_number:
        description: "PR number to rebase (conveyor passes it explicitly)"
        required: true
        type: string
  roles: [admin, maintainer, write]
  bots: ["github-actions[bot]"] # conflict_watch (95) fires this via dispatch (aw_context)
  cooldown: 20m
imports:
  - .github/workflows/shared/engine-lane.md
  - .github/workflows/shared/gate.md
  - .github/workflows/shared/budget.md
# Full history is the point of this lane: on the default shallow checkout
# (fetch-depth: 1) the rebase agent's first `git merge-tree origin/main HEAD`
# dies with "refusing to merge unrelated histories" (rc=128) — no visible
# merge-base, nothing rebased, PR stays DIRTY (#852, run 39547359427;
# spec #855).
checkout:
  fetch-depth: 0
permissions:
  contents: read
  issues: read
  pull-requests: read
concurrency:
  group: rebase-${{ github.event.pull_request.number }}
timeout-minutes: 25
max-turns: 20
max-ai-credits: 800
safe-outputs:
  threat-detection: false   # engine:pi detection runs on Copilot CLI — not available (no Copilot); structural guardrails + human merge carry the boundary
  push-to-pull-request-branch:
    max: 2
  remove-labels:
    allowed: [status/conflict]
    max: 1
  add-comment:
    target: "*"
    max: 1
---

# Rebase on conflict

PR context (labeled or dispatched as `pr_number`): the PR whose branch
the mechanical resolver couldn't un-stick.

The AI fallback for merge conflicts (ADR-0017 amendment, delta 16-f12): the
orchestrator's mechanical resolver handles the two deterministic classes
(append-only registry union, version-bump higher-wins). You own everything
else — including test files, under your own compile gate — with rules,
not vibes:

1. Confirm the conflict: `git fetch origin main` then
   `git merge-tree --write-tree --name-only origin/main HEAD` (rc=1 lists
   conflicted files). If clean — the conflict cleared between label and
   run: just `remove-labels status/conflict` and stamp, done.
2. Resolve in the working tree by `git merge origin/main` (a normal merge
   commit — no rebase, no force push; review history stays intact):
   - **Append-only registry JSON** (`GH_AW_REGISTRY_PATH` shape): union
     both sides — run/record entries append and dedupe; main wins scalars.
   - **Test files**: keep BOTH sides' test methods/blocks sequentially.
   - **Version files** (`build.gradle.kts`-style `version = N`): when the
     bodies differ solely in the version line, the higher number wins
     (its bump-comment rides); anything more differs → treat as next rule.
   - **Everything else**: minimal semantic merge preserving BOTH sides'
     intent. When that is genuinely ambiguous, DO NOT guess — skip the
     push and stamp what blocked you (see 6).
3. Verify before pushing: run the repo's own test command scoped to the
   affected modules (Android repo: `./gradlew <module>:test`). Red = do
   not push; fix or abort per rule 2.
4. Commit ALL resolutions (`git add` only the files you resolved) and
   invoke the safe-output push as an EXACT bash CLI line (delta-15
   contract, single quoted inline argument, one call):

       safeoutputs push_to_pull_request_branch --body '{"pull-request-count": <N>, "summary": "rebase complete"}'

   — never direct `git push` (the sandbox holds no write token).
5. After the safe-output push call lands: stamp via `add_comment`
   (stamp FIRST, label removal LAST — auditability), then
   `remove-labels status/conflict`. The
   stamp MUST land on the LINKED SPEC ISSUE (itemNumber = the `issue-<n>`
   in the branch name / the PR's closing issue) — NOT this PR: the
   sweeper's ledger scans issues only, PR-linked threads are excluded.
   The FIRST line of the comment MUST be
   `gh-aw budget: attempt=<n> kind=fix run=<this run URL>`
   (kind=fix — the ledger has no rebase kind; a rebase attempt rides the
   parent work item so max_attempts counts it), followed by:
   rebase done at `<sha>`, conflicts resolved (`<files>`), tests `<n/n>`.
6. **Blocked?** (ambiguity, red tests you can't resolve): do NOT push,
   DO NOT remove the label, and stamp the exact blocker — the human
   merge decision stays with the owner. A lost push is silent; a stamp
   is visible.

7. **The push is the rule-4 CLI call, exactly as written — never
   implicit, never paraphrased.** `git commit` delivers nothing — the
   sandbox is discarded after your final turn (observed runs
   39061496996 and 39066050579: full merge, green tests, then the
   safe-output layer processed ZERO messages because the literal CLI
   call was never issued). The `safeoutputs push_to_pull_request_branch
   --body '...'` line recited in the prompt must be the very invocation
   executed in bash — a recitation with no issued call is the identical
   failure with better narration.

Never finish silently — the stamp (or the push) is the terminal action.

Blocked or wall-died? Retry is remove+re-add of `status/conflict` (an
already-present label emits no second `labeled` event) — human action on
the PR; cooldown 20m applies. The stamp above is how the ledger records
each attempt against that lever.
