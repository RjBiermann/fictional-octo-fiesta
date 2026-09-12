# Issue closeout on human-merged devloop PRs

A guardrail in this pipeline is "an agent never closes an issue it was
spawned from". v0.3.3 adds a carve-out with the same precedent as
ADR-0003's close-on-retry: when a human merges a `devloop/issue-N` PR,
the merge IS the human's verdict that the work is done — devloop merely
executes it, posting the `devloop PR merged` ledger entry and closing
the issue via `devloop merged` (fired only from a real forge
`pull_request: closed` merge event, YAML-gated to `devloop/` branches,
never from agent output). Without it, the sweep reopens ghost issues
whose PR already merged.

Considered alternative: leave issues open for the human to close —
rejected because the sweep's reopen logic then resurrects merged work
as pending, which is worse than the narrow, event-gated close.
