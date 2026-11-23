---
features:
  dangerously-disable-sandbox-agent: true
sandbox:
  agent: false
strict: false
description: Repair acts on review findings — separate session, capped, cooldown
on:
  pull_request_review:
    types: [submitted]
  # /repair ownership (ADR-0015): /repair died with the devloop lane
  # (pilot #568); repair happens through this workflow's review rounds —
  # wiring a repair slash command here too fired duplicate repair
  # sessions on the same PR (kept as a guard note).
  # roles:all (owner decision 2026-10-16, "automate unless a human decision
  # is needed"): the review author on agent PRs is github-actions[bot]
  # (permission none) and run approval does not change the actor, so the
  # default gate silently skipped every review-triggered repair — same
  # defect fixed in 06-review. Repairs stay bounded: push-to-pull-request
  # max 3, cycle cap + status/fixing-stuck escalation to the human.
  roles: all
  workflow_dispatch:          # ADR-0019: fix surrogate fires via dispatch (aw_context)
  bots: ["github-actions[bot]", "RjBiermann-machinery[bot]"]
  cooldown: 10m
imports:
  - .github/workflows/shared/engine-lane.md
  - .github/workflows/shared/gate.md
  - .github/workflows/shared/budget.md
permissions:
  contents: read
  issues: read
  pull-requests: read
concurrency:
  # Every relay-fired repair (ADR-0019) is a workflow_dispatch: no
  # pull_request.number, `github.ref` is refs/heads/main, so gh-aw's default
  # group collapsed them into ONE slot with cancel-in-progress — a dispatch
  # for PR B killed PR A's in-flight repair. Keyed per PR now, same shape
  # 06-review already uses. Serialization across PRs stays where it is
  # deliberate: 95's `_repair_in_flight()` guard (spend), not a cancelled run.
  group: repair-${{ github.event.pull_request.number || (github.event.inputs.aw_context && fromJSON(github.event.inputs.aw_context).item_number) || github.run_id }}
  cancel-in-progress: true
timeout-minutes: 30
max-turns: 30
max-ai-credits: 1200
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
    max: 3
  add-labels:
    allowed: [status/fixing, status/fixing-stuck]
    max: 2
  remove-labels:
    # "*" because relay-fired runs (workflow_dispatch, ADR-0019) have no
    # triggering item to resolve against — same reason add-comment has it.
    target: "*"
    # status/in-review is the lane's own state (05-implement sets it), so
    # escalation must be able to drop it before adding the terminal label —
    # #1405's one-state-label invariant. status/planned|queued are the
    # maintainer's / orchestrator's and stay OFF this list.
    allowed: [status/fixing, status/in-review, status/ready-to-merge]
    max: 3
  add-comment:
    max: 2
    # Relay-fired (ADR-0019): "*" targets the payload's explicit item_number —
    # the default "triggering" silently drops comments on workflow_dispatch
    # (04-plan's round comments were lost this way, 2026-10-20 #804).
    target: "*"
  create-issue:
    max: 2
    title-prefix: "[from-repair] "
    labels: [triage/from-agent]
---

# Fix loop (repair; consumes findings from 06)

The fixer session — separate prompt, separate budget, separate judgment
from the reviewer (ADR-0001: review finds, repair acts).

## Rule 0 — pre-push identity guard (identical text in 07-fix-loop and 14-rebase)

The push is this lane's only irreversible act, so it never rides on a guess
about whose branch it targets. Before ANY `push-to-pull-request-branch` call
in this run, derive the expected identity from your own context and assert it:

- **Assert (target identity — must hold, before `N` is derived).** `PR` = the
  PR whose branch you are about to push, `BR` = that PR's head branch. `PR`
  must be the PR **this run was dispatched for**: its number equals the
  context's pull-request-number (or, on an event trigger, the event's
  `pull_request.number`). `push_to_pull_request_branch` accepts an explicit
  `pull_request_number`, so without this assertion everything below is true
  by construction for whatever target you pick. A different target FAILS.
- **Derive.** With `PR` fixed, `N` is the story it delivers:
  - your context names a **pull-request-number** (the fix surrogate dispatches
    that way, ADR-0019 — the number is a PR, never an issue, #1398) → `N` =
    the story `PR` delivers: the issue segment in `BR` (`devloop/issue-<k>-`
    or `ai/issue-<k>-`), else the story its body names (`Fixes #N`, else its
    provenance issue number).
  - your context names an **issue-number** → `N` = that issue.
  - neither (an event trigger) → `N` = `BR`'s issue segment, else the story
    the PR body names.
- **Assert (primary — must hold).** `N` is the story `BR` really delivers:
  - `BR` carries an issue segment — `devloop/issue-<k>-` or `ai/issue-<k>-`
    (the delivery shapes the conveyor admits) → `k` must equal `N` exactly.
    A branch naming a different issue FAILS.
  - `BR` carries no issue segment → allowed ONLY for an owner hand-delivery
    (`gh pr view <PR> --json author` = the repository owner): `N` must then
    come from the PR's own naming (`Fixes #N` / provenance) and must not be a
    *different* story. An agent-shaped branch with no segment, or no named
    story at all, FAILS — no derivable identity.
- **Assert (secondary — confirms).** the PR resolves to the same story: its
  body carries `Fixes #<N>` (or names `<N>` in its title/provenance), or
  `gh pr view <PR> --json closingIssuesReferences` lists `<N>`. Implement PRs
  are not required to carry a closing keyword (#1366), so a missing closing
  reference alone is NOT a mismatch — a PR naming a *different* story is.
- **On mismatch: NO push call is made.** Post exactly one `add_comment` on
  issue `<N>` — the owner issue, never the PR; when `N` is not derivable
  there is no issue to stamp, so post it on the PR — carrying the mismatch
  evidence: this run's URL, your context's item number, the derived story
  `<N>`, the target PR `<PR>`, the target branch `<BR>`, and which assertion
  failed. Then stop loudly. That
  stamp is the only artifact: no state-label churn, no second target, no retry
  of the push. A bad-context run must be cheap to retry and trivial to audit —
  this abort stamp is what makes it both.

1. Re-derive findings from the current review comments + diff; a PR with
   a stale/empty diff aborts loudly (never pass on unverifiable state).
2. If this would be cycle 4 or later (count work-attempt stamps on the
   issue), stop: add `status/fixing-stuck` first, then remove
   `status/fixing`, `status/in-review` and `status/ready-to-merge` (add
   before remove, so a failed add is retryable and the item is never left
   stateless). Those three are the only state labels this lane may remove —
   a pass round's readiness label must not survive a needs-human
   escalation, or `merge_readiness()` can auto-merge a stranded PR.
   Leave `status/planned` / `status/queued` alone (clearing those disarms
   the sweep that fires this lane) and leave `status/conflict` to
   conflict_watch. One state label per item, including at escalation
   (#1405).
   Comment "needs human" with the stamp evidence, and do nothing else.
3. Otherwise: add `status/fixing`, fix **only** the findings (scope
   discipline: new discoveries go to `create-issue`, max 2, linked —
   never fixed inline), verify before requesting delivery, and request
   the push via `push-to-pull-request-branch` (max 3 per run) — the
   safe-output job performs the push; you yourself stay read-only, so
   even the fixer never holds a write token.
4. Remove `status/fixing` after the push; let re-review re-fire. (On a
   relay-fired run this removal resolves to the story issue and no-ops —
   `merge_readiness()` clears the PR's label once a pass verdict lands, so
   the state still ends clean. Emit it anyway: it is the intent.)

**No actionable findings?** The correct outcome is usually "nothing to fix"
— bot reports, status annotations and stale noise are data, not tasks. But
an empty exit is indistinguishable from a crashed run and generates a
`[aw] no safe outputs` diagnostic (observed on the merged-PR approve, 2026-10-14).
So: close with a work-attempt stamp, and as your final bash step emit
`safeoutputs noop '{"message":"zero findings — nothing to repair"}'`.
Never finish silently (delta 12 terminal-action guarantee).
