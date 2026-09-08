# Command re-fires go through workflow_dispatch, never label re-apply

`/retry` re-fires the Builder/Task run by calling `gh workflow run` (workflow_dispatch)
on `ai-build.yml` / `ai-task.yml` with the issue number as input. It does **not**
remove-and-re-apply the trigger label: GitHub Actions never creates workflow runs for
events triggered with the repository's `GITHUB_TOKEN` (a loop-prevention rule; only
`workflow_dispatch` and `repository_dispatch` are exempt). The label re-apply variant
therefore succeeded visibly ("issue re-triggered") while firing nothing — a silent no-op,
which cost issue #169's completed Host-registry run (its tree was never pushed).

Rejected alternative: performing label events with a PAT or App token so the `labeled`
events do trigger workflows. It adds secret management and an over-privileged credential
for the same result dispatch already gives. Human-applied trigger labels (the `labeled`
path) keep working unchanged; `workflow_dispatch` inputs carry the issue number, and the
per-issue concurrency group (`${{ inputs.issue || github.event.issue.number }}`) preserves
the supersede semantics.
