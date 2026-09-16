# CI agent toolchain: project-pinned pi packages, output filter, headless trust

Devloop CI installs `pi` into a fresh runner every run, so agent-side tooling
must be reproducible from the repo, not from a maintainer's `~/.pi`. We
commit `.pi/settings.json` pinning the behavior skills (`ponytail@v4.10.0`,
`caveman@v2.7.0` — both prompt-only, no code) and add `--approve` to the
pipeline argv: headless modes ignore project resources without a saved trust
decision, which silently disabled the repo's own `.pi/skills/` in every CI
run until now. `rtk` (v0.49.0, pinned release, checksum-verified) is installed
globally by the workflow and hooks pi's tool_call to filter bash output before
the agent reads it — probe and Gradle transcripts were the runs' largest token
sink and a direct contributor to the issue-#421 budget exhaustion.

## Considered options

- Vendoring skill files into `.pi/skills/` — rejected: forks the packages from
  upstream and forfeits `pi update` ref reconciliation.
- Caveman Cloud gateway — deferred: needs maintainer-provisioned secrets
  (gateway URL, Cave key); the skills give the token reduction without it.
- `caveman-compress` on AGENTS.md — rejected for now: rewrites the committed
  spec source every agent run reads; input-token gain not yet measured against
  the clarity cost.

## Consequences

- Caveman compresses run narration only; its own Boundaries rule (and this
  repo's evidence-first ADRs) keep PR bodies, issue text, and commits in
  normal prose.
- rtk must never filter away verification evidence (stream URLs, HTTP codes,
  headers). First CI build after adoption diffs verify output before/after;
  if evidence is filtered, rtk's config is tightened or the step is dropped.
- Project settings only load with `--approve` (or `defaultProjectTrust`);
  future argv changes must keep the flag or re-audit trust.
