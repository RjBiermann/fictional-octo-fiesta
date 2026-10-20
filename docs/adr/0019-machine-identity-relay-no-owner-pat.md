# Machine-identity relay; owner PAT removed (ADR-0019)

Owner direction (2026-10-20): **full automation, human effort only where a
human decision is genuinely needed — and no machinery acting as the owner.**

## Decided

- **Machinery identity is `github-actions[bot]`** — every relay mutation
  (labels, comments, closes, merges) goes out under `GITHUB_TOKEN` with
  the scopes `95-orchestrator.yml` declares. `GH_AW_PROXY_PAT` is retired:
  no machinery holds the owner's identity, and the attribution-collision
  class (ADR-0014 known limitation — e.g. the plan round-2 self-approval of
  2026-10-19) is closed structurally. The `[orchestrator]` marker filters
  stay as defense-in-depth.
- **Event relay = `workflow_dispatch`** — GitHub's documented exception to
  GITHUB_TOKEN loop prevention ("workflow_dispatch and repository_dispatch
  events always create workflow runs"). The conveyor never relies on
  bot-authored label/comment *events* (those are loop-dead); it fires:
  - `04-plan` rounds via `aw_context {"item_type":"issue", …}` — gh-aw
    v0.89.21's compiled context restoration resolves the issue number
    natively (same shape `conflict_watch` already used for `14-rebase`);
  - `05-implement` via `_fire_implement`: stamp `status/planned` as
    *display state* + dispatch — the dispatch is the fire;
  - `07-fix-loop` via the fix surrogate: dispatch replaces the
    owner-actor stub `/repair` review; dedupe is the marker comment on
    the issue thread, per bot verdict.
- **Machine trust is explicit in gh-aw**: target workflows declare
  `on.bots: ["github-actions[bot]"]` (mixed-trigger workflows run the
  membership check; the allowlist authorizes the bot actor —
  `GH_AW_ALLOWED_BOTS` in the compiled gate) and `workflow_dispatch`
  (a "safe event" — dispatch-only paths skip the role check entirely).
- **What stays human**: chain-approval receipts (`approved` one-worder —
  exact match on the owner's comment; machinery never creates receipts),
  triage curation of agent-created findings, escalations, and merges
  beyond the receipt gate.
- **What stays plain Actions**: the 95 scan/decide logic — deterministic
  per ADR-0014; gh-aw has no agent-free mode (v0.89.21), and an LLM
  re-deriving loop state per comment is exactly the deciding the receipts
  forbid.

## Verified live (2026-10-20, run 39365507289)

A GITHUB_TOKEN dispatch of `05-implement` (bot actor, mixed triggers,
`on.bots` declared) passed `pre_activation` + `activation` and ran the
agent job end-to-end — the relay mechanism is proven on real work, not
inferred. `04-plan`/`07-fix-loop` share the identical trigger shape.

## Risks

- Dispatch activation for a bot actor on mixed-trigger workflows is
  code-inferred in gh-aw docs; the `on.bots` entry is the documented fix
  and is declared on every relay target.
- GITHUB_TOKEN cannot trigger workflows in *other* repositories
  (gh-aw `dispatch-repository` needs a real token). This conveyor is
  same-repo only; cross-repo relay would reintroduce a token decision.
