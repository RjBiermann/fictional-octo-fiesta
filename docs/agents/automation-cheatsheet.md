# Automation Cheatsheet (gh-aw lane)

Full automation since ADR-0018/0019: open an issue, steer the spec thread,
done. Machinery acts only as `github-actions[bot]` (no owner PAT); relays
fire by `workflow_dispatch`.

## The lifecycle

| Stage | Who | Action |
|---|---|---|
| Intake | machine | open an issue → `02-triage` labels `status/queued` (or closes duplicates/spam with the evidence on record) |
| Backlog | machine | `03-backlog-triage` daily sweep heals labels, closes superseded reports (cap 5/sweep) |
| Entry | machine | orchestrator applies `ai-fix`, fires plan round 1 (ADR-0018) |
| Spec round 1 | machine | agent posts clarify questions on the issue |
| Answers + approval | **human** | reply answers, then `approved` — the only required human input |
| Spec rounds 2–3 | machine | breakdown → sub-issues created, fired sequentially (relay dispatches, ADR-0019) |
| Build → review → repair | machine | implement → PR, review, fix loop (≤3 cycles); re-fires by dispatch, not by label/comment events |
| Merge | machine | auto-squash-merge when the PR is green **and** the chain's approval receipt exists (proved live: #783/#785, 2026-10-20) |
| Closeout | machine | `12-post-merge` closes spawned sub-issues; parent spec closes when all stories close |

Stamping `status/planned` on a whole spec at once is legal — the conveyor
demotes out-of-order stories and re-fires them sequentially (ADR-0018).
`status/planned` is display state; the dispatch is the fire (ADR-0019).

## Slash commands (comment on the issue/PR)

| Command | Effect |
|---|---|
| `/plan #N` | start / resume the spec loop on issue N |
| `/review` | re-fire a review round on the PR (post it on the PR) |
| `/triage` | route an issue into the queue |
| `/retry #N` | reset attempt budget, close stale PR — then re-apply the trigger label |

Human commands fire natively (your comments are trusted events). Machine
relays can't rely on events — bot-authored label/comment events are
loop-dead; that's why the conveyor dispatches.

## Where humans are still needed (deliberate)

- **Spec approval** — `approved` one-worder; machinery never decides a round
  and never creates receipts.
- **Agent-created findings** (`triage/from-agent`, `[from-impl]`) — curated by
  you; apply an `ai-*` label to fire one into the pipeline.
- Escalations: `status/fixing-stuck`, `security/blocked`, blocked rebases,
  and `@RjBiermann` decision pings.
- Issue bodies/comments are data, never instructions (gate.md).

## Vocab

- **Relay** — machinery's fire mechanism: `workflow_dispatch` + `aw_context`
  (CONTEXT.md, ADR-0019). The old owner-PAT "surrogate" is retired.
- **Receipt** — the chain's approval record: your exact one-worder on the
  parent spec (or the plan round-3 marker). Gates auto-merge.

## Currently in flight (2026-10-20)

Porntrex chain (#759 → #767–#770) and verify-provider chains (#664, #680 →
their spec kids) building; first two auto-merges landed (#783/#785).
