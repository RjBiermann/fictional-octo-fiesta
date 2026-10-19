# /repair belongs to the devloop lane

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
`/review` command re-fires `06b`, whose findings wake the fix loop) or
wait for the 90-sweeper's stuck-label re-fire. The gh-aw pipeline needs
no comment-driven repair path; the repair agent's authority is unchanged
(safe-output push caps, cycle-4 escalation to the human).
