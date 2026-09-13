# FINDINGS-349 — Issue #349: [review #347] P0-2: deliver-pr token handling

## Probe (branch devloop/issue-349, HEAD d8a2aaf)

### Claim

`.github/actions/deliver-pr/action.yml` (a) embeds the delivery token in the
remote URL (`git remote set-url origin https://x-access-token:${GH_TOKEN}@...`,
lines 18–24 of the old file) and (b) silently falls back to `github.token`
(`GH_TOKEN: ${{ inputs.token || github.token }}`, line 22), so a run whose
diff touches `.github/workflows/` fails at push time and its work is
discarded — the failure ADR-0004 documents. The issue itself notes no
workflow invokes the action any more (P1-8) and asks to decide
delete-vs-fix first.

### Evidence gathered

- **The action no longer exists at HEAD.** `ls .github/actions/` → no such
  directory; the only `.github` subdirs are `ISSUE_TEMPLATE`, `workflows`,
  and `dependabot.yml`.
- **It was deleted — the "delete" branch of delete-vs-fix is already taken.**
  Commit `fe40e95` ("chore(ci): delete orphaned composite actions
  agent-run/agent-runtime/deliver-pr (issue #361 P1-8)", merged as PR #395)
  removed `.github/actions/deliver-pr/action.yml` (42 lines) along with
  `agent-run` and `agent-runtime`. Its message records: "No workflow
  references them (grep: 0 refs) — remnants of the removed pre-devloop
  Builder pipeline."
- **The deleted file matches the finding exactly.** `git show
  fe40e95^:.github/actions/deliver-pr/action.yml` confirms both defects:
  ```yaml
  env:
    GH_TOKEN: ${{ inputs.token || github.token }}   # silent fallback
  run: |
    git remote set-url origin "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
    git config --unset-all http.https://github.com/.extraheader || true
  ```
  Token in the URL (leaks into `git remote -v`, error output, core dumps of
  any child process echoing config) plus the exact silent-degradation
  ADR-0004 warns about.
- **No references remain that could resurrect it.** Repo-wide grep for
  `deliver-pr|x-access-token|AGENT_PAT` hits only:
  - `.github/workflows/devloop.yml` — the *current* delivery mechanism. Its
    push path does not reintroduce either defect: credentials go into
    `http.https://github.com/.extraheader` (base64 basic auth, overriding
    checkout's persisted header) rather than the remote URL, and an unset
    `AGENT_PAT` yields an invalid header that fails the push **loudly**
    (401/403) instead of silently degrading to `github.token`. This is the
    fail-fast behavior the finding's fix options asked for, already in place.
    One caveat, for accuracy: line 52 embeds a token in a URL
    (`pip install git+https://x-access-token:${{ secrets.GITHUB_TOKEN }}@github.com/RjBiermann/devloop@v0.3.7`)
    — the same *syntactic* anti-pattern P0-2 describes. It is a materially
    different case, and acceptable as-is: it is a read-only dependency fetch
    (installing devloop, not pushing), it uses the repo-scoped `GITHUB_TOKEN`
    rather than a PAT, it grants no push privilege, and the actual push path
    is the `.extraheader` above with loud failure on a missing `AGENT_PAT`.
    Noted here so the evidence does not overclaim; fixing it is out of scope
    for P0-2 and would require editing `.github/workflows/`, which AGENTS.md
    reserves for issue specs that name the file.
  - `docs/adr/0004-agents-never-push.md` — banner (amended by fe40e95)
    records the deletion and states delivery is now performed by the devloop
    run itself; the historical body correctly describes the old mechanism as
    removed.
  - `docs/adr/0006`, `CONTEXT.md`, `AGENTS.md`, `FINDINGS.md` — prose
    references to the historical machinery only.
  - `.github/workflows/lint.yml:14` — `paths: ['.github/workflows/**',
    '.github/actions/**']`. The `.github/actions/**` entry now matches
    nothing (directory deleted). Harmless dead filter; left untouched (the
    finding is about token handling, not lint paths, and AGENTS.md restricts
    workflow edits to issue specs that name them).
- **Verify skill applicability:** `.pi/skills/verify-provider/scripts/verify.sh`
  performs live-site checks for a CloudStream provider (search/homepage/
  stream selectors). This issue is pipeline plumbing with no provider code
  touched — the skill's bar does not apply; the verification here is the
  grep/git evidence above plus the actionlint gate that lint.yml already
  runs on CI changes (none made).

### Verdict

Resolved upstream — nothing to fix. Both defects P0-2 describes were real in
the deleted file, but the delete-vs-fix decision was already made and merged
as deletion in issue #361 (P1-8, commit `fe40e95`, PR #395). The replacement
delivery path in `devloop.yml` avoids both problems on the push route:
header-based credentials and loud auth failure on a missing `AGENT_PAT`
(see the line-52 caveat in Evidence — a read-only `GITHUB_TOKEN` pip fetch,
not the push path P0-2 targets). ADR-0004's banner already
documents the deletion. Any further change here would be re-fixing a file
that does not exist.

### Resolution

No code change. Evidence recorded in this file; branch `devloop/issue-349`
committed for human review. Recommend closing #349 as already-resolved-by-#361.
