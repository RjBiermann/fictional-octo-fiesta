# The conveyor automates an approved chain only

The orchestrator's promotion motors (`chain_driver`, `wave_driver`) only
ever stamp `status/planned` inside a spec structure a human approved:
a `/plan` chain that reached round 3, or an epic carrying the plan loop's
round-3 marker as its approval-of-record (`_chain_approved`, ADR-0014).
Two exact receipts — an owner one-worder or the round-3 marker. Prose is
never parsed.

Context (corrected per review finding 4317230085): the wave rounds of
2026-10-16 (24/26 providers, plan rounds 5–7) were NOT orphaned by the
exact-match `_chain_approved` — epic #631 already carried an owner
one-worder `approved` (2026-10-16T03:33:51Z) *before* the waves, so the
gate was satisfied throughout. The real post-wave stall is the **kid
inflow seam**: the wave consumed the plan loop's kids to exhaustion and
nothing generates fresh spec kids without a human `/plan`. The round-3
marker receipt (this ADR's code change) is future-proofing, not a
resurrection: it covers approval records that live only in prose or in
the plan thread (e.g. the marker posted as `github-actions[bot]`, the
workflow identity — owner-authored markers are unreachable by
construction, see the blocker on PR #760).

Decided:
- **Level 1**: arming an epic is an owner action in the UI (approval
  comment + body-linked kids). The conveyor never arms an epic.
- **Level 2**: round-3 marker accepted as approval-of-record; the wave
  cap (`GH_AW_ORCH_WAVE_CAP`) is adopter-threadable via repo vars.
- **Level 3 — rejected**: auto-promoting `status/queued` (or
  trigger-labeled) issues without an approved spec chain revives
  ADR-0006's "machinery enlarges agent work" class and was declined.
  Trigger and state labels stay human-decided; the conveyor moves only
  what the human already approved.

Known limitation (deferred): owner-actor label events are anonymous in
issue timelines — machinery using the PAT and the human web session share
one identity, so attribution of a stray stamp (observed 2026-10-19,
#707) depends on event-log reasoning, not a marker. The '[orchestrator]'
dedupe markers are the only attribution trail.

Consequence: closing out an epic's delivered kids is required before
re-arming — `wave_driver` stamps the lowest-numbered open unclaimed kids
and cannot tell "delivered but open" from "next in line"; the plan loop's
sub-issue creation (UNLABELED, body-linked) is the intended fresh-inflow
channel.
