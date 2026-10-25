#!/usr/bin/env python3
"""Cross-contamination dispatch sim — parent spec #796, story C (#894).

Reproduces the PR #787 leak shape mechanically: a repair session dispatched
for story A with story B's findings/deliverables present in its context
(`aw_context` rewrite), then asserts the run either aborts without pushing
or pushes only story A's deliverables.

Two halves, both static/offline (stdlib only, no network, no AI):

1. **Prompt assertion** — the Rule 0 pre-push identity guard must be present
   in BOTH `07-fix-loop.md` and `14-rebase.md`, worded identically, and
   `14-rebase.md` rule 2 must carry the three-way merge classification
   (test files keep-BOTH / agent-state markdown merge-on-content /
   everything else semantic). The sim reads the real prompt files, so
   deleting the guard or reverting the merge-scope rule fails here.

2. **Dispatch sim** — every case in `fixtures/contamination-787.json` is
   replayed twice:
   - `baseline` = the pre-guard pipeline (no identity guard, keep-BOTH /
     wholesale import). It MUST reproduce the leak — that is what proves
     the detector has teeth rather than passing vacuously.
   - `guarded` = the pipeline derived from the prompt text. Each case must
     end in "no push + abort stamp" or "a push containing only story A's
     deliverables" (`abort` / `clean`).

This repo has no prompt-behavior harness (unit tests cover provider Parse
functions only), so the "agent" is a scripted decision procedure: the same
Rule 0 derivation the prompt instructs, applied to dispatch data.

Run: python3 .github/sims/cross_contamination_sim.py [--root .] [-v]
Exit 0 = pass, 1 = assertion failure.
"""

import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_ROOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir))
FIXTURE = os.path.join(HERE, "fixtures", "contamination-787.json")

FIX_LOOP = os.path.join(".github", "workflows", "07-fix-loop.md")
REBASE = os.path.join(".github", "workflows", "14-rebase.md")

GUARD_HEADING = "## Rule 0 — pre-push identity guard"

# Every one of these must appear in the guard section of BOTH prompts: they are
# the derivation inputs, the two assertions, and the abort/stamp behaviour.
GUARD_PHRASES = [
    "aw_context.item_number",
    "devloop/issue-<N>-",
    "closingIssuesReferences",
    "NO push call is made",
    "add_comment",
    "mismatch evidence",
]

# 14-rebase rule 2 — the three-way classification that keeps a sibling
# deliverable out of an agent-state markdown file.
MERGE_SCOPE_PHRASES = [
    "Test files ONLY",
    "Agent-state markdown",
    "merge on content",
    "never import a sibling branch's deliverable wholesale",
]

# 07-fix-loop step 3 — scope discipline: the run fixes only its own story.
STORY_SCOPE_PHRASES = [
    "fix **only** the findings",
    "scope discipline",
]


class Failure(Exception):
    pass


def read(path):
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


# The guard block is a self-contained paragraph run that both prompts end on;
# in 14-rebase it is followed by the merge-rule prose without a '## ' heading,
# so the end marker (not the next heading) delimits the block.
GUARD_END = "this abort stamp is what makes it both."


def extract_guard(text):
    """Return the Rule 0 guard block (heading through its last sentence)."""
    start = text.find(GUARD_HEADING)
    if start < 0:
        return None
    end = text.find(GUARD_END, start)
    if end >= 0:
        return text[start:end + len(GUARD_END)].strip()
    # Degraded fallback: next '## ' heading, so a reworded ending still
    # surfaces as a phrase/equality failure instead of crashing.
    rest = text[start + len(GUARD_HEADING):]
    nxt = rest.find("\n## ")
    return (GUARD_HEADING + (rest if nxt < 0 else rest[:nxt])).strip()


def normalize(text):
    """Collapse all whitespace: prompts are hard-wrapped, so phrase checks
    and the identical-text comparison must be wrapping-insensitive."""
    return " ".join(text.split())


def detect_pipeline(root):
    """Derive the modelled pipeline from the real prompt text.

    Returns (pipeline_dict, [prompt-assertion problems]). The pipeline dict is
    what the dispatch sim executes: identity_guard / merge_scope / story_scope
    are ON only when the prompt actually says so.
    """
    problems = []
    texts = {
        "07-fix-loop": read(os.path.join(root, FIX_LOOP)),
        "14-rebase": read(os.path.join(root, REBASE)),
    }

    guards = {}
    for name, text in texts.items():
        section = extract_guard(text)
        if section is None:
            problems.append("%s: missing '%s' section" % (name, GUARD_HEADING))
            guards[name] = ""
            continue
        guards[name] = normalize(section)
        for phrase in GUARD_PHRASES:
            if normalize(phrase) not in guards[name]:
                problems.append("%s: guard section is missing %r" % (name, phrase))

    if len(guards) == 2 and guards["07-fix-loop"] and guards["14-rebase"]:
        if guards["07-fix-loop"] != guards["14-rebase"]:
            problems.append(
                "guard text differs between 07-fix-loop.md and 14-rebase.md "
                "(Rule 0 requires identical wording)")

    rebase = normalize(texts["14-rebase"])
    merge_scope_ok = True
    for phrase in MERGE_SCOPE_PHRASES:
        if normalize(phrase) not in rebase:
            problems.append("14-rebase.md rule 2 is missing %r" % (phrase,))
            merge_scope_ok = False

    fix_loop = normalize(texts["07-fix-loop"])
    story_scope_ok = True
    for phrase in STORY_SCOPE_PHRASES:
        if normalize(phrase) not in fix_loop:
            problems.append("07-fix-loop.md is missing %r" % (phrase,))
            story_scope_ok = False

    pipeline = {
        "identity_guard": (len(guards) == 2 and all(guards.values())
                           and not [p for p in problems if "guard" in p.lower()]),
        "merge_scope": "test-only" if merge_scope_ok else "all",
        "story_scope": story_scope_ok,
    }
    return pipeline, problems


PRE_GUARD = {"identity_guard": False, "merge_scope": "all", "story_scope": False}


def simulate(case, stories, pipeline, run_url):
    """Execute one dispatch case under a modelled pipeline.

    Mirrors the prompts: Rule 0 derivation -> assertion -> abort-or-push, then
    story-scoped staging + agent-state merge classification.
    """
    story = stories[case["story"]]
    run = {
        "pushed_paths": None,
        "pushed_content": {},
        "target_branch": case["target_branch"],
        "target_pr": case["target_pr"],
        "stamps": [],
        "labels_applied": [],
    }

    ctx = case.get("aw_context") or {}
    n = ctx.get("item_number")
    if n is None:  # event trigger: derive from the PR's closing-issue link
        refs = case["pr_closing_issues"]
        n = refs[0] if len(refs) == 1 else None

    branch_ok = n is not None and case["target_branch"].startswith(
        "devloop/issue-%s-" % n)
    refs_ok = n is not None and n in case["pr_closing_issues"]

    if pipeline["identity_guard"] and not (branch_ok and refs_ok):
        failed = ("primary — branch lacks the devloop/issue-%s- prefix" % n
                  if not branch_ok else
                  "secondary — PR closing-issue link does not list %s" % n)
        run["stamps"].append({
            "issue": n,
            "body": ("ABORT (cross-contamination guard): run %s, aw_context item "
                     "%s, target PR %s, target branch %s — failed assertion: %s. "
                     "No push was made." % (run_url, n, case["target_pr"],
                                            case["target_branch"], failed)),
        })
        return run

    paths = list(case["changed_paths"])

    merge = case.get("agent_state_merge")
    if merge:
        if pipeline["merge_scope"] == "test-only":
            # start from main, append + dedupe only this story's own additions
            kept = [ln["text"] for ln in merge["lines"] if ln["story"] == case["story"]]
        else:
            kept = [ln["text"] for ln in merge["lines"]]  # pre-guard: keep BOTH
        run["pushed_content"][merge["path"]] = "\n".join(kept)

    if pipeline["story_scope"]:
        own = set(story["deliverables"])
        paths = [p for p in paths if p in own]

    run["pushed_paths"] = paths
    run["labels_applied"] = ["status/fixing", "-status/fixing"]
    return run


def classify(run, case, stories):
    """abort | silent | leak | clean."""
    story = stories[case["story"]]
    sibling = stories[case["furnished_findings"]]

    if run["pushed_paths"] is None:
        return "abort" if run["stamps"] else "silent"

    if not run["target_branch"].startswith("devloop/issue-%s-" % story["issue"]):
        return "leak"  # pushed onto a branch that is not story A's

    sibling_only = set(sibling["deliverables"]) - set(story["deliverables"])
    if sibling_only & set(run["pushed_paths"]):
        return "leak"  # a sibling deliverable rode along

    merge = case.get("agent_state_merge")
    if merge:
        body = run["pushed_content"].get(merge["path"], "")
        for line in merge["lines"]:
            if line["story"] != case["story"] and line["text"] in body:
                return "leak"  # sibling note imported into an agent-state file

    return "clean"


def abort_stamp_problems(run, case, story, run_url):
    problems = []
    if len(run["stamps"]) != 1:
        problems.append("expected exactly 1 abort stamp, saw %d" % len(run["stamps"]))
        return problems
    stamp = run["stamps"][0]
    if stamp["issue"] != story["issue"]:
        problems.append("abort stamp went to issue %r, not the owner issue %r"
                        % (stamp["issue"], story["issue"]))
    for needle in (run_url, str(story["issue"]), str(case["target_pr"]),
                   case["target_branch"], "failed assertion"):
        if needle not in stamp["body"]:
            problems.append("abort stamp lacks evidence %r" % (needle,))
    if run["labels_applied"]:
        problems.append("abort run churned state labels: %r" % (run["labels_applied"],))
    return problems


def check_vacuity(cases, stories, pipeline, run_url, verbose):
    """Disabling the identity guard must flip a guarded abort into a leak.

    Without this, an always-abort detector (or an always-clean fixture) would
    pass the sim for the wrong reason.
    """
    blind = dict(pipeline)
    blind["identity_guard"] = False
    flipped = []
    for case in cases:
        if case["expect_guarded"] != "abort":
            continue
        outcome = classify(simulate(case, stories, blind, run_url), case, stories)
        if verbose:
            print("  [vacuity] %-32s guard-disabled -> %s" % (case["name"], outcome))
        if outcome == "leak":
            flipped.append(case["name"])
    if not flipped:
        raise Failure("vacuity check: disabling the identity guard flipped no "
                      "case to 'leak' — the sim cannot detect the leak shape")
    return flipped


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", default=DEFAULT_ROOT,
                    help="repo root (default: two levels above this script)")
    ap.add_argument("--fixture", default=FIXTURE)
    ap.add_argument("--run-url",
                    default="http://localhost/sim/cross-contamination",
                    help="stand-in run URL carried in abort-stamp evidence")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args(argv)

    root = os.path.abspath(args.root)
    fixture = json.loads(read(args.fixture))
    stories = fixture["stories"]
    cases = fixture["cases"]

    failures = []
    print("cross-contamination sim — story #894 (parent #796), "
          "evidence PR #%s" % fixture["evidence"]["pr"])

    # ---- 1. static prompt assertion -------------------------------------
    # A failure here does NOT short-circuit: the degraded pipeline that the
    # weakened prompt implies still runs below, so the report shows both the
    # missing rule and the leak it lets through.
    pipeline, problems = detect_pipeline(root)
    failures.extend(problems)
    if problems:
        print("prompt assertion: FAIL")
        for problem in problems:
            print("  - %s" % problem)
        print("  (continuing with the pipeline the current prompts imply: "
              "identity_guard=%s merge_scope=%s story_scope=%s)"
              % (pipeline["identity_guard"], pipeline["merge_scope"],
                 pipeline["story_scope"]))
    else:
        print("prompt assertion: OK — Rule 0 present and identical in "
              "07-fix-loop.md / 14-rebase.md; rule 2 three-way merge scope "
              "intact; fix-loop story scope intact")

    # ---- 2. baseline (pre-guard) must reproduce the leak ----------------
    print("\nbaseline (pre-guard) — leak reproduction:")
    for case in cases:
        outcome = classify(simulate(case, stories, PRE_GUARD, args.run_url),
                           case, stories)
        expected = case["expect_baseline"]
        ok = outcome == expected
        print("  case %-32s expected=%-6s got=%-6s %s"
              % (case["name"], expected, outcome, "ok" if ok else "FAIL"))
        if not ok:
            failures.append("baseline %s: expected %s, got %s"
                            % (case["name"], expected, outcome))

    # ---- 3. guarded (current prompts) -----------------------------------
    print("\nguarded (current prompts):")
    for case in cases:
        story = stories[case["story"]]
        run = simulate(case, stories, pipeline, args.run_url)
        outcome = classify(run, case, stories)
        expected = case["expect_guarded"]
        ok = outcome == expected
        print("  case %-32s expected=%-6s got=%-6s %s"
              % (case["name"], expected, outcome, "ok" if ok else "FAIL"))
        if not ok:
            failures.append("guarded %s: expected %s, got %s"
                            % (case["name"], expected, outcome))
        if outcome == "abort":
            stamp_problems = abort_stamp_problems(run, case, story, args.run_url)
            failures.extend("guarded %s: %s" % (case["name"], p)
                            for p in stamp_problems)
            if args.verbose and not stamp_problems:
                print("      abort stamp on issue %s: %s"
                      % (run["stamps"][0]["issue"], run["stamps"][0]["body"]))
        elif outcome == "clean" and args.verbose:
            print("      pushed: %s" % (run["pushed_paths"],))

    # ---- 4. vacuity control --------------------------------------------
    try:
        flipped = check_vacuity(cases, stories, pipeline, args.run_url,
                                args.verbose)
        print("\nvacuity control: OK — guard-disabled run leaks on %s"
              % ", ".join(flipped))
    except Failure as exc:
        print("\nvacuity control: FAIL — %s" % exc)
        failures.append(str(exc))

    if failures:
        print("\nRESULT: FAIL (%d)" % len(failures))
        for failure in failures:
            print("  - %s" % failure)
        return 1
    print("\nRESULT: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
