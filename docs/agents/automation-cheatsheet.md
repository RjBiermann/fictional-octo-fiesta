# Automation Cheatsheet (gh-aw lane)

How to drive the pipeline. Two clicks + one word per story; everything else is the conveyor.

## The lifecycle

| Stage | Who | Action |
|---|---|---|
| Intake | machine | open an issue → `02-triage` labels `status/queued` |
| Start work | **human** | apply `ai-fix` / `ai-new-site` / `ai-remove-site` **or** comment `/plan #N` |
| Spec round 1 | machine | orchestrator fires `/plan` → agent posts clarify questions |
| Answers | **human** | reply in the thread, any format |
| Spec round 2 | machine | breakdown posted |
| Approval | **human** | reply `approved` (one word) |
| Spec round 3 | machine | sub-issues created + finalized spec on parent |
| Arm story | **human** | apply `status/planned` to each sub-issue to build now |
| Build | machine | `05-implement` → PR, `status/in-review` |
| Review | machine | `06-review` findings-only |
| Repair | machine | `07-fix-loop` acts on findings, ≤3 cycles |
| Merge | **human** | merge when `status/ready-to-merge` |
| Closeout | machine | `12-post-merge` closes spawned sub-issues |

## Slash commands (comment on the issue/PR)

| Command | Effect |
|---|---|
| `/plan #N` | start / resume the spec loop on issue N |
| `/review` | re-fire a review round on the PR (post it on the PR) |
| `/triage` | route an issue into the queue |
| `/retry #N` | human sanction: reset attempt budget, close stale PR — then re-apply the trigger label |

## Labels you control

- **Trigger labels** (`ai-fix`, `ai-new-site`, `ai-remove-site`): the only way work enters the pipeline. `status/queued` alone starts nothing.
- **`status/planned`**: human-only. Agent runs can never apply it.
- `status/fixing-stuck` / `security/blocked`: agents stop and wait for you.

## Mid-flight knobs

- `status/conflict` on a PR → `14-rebase` fires automatically. If it blocks: remove + re-add the label (20 min cooldown).
- Escalations land as `@RjBiermann` pings from the orchestrator (waiting rounds, stuck fixes, merge readiness).
- No agent ever merges, approves, applies trigger labels, or labels `status/planned`.

## Currently stalled (2026-10-19)

- **#759** — apply `ai-fix` (or `/plan #759`) to start the porntrex bug fix.
- **#631** — epic completed; back to bare `status/queued`. No action unless re-arming a new audit wave: answer `/plan` rounds again.
