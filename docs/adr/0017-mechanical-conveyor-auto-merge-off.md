# Mechanical conveyor; auto-merge stays off

> Amended 2026-10-20 by ADR-0018: `GH_AW_AUTO_MERGE` is now **true** —
> the receipt gate below makes the merge automatic only on chains whose
> owner approval is on record. Humans merge everything else.

The 95-orchestrator is a non-AI, fails-closed conveyor: it automates the
mechanics of an already-approved chain (arming stories, firing plan-loop
rounds, approving companion runs stuck in `action_required`, resolving
the two deterministic conflict classes, closing stale `[aw]` diagnostics)
and surfaces every real decision as an `@owner` ping. It never decides a
round and never touches `ai-*` trigger labels.

Merge contract: **humans merge**. `status/ready-to-merge` + a head-sha
ping is the terminal automated state. `GH_AW_AUTO_MERGE=true` may queue
auto-merge, but only when the chain's owner approval receipt exists on
the parent spec — and the owner decision (2026-10) is to keep it FALSE:
the receipt proves the *chain* was approved, not that *this diff* was
reviewed. Branch protection remains the real gate either way.

Surrogate relay (historical — superseded 2026-10-20 by ADR-0019):
surrogate fires used to act through the owner PAT (`GH_AW_PROXY_PAT`)
because both native fan-out paths were closed: events triggered by
`GITHUB_TOKEN` created no workflow runs (GitHub loop prevention), and
gh-aw's activation gate treated `github-actions[bot]`-actor events as
unprivileged. Observed 2026-10-16: 4 agent review verdicts via
GITHUB_TOKEN, zero fix-loop runs — the surrogate existed because of it.
ADR-0019 replaced the identity trick with the documented
`workflow_dispatch` exception plus `on.bots`; the constraint above is
what makes the dispatch relay load-bearing.

Consequence: `[orchestrator]`-marked comments are machinery-authored
(now `github-actions[bot]`; the owner identity left the machinery in
ADR-0019); the secret-guard's machine-authored test keys off that marker
(see CONTEXT.md) — every orchestrator post MUST carry it.
