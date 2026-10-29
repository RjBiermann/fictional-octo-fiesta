# Issue closeout on human-merged devloop PRs

> Amended 2026-10-29 (#1380): the merge that closes a story is no longer only a
> `devloop/issue-N` PR. A maintainer may hand-deliver on a `maint/…` branch; that
> merge is the same verdict when the PR names its story with a closing keyword
> (`Fixes #N`), because the machinery reads GitHub's `closingIssuesReferences`
> rather than parsing the body. The convention lives in `AGENTS.md`, the term in
> `CONTEXT.md` (*Delivery*), and the rule below applies unchanged — the carve-out
> is "a human merged the story's delivery", whichever branch carried it.

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
