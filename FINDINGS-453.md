# FINDINGS-453 — fix pipeline

Issue: fixed-octo-fiesta#453 (`ai-fix`, verdict: **devloop issue, not a pipeline/repo issue** — answer to the issue's question)
Failure run: https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/35751526301/job/106826609112
Date: run timestamp 2026-09-22T16:03Z, probed 2026-09-23.

## What failed

Job `run`, step **"Run comment command"** (`devloop command`, exit 1). Trigger was an
`issue_comment` event — this run *never got to the build*; it is the comment-command branch of
`.github/workflows/devloop.yml` (line ~142).

Traceback from CI log:

```
File "/opt/hostedtoolcache/Python/3.12.14/x64/bin/devloop", line 6, in <module>
    sys.exit(main())
File ".../site-packages/devloop/cli.py", line 197, in main
    args.fn(args)
File ".../site-packages/devloop/cli.py", line 116, in cmd_command
    ev = _event(args)
NameError: name 'args' is not defined. Did you mean: '_args'?
```

Evidence: job log fetched with `gh run view --job 106826609112 --log` (attributed step
"Run comment command"). All earlier steps (`Install devloop (pinned)` etc.) succeeded.

## Root cause — in devloop, upstream

Installed pin: `pip install git+.../RjBiermann/devloop@v0.3.19` (workflow line ~81).

Reproduced locally against the same install:

```python
def cmd_command(_args: argparse.Namespace) -> None:
    ev = _event(args)   # ← NameError
```

Cross-checked every tag and `main` on `RjBiermann/devloop` via the GitHub contents API:

- `v0.3.19` (line 112) — bug present
- `v0.3.18` (line 89) — bug present
- `main/HEAD` — bug present; `_args` appears 8× as parameter, and `cmd_command` is the only
  function that still refers to bare `args` inside its body. `cmd_merged` correctly uses
  `_event(_args)` — so this is a rename miss in `cmd_command` only.

Consequence: every `issue_comment` event (any comment on any `ai-` labeled issue — not just
command comments) crashes with a traceback before devloop even reads the payload. There is
**no broken devloop version to re-pin to and no fixed version to upgrade to** — the bug is
plainly a one-line rename miss.

Repo workflow/config verified clean: the step is `run: devloop command` with `GH_TOKEN` set —
nothing repo-side to fix. `devloop merged` (pull_request path) uses `_event(_args)` and is fine.

## Upstream issue to file (needs human action)

Tried `gh issue create --repo RjBiermann/devloop` from this run: the CI token
(`github-actions[bot]`) is not authorized to create issues there —
`GraphQL: Resource not accessible by integration (createIssue)`. A human with access must file:

> **Title:** Bug: `cmd_command` calls `_event(args)` but the parameter is `_args` — NameError on every comment command
>
> Reproduced on v0.3.19 (installed from git tag) and confirmed still present on `main` in
> `devloop/cli.py`:
>
> ```python
> def cmd_command(_args: argparse.Namespace) -> None:
>     ev = _event(args)   # NameError: name 'args' is not defined
> ```
>
> The parameter was renamed to `_args` but the `_event(args)` call inside was not;
> `cmd_merged` directly below correctly uses `_event(_args)`, so this is a rename miss in
> `cmd_command` only.
>
> Impact: every issue_comment-triggered `devloop command` crashes with a traceback before
> reading the payload — no build runs. Observed in CI:
> https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/35751526301/job/106826609112
>
> Fix: `ev = _event(_args)` in `cmd_command`.

## Once upstream ships a fix

1. Tag it (e.g. `v0.3.20`).
2. Bump the install pin in `.github/workflows/devloop.yml` (`@v0.3.19` → new tag). Note
   AGENTS.md: agents touch `.github/workflows/` only when the issue spec names it, and the
   push needs `AGENT_PAT` (ADR-0004) — maintainer work.
3. Re-comment on the affected `ai-` issue to re-trigger the command run.

Nothing in this repo needed changing for #453; this file plus the upstream issue are the
entire deliverable.
