# FINDINGS-436 — Retire the `ai-task` ghost trigger

Probe: repo grep + GitHub API (labels, issue timelines, workflow run 35171890274)
+ devloop upstream config.py and CHANGELOG.

## Probe results

1. **Upstream dropped it.** devloop `CHANGELOG.md` (v0.3.0): "Dropped the
   speculative `ai-task` trigger (v0.2.x leftover); trigger vocabulary is
   `ai-fix` / `ai-build` / `ai-remove`". `devloop/config.py`:
   `KINDS = ("fix", "new", "remove")`; `Labels` has exactly fix/new/remove;
   `kind_for()` maps only those three. `ai-task` cannot fire.
2. **This repo's config never mapped it.** `config.toml` `[labels]` maps
   fix → `ai-fix`, new → `ai-new-site`, remove → `ai-remove` (note: this repo
   renamed `new` to `ai-new-site`; no task key exists). An issue labeled only
   `ai-task` returns no kind → silent skip.
3. **Observed live no-op.** Run 35171890274 on issue #435
   (`labeled: ai-task` at 2026-09-17T01:47:09Z; label re-applied to `ai-fix`
   4 minutes later): event `issues`, started 01:47:13, completed 01:47:37,
   conclusion "success", zero shipped output — no branch, no PR, no spec.
   The pipeline ran and did nothing because no trigger kind matched.
4. **The label itself is already gone from GitHub.** `gh api …/labels/ai-task`
   → 404; not in `gh label list`. Remaining live issues carrying it: none
   (only closed #372/#364/#356/#347 in the historical filter list). Issue
   #435 was relabeled `ai-fix` and completed via the live trigger.
5. **Reviewer whack-a-mole around a dead trigger**: #364 (stale.yml exemption
   missing `ai-task`), #370 (AGENTS.md trigger list omits it), #385
   (AGENTS.md documents it) — each maintained vocabulary for a trigger the
   pipeline can never run.

## Remaining references in this repo (grep, excluding .pi/git and closed history)

| File | Line | Handling |
|---|---|---|
| `.github/workflows/stale.yml:32` | exempt-issue-labels | removed |
| `AGENTS.md:75` | parenthetical in trigger-label paragraph | removed, with one-line note that non-build work uses the same three triggers |
| `docs/agents/triage-labels.md:16` | trigger-label list | removed |
| `docs/adr/0003-command-refires-via-workflow-dispatch.md:4` | mentions historical `ai-build.yml` / `ai-task.yml` workflows | kept — ADR is closed history (that workflow set was removed); acceptance excludes closed history |

Apply noted inside the run: the label deletion step (scope item 1) found
nothing to delete — probe item 4.

## Out-of-scope drift observed (not touched)

- Commit 293e554 renamed the remove trigger in `config.toml` to `ai-remove`
  today, but `AGENTS.md`, `docs/agents/triage-labels.md`, and
  `stale.yml` (exempt list) still say `ai-remove-site`. Config is the
  source of truth (acceptance criterion); the three doc/exemption
  references need the same one-word follow-up — and the issue-scope text
  of #436 itself was written pre-rename, so the names above follow it.
