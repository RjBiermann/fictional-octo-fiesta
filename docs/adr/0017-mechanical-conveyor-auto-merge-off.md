# Mechanical conveyor; auto-merge stays off

> Amended 2026-10-20 by ADR-0018: `GH_AW_AUTO_MERGE` is now **true** —
> the receipt gate below makes the merge automatic only on chains whose
> owner approval is on record. Humans merge everything else.

> Amended 2026-11-28 (#1676): the two resolution layers use different push
> transports. The mechanical resolver commits a merge of `origin/main` and
> pushes it with plain `git push` under `GITHUB_TOKEN` — unaffected by
> signing. The AI fallback (`14-rebase`) resolves the same way (`git merge`,
> review history intact, its rule 2), so its push runs with
> `signed-commits: false`: gh-aw's signed path is GitHub's
> `createCommitOnBranch` GraphQL mutation, which cannot represent a merge
> commit, and every signed rebase push failed `ERR_VALIDATION` while the PR
> stayed conflicted. `main` requires no signatures (`required_signatures`
> disabled), so unsigned machinery commits are accepted; the human merge is
> still the only route into `main`.

The 95-orchestrator is a non-AI, fails-closed conveyor: it automates the
mechanics of an already-approved chain (arming stories, firing plan-loop
rounds, approving companion runs stuck in `action_required`, resolving
the two deterministic conflict classes, closing stale `[aw]` diagnostics)
and surfaces every real decision as an `@owner` ping. It never decides a
round and never touches `ai-*` trigger labels.

Merge contract: **humans merge**. `status/ready-to-merge` + a head-sha
ping is the terminal automated state. `GH_AW_AUTO_MERGE=true` may queue
auto-merge, but only when the chain's owner approval receipt exists on
the parent spec. The 2026-10 decision recorded here — keep it FALSE — was
amended 2026-10-20 (ADR-0018): the repo variable is `true`, and the
receipt gate, not the flag, is the human decision. Branch protection
remains the real gate either way.

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
