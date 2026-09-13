# FINDINGS-350 — [review #347] P0-3: devloop.yml job-level contents: write is broader than needed

## Probe (branch devloop/issue-350, HEAD d8a2aaf)

### Claim

`.github/workflows/devloop.yml` grants the single `run` job
`issues: write, pull-requests: write, contents: write`, and the agent
(`devloop once`/`command`/`merged`) runs with that write-scoped GH_TOKEN
plus full shell access.

Confirmed: one job, `permissions:` block at lines 18-21 with all three
`write` scopes. Less-privilege analysis depends on *what each credential
actually authenticates in this workflow*:

### How writes actually travel (evidence from devloop v0.3.7 source, installed via pip into site-packages/devloop)

All GitHub writes devloop performs go through one of two channels:

1. **`gh` CLI with `GH_TOKEN`** (=`GITHUB_TOKEN`) — every `_run(["gh", ...])`
   in `devloop/forge/github.py`:
   - `gh issue create / edit / comment / close` → **issues: write**
   - `gh pr create / comment / close / view / diff / list` →
     **pull-requests: write** (reads are covered by it too)
   - `gh api repos/.../issues/N/comments` (read) and
     `gh api .../collaborators/U/permission` (trigger authority) → read
2. **`git push`** — `start_work` (`git push -u / --force -u origin branch`),
   `commit_all` (`git push`), `rebase_branch`
   (`git push --force-with-lease`), and `gh pr close --delete-branch`
   (which deletes the remote branch via `git push origin :branch`).

Channel 2 never authenticates with `GITHUB_TOKEN` in this workflow: the
"PAT credentials (ADR-0004)" step unconditionally replaces the
`http.https://github.com/.extraheader` that `actions/checkout` installed,
so every fetch *and push* uses `AGENT_PAT`. There is no fallback code
path: if `AGENT_PAT` were unset the header would carry an invalid token
and pushes would fail regardless (the failure mode ADR-0004 documents).
So job-level `contents: write` on the `GITHUB_TOKEN` is **never used by
the pipeline's own push channel**.

What `GITHUB_TOKEN` IS used for besides `gh`/`gh api`:
- `actions/checkout` fetch → contents: **read**
- `pip install git+https://x-access-token:${{ secrets.GITHUB_TOKEN }}@github.com/RjBiermann/devloop@…`
  → contents: **read** (clone of the pinned devloop commit)

Reducing `contents: write` → `contents: read` therefore removes exactly
one reachable grant: the GITHUB_TOKEN's ability to create/update
repository content via the REST API from the agent's process tree. Git
push capability (the actual delivery path) stays with `AGENT_PAT`, whose
scopes are a maintainer-side secret decision (ADR-0004) — not a workflow
permission.

### Why documentation is part of the fix (issue ask)

The agent process tree holding a write token is by design — the issue
asks the workflow to *document why*. The pipeline is "agents never merge,
humans decide" (AGENTS.md; ADR-0004/0007), but delivery is
automated: devloop's own adapter calls `git push` (branch) and
`gh pr create` (PR) and `gh issue comment/close` (error tails, closeout,
spec sub-issues). A write-scoped GH_TOKEN for issues + PRs is what makes
those calls work; a read-only token would make every run dead-letter.
This belongs in an inline comment next to the permissions block, not a
new ADR (no decision of record is changed).

## Root cause

The permissions block was written as a convenience superset ("give the
job everything the pipeline might touch") rather than scoped to the two
distinct credential channels above.

## Fix (minimal, one file, one hunk)

`.github/workflows/devloop.yml`:

- `contents: write` → `contents: read`
- keep `issues: write`, `pull-requests: write`
- add a short comment block stating which channel uses which credential:
  GITHUB_TOKEN (issues + pull-requests write; contents read-only) for the
  `gh` channel, AGENT_PAT for the git push channel.

## Verification

- `actionlint` on all workflows (repo rule for `.github/**` changes):

  ```
  $ actionlint .github/workflows/*.yml
  # → no output, exit 0 (all workflows pass)
  ```

- No provider code touched → the verify-provider skill's live-site
  selector/stream checks do not apply; the relevant verification is
  workflow lint (above) plus CI compile/build checks at merge.
- Behavior check by inspection: every `gh` write call above is inside
  issues/pull-requests scopes; every `git push` rides the extraheader
  PAT, independent of GITHUB_TOKEN contents scope. A real workflow run
  requires push + secrets (maintainer-only), so CI on merge covers it.
- Version caveat (recorded in the workflow comment): the channel
  analysis is verified against devloop v0.3.7; a future tag that moves
  any write outside issues/pull-requests makes contents: read fail
  loudly mid-run — re-audit the permissions block when bumping the pin.
