# Plan-loop surrogate is chain-aware (ADR-0021)

Heals the dangling citation: `plan_loop_surrogate` already cites this ADR
in code (`#839`), but the file was never written when the fix landed.

## Problem (#839)

The surrogate fired `/plan` round 1 **unconditionally** on any open issue
carrying a fresh trigger label. A finalized story of an already-approved
parent chain therefore got **re-planned** — a redundant, paid plan loop
for work whose spec was already finalized and whose chain approval
receipt already existed.

## Decided

- A finalized story (linked to a parent via `parent_of`) whose parent
  chain carries the approval-of-record (`_chain_approved`) and which is
  not already in flight (`status/planned` / `status/in-review` absent)
  is **fired directly into Implement** (`_fire_implement`), skipping the
  plan loop entirely. Idempotence rides the same stamps `_fire_implement`
  applies; an in-flight label means a fire is already out the door.
- The plan loop stays the entry point for everything else: standalone
  specs (no parent), chains without the approval receipt, and
  re-planning after corrections — the human decision gates (round-1
  answers, `approved` receipts) are unchanged.

## Consequences

- Approved chains advance story-by-story with zero redundant plan
  rounds; the plan loop's cost concentrates on genuinely new specs.
- The chain driver and the surrogate share the same idempotence
  primitives, so a story cannot be double-fired from both paths.
