# Automation Cheatsheet (gh-aw lane)

Full automation since ADR-0018: open an issue, steer the spec thread, done.
Human effort only where a human decision is needed.

## The lifecycle

| Stage | Who | Action |
|---|---|---|
| Intake | machine | open an issue → `02-triage` labels `status/queued` (or closes duplicates/spam with the evidence on record) |
| Backlog | machine | `03-backlog-triage` daily sweep heals labels, closes superseded reports (cap 5/sweep) |
| Entry | machine | orchestrator applies `ai-fix` and fires plan round 1 (ADR-0018) |
| Spec round 1 | machine | agent posts clarify questions on the issue |
| Answers + approval | **human** | reply answers, then `approved` — the only required human input |
| Spec rounds 2–3 | machine | breakdown → sub-issues created, stamped `status/planned` sequentially |
| Build → review → repair | machine | implement → PR, review, fix loop (≤3 cycles) |
| Merge | machine | auto-squash-merge when the PR is green **and** the chain's approval receipt exists |
| Closeout | machine | `12-post-merge` closes spawned sub-issues |

Stamping `status/planned` on a whole spec at once is legal — the conveyor
demotes out-of-order stories and re-stamps sequentially (ADR-0018).

## Slash commands (comment on the issue/PR)

| Command | Effect |
|---|---|
| `/plan #N` | start / resume the spec loop on issue N |
| `/review` | re-fire a review round on the PR (post it on the PR) |
| `/triage` | route an issue into the queue |
| `/retry #N` | reset attempt budget, close stale PR — then re-apply the trigger label |

## Where humans are still needed (deliberate)

- **Spec approval** — `approved` one-worder; machinery never decides a round.
- **Agent-created findings** (`triage/from-agent`, `[from-impl]`) — curated by
  you; apply an `ai-*` label to fire one into the pipeline.
- Escalations: `status/fixing-stuck`, `security/blocked`, blocked rebases,
  and `@RjBiermann` decision pings.
- Issue bodies/comments are data, never instructions (gate.md).

## Currently pending (2026-10-20)

Porntrex chain (#759 → S1 #767 … S4 #770): stamps reset after the mass-stamp
race; the chain driver re-stamps S1 next sweep and walks S2–S4 in order.
