# FINDINGS-453 — "fix pipeline": devloop `command` crash

Issue reports the failure of
[`devloop` run 35751526301](https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/35751526301/job/106826609112),
triggered by an `issue_comment` event (a `/`-command comment on an issue), and asks
which side is broken: this repo's workflow or devloop.

## Verdict

**Devloop issue, not a workflow issue. One-character bug: `cmd_command` calls
`_event(args)` but its parameter is named `_args` (NameError). No fix exists at any
published devloop tag (verified v0.3.5–v0.3.19); `devloop merged` is fine. Fix is
`_event(_args)` in `devloop/cli.py` line 116 plus a regression test covering the
`main()` → `command` path. No changes to this repo.**

## Evidence

### 1. The failing step and its signature

The failed job ran only the `Run comment command` step
(`devloop command`, step `if: github.event_name == 'issue_comment'`), skipting
`Run pipeline` and `Close out merged devloop PR's issue` per the same condition —
so the run did exactly what it should have: picked the comment-command entrypoint
and executed it. The step died 90 ms after start:

```
Traceback (most recent call last):
  File ".../devloop/cli.py", line 197, in main
    args.fn(args)
  File ".../devloop/cli.py", line 116, in cmd_command
    ev = _event(args)
NameError: name 'args' is not defined. Did you mean: '_args'?
```

### 2. Root cause in devloop's pinned tag (v0.3.19, commit 7c1509f)

`devloop/cli.py` (local clone of RjBiermann/devloop @ v0.3.19, well before HEAD):

```python
def cmd_command(_args: argparse.Namespace) -> None:
    ...
    ev = _event(args)        # ← line 116: `args` is not defined here
```

`cmd_merged` does the same lookup correctly (`ev = _event(_args)`); `cmd_command`
kept the bare name from the pre-refactor code.

### 3. Regression provenance

`git show v0.3.9:devloop/cli.py` — `cmd_command` reads
`path = _args.event or os.environ.get("GITHUB_EVENT_PATH","")` and never references a
bare `args`, so it works at v0.3.9.

Commit **f69ddcc** ("refactor(cli): one read_event() bracket for the CI
entrypoints") replaced that inline block with `ev = _event(args)` in both
`cmd_command` and `cmd_merged` but only renamed `cmd_merged`'s parameter to match.
`git log f69ddcc -- devloop/cli.py` confirms `cmd_command`'s diff hunk kept its
`_args` signature. Every tag ≥ v0.3.10 carries a broken `devloop command`.

### 4. Upstream fix status

- `git ls-remote --tags origin`: latest tag **v0.3.19** (already pinned here) — no
  fix release.
- Master (`7c1509f`) still has `cmd_command(_args)` calling `_event(args)` — the
  regression is live at HEAD.
- Open PRs #1 and #2 branch from `6c3803e`, which predates the f69ddcc refactor —
  neither contains a fix.

### 5. Point repro (v0.3.19, `pip install` from the same pinned tag)

```bash
echo '{"comment":{"body":"/review","user":{"login":"x"}},"issue":{"number":1}}' > ev.json
GITHUB_EVENT_PATH=ev.json devloop command
# → NameError, exit code 1 — same crash CI hits
```

## What this repo's workflow got right

`devloop.yml`'s `if` correctly routed a `/`-payload comment to the
`comment command` step. The workflow itself behaved exactly as designed; nothing to
fix here.

## Upstream follow-up

Not filed — tried `gh issue create -R RjBiermann/devloop`, got `GraphQL: Resource
not accessible by integration` (this run's `GITHUB_TOKEN` is the
fictional-octo-fiesta app installation and has no access to the devloop repo).
Maintainer should file it; ready-to-paste body:

---

**Title:** `v0.3.19: devloop command crashes with NameError (cmd_command calls _event(args), arg is _args)`

**Body:**

`cli.py` line 116 in `cmd_command` calls `_event(args)` where the parameter is
named `_args` — NameError on every `issue_comment`-triggered run. Introduced in
f69ddcc ("refactor(cli): one read_event() bracket for the CI entrypoints"), which
rewrote `cmd_command`/`cmd_merged` to the new `_event(args)` helper but only
renamed `cmd_merged`'s parameter; every tag ≥ v0.3.10 carries it. Master (7c1509f)
still has it. Repro:

    echo '{"comment":{"body":"/review","user":{"login":"x"}},"issue":{"number":1}}' > ev.json
    GITHUB_EVENT_PATH=ev.json devloop command
    # → NameError: name 'args' is not defined. Did you mean: '_args'?

Fix: `cmd_command` → `ev = _event(_args)`; add a regression test driving `main()`
to `command` with a synthetic `GITHUB_EVENT_PATH` (none exists today). Context:
https://github.com/RjBiermann/fictional-octo-fiesta/issues/453

## What shipped

Nothing repo-side: this is a devloop bug. The fix is one edit + one test in the
devloop repo (`devloop/cli.py`, `cmd_command`: `_event(args)` → `_event(_args)`), to
be released as a new tag and pinned by the workflow's
`Install devloop (pinned)` step.
