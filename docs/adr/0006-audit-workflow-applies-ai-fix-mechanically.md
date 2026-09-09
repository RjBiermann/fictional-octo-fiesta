# Audit workflow applies `ai-fix` mechanically

AGENTS.md forbids any *agent* from applying trigger labels (`ai-fix` et al.) — a
guardrail against agents enlarging their own work. The Audit pipeline, however,
must turn its findings into fix runs without a human clicking `ai-fix` on each
issue, or "fix all gaps" needs N manual clicks per sweep.

Decided: the **workflow applies `ai-fix` in a mechanical shell step** after the
audit agent finishes writing its issues. The agent never touches trigger labels
— the letter of the rule holds; the human decision is the `workflow_dispatch`
itself, and the shell step is machinery, same as `deliver-pr`.

Considered and rejected:
- Non-trigger label (`audit-finding`) + human applies `ai-fix`: preserves the
  rule absolutely but reintroduces per-gap manual labor the pipeline exists to
  remove.
- Letting the audit agent apply `ai-fix` directly: simplest, but weakens the
  guardrail for every future agent run — rejected for that reason alone.

Consequence: a misfired audit dispatch can open fix issues and fire Builder
runs without further confirmation. Mitigation is upstream, not in-run: the
dispatch is manual, dedup rules skip providers with open issues/PRs, and
dead/Blocked sites get reports instead of Fix requests.

Post-merge correction (learned on the first live run): labeling with
`GITHUB_TOKEN` does not fire the Builder at all — GITHUB_TOKEN label events
never trigger other workflows (ADR-0003). The mechanical step now applies the
label for state *and* calls `gh workflow run ai-build.yml -f issue=N
-f kind=ai-fix` — the same dispatch path `/retry` uses — which is why the
workflow needs `actions: write`.
