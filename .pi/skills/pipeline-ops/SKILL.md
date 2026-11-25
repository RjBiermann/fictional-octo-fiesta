---
name: pipeline-ops
description: Operate the gh-aw pipeline from an agent session — watch CI runs without polling, read run logs, handle a "merge it" request. Use before any gh pr merge/close/review-approve, and whenever waiting on a workflow run.
---

# Pipeline ops

## Delivery is not yours
Never run `gh pr merge`, `gh pr close`, `git merge`, `gh pr review --approve|--request-changes`,
`gh issue close`, or a label change (`gh issue edit --add-label`). No agent merges or closes.
Machinery does: it applies trigger labels and auto-squash-merges only behind the owner-approval
receipt (ADR-0018).

`.pi/extensions/deny-human-only.ts` blocks these forms (per shell segment, any flag order), but it
only sees literal commands: `$()`, backticks, and `gh api …/merge` still get through. Treat the
guard as a backstop, not the rule — if the user asks you to merge, name the conflicting rule and
stop.

## Wait without polling
`gh run watch <id> --exit-status`, or `gh event listen --resource …` (the hint gh itself prints).
Never `for i in $(seq 1 N); do …; sleep 30; done`. Download a run log once to a file and grep the
file — never re-fetch it.

## One investigator per question
Do not dispatch two agents (or two flavours of agent) on the same OBJECTIVE: a duplicated
investigation doubles cost and yields two unreconciled answers.

## Report once
End with: what changed, run/PR links, the next action. No play-by-play.
