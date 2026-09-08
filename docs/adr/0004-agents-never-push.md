# Agents never push; a mechanical delivery action commits and opens PRs

Agent runs (Builder, Task, Reviewer) never authenticate to git themselves: the workflow's `deliver-pr` composite action commits the working tree as `github-actions[bot]` and creates/updates the PR. We decided this because `GITHUB_TOKEN` cannot be granted the `workflow` scope (so agent-pushed changes touching `.github/workflows/` are rejected), while giving agents a broad PAT is an audit and key-sprawl risk; a single mechanical step keeps provenance uniform (bot commits) and the token surface at one call site.

Consequence: an agent run's work lives only in the runner's tree until Delivery succeeds, so a delivery bug silently discards the entire run (issue #169 lost two full runs to a YAML typo in `deliver-pr`). Mitigated by the actionlint pre-flight in the shared agent-runtime action, which fails broken plumbing in seconds instead of after a full model run.
