# Queue auto-entry and receipt-gated auto-merge (ADR-0018)

Owner direction (2026-10-20): **full automation, human effort only where a
human decision is genuinely needed.** This partially supersedes ADR-0014's
Level-3 rejection and flips ADR-0017's auto-merge stance.

Decided:

- **Queue auto-entry** (orchestrator `queue_to_pipeline`): machinery applies
  `ai-fix` to every `status/queued` issue. The human decision is the triage
  that produced the queue label (`02-triage`) — after that, the spec loop
  starts without a per-issue click (ADR-0006 precedent: machinery, never an
  agent, applies trigger labels). Completed chains (round-3 marker present)
  are inert even with a trigger label, so old epics don't re-fire.
- **Excluded from auto-entry**: `triage/from-agent` issues (agent-created
  sub-work waits for human curation — the anti-"machinery enlarges agent
  work" guardrail now lives here instead of in the entry decision) and
  machinery report titles (`[status-report]`, `[aw]`, `[cost-report]`,
  `[backlog-report]`).
- **Mass stamps self-heal** (`_demote_out_of_order`): stamping
  `status/planned` on a whole spec's stories at once is now legal — the
  conveyor demotes out-of-order stories (label removed + the label-fired
  Implement run cancelled) and the chain driver re-stamps them sequentially
  as predecessors close.
- **Auto-merge ON** (`GH_AW_AUTO_MERGE=true`): a ready PR auto-squash-merges
  only when the chain's owner-approval receipt exists on the parent spec
  (the exact gate ADR-0017 already implemented). The remaining human
  decisions are: triage disposition, spec approval (`approved`), and
  anything agents escalate (`status/fixing-stuck`, `security/blocked`,
  `status/conflict` after blocked rebase).

Consequence: the pipeline's steady state is one issue → triaged once →
PR merged without further human action; the human reads and steers the
spec thread. Budget rails (per-run `max-ai-credits`, daily
`GH_AW_DEFAULT_MAX_DAILY_AI_CREDITS`, wave cap) bound the enlarged
machinery surface.
