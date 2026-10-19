# The conveyor automates an approved chain only

The orchestrator's promotion motors (`chain_driver`, `wave_driver`) only
ever stamp `status/planned` inside a spec structure a human approved:
a `/plan` chain that reached round 3, or an epic carrying the plan loop's
round-3 marker as its approval-of-record (`_chain_approved`, ADR-0014).
Two exact receipts — an owner one-worder or the round-3 marker. Prose is
never parsed.

Context: the wave rounds of 2026-10-16 (24/26 providers, plan rounds 5–7)
could not be recognized by today's exact-match `_chain_approved`, and the
epic itself (#631) carried no body linkage — so after the waves landed,
the queue went quiet with automation armed but gates unreachable. The
round-3 receipt closes that seam without authorizing anything the human
did not already approve in-thread.

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
