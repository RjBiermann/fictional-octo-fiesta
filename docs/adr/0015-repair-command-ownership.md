# /repair belongs to the devloop lane

> **Superseded (2026-11).** The devloop lane is retired (ADR-0014) and no
> workflow registers a `/repair` command. Repairs happen through
> 06-review → 07-fix-loop; a human comment on the PR after a verdict
> re-fires 07 through the orchestrator's sweep when the verdict is
> unconsumed, which is why typing `/repair` still appears to work. The
> devloop half of this record is history, not current behaviour.

Two automation stacks serve this repo: the devloop pipeline (`devloop.yml`,
`config.toml` — `ai-*` trigger labels, its command path) and the gh-aw
pipeline (`status/*` state labels, `04`–`08` agent workflows, the
`95-orchestrator` conveyor). Both reacted to `/repair`: devloop's
`devloop command` step runs on every `/`-comment, and gh-aw's
`07-fix-loop` carried its own `slash_command: repair` trigger. One
comment could fire two independent repair sessions against the same PR —
double spend, racing pushes, conflicting verdicts.

Decided:

- The **devloop lane owns `/repair`** as a human-invoked command.
- gh-aw's `07-fix-loop` triggers **only** on `pull_request_review`
  (submitted) — it consumes findings produced by the review workflows
  (`06`, `06b`) and never re-fires from comments.

Consequence: repairs requested mid-stream ride the review round (a
`/review` command relays a review round through `06c` → `06-review` since
2026-11-21 — gh-aw cannot activate a slash command on a PR conversation
comment, #1412 — whose findings wake the fix loop) or
wait for the 90-sweeper's stuck-label re-fire. The gh-aw pipeline needs
no comment-driven repair path; the repair agent's authority is unchanged
(safe-output push caps, cycle-4 escalation to the human).
