# FINDINGS — issue #361 (P1-8: orphaned composite actions)

## Probe

- `.github/actions/{agent-run,agent-runtime,deliver-pr}/action.yml` all exist
  (60 / 65 / 42 lines).
- Grep across the repo for `actions/agent-run|actions/agent-runtime|actions/deliver-pr`
  in all `*.yml`/`*.yaml` files: **0 references**. Only remaining callers were the
  removed Builder/Task/Reviewer workflows (`AGENTS.md`: "The old pipeline's
  Builder/Reviewer/Triage/Monitor workflows have been removed").
- `docs/adr/0004-agents-never-push.md` already carries a historical-banner blockquote
  (added in review #347 follow-ups) stating the `deliver-pr` action "belonged to the
  removed pre-devloop pipeline; no workflow invokes it today."
- `CONTEXT.md` historical-machinery note (top of file) already covers `deliver-pr`
  and the Model chain as pre-devloop vocabulary.
- P0-7 model-config divergence: `agent-runtime/action.yml` writes
  `"$OPENCODE_API_KEY"` unexpanded into models.json (heredoc unquoted-free string but
  no envsubst in that action) — divergent from `devloop.yml`, which writes a quoted
  heredoc plus `envsubst` (P0-7 fix). Moot once the action is deleted.

## Decision (per the issue's "decide" fork)

**Delete all three actions.** Rationale:
- Nothing invokes them; keeping dead token-handling code (`deliver-pr`) is an audit
  liability, and the stale model-config heredoc is a drift trap against devloop.yml.
- Wiring the actionlint pre-flight into `devloop.yml` is redundant: CI `lint.yml`
  already runs actionlint (reviewdog) on push, and per AGENTS.md agents run
  `actionlint` locally before pushing `.github/**` changes.
- ADR-0004's banner needs only a small amendment to record that the actions were
  deleted, not just orphaned.

## Change

1. `git rm -r .github/actions/` (all three composite actions).
2. `docs/adr/0004-agents-never-push.md`: banner updated to note the action files were
   deleted (the decision record stays; only the dead code is gone).

## Verification

- Repo-wide grep: 0 remaining references to the three actions.
- `actionlint` clean on all workflow files (local pre-flight per AGENTS.md).
- No Kotlin/provider code touched; no need to bump provider versions or run
  `verify.sh` (this is CI plumbing only, not a provider change).
