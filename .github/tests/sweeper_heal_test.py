#!/usr/bin/env python3
"""Offline coverage for 90-sweeper's heal loop — the #1609 PR-hold gate.

The heal loop is an inline `python3 - <<'EOF'` block inside
`.github/workflows/90-sweeper.yml`, so nothing in CI executed it: every
regression class in that file (#880 re-fire by dispatch, #913 silent-empty
ledger caps, #1609 PR-hold) was validated by hand. This harness extracts the
step verbatim from the YAML, runs it as a subprocess in a temp cwd, and asserts
on a stub `gh`'s call log plus the script's stdout.

The gate: an idle story whose deliverable PR carries `status/in-review` or
`status/ready-to-merge` is waiting on a human merge, not stalled — the sweeper
must not roll its label, comment, or re-dispatch `05-implement`.

Python-stdlib unittest only, no network: every `gh` call is a stub that records
its argv and the identity (`GH_TOKEN`) it was invoked with. The stub answers the
pre-pass with a raw `pullRequests` page and applies the workflow's own `--jq`
filter with real jq, so the production label filter — not a pre-filtered fixture
that would agree with a broken one — decides which PRs reach the heal loop.

Run: python3 -m unittest discover -s .github/tests -p 'sweeper_heal_test.py'
"""

import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
WORKFLOW = os.path.join(_HERE, os.pardir, "workflows", "90-sweeper.yml")
STEP_NAME = "Parse ledger + heal stuck labels"

STUB_GH = """#!/usr/bin/env bash
# Records argv + the identity the sweeper handed us, then answers like the real CLI: for
# `api graphql` it emits a raw page and applies the *workflow's own* --jq filter with real
# jq, so the production label filter is what this harness exercises, not a pre-filtered
# fixture that could agree with a broken filter.
echo "TOKEN=${GH_TOKEN:-none}|$*" >> "$STUB_CALLS"
case "$1 $2" in
  "api graphql")
    [ -n "${STUB_GRAPHQL_FAIL:-}" ] && { echo "gh: graphql unavailable" >&2; exit 1; }
    jqf=""; prev=""
    for a in "$@"; do [ "$prev" = "--jq" ] && jqf="$a"; prev="$a"; done
    jq -r "$jqf" < "$STUB_PR_PAGE"; exit 0 ;;
  "api repos"*)  exit 0 ;;
  *)             exit 0 ;;
esac
"""


def setUpModule():
    # The stub applies the workflow's own `--jq` filter with real jq (preinstalled on
    # GitHub's ubuntu-latest runners). Fail loudly rather than skipping: without jq this
    # suite would silently stop covering the production label filter.
    if not shutil.which("jq"):
        raise RuntimeError("jq is required: the stub applies the sweeper's own --jq filter")


def sweeper_script():
    """The heal step's python, verbatim, de-indented out of the YAML block."""
    with open(WORKFLOW, encoding="utf-8") as fh:
        text = fh.read()
    step = text.split(f"- name: {STEP_NAME}", 1)
    if len(step) != 2:
        raise AssertionError(f"step {STEP_NAME!r} not found in {WORKFLOW}")
    body = step[1].split("\n")
    start = next(i for i, line in enumerate(body) if "python3 - <<'EOF'" in line)
    out = []
    for line in body[start + 1:]:
        if line.strip() == "EOF":
            return "\n".join(out) + "\n"
        out.append(re.sub(r"^ {10}", "", line))
    raise AssertionError("unterminated python heredoc in the heal step")


class Sweep:
    """One run of the extracted heal loop; `calls` is the stub's call log."""

    def __init__(self, issues, prs=(), graphql_fail=False, app_token=None):
        self.dir = tempfile.mkdtemp(prefix="sweeper-")
        bindir = os.path.join(self.dir, "bin")
        os.makedirs(bindir)
        stub = os.path.join(bindir, "gh")
        # Created owner-read/execute from the start: nothing is ever widened afterwards,
        # and the harness only executes a file it just wrote inside its own tempdir.
        with os.fdopen(os.open(stub, os.O_CREAT | os.O_WRONLY | os.O_TRUNC, 0o500),
                       "w", encoding="utf-8") as fh:
            fh.write(STUB_GH)
        with open(os.path.join(self.dir, "issues.json"), "w", encoding="utf-8") as fh:
            json.dump(issues, fh)
        page = os.path.join(self.dir, "pr-page.json")
        with open(page, "w", encoding="utf-8") as fh:
            fh.write(pr_page(*prs))
        self.calls_path = os.path.join(self.dir, "calls.log")

        env = {k: v for k, v in os.environ.items() if k not in ("GH_TOKEN", "GH_AW_PROXY_PAT")}
        env.update({
            "PATH": bindir + os.pathsep + os.environ.get("PATH", ""),
            "REPO": "test/repo",
            "STUCK_HOURS": "12",
            "DAILY_ATTEMPT_CAP": "10",
            "MAX_ATTEMPTS": "20",
            "BUDGET_PREFIX": "gh-aw budget:",
            "GH_TOKEN": "ghs_placeholder",
            "GITHUB_TOKEN": "gh-token",
            "STUB_CALLS": self.calls_path,
            "STUB_PR_PAGE": page,
        })
        if app_token:
            env["GH_AW_PROXY_PAT"] = app_token
        if graphql_fail:
            env["STUB_GRAPHQL_FAIL"] = "1"
        script = os.path.join(self.dir, "sweep.py")
        with open(script, "w", encoding="utf-8") as fh:
            fh.write(sweeper_script())
        # argv is a list with shell=False, and both paths are ones this harness just
        # wrote into its own tempdir (interpreter = the running python): no input-controlled
        # command execution. The stub `gh` is reached only through env["PATH"].
        # List form, static interpreter literal, shell=False; `script` is a path this
        # harness just wrote into its own tempdir — never user input. The rule that wants
        # a fully static command cannot hold for a harness spawning a generated fixture.
        proc = subprocess.run(["python3", script], cwd=self.dir, env=env,  # nosemgrep
                              capture_output=True, text=True, shell=False)
        self.rc = proc.returncode
        self.out = proc.stdout + proc.stderr
        self.calls = []
        if os.path.exists(self.calls_path):
            with open(self.calls_path, encoding="utf-8") as fh:
                self.calls = [line for line in fh.read().splitlines() if line]

    def mutations(self, issue):
        """Label writes, comments and dispatches the sweeper made for `issue`."""
        hits = [c for c in self.calls
                if re.search(rf"\b(issue edit|issue comment) {issue}\b", c)
                or ("workflow run" in c and f'"item_number":{issue}' in c)]
        return hits


def issue(number, label, updated="2026-11-20T00:00:00Z"):
    return {"number": number, "labels": [{"name": label}], "updated_at": updated}


def pr_page(*prs):
    """A raw `pullRequests(states:OPEN)` page — what `gh api graphql` hands to `--jq`."""
    return json.dumps({"data": {"repository": {"pullRequests": {
        "pageInfo": {"hasNextPage": False, "endCursor": None},
        "nodes": [{"number": pr, "labels": {"nodes": [{"name": label}]},
                   "closingIssuesReferences": {"nodes": [
                       {"number": ref, "repository": {"nameWithOwner": owner}}]}}
                  for pr, ref, label, owner in prs]}}}})


def pr(number, ref, label="status/ready-to-merge", owner="test/repo"):
    return (number, ref, label, owner)


class HealGate(unittest.TestCase):
    def test_in_review_pr_holds_the_story(self):
        run = Sweep([issue(1458, "status/in-review"), issue(900, "status/in-review")],
                    [pr(1585, 1458)])
        self.assertEqual(run.rc, 0, run.out)
        self.assertEqual(run.mutations(1458), [], "held story must not be touched")
        self.assertIn("deliverable PR #1585", run.out)
        self.assertEqual(len(run.mutations(900)), 3, "unheld story still heals")

    def test_ready_to_merge_pr_holds_the_story(self):
        run = Sweep([issue(1458, "status/ready-to-merge")], [pr(1585, 1458)])
        # The skip line too: the heal loop catches per-issue exceptions, so "no mutations"
        # alone would also pass if the held story crashed instead of being held.
        self.assertEqual(run.rc, 0, run.out)
        self.assertIn("deliverable PR #1585", run.out)
        self.assertEqual(run.mutations(1458), [])

    def test_planned_story_with_held_pr_is_not_dispatched(self):
        held = Sweep([issue(1458, "status/planned")], [pr(1585, 1458)])
        # Exit status and side effects in one assertion: a crashed sweep must not read as
        # "correctly held", and "held" means no label write, comment or 05-implement dispatch.
        self.assertEqual((held.rc, held.mutations(1458)), (0, []), held.out)
        self.assertIn("deliverable PR #1585", held.out)
        idle = Sweep([issue(901, "status/planned")])
        self.assertEqual(idle.rc, 0, idle.out)
        self.assertEqual(len([c for c in idle.calls if "05-implement" in c]), 1,
                         "an unheld planned story still re-arms")

    def test_pr_without_a_hold_label_does_not_suppress(self):
        # Exercises the workflow's own --jq filter: a PR that closes the story but carries
        # neither hold label must not reach the heal loop as "held".
        run = Sweep([issue(1458, "status/in-review")], [pr(1585, 1458, label="status/fixing")])
        self.assertEqual(run.rc, 0, run.out)
        self.assertEqual(len(run.mutations(1458)), 3)

    def test_story_without_a_pr_heals_as_before(self):
        run = Sweep([issue(900, "status/in-review")])
        self.assertEqual(run.rc, 0, run.out)
        self.assertEqual(len(run.mutations(900)), 3, "label roll (2) + re-fired comment (1)")
        self.assertTrue([c for c in run.calls if "re-fired: `status/in-review`" in c])

    def test_graphql_failure_falls_back_to_healing(self):
        run = Sweep([issue(900, "status/in-review")], graphql_fail=True)
        self.assertEqual(run.rc, 0, run.out)
        self.assertIn("::warning::open-PR pre-pass skipped", run.out)
        self.assertEqual(len(run.mutations(900)), 3, "an unreadable PR state must never suppress a heal")

    def test_cross_repo_closing_reference_does_not_suppress(self):
        run = Sweep([issue(1458, "status/in-review")], [pr(999, 1458, owner="someone/fork")])
        self.assertEqual(run.rc, 0, run.out)
        self.assertEqual(len(run.mutations(1458)), 3)

    def test_prepass_uses_github_token_not_the_app_token(self):
        run = Sweep([issue(900, "status/in-review")], [pr(1585, 1458)], app_token="ghs_app")
        prepass = [c for c in run.calls if "api graphql" in c]
        self.assertEqual(len(prepass), 1, run.calls)
        self.assertTrue(prepass[0].startswith("TOKEN=gh-token|"),
                        f"pre-pass must read with GITHUB_TOKEN, got {prepass[0]}")
        self.assertIn("--paginate", prepass[0], "every page must be read, not the first 100")
        self.assertIn("pullRequests(states:OPEN", prepass[0], "open PRs, not the whole history")
        self.assertIn("closingIssuesReferences", prepass[0])
        edits = [c for c in run.calls if "issue edit" in c]
        self.assertTrue(edits and all(c.startswith("TOKEN=ghs_app|") for c in edits),
                        "label mutations must keep the ADR-0020 App identity")


if __name__ == "__main__":
    unittest.main()
