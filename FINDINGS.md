# FINDINGS — issue #368 (review #347 P2-1): ADR-0004/ADR-0006 describe removed machinery

## Probe (reality check)

- **ADR-0004** (`docs/adr/0004-agents-never-push.md`) names `deliver-pr` as the live
  delivery mechanism ("the workflow's `deliver-pr` composite action commits the working
  tree as `github-actions[bot]`"). Reality: the composite action still exists at
  `.github/actions/deliver-pr/action.yml`, but **no workflow invokes it** —
  `grep "uses: ./.github/actions"` across `.github/workflows/*.yml` exits 1. The only
  agent workflow is `devloop.yml`, which delivers via the devloop run itself (PAT
  credentials step, `devloop once`; commits are authored `github-actions[bot]` —
  `git log --format='%an'`).
- **ADR-0006** (`docs/adr/0006-audit-workflow-applies-ai-fix-mechanically.md`) describes
  an **`ai-build.yml`** workflow, an Audit pipeline creating Fix requests, and a Builder
  dispatch (`gh workflow run ai-build.yml -f issue=N -f kind=ai-fix`, `actions: write`).
  Reality: `.github/workflows/` contains only `build.yml`, `codeql.yml`, `devloop.yml`,
  `lint.yml`, `stale.yml` — no `ai-build.yml`, no Audit/Builder dispatch machinery.
- Both ADRs postdate the devloop migration in intent but describe the *pre-devloop*
  runtime; the same staleness was already fixed for CONTEXT.md via the
  **"Historical machinery note"** banner at `CONTEXT.md:5-11` (added by the devloop
  migration commit). Review #347 (P2-1, commit `df4ea76`) cites exactly this pattern
  as the fix shape: "ADRs need a status banner (like CONTEXT.md:5-11 got) rather than
  silent staleness."
- AGENTS.md:45 and `.github/actions/agent-runtime/action.yml:11` also still reference
  ADR-0004 / `deliver-pr`; the AGENTS.md reference (AGENT_PAT / GITHUB_TOKEN can't push
  workflow files) is **still true** — `devloop.yml:67` implements it — so AGENTS.md
  needed no change. The agent-runtime comment references a dead path but is a code
  comment, out of this finding's docs scope.

## Fix (minimal)

Docs-only, decision records kept intact; one banner each, mirroring CONTEXT.md's
pattern (blockquote directly under the title, before the body):

1. `docs/adr/0004-agents-never-push.md` — banner: the `deliver-pr` composite action and
   the Builder/Task/Reviewer workflow runs that called it are removed pre-devloop
   machinery; the decision stands (agents never authenticate to git; a mechanical step
   delivers), delivery is now the devloop run itself.
2. `docs/adr/0006-audit-workflow-applies-ai-fix-mechanically.md` — banner: `ai-build.yml`,
   the Audit pipeline, and the Builder dispatch are removed pre-devloop machinery; the
   decision stands (machinery, never an agent, applies trigger labels), not currently wired.

## Verification

- No provider code, Gradle, or workflow files touched — `verify-provider`'s live-site
  `verify.sh` does not apply (no provider change; equivalent to the docs-only scope of
  the P2-5/P2-3 fixes).
- Mechanical checks run: both files parse as expected (banner is plain Markdown
  blockquote — no YAML/frontmatter to lint); `git diff --stat` shows exactly the two
  ADR files + FINDINGS.md; each banner's factual claims re-checked against the probe
  above (no `uses:` of composite actions; no `ai-build.yml` in `.github/workflows/`;
  `devloop.yml` PAT step present at line 67).
- Cross-reference sanity: AGENTS.md's ADR-0004 mention still resolves to a true
  statement (PAT requirement), so no contradiction introduced.
