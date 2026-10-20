# Machine-identity relay; owner PAT removed (ADR-0019)

Owner direction (2026-10-20): full automation with **no owner identity in
the machinery**. Supersedes the ADR-0017 surrogate mechanism (owner-PAT
comment/label/stub relays) while keeping its receipts.

## Decided

- **Machinery acts as `github-actions[bot]` only.** Every relay mutation
  (labels, comments, closes, merges) goes out under `GITHUB_TOKEN` with
  the scopes `95-orchestrator.yml` declares. `GH_AW_PROXY_PAT` is retired;
  no machinery holds the owner's identity — the attribution-collision
  class (ADR-0014 known limitation, e.g. the round-2 self-approval of
  2026-10-19) is closed structurally, not by marker filtering (the
  `[orchestrator]` marker filters stay as defense-in-depth).
- **Event relay = `workflow_dispatch`** — GitHub's documented exception to
  GITHUB_TOKEN loop prevention ("dispatch and repository_dispatch always
  create runs"). The conveyor never relies on bot-authored label/comment
  *events* (those are loop-dead); it fires:
  - `04-plan` rounds via `aw_context {"item_type":"issue", …}` — the
    compiled context restoration resolves the issue number natively;
  - `05-implement` via `_fire_implement` (stamp the label as display
    state + dispatch — the dispatch is the fire);
  - `07-fix-loop` via the fix surrogate (dispatch replaces the
    owner-actor stub `/repair` review, deduped per bot-verdict via the
    marker comment);
  - `14-rebase` (unchanged pattern, now gate-valid).
- **Machine trust is explicit in gh-aw**: target workflows declare
  `on.bots: ["github-actions[bot]"]` (mixed-trigger workflows run the
  membership check; the allowlist authorizes the bot actor) and
  `workflow_dispatch` (a "safe event" — dispatch-only paths skip the
  role check entirely).
- **What stays human**: chain-approval receipts (`approved` one-worder,
  exact match on the owner's comment — machinery never creates receipts),
  triage curation of agent-created findings, escalations, and merges
  beyond the receipt gate.
- **What stays plain Actions**: the 95 scan/decide logic — deterministic
  by ADR-0014; gh-aw has no agent-free mode (v0.89.21), and an LLM
  re-deriving loop state is the class the receipts forbid.

## Risks / mitigations

- Dispatch activation for a bot actor on *mixed-trigger* workflows is
  code-inferred (v0.89.21), not doc-guaranteed: first live verification
  watched the next orchestrator pass fire plan rounds and the dispatched
  runs reach the agent job. If a run denies at `pre_activation`, the fix
  is the already-declared `on.bots` entry (or making the target
  dispatch-only).
- The GITHUB_TOKEN lacks `workflow_dispatch`-triggering rights on *other*
  repositories — cross-repo relay stays impossible without a token
  (gh-aw `dispatch-repository` needs one); this repo's conveyor is
  same-repo, so nothing needs it.
