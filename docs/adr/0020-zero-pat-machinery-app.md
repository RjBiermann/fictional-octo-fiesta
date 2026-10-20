# Zero-PAT machinery; GITHUB_TOKEN-native relay, optional machinery App (ADR-0020)

Owner direction (2026-10-20): full automation, human effort only where a human
decision is genuinely needed — and no PAT secrets anywhere in the pipeline.

## Decided

- **No PAT secrets.** `AGENT_PAT` and `GH_AW_PROXY_PAT` are both retired.
  The pipeline is the gh-aw lane (pilot #568 retired the devloop loop on
  2026-10-14; `devloop.yml` is a dead file kept as history) plus plain-Actions
  support lanes (90-sweeper, 91-secret-guard, 95-orchestrator, load-check).
- **Delivery needs no PAT**: gh-aw safe outputs deliver agent work as git
  patches applied by the safe-output job under `GITHUB_TOKEN`
  (`create_pull_request` / `push_to_pull_request_branch`, contents: write).
  The ADR-0004 push-channel problem only ever existed for devloop.yml's
  direct `git push`; the gh-aw lanes never git-push. The one gap — a patch
  touching `.github/workflows/` fails to apply under `GITHUB_TOKEN` — is
  **unfixed by design for now** (workflow-touching agent work is rare and
  spec-gated); the native fix when needed is gh-aw's `github-app:` config
  (an App token with the **Workflows** permission can apply such patches).
- **Event-side is GITHUB_TOKEN-native** (ADR-0019): relays fire by
  `workflow_dispatch` (`aw_context` restores the target item), labels are
  display state. load-check joins the same pattern (ADR-0020): failure
  issues carry `ai-fix` + evidence under `GITHUB_TOKEN`, and the caller
  stamps `status/planned` + dispatches `05-implement.lock.yml` — the
  orchestrator's `_fire_implement` shape, create-only (a recurring failing
  set appends evidence, no re-fire, same semantics as the retired
  PAT-label-event fire).
- **Machinery App is optional, not required**: `95-orchestrator.yml` and
  `90-sweeper.yml` accept a machinery GitHub App token in the legacy
  `GH_AW_PROXY_PAT` env (minted per job via `actions/create-github-app-token`,
  guarded on `vars.MACHINERY_APP_ID`). It upgrades two PAT-era behaviors that
  GITHUB_TOKEN alone cannot do:
  - `approve_companion_runs` — approving `action_required` gh-aw runs;
  - sweeper label re-fires — App-actor label events can pass gh-aw
    activation (needs the App login in the relay targets' `on.bots`).
  Unset (App unregistered), both degrade exactly as ADR-0019's
  GITHUB_TOKEN-only path: approvals stay manual, re-fires are
  bookkeeping-only and the schedule sweeps heal instead.
- **Attribution stays structural** (ADR-0019): machinery never holds the
  owner's identity — GITHUB_TOKEN is `github-actions[bot]`; the optional App
  is a distinct machine actor whose content the secret-guard covers as
  bot-authored (its former owner-PAT blind spot is closed).

## Owner steps (human, optional — only to enable the App upgrades)

1. Register a repo-scoped GitHub App (Contents rw, Workflows rw, Issues rw,
   Pull requests rw, Actions rw); set `MACHINERY_APP_ID` (variable) and
   `MACHINERY_APP_KEY` (secret).
2. Add the App login (`<app>[bot]`) to the `on.bots` allowlist of relay
   targets that react to App-actor label events (`04-plan`, `05-implement`,
   `07-fix-loop`, `14-rebase`) and recompile (`gh aw compile`).
3. When an agent spec names `.github/workflows/`, adopt gh-aw's
   `github-app:` block in the lane's frontmatter so the patch can apply.

## Consequences

- Zero PAT secrets; the only non-human identities are `github-actions[bot]`
  (always) and the machinery App (if the owner registers it).
- Renaming the legacy `GH_AW_PROXY_PAT` env in orchestrator.py/sweeper is
  cosmetic follow-up — the name is a compat shim, not a dependency.
- `devloop.yml` deletion and the stale devloop sections in AGENTS.md /
  ADR-0011 are acknowledged drift; cleanup is a separate decision.
