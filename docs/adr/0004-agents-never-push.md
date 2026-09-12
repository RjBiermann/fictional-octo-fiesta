# Agents never push; a mechanical delivery action commits and opens PRs

> **Historical machinery note:** the mechanism described below — the `deliver-pr`
> composite action (`.github/actions/deliver-pr/`) committing the working tree and
> opening PRs from Builder/Task/Reviewer workflow runs — belonged to the removed
> pre-devloop pipeline; no workflow invokes it today. The decision stands (agents
> never authenticate to git; a mechanical step delivers), but delivery is now
> performed by the devloop run itself (see AGENTS.md and
> `.github/workflows/devloop.yml`). Kept as the decision record.

Agent runs (Builder, Task, Reviewer) never authenticate to git themselves: the workflow's `deliver-pr` composite action commits the working tree as `github-actions[bot]` and creates/updates the PR. We decided this because `GITHUB_TOKEN` cannot be granted the `workflow` scope (so agent-pushed changes touching `.github/workflows/` are rejected), while giving agents a broad PAT is an audit and key-sprawl risk; a single mechanical step keeps provenance uniform (bot commits) and the token surface at one call site.

Consequence: an agent run's work lives only in the runner's tree until Delivery succeeds, so a delivery bug silently discards the entire run (issue #169 lost two full runs to a YAML typo in `deliver-pr`). Mitigations: the actionlint pre-flight in the shared agent-runtime action fails broken plumbing in seconds instead of after a full model run, and the Builder/Task workflows upload the working tree as a `tree-issue-<n>` artifact before Delivery — if Delivery still fails, the maintainer recovers by downloading the artifact and pushing/PR-ing manually instead of paying a full `/retry` model re-run.

Second constraint, also invisible in code: `GITHUB_TOKEN` (a GitHub App token) can never push changes touching `.github/workflows/` — the push is rejected regardless of workflow `permissions`. Delivery of workflow-touching changes requires the `AGENT_PAT` repo secret (fine-grained PAT on this repo, Contents + Workflows read/write); if it is unset the fallback silently degrades to `github.token` and the run's work is discarded at push time. Agents touch `.github/workflows/` only when the issue spec explicitly names it, so the ordinary provider run never needs the PAT.
