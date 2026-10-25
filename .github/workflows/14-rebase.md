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
  bots: ["github-actions[bot]", "RjBiermann-machinery[bot]"] # conflict_watch (95) fires this via dispatch (aw_context)
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
  # ADR-0020 step 3: machinery App identity for safe outputs — Workflows rw
  # lets patch pushes carry workflow-file commits (GITHUB_TOKEN is rejected
  # for those); ignore-if-missing falls back to GITHUB_TOKEN when unset.
  github-app:
    client-id: ${{ vars.MACHINERY_APP_CLIENT_ID }}
    private-key: ${{ secrets.MACHINERY_APP_KEY }}
    ignore-if-missing: true
  push-to-pull-request-branch:
    # workflows: write on the minted machinery-App token — without it the
    # remote rejects every push touching .github/workflows/** ("refusing
    # to allow a GitHub App ... without 'workflows' permission"; #1063).
    allow-workflows: true
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

## Rule 0 — pre-push identity guard (identical text in 07-fix-loop and 14-rebase)

The push is this lane's only irreversible act, so it never rides on a guess
about whose branch it targets. Before ANY `push-to-pull-request-branch` call
in this run, derive the expected identity from your own context and assert it:

- **Derive.** `N` = this run's `aw_context.item_number` (the issue the relay
  dispatched for; on an event trigger, the number in the PR's closing-issue
  link). `PR` = the PR whose branch you are about to push. `BR` = that PR's
  head branch.
- **Assert (primary — must hold).** `BR` starts with `devloop/issue-<N>-`: the
  fixed issue segment `<N>` must equal the aw_context item exactly. A branch
  naming a different issue, or carrying no issue segment at all, FAILS.
- **Assert (secondary — confirms).** `gh pr view <PR> --json
  closingIssuesReferences` lists `<N>` — the PR and this run resolve to the
  same spec issue.
- **On mismatch: NO push call is made.** Post exactly one `add_comment` on
  issue `<N>` — the owner issue, never the PR — carrying the mismatch
  evidence: this run's URL, `aw_context` item `<N>`, the target PR `<PR>`, the
  target branch `<BR>`, and which assertion failed. Then stop loudly. That
  stamp is the only artifact: no state-label churn, no second target, no retry
  of the push. A bad-context run must be cheap to retry and trivial to audit —
  this abort stamp is what makes it both.

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
   commit — no rebase, no force push; review history stays intact).
   Classify every conflicted file by family, not by literal filename:
   - **Append-only registry JSON** (`GH_AW_REGISTRY_PATH` shape): union
     both sides — run/record entries append and dedupe; main wins scalars.
   - **Test files ONLY**: keep BOTH sides' test methods/blocks sequentially.
     Keep-BOTH is scoped to test files — never apply it to any other
     category below.
   - **Agent-state markdown** — the family of `FINDINGS-*` notes plus
     budget/attempt ledger notes (any agent-state note file, whatever its
     exact name): merge on content. Start from main's file, append and
     dedupe this branch's own (this story's) note additions, and never
     import a sibling branch's deliverable wholesale. If import-vs-merge
     is ambiguous, DO NOT guess — skip the push and stamp what blocked
     you (see 6).
   - **Version files** (`build.gradle.kts`-style `version = N`): when the
     bodies differ solely in the version line, the higher number wins
     (its bump-comment rides); anything more differs → treat as next rule.
   - **Everything else**: minimal semantic merge preserving BOTH sides'
     intent. When that is genuinely ambiguous, DO NOT guess — skip the
     push and stamp what blocked you (see 6).

   These categories are exhaustive and mutually exclusive. Ambiguity in
   any of them resolves to the rule-6 blocked path (skip + stamp), never
   to a guessed import of the other side's deliverable.
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
