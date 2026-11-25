#!/usr/bin/env python3
"""Unit tests for the orchestrator's branch-executor decision layer
(parent spec #847, S1 #897). Python-stdlib unittest only — orchestrator.py
had no test harness before this. Fixtures are carved from the real
#840/#846/#845/#844 story texts (conditional fan-out on the PerverZija
chain) so the regression fixtures stay anchored to live data shapes.

Run: python3 -m unittest .github.orchestrator_test  (or:
     python3 -m unittest discover -s .github -p 'orchestrator_test.py')
"""

import calendar
import importlib.util
import json
import os
import re
import unittest
from unittest import mock

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "orchestrator", os.path.join(_HERE, "orchestrator.py"))
orchestrator = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(orchestrator)


def story(num, body, state="open"):
    return {"number": num, "body": body, "state": state, "labels": []}


def comment(body, user_type="Bot"):
    return {"body": body, "user": {"type": user_type},
            "created_at": "2026-10-21T12:00:00Z"}


# --- fixtures carved from the real #845/#844 bodies -----------------------
GATE_TEXT = ("Three sub-issues were created and linked; stories 2 and 3 are "
             "alternative branches — exactly one of the two conditional "
             "branches runs: each runs only if the probe (#846) verdicts "
             "its declared outcome.")
GATE_TEXT_IF_VERDICTS = ("Exactly one branch story fires after the probe; "
                         "each runs only if the probe matches its declared "
                         "outcome.")
PROBE_846_BODY = ("[spec] PerverZija: probe XSFTK signed-token gate on "
                  "xtremestream player. Story 1 of 3.")
BRANCH_845_BODY = ("[spec] Story 2 of 3 — fix. **Conditional: runs only if "
                   "the probe (#846) verdicts fixable.** Mutually exclusive "
                   "with story 3 (#844).")
BRANCH_844_BODY = ("[spec] Story 3 of 3 — fallback. Conditional: runs only "
                   "if the probe (#846) verdicts not fixable.")
VERDICT_FIXABLE = (
    "## Probe verdict — PerverZija XSFTK token chain\n\n"
    "The XSFTK signed-token chain is satisfied without JS execution: "
    "outcome **fixable**.\n\n- proto_api.php ops key/manifest/sign/seg…")
VERDICT_NOT_FIXABLE = (
    "## Probe verdict — PerverZija XSFTK token chain\n\n"
    "outcome **not fixable** — non-JS extraction cannot satisfy the gate.")
# the real #846 verdict as posted: free-form prose, no heading, no bold token
VERDICT_846_REAL = (
    "gh-aw budget: attempt=1 kind=fix run=42 run_url=https://x\n\n"
    "**FINDINGS-840 — probe verdict for #840 story 1 of 3: FIXABLE.**\n\n"
    "- XSFTK signed-token chain satisfied without JS execution…")


class TestStripMd(unittest.TestCase):
    def test_bold_and_parens_removed(self):
        self.assertEqual(
            orchestrator._strip_md("**Conditional: runs only if the probe "
                                   "(#846) verdicts fixable.**"),
            "Conditional: runs only if the probe (#846) verdicts fixable.")


class TestFanoutGate(unittest.TestCase):
    def test_body_only(self):
        self.assertIsNotNone(orchestrator._fanout_gate([GATE_TEXT]))

    def test_comment_only_live_840_shape(self):
        # on the live #840 chain the gate rendered in the round-3 finalize
        # comment, not the issue body
        self.assertIsNotNone(orchestrator._fanout_gate(
            ["unrelated thread chatter", GATE_TEXT_IF_VERDICTS]))

    def test_no_gate(self):
        self.assertIsNone(orchestrator._fanout_gate(
            ["exactly one provider fixed so far", "nothing conditional here"]))

    def test_exactly_one_without_conditional_wording(self):
        self.assertIsNone(orchestrator._fanout_gate(
            ["exactly one of three stories is a probe"]))

    def test_exactly_one_with_if_verdicts_shape(self):
        self.assertIsNotNone(orchestrator._fanout_gate(
            ["exactly one branch runs, only if the probe verdicts fixable"]))



class TestBranchDeclarationRegex(unittest.TestCase):
    def test_bold_parenthesized_shape_845(self):
        m = orchestrator.BRANCH_DECL_RE.search(orchestrator._strip_md(BRANCH_845_BODY))
        self.assertEqual((m.group(1), m.group(2).lower()), ("846", "fixable"))

    def test_plain_shape_844(self):
        m = orchestrator.BRANCH_DECL_RE.search(orchestrator._strip_md(BRANCH_844_BODY))
        self.assertEqual((m.group(1), m.group(2).lower()), ("846", "not fixable"))

    def test_case_insensitive(self):
        m = orchestrator.BRANCH_DECL_RE.search(
            orchestrator._strip_md("RUNS ONLY IF THE PROBE #12 VERDICTS fixable"))
        self.assertEqual((m.group(1), m.group(2)), ("12", "fixable"))

    def test_probe_link_not_lowest_sibling(self):
        # the probe (#30) is NOT the lowest-numbered sibling (#22) — the
        # declaration's own #N must win
        m = orchestrator.BRANCH_DECL_RE.search(orchestrator._strip_md(
            "runs only if the probe (#30) verdicts not fixable"))
        self.assertEqual(m.group(1), "30")

    def test_dropped_declaration_fails_closed(self):
        # a branch edit that mangles/drops the declaration sentence is not
        # routed — the regex finds nothing
        self.assertIsNone(orchestrator.BRANCH_DECL_RE.search(
            orchestrator._strip_md("Conditional: gated on the probe result.")))


class TestProbeVerdictParser(unittest.TestCase):
    def test_fixable_heading_comment(self):
        out, matched = orchestrator._probe_verdict(
            [comment("chatter"), comment(VERDICT_FIXABLE)])
        self.assertEqual(out, "fixable")
        self.assertEqual(matched, VERDICT_FIXABLE)

    def test_not_fixable_resolved_before_fixable(self):
        # heading comment containing BOTH — 'not fixable' must win
        body = ("## Probe verdict — chain\n\nprobe verdict: **not fixable** "
                "(fixable variants considered)")
        self.assertEqual(orchestrator._probe_verdict([comment(body)])[0],
                         "not fixable")

    def test_heading_without_bold_token_fails(self):
        body = "## Probe verdict — chain\n\nThe probe succeeded, mostly."
        self.assertEqual(orchestrator._probe_verdict([comment(body)]),
                         (None, body))

    def test_human_authored_heading_fails_closed(self):
        self.assertEqual(orchestrator._probe_verdict(
            [comment(VERDICT_FIXABLE, user_type="User")]), (None, None))

    def test_real_846_freeform_prose_fails_closed(self):
        # the actual #846 comment: no "## Probe verdict" heading, no bold
        # outcome token → NOT parseable evidence
        self.assertEqual(
            orchestrator._probe_verdict([comment(VERDICT_846_REAL)]),
            (None, None))

    def test_latest_verdict_wins(self):
        old = "## Probe verdict — chain\n\noutcome **not fixable**"
        new = "## Probe verdict — chain\n\noutcome **fixable**"
        self.assertEqual(orchestrator._probe_verdict(
            [comment(old), comment(new)])[0], "fixable")


class TestBranchDecision(unittest.TestCase):
    def decisions(self, *, gate=GATE_TEXT, b845=BRANCH_845_BODY,
                  b844=BRANCH_844_BODY, verdict=VERDICT_FIXABLE,
                  verdict_user="Bot", states=("open", "open", "open")):
        parent_texts = ["parent body w/o gate"] + ([gate] if gate else [])
        probe_c = ([comment(verdict, verdict_user)] if verdict else [])
        return orchestrator._branch_decision(
            parent_texts,
            [story(846, PROBE_846_BODY, states[0]),
             story(845, b845, states[1]),
             story(844, b844, states[2])],
            probe_comments={846: probe_c})

    def test_happy_path_fixable(self):
        d = self.decisions()
        self.assertIsNone(d["fail"])
        self.assertEqual(d["verdict"], "fixable")
        self.assertEqual(d["probe"], 846)
        self.assertEqual(d["fire"]["number"], 845)
        self.assertEqual([s["number"] for s in d["close"]], [844])

    def test_not_fixable_fires_fallback(self):
        d = self.decisions(verdict=VERDICT_NOT_FIXABLE)
        self.assertIsNone(d["fail"])
        self.assertEqual(d["fire"]["number"], 844)
        self.assertEqual([s["number"] for s in d["close"]], [845])

    def test_no_gate_returns_none(self):
        self.assertIsNone(self.decisions(gate=None))

    def test_no_declarations_fails_closed(self):
        d = self.decisions(b845="[spec] story 2", b844="[spec] story 3")
        self.assertIn("declares a branch", d["fail"])

    def test_partial_branches_fail_closed(self):
        # the #844 declaration was dropped by an edit — neither resolves
        d = self.decisions(b844="[spec] story 3, conditional on the probe result")
        self.assertEqual(sorted(d["fail"].split("(declared: ")[0:1]),
                         ["branches must declare exactly one fixable + one "
                          "not fixable "])

    def test_different_probes_fail_closed(self):
        d = self.decisions(b844=BRANCH_844_BODY.replace("#846", "#999"))
        self.assertIn("different probes", d["fail"])

    def test_missing_verdict_fails_closed(self):
        d = self.decisions(verdict=None)
        self.assertIn("no anchored", d["fail"])
        self.assertIsNone(d["fire"])

    def test_unbold_token_fails_closed(self):
        d = self.decisions(verdict="## Probe verdict — chain\n\noutcome: fixable")
        self.assertIn("not exactly one bold", d["fail"])
        self.assertIsNone(d["fire"])

    def test_closed_branch_ignored(self):
        # an already-resolved chain (one branch closed) — decision layer
        # sees one declaration, fails closed (nothing more to do; other
        # steps own the retiming)
        d = self.decisions(states=("open", "open", "closed"))
        self.assertIsNotNone(d["fail"])


class TestBranchPendingPredicate(unittest.TestCase):
    def comments_of(self, pcs):
        return lambda _n: pcs

    def test_unresolved_verdict_is_owned(self):
        s = story(845, BRANCH_845_BODY)
        self.assertTrue(orchestrator._branch_pending(
            s, [story(846, PROBE_846_BODY), s, story(844, BRANCH_844_BODY)],
            self.comments_of([comment(VERDICT_846_REAL)])))
        # probe without any comment at all: unresolved
        self.assertTrue(orchestrator._branch_pending(
            s, [s], self.comments_of([])))

    def test_resolved_verdict_not_owned(self):
        s = story(845, BRANCH_845_BODY)
        self.assertFalse(orchestrator._branch_pending(
            s, [s], self.comments_of([comment(VERDICT_FIXABLE)])))

    def test_plain_story_not_owned(self):
        self.assertFalse(orchestrator._branch_pending(
            story(900, "[spec] plain story"), [], self.comments_of([])))


class TestClaimsFire(unittest.TestCase):
    """The claim bar (#905 r1 blocker 1): run rows claiming a planned
    stamp. Classes carved from the live saga: hollow (activation-skipped,
    #879), failed (S07's AIC death), silent-empty (#904/#904-class:
    success but zero safe-outputs), long-running (#884 r1: must claim
    early), real success."""

    RUN_TS = "2026-10-23T03:11:19Z"
    OUTPUT_AFTER = {"created_at": "2026-10-23T03:12:00Z",
                    "user": {"type": "Bot"},
                    "body": "gh-aw budget: attempt=1 kind=spec run=1 credits=1"}
    BOT_CHATTER = {"created_at": "2026-10-23T03:12:00Z",
                   "user": {"type": "Bot"},
                   "body": "re-fired: `status/planned` idle 12h."}

    def row(self, status="completed", conclusion="success"):
        return {"databaseId": 1, "createdAt": self.RUN_TS,
                "status": status, "conclusion": conclusion}

    def test_success_with_output_claims(self):
        self.assertTrue(orchestrator._claims_fire(
            self.row(), True, [self.OUTPUT_AFTER]))

    def test_success_silent_empty_no_claim(self):
        # #904: agent success, zero safe-outputs — the run fired no work
        self.assertFalse(orchestrator._claims_fire(self.row(), True, []))

    def test_success_bot_chatter_not_output(self):
        # r1 suggestion 3: a sibling fire's Bot comment doesn't bless it
        self.assertFalse(orchestrator._claims_fire(
            self.row(), True, [self.BOT_CHATTER]))

    def test_terminal_failure_never_claims(self):
        self.assertFalse(orchestrator._claims_fire(
            self.row(conclusion="failure"), True, [self.OUTPUT_AFTER]))

    def test_terminal_skipped_never_claims(self):
        self.assertFalse(orchestrator._claims_fire(
            self.row(conclusion="skipped"), True, [self.OUTPUT_AFTER]))

    def test_long_running_claims_early(self):
        # #884 r1: in-progress rows claim via activation alone, else the
        # 10-min sweep loop re-fires a >10min run forever
        self.assertTrue(orchestrator._claims_fire(
            self.row(status="in_progress", conclusion=""), True, []))

    def test_unactivated_success_no_claim(self):
        self.assertFalse(orchestrator._claims_fire(self.row(), False, [self.OUTPUT_AFTER]))


if __name__ == "__main__":
    unittest.main()


class TestAllStoriesWindows(unittest.TestCase):
    """The close-burst class (2026-10-23): a same-day bulk close of 300
    issues pushed every OPEN story (#906–#909, chain approved at round 3)
    out of the recent-100 `--state all` recency window, so all_stories()
    enumerated only closed rows and the chain driver fired nothing. The
    open-state window must keep open stories visible regardless of churn;
    the recency window stays for closed-predecessor state lookups."""

    def test_open_story_beyond_recent_window_is_enumerated(self):
        open_row = {"number": 906, "title": "[spec] S1 — census (parent: #903)",
                    "state": "OPEN", "body": "", "labels": []}
        recent = [{"number": 1300 + i, "title": f"[spec] S1 — old close {i}",
                   "state": "CLOSED", "body": "", "labels": []}
                  for i in range(100)]

        def fake_run(args, **kw):
            state = args[args.index("--state") + 1]
            return json.dumps([open_row] if state == "open" else recent)

        with mock.patch.object(orchestrator, "run", fake_run):
            stories = orchestrator.all_stories()
        self.assertIn(906, {s["number"] for s in stories})

    def test_dedup_across_windows_open_wins(self):
        row = {"number": 906, "title": "[spec] S1 — census (parent: #903)",
               "state": "OPEN", "body": "", "labels": []}
        recent = [dict(row, state="CLOSED")]

        def fake_run(args, **kw):
            state = args[args.index("--state") + 1]
            return json.dumps([row] if state == "open" else recent)

        with mock.patch.object(orchestrator, "run", fake_run):
            stories = orchestrator.all_stories()
        self.assertEqual(len(stories), 1)
        self.assertEqual(stories[0]["state"], "open")


class TestPlanKidOrdinals(unittest.TestCase):
    """Ordinal-less plan chains (2026-10-23): 04-plan created #920/#919/
    #921/#918 (parent #910), #922–#926 (#864) and #896/#895/#894 (#796)
    without the `[spec] S<N>` title contract, declaring the parent in the
    body only. all_stories() keyed on the title ordinal, so those 12 kids
    were invisible to chain_driver and every relay — three approved chains,
    zero fires, no PR for ~15h. Ordinals now come from the parent's
    round-3 story list, which is where fire order actually lives.
    Fixtures are the three live comment shapes."""

    # "1. #920 — …" numbered list (#910)
    R3_NUMBERED = ("Plan round 3 — finalized spec & sub-issues created\n\n"
                   "1. #920 — Affected-set classifier.\n2. #919 — build.yml "
                   "gating.\n3. #921 — Auto version-bump.\n4. #918 — "
                   "Verification & wiring.\n")
    # "1. **Archive …** — … (#922)" trailing reference (#864)
    R3_TRAILING = ("Plan round 3 — spec finalized; sub-issues created\n\n"
                   "1. **Archive root run reports** (#922)\n"
                   "2. **Archive provider run reports** (#923)\n"
                   "3. **Doc-noise sweep** (#924)\n"
                   "4. **Noise-free convention** (#925)\n"
                   "5. **Verify & open PR** (#926)\n")
    # "- #896 — A" bullets, a probe ref mixed in (#796)
    R3_BULLETS = ("Plan round 3 — finalized spec (approved): guards\n\n"
                  "- #896 — **A — identity guard**\n"
                  "- #895 — **B — merge-scope hardening**\n"
                  "- #894 — **C — contamination sim**\n")

    def _kids(self, numbers, parent):
        return [{"number": n, "title": f"[spec] task {n} — ordinal-less",
                 "state": "open",
                 "body": f"Parent: #{parent} (approved spec, plan round 3).",
                 "labels": [{"name": "status/queued"}]} for n in numbers]

    def _assert_ranks(self, r3, kids, parent, expected):
        rows = self._kids(kids, parent)
        by_num = {r["number"]: r for r in rows}

        def fake_run(args, **kw):
            state = args[args.index("--state") + 1]
            return json.dumps(rows if state == "open" else [])

        with mock.patch.object(orchestrator, "run", fake_run), \
                mock.patch.object(orchestrator, "comments",
                                  lambda n: [{"body": r3}]):
            stories = orchestrator.all_stories()
        got = {s["number"]: orchestrator.story_num(s) for s in stories}
        self.assertEqual(got, expected)
        self.assertEqual(sorted(by_num), sorted(expected))  # nothing dropped

    def test_numbered_list_ranks_in_fire_order(self):
        self._assert_ranks(self.R3_NUMBERED, [918, 919, 920, 921], 910,
                           {920: 1, 919: 2, 921: 3, 918: 4})

    def test_trailing_reference_ranks_in_fire_order(self):
        self._assert_ranks(self.R3_TRAILING, [922, 923, 924, 925, 926], 864,
                           {922: 1, 923: 2, 924: 3, 925: 4, 926: 5})

    def test_bullets_rank_in_fire_order_not_number_order(self):
        self._assert_ranks(self.R3_BULLETS, [894, 895, 896], 796,
                           {896: 1, 895: 2, 894: 3})

    def test_kid_absent_from_round3_list_still_ranked(self):
        # a kid the finalize comment never named must not vanish (unranked
        # = never fired); it ranks last so the chain still drains.
        got = orchestrator._plan_ordinals
        rows = {"body": self.R3_BULLETS}
        with mock.patch.object(orchestrator, "comments",
                               lambda n: [{"body": rows["body"]}]), \
                mock.patch.dict(orchestrator._R3_BODY_CACHE, {}, clear=True):
            self.assertEqual(got(796, {894, 895, 896, 999}),
                             {896: 1, 895: 2, 894: 3, 999: 4})

    def test_non_plan_followup_is_not_a_story(self):
        # Review #1324 round 1 blocker: 07-fix-loop/12-post-merge file
        # `[from-impl]`/`[aw]` follow-ups that name their spec as parent. A
        # round-3 comment on the parent is NOT enough — ranking one of those
        # lets chain_driver stamp status/planned on another lane's work item
        # and _demote_out_of_order cancel its run.
        kid = {"number": 1303, "title": "[from-impl] delivered-guard misses",
               "state": "open", "body": "Parent: #797 (approved spec)",
               "labels": []}
        spec_kid = {"number": 929, "title": "[spec] replay harness",
                    "state": "open", "body": "Parent: #797", "labels": []}

        def fake_run(args, **kw):
            state = args[args.index("--state") + 1]
            return json.dumps([kid, spec_kid] if state == "open" else [])

        with mock.patch.object(orchestrator, "run", fake_run), \
                mock.patch.object(orchestrator, "comments",
                                  lambda n: [{"body": self.R3_BULLETS}]), \
                mock.patch.dict(orchestrator._R3_BODY_CACHE, {}, clear=True):
            stories = orchestrator.all_stories()
        self.assertEqual({s["number"] for s in stories}, {929})

    def test_round3_body_fetched_once_per_parent(self):
        # five drivers call all_stories() per pass — the memo keeps that at
        # one thread fetch per parent instead of five.
        rows = self._kids([922, 923], 864)
        calls = []

        def fake_run(args, **kw):
            state = args[args.index("--state") + 1]
            return json.dumps(rows if state == "open" else [])

        with mock.patch.object(orchestrator, "run", fake_run), \
                mock.patch.object(orchestrator, "comments",
                                  lambda n: calls.append(n) or
                                  [{"body": self.R3_TRAILING}]), \
                mock.patch.dict(orchestrator._R3_BODY_CACHE, {}, clear=True):
            orchestrator.all_stories()
            orchestrator.all_stories()
        self.assertEqual(calls.count(864), 1)

    def test_s0_title_is_not_treated_as_ordinal_less(self):
        # `[spec] S0 — Baseline` (live #809 under #804) parses to a falsy 0,
        # not to "no ordinal" — it must not pick up a round-3 rank.
        kid = {"number": 809, "title": "[spec] S0 — Baseline", "state": "closed",
               "body": "Parent: #804", "labels": []}

        def fake_run(args, **kw):
            state = args[args.index("--state") + 1]
            return json.dumps([kid] if state == "open" else [kid])

        with mock.patch.object(orchestrator, "run", fake_run), \
                mock.patch.object(orchestrator, "comments",
                                  lambda n: [{"body": self.R3_BULLETS}]), \
                mock.patch.dict(orchestrator._R3_BODY_CACHE, {}, clear=True):
            orchestrator.all_stories()
        self.assertNotIn("_ordinal", kid)
        self.assertEqual(orchestrator.story_num(kid), 0)

    def test_title_ordinal_still_wins(self):
        s = {"number": 1319, "title": "[spec] S4 — Verify (live) + build",
             "body": "Parent: #876", "_ordinal": 9}
        self.assertEqual(orchestrator.story_num(s), 4)


class TestMachineryAppIdentity(unittest.TestCase):
    """ADR-0020 step 3 moved machinery safe-output deliveries to the
    GitHub App identity (rjbiermann-machinery[bot]). _agent_pr's trust
    anchor still required github-actions[bot], so every machinery-authored
    PR fell out of _fixable_pr — the fix surrogate never fired on blocker
    verdicts (recorded 2026-10-23: #1307/#1310, blockers outstanding,
    zero 07-fix-loop runs)."""

    def test_machinery_app_authored_pr_is_fixable(self):
        pr = {"draft": False, "user": {"login": "rjbiermann-machinery[bot]"},
              "head": {"ref": "devloop/issue-892-f0c00351a8f20524"}}
        self.assertTrue(orchestrator._agent_pr(pr))
        self.assertTrue(orchestrator._fixable_pr(pr))

    def test_unknown_author_stays_fail_closed(self):
        pr = {"draft": False, "user": {"login": "some-random-user"},
              "head": {"ref": "devloop/issue-892-abcdef"}}
        self.assertFalse(orchestrator._agent_pr(pr))
        self.assertFalse(orchestrator._fixable_pr(pr))


def bot_comment(body):
    """A comment as the gh-aw verdict bot posts it (login, not just type —
    fix_surrogate keys on the login, so the `comment()` fixture above is
    not usable here)."""
    return {"body": body, "user": {"login": "github-actions[bot]",
                                   "type": "Bot"},
            "created_at": "2026-11-18T23:49:02Z"}


class TestEffectiveReady(unittest.TestCase):
    """A cancelled conveyor self-check must not pin readiness (#1411).

    `orchestrate` runs on PR events and its concurrency group cancels it
    mid-run; `cancelled` is in the bad-conclusion set, so readiness never
    fired for the PR (#1411, and #1389 since 2026-11-04)."""

    def ready(self, runs, mstate="unstable"):
        def fake_api(path):
            if path.startswith("pulls/"):
                return {"head": {"sha": "959aa36"}}
            return {"check_runs": runs}
        with mock.patch.object(orchestrator, "api", fake_api):
            return orchestrator._effective_ready(1411, mstate)

    def check(self, name, conclusion, status="completed"):
        return {"name": name, "status": status, "conclusion": conclusion}

    def test_cancelled_orchestrate_does_not_block(self):
        self.assertTrue(self.ready([self.check("orchestrate", "cancelled"),
                                    self.check("verify", "success")]))

    def test_cancelled_real_check_still_blocks(self):
        self.assertFalse(self.ready([self.check("orchestrate", "cancelled"),
                                     self.check("CodeQL", "cancelled")]))

    def test_failed_check_still_blocks(self):
        self.assertFalse(self.ready([self.check("verify", "failure")]))

    def test_clean_needs_no_scan(self):
        with mock.patch.object(orchestrator, "api") as api:
            self.assertTrue(orchestrator._effective_ready(1, "clean"))
            api.assert_not_called()


class TestFixSurrogateDedupe(unittest.TestCase):
    """An aborted repair (no push) must not count as consumed (#1411).

    fix_surrogate used to call a verdict served when the dispatched run's
    `agent` job executed — a Rule 0 abort does exactly that (success, no
    push), so the blocker verdict on #1411 never re-fired and never hit
    the marker cap that escalates."""

    VERDICT = "## Review\n\n**Verdict: blocker** — rows parse empty."

    def run_surrogate(self, markers, branch_moved, in_flight=False, tail=(),
                      verdict=None, runs=(), jobs=None):
        posted, fired, cmds = [], [], []
        pr = {"number": 1411, "user": {"login": "rjbiermann-machinery[bot]"},
              "head": {"ref": "devloop/issue-1407-c141da7ff2421900"}}
        thread = [bot_comment(verdict or self.VERDICT)]
        thread += [bot_comment(f"{orchestrator.MARK} fix-loop re-fired for "
                              f"the verdict at 2026-11-18T23:49:02Z")] * markers
        thread += list(tail)
        jobs = jobs if jobs is not None else {
            "149": [{"name": "agent", "conclusion": "success"}]}

        def fake_run(cmd, **kw):
            if cmd[:3] == ["gh", "run", "list"]:
                return json.dumps(list(runs))
            return cmds.append(cmd) or ""

        def fake_jobs(rid):
            return jobs.get(str(rid), [])

        def fake_api(path):          # owner-issue comments; labels elsewhere
            return [] if "comments" in path else {"labels": []}

        with mock.patch.object(orchestrator, "open_pulls", lambda: [pr]), \
                mock.patch.object(orchestrator, "_fixable_pr", lambda p: True), \
                mock.patch.object(orchestrator, "comments", lambda n: thread), \
                mock.patch.object(orchestrator, "_repair_in_flight",
                                  lambda: in_flight), \
                mock.patch.object(orchestrator, "_branch_moved_after",
                                  lambda n, ts: branch_moved), \
                mock.patch.object(orchestrator, "api_run_jobs", fake_jobs), \
                mock.patch.object(orchestrator, "api", fake_api), \
                mock.patch.object(orchestrator, "post_comment",
                                  lambda n, b: posted.append(b)), \
                mock.patch.object(orchestrator, "run", fake_run), \
                mock.patch.object(orchestrator, "dispatch_ok",
                                  lambda cmd, **kw: fired.append(cmd) or True):
            orchestrator.fix_surrogate()
        return posted, fired, cmds

    def test_aborted_repair_refires(self):
        posted, fired, _ = self.run_surrogate(markers=1, branch_moved=False)
        self.assertEqual(len(fired), 1, "unserved verdict must re-fire")
        self.assertEqual(len(posted), 1)

    def test_comment_only_repair_suppresses_refire(self):
        # ADR-0013: the repair lands as a comment, the branch never moves.
        # Its budget stamp names the run, which is the PR identity.
        stamp = bot_comment("gh-aw budget: attempt=2 kind=repair "
                            "run=https://github.com/o/r/actions/runs/149 credits=1")
        posted, fired, _ = self.run_surrogate(
            markers=1, branch_moved=False, tail=[stamp],
            runs=[{"databaseId": 149,
                   "createdAt": "2026-11-18T23:49:05Z"}])
        self.assertEqual(fired, [], "a comment-only repair is consumption")
        self.assertEqual(posted, [])

    def test_another_prs_repair_does_not_suppress_refire(self):
        # the repo-wide run list alone must not mark this verdict served
        stamp = bot_comment("gh-aw budget: attempt=2 kind=repair "
                            "run=https://github.com/o/r/actions/runs/149 credits=1")
        posted, fired, _ = self.run_surrogate(
            markers=1, branch_moved=False, tail=[stamp],
            runs=[{"databaseId": 999,
                   "createdAt": "2026-11-18T23:49:05Z"}])
        self.assertEqual(len(fired), 1, "run 999 has no identity on this PR")
        self.assertEqual(len(posted), 1)

    def test_resolved_blocker_line_does_not_fire(self):
        # PR #1420 round 2's header: a PASS that names the blocker it fixed
        posted, fired, _ = self.run_surrogate(
            markers=0, branch_moved=False,
            verdict="## 06-review — findings (round 2)\n\n"
                    "**Round-1 blocker resolved.** The fixed-35 wording is "
                    "gone.\n\n**Verdict: no blockers.**")
        self.assertEqual(fired, [], "a resolved-blocker pass must not fire")

    def test_body_blocker_after_a_pass_header_does_not_fire(self):
        # the header is authoritative; 06-review always states it up top
        posted, fired, _ = self.run_surrogate(
            markers=0, branch_moved=False,
            verdict="## 06-review — findings (round 3)\n\n"
                    "**Verdict:** no blockers.\n\n" + ("x" * 600) +
                    "\n\n**Blocker** — quoted from round 1 for context.")
        self.assertEqual(fired, [])

    def test_real_blocker_survives_a_negated_phrase_elsewhere(self):
        # line-scoped, not body-wide: swallowing this verdict would strand
        # the blocker (#1420's class), while a needless fire is cheap
        posted, fired, _ = self.run_surrogate(
            markers=0, branch_moved=False,
            verdict="## Review\n\n**Blocker** — rows parse empty.\n\n"
                    "No blockers were found on load/streams.")
        self.assertEqual(len(fired), 1)

    def test_verdict_followed_by_budget_stamp_still_fires(self):
        # 06-review posts its work-attempt stamp as a separate comment a
        # second AFTER the verdict (#1420: blocker 04:45:03, stamp 04:45:04).
        # Reading "the last bot comment" as the verdict found the stamp and
        # never fired — and never escalated.
        stamp = bot_comment("gh-aw budget: attempt=1 kind=review "
                            "run=https://github.com/o/r/actions/runs/1 credits=0")
        posted, fired, _ = self.run_surrogate(markers=0, branch_moved=False,
                                              tail=[stamp])
        self.assertEqual(len(fired), 1, "verdict must survive a later stamp")
        self.assertEqual(len(posted), 1)

    def test_stamp_summary_line_does_not_shadow_the_verdict(self):
        # PR #1511: the stamp's summary prose ("Review round at head `sha` …;
        # blocker on the red CodeFactor check") matched VERDICT_HINT, so the
        # blocker one second earlier was never read, the repair never fired
        # and the stalled PR sat in status/fixing.
        stamp = bot_comment("gh-aw budget: attempt=1 kind=review "
                            "run=https://github.com/o/r/actions/runs/1 credits=0\n\n"
                            "Review round at head `1b08fc4`: round-1 findings "
                            "resolved; blocker on the red CodeFactor check.")
        posted, fired, _ = self.run_surrogate(markers=0, branch_moved=False,
                                              tail=[stamp])
        self.assertEqual(len(fired), 1, "summary prose must not shadow the verdict")

    def test_plural_blocker_verdict_is_recognised(self):
        # the fire predicate is a substring test, so "2 blockers" fires —
        # the candidate filter must not be narrower than that (#1360, #1325)
        verdict = bot_comment("## Review round 3\n\n**2 blockers** outstanding.")
        self.assertTrue(orchestrator.VERDICT_HINT.search(verdict["body"]))

    def test_budget_stamp_alone_is_not_a_verdict(self):
        stamp = bot_comment("gh-aw budget: attempt=1 kind=review "
                            "run=https://github.com/o/r/actions/runs/1 credits=0")
        with mock.patch.object(orchestrator, "open_pulls",
                               lambda: [{"number": 1420,
                                         "user": {"login": "github-actions[bot]"},
                                         "head": {"ref": "devloop/issue-1410-x"}}]), \
                mock.patch.object(orchestrator, "_fixable_pr", lambda p: True), \
                mock.patch.object(orchestrator, "comments", lambda n: [stamp]), \
                mock.patch.object(orchestrator, "dispatch_ok",
                                  lambda cmd, **kw: self.fail("dispatched on a stamp")):
            orchestrator.fix_surrogate()

    def test_pushed_repair_is_served(self):
        posted, fired, _ = self.run_surrogate(markers=1, branch_moved=True)
        self.assertEqual(fired, [])
        self.assertEqual(posted, [])

    def test_one_dispatch_per_sweep(self):
        # registration is async: a second PR in the same sweep would
        # cancel the first repair through 07's shared concurrency group
        fired = []
        prs = [{"number": n, "user": {"login": "github-actions[bot]"},
                "head": {"ref": f"devloop/issue-{n}-x"}} for n in (1407, 1409)]
        thread = [bot_comment(self.VERDICT)]
        with mock.patch.object(orchestrator, "open_pulls", lambda: prs), \
                mock.patch.object(orchestrator, "_fixable_pr", lambda p: True), \
                mock.patch.object(orchestrator, "comments", lambda n: thread), \
                mock.patch.object(orchestrator, "_repair_in_flight",
                                  lambda: False), \
                mock.patch.object(orchestrator, "_branch_moved_after",
                                  lambda n, ts: False), \
                mock.patch.object(orchestrator, "api", lambda p: {"labels": []}), \
                mock.patch.object(orchestrator, "post_comment", lambda n, b: None), \
                mock.patch.object(orchestrator, "run",
                                  lambda cmd, **kw: "[]" if cmd[:3] == ["gh", "run", "list"] else ""), \
                mock.patch.object(orchestrator, "dispatch_ok",
                                  lambda cmd, **kw: fired.append(cmd) or True):
            orchestrator.fix_surrogate()
        self.assertEqual(len(fired), 1, fired)

    def test_in_flight_repair_blocks_a_second_dispatch(self):
        # the fire marker is an issue_comment, so the sweep re-enters
        # seconds later — that pass must not cancel the running repair
        posted, fired, _ = self.run_surrogate(markers=1, branch_moved=False,
                                              in_flight=True)
        self.assertEqual(fired, [])
        self.assertEqual(posted, [])

    def test_two_unserved_fires_escalate_to_human(self):
        posted, fired, cmds = self.run_surrogate(markers=2, branch_moved=False)
        self.assertEqual(fired, [])
        self.assertEqual(posted, [])
        self.assertTrue(any("status/fixing-stuck" in " ".join(c)
                            for c in cmds), cmds)

    def test_served_verdict_is_not_escalated(self):
        # Two markers but the branch moved after the verdict: a repair landed,
        # so the cap must not stamp status/fixing-stuck on a served PR — the
        # escalation arm now runs before the sweep budget, and this is the
        # guard that keeps it from paging on resolved work.
        posted, fired, cmds = self.run_surrogate(markers=2, branch_moved=True)
        self.assertEqual(fired, [])
        self.assertEqual(posted, [])
        self.assertFalse(any("status/fixing-stuck" in " ".join(c)
                             for c in cmds), cmds)


class TestEscalationNotStarvedBySweepBudget(unittest.TestCase):
    """The needs-human stamp must not wait on the sweep-local dispatch slot.

    fix_surrogate dispatches at most one PR per pass, and the `markers >= 2`
    arm used to sit behind that `dispatched` flag — so a PR that had already
    burned its two fires stayed silent for as long as any *earlier* PR in the
    same pass was dispatchable. Terminal needs-human state was the one thing
    the ordering could starve."""

    BLOCKER = "## Review\n\n**Verdict: blocker** — rows parse empty."

    def test_second_pr_escalates_after_the_first_dispensed_the_dispatch(self):
        def pr(num, branch):
            return {"number": num,
                    "user": {"login": "rjbiermann-machinery[bot]"},
                    "head": {"ref": branch}}
        first = pr(1401, "devloop/issue-1400-a")
        second = pr(1411, "devloop/issue-1407-b")
        marker_b = (f"{orchestrator.MARK} fix-loop re-fired for the verdict "
                    "at 2026-11-18T23:49:02Z")
        threads = {1401: [bot_comment(self.BLOCKER)],
                   1411: [bot_comment(self.BLOCKER)] + [bot_comment(marker_b)] * 2}
        fired, cmds = [], []

        def fake_run(cmd, **kw):
            if cmd[:3] == ["gh", "run", "list"]:
                return "[]"
            return cmds.append(cmd) or ""

        with mock.patch.object(orchestrator, "open_pulls", lambda: [first, second]), \
                mock.patch.object(orchestrator, "_fixable_pr", lambda p: True), \
                mock.patch.object(orchestrator, "comments", lambda n: threads[n]), \
                mock.patch.object(orchestrator, "_repair_in_flight", lambda: False), \
                mock.patch.object(orchestrator, "_branch_moved_after",
                                  lambda n, ts: False), \
                mock.patch.object(orchestrator, "api_run_jobs", lambda rid: []), \
                mock.patch.object(orchestrator, "api",
                                  lambda path: [] if "comments" in path
                                  else {"labels": []}), \
                mock.patch.object(orchestrator, "post_comment", lambda n, b: None), \
                mock.patch.object(orchestrator, "run", fake_run), \
                mock.patch.object(orchestrator, "dispatch_ok",
                                  lambda cmd, **kw: fired.append(cmd) or True):
            orchestrator.fix_surrogate()
        self.assertEqual(len(fired), 1, "only one PR may dispatch per sweep")
        self.assertTrue(any("status/fixing-stuck" in " ".join(c) for c in cmds),
                        "the starved PR must still be escalated")


class TestRepairConsumedFailClosed(unittest.TestCase):
    def test_unreadable_owner_thread_reads_as_unknown(self):
        # An API failure on the story thread drops the comment-only-repair
        # evidence (#1482 shape). Unknown must never read as "not consumed":
        # that re-fires a repaired PR and ends in status/fixing-stuck.
        pr = {"number": 1500,
              "user": {"login": "rjbiermann-machinery[bot]"},
              "head": {"ref": "devloop/issue-1448-abc"}}
        with mock.patch.object(orchestrator, "comments", lambda n: []), \
                mock.patch.object(orchestrator, "_pr_owner_issue", lambda p: 1448), \
                mock.patch.object(orchestrator, "api", lambda path: None), \
                mock.patch.object(orchestrator, "run", lambda cmd, **kw: "[]"):
            self.assertTrue(orchestrator._repair_consumed(
                1500, "2026-11-23T00:00:00Z", pr=pr))


class TestVerdictAuthorIdentity(unittest.TestCase):
    def test_machinery_app_verdict_is_read(self):
        # ADR-0020 step 3: once 06 posts through the App the verdict arrives
        # as <slug>-machinery[bot]. Reading only github-actions[bot] would
        # blind both merge_readiness and fix_surrogate on that day.
        c = {"body": "**Verdict: blocker** — x",
             "created_at": "2026-11-23T00:00:00Z",
             "user": {"login": "rjbiermann-machinery[bot]", "type": "Bot"}}
        body, ts = orchestrator._latest_verdict([c])
        self.assertEqual(body, c["body"])
        self.assertEqual(ts, c["created_at"])


class TestBranchMovedAfter(unittest.TestCase):
    def moved(self, commit_date):
        def fake_api(path):
            if path.startswith("pulls/"):
                return {"head": {"sha": "959aa36"}}
            return {"commit": {"committer": {"date": commit_date},
                                "author": {"date": commit_date}}}
        with mock.patch.object(orchestrator, "api", fake_api):
            return orchestrator._branch_moved_after(1411,
                                                    "2026-11-18T23:49:02Z")

    def test_commit_after_verdict_is_consumption(self):
        self.assertTrue(self.moved("2026-11-19T00:41:00Z"))

    def test_no_commit_after_verdict_is_unserved(self):
        self.assertFalse(self.moved("2026-11-18T23:29:57Z"))

    def test_reads_head_commit_not_a_paged_list(self):
        # a >100-commit PR must still be detectable — the head commit is
        # fetched directly instead of page 1 of pulls/{n}/commits
        seen = []

        def fake_api(path):
            seen.append(path)
            if path.startswith("pulls/"):
                return {"head": {"sha": "959aa36"}}
            return {"commit": {"committer": {"date": "2026-11-19T00:41Z"}}}
        with mock.patch.object(orchestrator, "api", fake_api):
            orchestrator._branch_moved_after(1411, "2026-11-18T23:49:02Z")
        self.assertIn("commits/959aa36", seen)
        self.assertFalse(any("/commits?" in p for p in seen), seen)

    def test_missing_verdict_time_fails_closed(self):
        with mock.patch.object(orchestrator, "api") as api:
            self.assertFalse(orchestrator._branch_moved_after(1411, ""))
            api.assert_not_called()

    def test_api_failure_skips_the_sweep(self):
        # None = rate-limited/failed read; re-firing blind could cancel a
        # live repair, so an unreadable PR reads as "skip this sweep"
        with mock.patch.object(orchestrator, "api", lambda p: None):
            self.assertTrue(orchestrator._branch_moved_after(
                1411, "2026-11-18T23:49:02Z"))


class TestRepairInFlight(unittest.TestCase):
    """07's concurrency group is shared and cancel-in-progress, so a
    dispatch while a repair runs kills it (#1415 review)."""

    VERDICT_TS = "2026-11-18T23:49:02Z"

    NOW = __import__("datetime").datetime(2026, 11, 23, 18, 0, 0)

    def at(self, minutes_ago):
        ts = self.NOW - __import__("datetime").timedelta(minutes=minutes_ago)
        return ts.strftime("%Y-%m-%dT%H:%M:%SZ")

    def in_flight(self, *rows):
        """rows: status strings, or (status, minutes_ago) pairs."""
        items = []
        for r in rows:
            status, ago = (r, 60) if isinstance(r, str) else r
            items.append({"status": status, "updatedAt": self.at(ago)})
        payload = json.dumps(items)
        with mock.patch.object(orchestrator, "run", lambda cmd, **kw: payload):
            return orchestrator._repair_in_flight(now=self.NOW)

    def test_running_repair_blocks(self):
        self.assertTrue(self.in_flight("in_progress"))

    def test_queued_repair_blocks(self):
        self.assertTrue(self.in_flight("queued"))

    def test_finished_repair_does_not_block(self):
        self.assertFalse(self.in_flight(("completed", 30)))

    def test_just_finished_repair_holds_the_dispatch(self):
        # gh-aw's 600s activation cooldown: the dispatch would create a run
        # that never starts, and its marker still counts toward the stuck cap
        # (#1540: two such markers escalated a PR with zero attempts)
        self.assertTrue(self.in_flight(("completed", 2)))

    def test_repair_finished_inside_no_cooldown_blocks(self):
        self.assertTrue(self.in_flight(("completed", 9)))

    def test_cooldown_matches_the_fix_loop_frontmatter(self):
        # the constant duplicates 07's own `cooldown: 10m`; silent drift here
        # re-creates the false status/fixing-stuck it exists to prevent
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "workflows", "07-fix-loop.md")
        with open(path) as fh:
            md = fh.read()
        m = re.search(r"^\s*cooldown:\s*(\d+)m\s*$", md, re.M)
        self.assertIsNotNone(m, "07-fix-loop.md has no `cooldown: <n>m` line")
        self.assertEqual(orchestrator.FIX_LOOP_COOLDOWN_SEC, int(m.group(1)) * 60)

    def repair_cap_block(self):
        """Step 2 of 07-fix-loop.md (the cycle cap), whitespace-unwrapped so
        the assertions do not depend on where the prose happens to wrap."""
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "workflows", "07-fix-loop.md")
        with open(path) as fh:
            md = " ".join(fh.read().split())
        self.assertIn("repair round or later", md,
                      "07 step 2 no longer states the repair-round cap")
        start = md.index("repair round or later")
        return md[start:md.index("3. Otherwise:", start)]

    def test_cycle_cap_counts_repair_stamps_only(self):
        # #1567: counting every work-attempt stamp let two review rounds eat
        # the repair budget, so #1564 escalated to status/fixing-stuck after
        # two real repairs with its findings still unserved. Naming
        # `kind=fix` as counted was not enough (#1569 review): fix is the
        # Implement (and rebase) stamp, so one implement + two repairs still
        # read as "cycle 4" and the third repair stayed refused.
        block = self.repair_cap_block()
        self.assertIn("count only the `kind=repair` budget stamps", block)
        self.assertIn("after the latest `gh-aw budget: reset` stamp", block)
        self.assertNotIn("count work-attempt stamps", block)
        # Every other kind spends the ledger and must be named as excluded,
        # or "count work attempts" creeps back in one kind at a time.
        for kind in ("fix", "review", "spec", "new", "remove"):
            self.assertIn(f"`kind={kind}`", block,
                          f"kind={kind} must be named as ledger-only")

    def test_shared_budget_states_the_same_repair_cap(self):
        # Two documents state the cap (07 step 2 and shared/budget.md);
        # drift between them is what left the repair budget unbound by
        # repairs in the first place (#1567). The lifetime/daily bounds stay
        # flat, independent counters.
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "workflows", "shared", "budget.md")
        with open(path) as fh:
            md = " ".join(fh.read().split())
        self.assertIn("4th repair round", md)
        self.assertIn("`kind=repair` stamps only", md)
        self.assertIn("after the latest `reset`", md)
        self.assertIn("`MAX_ATTEMPTS` 20", md)
        self.assertIn("`DAILY_ATTEMPT_CAP` 10", md)

    def test_completed_row_without_a_timestamp_does_not_block(self):
        payload = json.dumps([{"status": "completed"}])
        with mock.patch.object(orchestrator, "run", lambda cmd, **kw: payload):
            self.assertFalse(orchestrator._repair_in_flight(now=self.NOW))

    def test_any_running_repair_blocks_even_if_old(self):
        # dispatch runs key the concurrency group on refs/heads/main, so an
        # older running repair is still the one a new dispatch cancels
        self.assertTrue(self.in_flight("in_progress", "completed"))

    def test_empty_run_list(self):
        with mock.patch.object(orchestrator, "run", lambda cmd, **kw: "[]"):
            self.assertFalse(orchestrator._repair_in_flight())

    def test_unreadable_run_list_fails_closed(self):
        # "" = gh run list failed; reading it as "nothing running" would
        # dispatch over a live repair and cancel it (rate limits hit this)
        for payload in ("", "not json"):
            with mock.patch.object(orchestrator, "run",
                                   lambda cmd, **kw: payload):
                self.assertTrue(
                    orchestrator._repair_in_flight(), payload)


class TestParentOfOwner(unittest.TestCase):
    """#1381: the sub-issue fallback queried repository(owner: OWNER) — the
    optional actor override, empty whenever GH_AW_ORCH_OWNER is unset, so
    the GraphQL query returned nothing and the straggler class (#845/#844)
    re-opened silently. The repo owner is the only owner that can hold the
    repo's issues."""

    def query_args(self, owner_env):
        seen = []

        def fake_run(cmd, **kw):
            seen.append(" ".join(cmd))
            return json.dumps({"data": {"repository": {
                "issue": {"parent": {"number": 664}}}}})

        with mock.patch.object(orchestrator, "REPO", "someone/some-repo"), \
                mock.patch.object(orchestrator, "OWNER", owner_env), \
                mock.patch.object(orchestrator, "run", fake_run):
            return orchestrator.parent_of(
                {"number": 900, "body": "no parent anchor in this body"}), seen

    def test_owner_falls_back_to_the_repo_owner(self):
        got, seen = self.query_args("")
        self.assertEqual(got, 664)
        self.assertIn("o=someone", seen[0])
        self.assertIn("r=some-repo", seen[0])

    def test_actor_override_never_reaches_the_owner_argument(self):
        # OWNER is an actor identity, not a repository owner; it must not be
        # what the query asks for
        got, seen = self.query_args("some-actor")
        self.assertEqual(got, 664)
        self.assertNotIn("o=some-actor", seen[0])


class TestBlockerVerdictShapes(unittest.TestCase):
    """The verdict-predicate contract, pinned.

    A false fire is not cheap: two of them apply status/fixing-stuck, the
    terminal needs-human label (fix_surrogate's marker cap). So only a
    positive marker or an explicit positive shape asserts a blocker; prose
    that merely contains the word stays silent. Shapes transcribed from
    #1411, #1420, #1422 and #1438 (all rounds)."""

    FIRES = [
        "**Verdict: blocker on the S1 live verification; not ready-to-merge.**",
        "**Verdict:** blocker — rows parse empty.",
        "**Verdict: Blocker (1) — not ready-to-merge.**",
        "**Verdict: 2 blockers.** Both are the change's own stated goals.",
        "**Verdict: 1 blocker + 2 non-blocking findings.**",
        "### Blocker — the row endpoint redirects away from the listing\n\n"
        "Every row uses a /video URL.",
        "### Blockers\n\n1. one-state-label invariant",
        "## Review\n\n**Blocker** — rows parse empty.",
        "**Blocker** — not a blocker for playback, but the rows parse empty.",
        "**Blockers:** 2\n\n- a\n- b",
        "**Verdict**: blocker — the row endpoint redirects.",
        "## Verdict: blocker",
        "**Verdict — blocker**\n\nrows parse empty",
        "**Verdict: blockers**",
        "**Verdict — blockers.**",
        "**Verdict: one blocker** — rows parse empty.",
        "**Verdict: 3 blockers.**",
        "### Blocker — rows parse empty",
        "### Blocker — no fallback exists; the rows parse empty.",
        "### Blocker — 0 results returned for every search.",
        "### Blockers\nNo rows parse from the listing.",
        "### Blockers\n**No** fallback exists.",
        "### Blockers\n0 results returned.",
    ]

    SILENT = [
        "**Verdict:** no blockers. All three round-1 findings are resolved.",
        "**Verdict: no blockers — 3 non-blocking findings, posted inline.**",
        "**Round-1 blocker resolved.** The fixed-35 wording is gone.\n\n"
        "**Verdict: no blockers.**",
        "### Blockers\nNone.",
        "### Blockers\n**None.**",
        "## Review\n\nsecurity/flagged stands, no blocker.",
        "Verdict: not a blocker.",
        "2. **`negated_blocker` was body-wide** — fixed.",
        "no outstanding blockers on the remaining surfaces",
        "Blockers: none.",
        "**Blockers: none**",
        "**Blockers: 0**",
        "**Blocker resolved** — the row endpoint now 200s.",
        "**Verdict: 0 blockers.**",
        "**Verdict: 0 blocker.**",
        "### Blocker resolved — the row endpoint now 200s.",
        "**Blockers** — none.",
        "**Blocker (0)**",
        "**Verdict: blocker-free.**",
        "### Blockers\n**None.**",
        # a quoted prior-round verdict must not outvote the current one
        "> **Verdict: blocker** — quoted from round 1\n\n"
        "**Verdict:** no blockers.\n\nAll three findings are fixed.",
    ]

    def test_recorded_blocker_shapes_fire(self):
        for shape in self.FIRES:
            self.assertTrue(orchestrator._blocker_verdict(shape.lower()), shape)

    def test_recorded_pass_shapes_stay_silent(self):
        for shape in self.SILENT:
            self.assertFalse(orchestrator._blocker_verdict(shape.lower()), shape)

    # Pass verdicts that a thread scan must still see; the rest of SILENT is
    # prose that merely mentions the word (a bullet, a negation) and is not a
    # verdict at all.
    PASS_VERDICTS = [
        "**Verdict:** no blockers. All three round-1 findings are resolved.",
        "**Verdict: no blockers — 3 non-blocking findings, posted inline.**",
        "## Verdict: no blockers",
        "### Verdict — no blockers",
        "# Verdict: 0 blockers",
        "Round 2 review — 0 blockers.",
        "### Blockers\nNone.",
    ]

    def test_every_pinned_shape_survives_the_thread_scan(self):
        """The gate must admit every verdict shape, or `_latest_verdict()`
        falls back to an older verdict and re-fires on a PR that passed
        (#1469 R5)."""
        for shape in self.FIRES + self.PASS_VERDICTS:
            thread = [{"body": shape, "user": {"login": "github-actions[bot]"},
                       "created_at": "2026-11-21T20:00:00Z"}]
            body, _ = orchestrator._latest_verdict(thread)
            self.assertEqual(body, shape, shape)


class TestVerdictScanShapes(unittest.TestCase):
    """#1466: the work-attempt stamp says "06-review round 1 on #1466", so a
    scan keyed on mentions read the stamp as the verdict and skipped the
    blocker under it. Only a heading/marker is a verdict."""

    VERDICT = "## Review — 1 blocker (registry-entry anchor)\n\nbody"
    STAMP = ("gh-aw budget: attempt=3 kind=review run=https://x credits=1 "
             "**06-review round 1 on #1466 — findings only, nothing pushed.**")

    def thread(self, *bodies):
        return [{"body": b, "user": {"login": "github-actions[bot]"},
                 "created_at": "2026-11-21T19:32:3%dZ" % i}
                for i, b in enumerate(bodies)]

    def test_stamp_mention_is_not_a_verdict(self):
        body, _ = orchestrator._latest_verdict(self.thread(self.VERDICT,
                                                           self.STAMP))
        self.assertEqual(body, self.VERDICT)
        self.assertTrue(orchestrator._blocker_verdict(body.lower()))

    def test_marker_heading_is_a_verdict(self):
        body, _ = orchestrator._latest_verdict(
            self.thread("**Verdict: blocker** — rows parse empty."))
        self.assertTrue(orchestrator._blocker_verdict(body.lower()))

    def test_newest_verdict_wins(self):
        body, _ = orchestrator._latest_verdict(
            self.thread(self.VERDICT, "## Review — findings (round 2)\n\n"
                                      "**Verdict:** no blockers."))
        self.assertFalse(orchestrator._blocker_verdict(body.lower()))

    def test_list_and_quote_forms_are_verdicts(self):
        for body in ("- **Verdict: blocker** — rows parse empty.",
                     "> **Verdict:** no blockers.",
                     "* Verdict: blocker (2) on streams."):
            self.assertTrue(orchestrator.VERDICT_HINT.search(body), body)

    def test_stamp_still_not_a_verdict(self):
        self.assertFalse(orchestrator.VERDICT_HINT.search(self.STAMP))

    def test_no_verdict_at_all(self):
        self.assertEqual(orchestrator._latest_verdict(self.thread(self.STAMP)),
                         ("", ""))


class TestMergeReadinessRespectsBlockerVerdict(unittest.TestCase):
    """A green-checks PR with a live blocker verdict must not be labelled
    ready (PR #1466 was, 3 minutes after its blocker)."""

    def test_blocker_verdict_withholds_the_label(self):
        pr = {"number": 1466, "user": {"login": "rjbiermann-machinery[bot]"},
              "head": {"ref": "devloop/issue-1439-x"}}
        with mock.patch.object(orchestrator, "open_pulls", lambda: [pr]), \
                mock.patch.object(orchestrator, "_pr_labels_map", lambda n: {}), \
                mock.patch.object(orchestrator, "_effective_ready",
                                  lambda n, s: True), \
                mock.patch.object(orchestrator, "api", lambda p: (
                    [{"body": "## Review — 1 blocker\n\nregistry entry unanchored",
                      "user": {"login": "github-actions[bot]"},
                      "created_at": "2026-11-21T19:32:35Z"}]
                    if p.startswith("issues/") and "/comments" in p
                    else {"mergeable_state": "clean"})), \
                mock.patch.object(orchestrator, "label_issue",
                                  lambda n, l: self.fail("labelled a blocked PR")), \
                mock.patch.object(orchestrator, "post_comment",
                                  lambda n, b: self.fail("pinged a blocked PR")), \
                mock.patch.object(orchestrator, "print", lambda *a, **k: None):
            orchestrator.merge_readiness()


class TestMergeReadinessRespectsFixingStuck(unittest.TestCase):
    """A PR the lane declared needs-human must not be reported ready, however
    green its checks are (#1405: a pass round's ready-to-merge label can
    otherwise survive the escalation)."""

    def test_fixing_stuck_pr_is_skipped(self):
        printouts = []
        pr = {"number": 1438, "user": {"login": "rjbiermann-machinery[bot]"},
              "head": {"ref": "devloop/issue-1410-x"}}
        with mock.patch.object(orchestrator, "open_pulls", lambda: [pr]), \
                mock.patch.object(orchestrator, "_pr_labels_map",
                                  lambda nums: {1438: {"status/fixing-stuck"}}), \
                mock.patch.object(orchestrator, "_effective_ready", lambda n, s: True), \
                mock.patch.object(orchestrator, "api", lambda p: (
                    [] if "/comments" in p else {"mergeable_state": "clean"})), \
                mock.patch.object(orchestrator, "label_issue",
                                  lambda n, l: self.fail("labelled a needs-human PR")), \
                mock.patch.object(orchestrator, "post_comment",
                                  lambda n, b: self.fail("pinged a needs-human PR")), \
                mock.patch.object(orchestrator, "print", printouts.append, create=True):
            orchestrator.merge_readiness()
        self.assertEqual(len(printouts), 1, printouts)
        self.assertIn("fixing-stuck", printouts[0])


class TestReadinessRelabelAfterBlocker(unittest.TestCase):
    """#1469 R4/R5: three sweeps on one PR — pass labels it, a blocker clears
    the label, a pass at the SAME head (the /review-without-a-push case) must
    label it again. The thread accumulates posted comments and the label set
    is modelled, so suppression is exercised rather than assumed."""

    PR = {"number": 1466, "user": {"login": "rjbiermann-machinery[bot]"},
          "head": {"ref": "devloop/issue-1439-x"}}
    SHA = "ac8cf79d6355"

    def sweep(self, state, verdict_body=None, vtime=None):
        if verdict_body is not None:
            state["thread"].append({"body": verdict_body,
                                    "user": {"login": "github-actions[bot]"},
                                    "created_at": vtime})

        def api(path):
            if "/comments" in path:
                return state["thread"]
            if path.startswith("pulls/"):
                return {"mergeable_state": "clean", "head": {"sha": self.SHA}}
            if path.startswith("commits/"):
                return {"commit": {"committer": {"date": "2026-11-21T18:00:00Z"}}}
            return {}

        def label_issue(n, l):
            state["labelled"].append(l)
            state["labels"].add(l)

        def post_comment(n, body):
            state["posted"].append(body)
            state["thread"].append({"body": body,
                                    "user": {"login": "github-actions[bot]"},
                                    "created_at": "2026-11-21T21:00:0%d Z" % len(state["posted"])})

        with mock.patch.object(orchestrator, "open_pulls", lambda: [self.PR]), \
                mock.patch.object(orchestrator, "_pr_labels_map",
                                  lambda n: ({1466: set(state["labels"])}
                                             if state["labels"] else {})), \
                mock.patch.object(orchestrator, "_effective_ready", lambda n, s: True), \
                mock.patch.object(orchestrator, "api", api), \
                mock.patch.object(orchestrator, "label_issue", label_issue), \
                mock.patch.object(orchestrator, "post_comment", post_comment), \
                mock.patch.object(orchestrator, "run",
                                  lambda cmd, **kw: state["deleted"].append(cmd) or ""), \
                mock.patch.object(orchestrator, "print", lambda *a, **k: None):
            orchestrator.merge_readiness()

    def test_blocker_clears_then_a_later_pass_relabels_at_the_same_head(self):
        state = {"thread": [], "labels": set(), "labelled": [], "posted": [],
                 "deleted": []}
        self.sweep(state, "**Verdict:** no blockers.", "2026-11-21T17:00:00Z")
        self.assertEqual(state["labelled"], ["status/ready-to-merge"])

        state["labelled"].clear()
        self.sweep(state, "**Verdict: blocker** — registry unanchored.",
                   "2026-11-21T19:00:00Z")
        self.assertEqual(state["labelled"], [])
        self.assertTrue(any("ready-to-merge" in " ".join(c) for c in state["deleted"]))

        state["labels"].clear()
        self.sweep(state, "**Verdict:** no blockers.", "2026-11-21T20:00:00Z")
        self.assertEqual(state["labelled"], ["status/ready-to-merge"],
                         "a pass at the same head must re-label")


class TestMergeReadinessRespectsFixingStuck(unittest.TestCase):
    """A PR the lane declared needs-human must not be reported ready, however
    green its checks are (#1405: a pass round's ready-to-merge label can
    otherwise survive the escalation)."""

    def test_fixing_stuck_pr_is_skipped(self):
        printouts = []
        pr = {"number": 1438, "user": {"login": "rjbiermann-machinery[bot]"},
              "head": {"ref": "devloop/issue-1410-x"}}
        with mock.patch.object(orchestrator, "open_pulls", lambda: [pr]), \
                mock.patch.object(orchestrator, "_pr_labels_map",
                                  lambda nums: {1438: {"status/fixing-stuck"}}), \
                mock.patch.object(orchestrator, "_effective_ready", lambda n, s: True), \
                mock.patch.object(orchestrator, "api", lambda p: (
                    [] if "/comments" in p else {"mergeable_state": "clean"})), \
                mock.patch.object(orchestrator, "label_issue",
                                  lambda n, l: self.fail("labelled a needs-human PR")), \
                mock.patch.object(orchestrator, "post_comment",
                                  lambda n, b: self.fail("pinged a needs-human PR")), \
                mock.patch.object(orchestrator, "print", printouts.append, create=True):
            orchestrator.merge_readiness()
        self.assertEqual(len(printouts), 1, printouts)
        self.assertIn("fixing-stuck", printouts[0])


class TestReadinessMarkerAnchorsOnVerdict(unittest.TestCase):
    """#1469 R4: the "ready … at <sha>" comment suppressed re-labelling after
    a blocker cleared the label and a re-review passed at the SAME head (the
    /review-without-a-push case)."""

    def components(self, verdict_body, vtime, seen):
        pr = {"number": 1466, "user": {"login": "rjbiermann-machinery[bot]"},
              "head": {"ref": "devloop/issue-1439-x"}}
        thread = ([{"body": verdict_body, "user": {"login": "github-actions[bot]"},
                    "created_at": vtime}] if verdict_body else [])

        def api(path):
            if "/comments" in path:
                return thread
            if path.startswith("pulls/"):
                return {"mergeable_state": "clean",
                        "head": {"sha": "ac8cf79d6355"}}
            return {}

        def post_comment(n, body):
            seen.append(body)

        with mock.patch.object(orchestrator, "open_pulls", lambda: [pr]), \
                mock.patch.object(orchestrator, "_pr_labels_map", lambda n: {}), \
                mock.patch.object(orchestrator, "_effective_ready", lambda n, s: True), \
                mock.patch.object(orchestrator, "comments", lambda n: thread), \
                mock.patch.object(orchestrator, "api", api), \
                mock.patch.object(orchestrator, "label_issue", lambda n, l: None), \
                mock.patch.object(orchestrator, "post_comment", post_comment), \
                mock.patch.object(orchestrator, "print", lambda *a, **k: None):
            orchestrator.merge_readiness()

    def test_marker_differs_per_verdict_at_the_same_head(self):
        seen = []
        self.components("**Verdict:** no blockers.", "2026-11-21T17:00:00Z", seen)
        self.components("**Verdict:** no blockers.", "2026-11-21T19:00:00Z", seen)
        self.assertEqual(len(seen), 2, seen)
        self.assertNotEqual(seen[0], seen[1])
        for body in seen:
            self.assertIn("verdict 2026-11-21T", body)

    def test_unreadable_thread_withholds_readiness(self):
        pr = {"number": 1466, "user": {"login": "rjbiermann-machinery[bot]"},
              "head": {"ref": "devloop/issue-1439-x"}}
        prints = []
        with mock.patch.object(orchestrator, "open_pulls", lambda: [pr]), \
                mock.patch.object(orchestrator, "_pr_labels_map", lambda n: {}), \
                mock.patch.object(orchestrator, "api", lambda p: None), \
                mock.patch.object(orchestrator, "label_issue",
                                  lambda n, l: self.fail("labelled without reading the thread")), \
                mock.patch.object(orchestrator, "post_comment",
                                  lambda n, b: self.fail("pinged without reading the thread")), \
                mock.patch.object(orchestrator, "print", prints.append, create=True):
            orchestrator.merge_readiness()
        self.assertTrue(any("unreadable" in str(p) for p in prints), prints)


class TestConflictWatchUnknownState(unittest.TestCase):
    """PR #1466: `mergeable_state` read "unknown" (actor-cold cache) while the
    branch really conflicted, and a strictly-dirty gate meant the conflict was
    never looked at — every sweep logged readiness only. The local merge-tree
    is cache-free, so "unknown" must fall through to it."""

    PR = {"number": 1466, "user": {"login": "rjbiermann-machinery[bot]"},
          "head": {"ref": "devloop/issue-1439-x"}, "draft": False}

    def run_watch(self, mstate, files):
        resolved = []
        with mock.patch.object(orchestrator, "open_pulls", lambda: [self.PR]), \
                mock.patch.object(orchestrator, "_pr_labels_map", lambda n: {}), \
                mock.patch.object(orchestrator, "_pl", lambda a, **k: (0, "")), \
                mock.patch.object(orchestrator, "api",
                                  lambda p: {"mergeable_state": mstate}), \
                mock.patch.object(orchestrator, "_conflict_files", lambda b: files), \
                mock.patch.object(orchestrator, "_auto_resolve_conflict",
                                  lambda b: resolved.append(b) or True), \
                mock.patch.object(orchestrator, "post_comment", lambda n, b: None), \
                mock.patch.object(orchestrator, "run", lambda c, **k: ""):
            orchestrator.conflict_watch()
        return resolved

    def test_unknown_state_still_finds_the_mechanical_class(self):
        resolved = self.run_watch("unknown", frozenset({"PornHub/build.gradle.kts"}))
        self.assertEqual(resolved, ["devloop/issue-1439-x"])

    def test_unknown_state_with_a_clean_tree_does_nothing(self):
        self.assertEqual(self.run_watch("unknown", frozenset()), [])

    def test_unknown_state_with_a_non_mechanical_conflict_hands_off(self):
        # the fix must still reach the 14-rebase handoff for real conflicts
        posted, labelled = [], []
        with mock.patch.object(orchestrator, "open_pulls", lambda: [self.PR]), \
                mock.patch.object(orchestrator, "_pr_labels_map", lambda n: {}), \
                mock.patch.object(orchestrator, "_pl", lambda a, **k: (0, "")), \
                mock.patch.object(orchestrator, "api",
                                  lambda p: {"mergeable_state": "unknown"}), \
                mock.patch.object(orchestrator, "_conflict_files",
                                  lambda b: frozenset({"HQPorner/src/main/kotlin/X.kt"})), \
                mock.patch.object(orchestrator, "_auto_resolve_conflict",
                                  lambda b: self.fail("non-mechanical conflict resolved")), \
                mock.patch.object(orchestrator, "comments", lambda n: []), \
                mock.patch.object(orchestrator, "post_comment",
                                  lambda n, b: posted.append(b)), \
                mock.patch.object(orchestrator, "run",
                                  lambda c, **k: labelled.append(c) or ""):
            orchestrator.conflict_watch()
        self.assertTrue(any("merge conflict detected" in b for b in posted), posted)
        self.assertTrue(any("status/conflict" in " ".join(c) for c in labelled),
                        labelled)

    def test_dirty_with_empty_files_still_hands_off(self):
        # unreadable merge-tree under "dirty" used to reach the handoff; the
        # unknown-state no-op must not swallow it
        posted = []
        with mock.patch.object(orchestrator, "open_pulls", lambda: [self.PR]), \
                mock.patch.object(orchestrator, "_pr_labels_map", lambda n: {}), \
                mock.patch.object(orchestrator, "_pl", lambda a, **k: (0, "")), \
                mock.patch.object(orchestrator, "api",
                                  lambda p: {"mergeable_state": "dirty"}), \
                mock.patch.object(orchestrator, "_conflict_files",
                                  lambda b: frozenset()), \
                mock.patch.object(orchestrator, "comments", lambda n: []), \
                mock.patch.object(orchestrator, "post_comment",
                                  lambda n, b: posted.append(b)), \
                mock.patch.object(orchestrator, "run", lambda c, **k: ""):
            orchestrator.conflict_watch()
        self.assertTrue(any("unresolvable via merge-tree" in b for b in posted),
                        posted)

    def test_dirty_with_an_errored_merge_tree_still_hands_off(self):
        # `None` = git error (missing ref / shallow checkout). A dirty PR must
        # still reach the 14-rebase handoff — skipping was the strand this pass
        # removes elsewhere (round-4 review).
        posted = []
        with mock.patch.object(orchestrator, "open_pulls", lambda: [self.PR]), \
                mock.patch.object(orchestrator, "_pr_labels_map", lambda n: {}), \
                mock.patch.object(orchestrator, "_pl", lambda a, **k: (0, "")), \
                mock.patch.object(orchestrator, "api",
                                  lambda p: {"mergeable_state": "dirty"}), \
                mock.patch.object(orchestrator, "_conflict_files", lambda b: None), \
                mock.patch.object(orchestrator, "comments", lambda n: []), \
                mock.patch.object(orchestrator, "post_comment",
                                  lambda n, b: posted.append(b)), \
                mock.patch.object(orchestrator, "run", lambda c, **k: ""):
            orchestrator.conflict_watch()
        self.assertTrue(any("unresolvable via merge-tree" in b for b in posted),
                        posted)

    def test_mergeable_states_are_left_alone(self):
        for state in ("blocked", "unstable", "clean", "behind"):
            self.assertEqual(self.run_watch(state, frozenset({"x.kt"})), [], state)


class TestRepairConsumedWithoutPush(unittest.TestCase):
    """#1482 / #1485: a comment-only repair consumes, but only a run for
    THIS PR — the repo-wide run list alone let another PR's repair mark the
    verdict served, and an abort stamp naming another round must not block a
    later successful repair."""

    VERDICT_TS = "2026-11-21T23:30:10Z"
    MARKER_TS = "2026-11-21T23:59:41Z"
    RUN_TS = "2026-11-21T23:59:43Z"

    def setUp(self):
        # the closing-reference cache is module state (one pass = one cache)
        orchestrator._closes_cache_reset()

    def marker_comment(self):
        return {"body": f"{orchestrator.MARK} fix-loop re-fired for the "
                        f"verdict at {self.VERDICT_TS} — dispatch fires 07 "
                        f"(machine relay, ADR-0019)",
                "user": {"login": "rjbiermann-machinery[bot]"},
                "created_at": self.MARKER_TS}

    @staticmethod
    def run_stamp(rid, body=None):
        return {"body": body or (
                    f"gh-aw budget: attempt=3 kind=repair "
                    f"run=https://github.com/o/r/actions/runs/{rid} credits=1"),
                "user": {"login": "rjbiermann-machinery[bot]"},
                "created_at": "2026-11-22T00:02:25Z"}

    def run_consumed(self, runs, thread, jobs=None, pr=None, owner_thread=(),
                     unreadable_jobs=False):
        jobs = jobs or {
            "149": [{"name": "agent", "conclusion": "success"}],
            "150": [{"name": "agent", "conclusion": "skipped"}]}

        def fake_run(cmd, **kw):
            if cmd[:3] == ["gh", "run", "list"]:
                # "" is how run() reports a failed gh call (never `[]`)
                return "" if runs is None else json.dumps(runs)
            return ""

        def fake_jobs(rid):            # api_run_jobs returns a job list
            return None if unreadable_jobs else jobs.get(str(rid), [])

        # `_repair_consumed` asks for the owner thread only when it derived
        # an owner; the mock needs no owner to answer.
        def fake_api(path):
            return list(owner_thread) if "comments" in path else None

        with mock.patch.object(orchestrator, "run", fake_run), \
                mock.patch.object(orchestrator, "api_run_jobs", fake_jobs), \
                mock.patch.object(orchestrator, "api", fake_api), \
                mock.patch.object(orchestrator, "comments", lambda n: thread):
            return orchestrator._repair_consumed(1482, self.VERDICT_TS,
                                                 cs=thread, pr=pr)

    def test_comment_only_repair_consumes(self):
        self.assertTrue(self.run_consumed(
            [{"databaseId": 149, "createdAt": self.RUN_TS}],
            [self.marker_comment(), self.run_stamp(149)]))

    def test_abort_stamp_on_owner_issue_is_not_consumption(self):
        # Rule 0 posts ABORT on the owner/story issue, never on the PR
        pr = {"number": 1482, "head": {"ref": "devloop/issue-1440-x"}}
        abort = self.run_stamp(
            149, "**ABORT (Rule 0 pre-push identity guard) — no push.**\n"
                 "- Run: https://github.com/o/r/actions/runs/149")
        self.assertFalse(self.run_consumed(
            [{"databaseId": 149, "createdAt": self.RUN_TS}],
            [self.marker_comment(), self.run_stamp(149)],
            pr=pr, owner_thread=[abort]))

    def test_abort_stamp_for_another_run_does_not_block_this_one(self):
        # an earlier abort must not switch the path off for a later repair
        pr = {"number": 1482, "head": {"ref": "devloop/issue-1440-x"}}
        old_abort = self.run_stamp(
            148, "**ABORT (Rule 0 pre-push identity guard) — no push.**\n"
                 "- Run: https://github.com/o/r/actions/runs/148")
        self.assertTrue(self.run_consumed(
            [{"databaseId": 149, "createdAt": self.RUN_TS}],
            [self.marker_comment(), self.run_stamp(149)],
            pr=pr, owner_thread=[old_abort]))

    def test_run_for_another_pr_does_not_consume(self):
        # run 999 never posted on this PR/owner issue → no PR identity
        self.assertFalse(self.run_consumed(
            [{"databaseId": 999, "createdAt": self.RUN_TS}],
            [self.marker_comment(), self.run_stamp(149)]))

    def test_prose_mentioned_pr_thread_does_not_supply_evidence(self):
        # #1485 round 2, end to end: the body names the symptom PR from prose,
        # so that thread must not be scanned as this delivery's evidence.
        pr = {"number": 1485,
              "head": {"ref": "maint/repair-consumed-without-push"},
              "title": "fix(conveyor): a comment-only repair consumed its verdict",
              "body": "Fixes why **#1482** was marked stuck when it had been "
                      "repaired twice."}
        self.assertFalse(self.run_consumed(
            [{"databaseId": 149, "createdAt": self.RUN_TS}],
            [self.marker_comment()],
            pr=pr, owner_thread=[self.run_stamp(149)]))

    def test_failed_run_does_not_consume(self):
        jobs = {"149": [{"name": "agent", "conclusion": "failure"}]}
        self.assertFalse(self.run_consumed(
            [{"databaseId": 149, "createdAt": self.RUN_TS}],
            [self.marker_comment(), self.run_stamp(149)], jobs=jobs))

    def test_cancelled_run_does_not_consume(self):
        jobs = {"149": [{"name": "agent", "conclusion": "cancelled"}]}
        self.assertFalse(self.run_consumed(
            [{"databaseId": 149, "createdAt": self.RUN_TS}],
            [self.marker_comment(), self.run_stamp(149)], jobs=jobs))

    def test_skipped_run_does_not_consume(self):
        self.assertFalse(self.run_consumed(
            [{"databaseId": 150, "createdAt": self.RUN_TS}],
            [self.marker_comment(), self.run_stamp(150)]))

    def test_run_predating_the_verdict_does_not_consume(self):
        self.assertFalse(self.run_consumed(
            [{"databaseId": 149, "createdAt": "2026-11-21T23:00:00Z"}],
            [self.marker_comment(), self.run_stamp(149)]))

    def test_run_before_this_dispatch_marker_does_not_consume(self):
        self.assertFalse(self.run_consumed(
            [{"databaseId": 149, "createdAt": "2026-11-21T23:45:00Z"}],
            [self.marker_comment(), self.run_stamp(149)]))

    def test_run_in_the_marker_second_consumes(self):
        # `<=` dropped a run created in its own fire marker's second: the
        # marker is posted, then `gh workflow run` — the run cannot precede it.
        self.assertTrue(self.run_consumed(
            [{"databaseId": 149, "createdAt": self.MARKER_TS}],
            [self.marker_comment(), self.run_stamp(149)]))

    def test_unreadable_run_list_does_not_refire(self):
        # unknown must skip the sweep, never dispatch blind (like _repair_in_flight)
        self.assertTrue(self.run_consumed(
            None, [self.marker_comment(), self.run_stamp(149)]))

    def test_unreadable_jobs_does_not_refire(self):
        self.assertTrue(self.run_consumed(
            [{"databaseId": 149, "createdAt": self.RUN_TS}],
            [self.marker_comment(), self.run_stamp(149)],
            unreadable_jobs=True))


class TestPrOwnerIssue(unittest.TestCase):
    """#1485 round 2: which thread counts as this PR's evidence.

    `_pr_owner_issue` decides where Rule 0's abort stamp lives, and the
    owner thread is also scanned for the run's own safe-output. A loose
    prose parse derived the symptom PR from `Fixes why #1482 …`, and then
    read another delivery's thread as this PR's evidence. The branch
    segment and GitHub's `closingIssuesReferences` are the only two
    identities; prose is not one."""

    @staticmethod
    def owner_for(pr, closed=()):
        def fake_run(cmd, **kw):
            if cmd[:3] == ["gh", "api", "graphql"]:
                nodes = [{"number": n} for n in closed]
                return json.dumps({"data": {"repository": {"pullRequest": {
                    "closingIssuesReferences": {"nodes": nodes}}}}} )
            return ""
        with mock.patch.object(orchestrator, "run", fake_run):
            return orchestrator._pr_owner_issue(pr)

    def setUp(self):
        orchestrator._closes_cache_reset()

    def test_branch_issue_segment_wins(self):
        self.assertEqual(self.owner_for(
            {"number": 1485, "head": {"ref": "devloop/issue-1440-x"},
             "title": "", "body": ""}, closed=[9999]), 1440)

    def test_github_closing_reference_is_the_owner(self):
        self.assertEqual(self.owner_for(
            {"number": 1485, "head": {"ref": "maint/hand-delivery"},
             "title": "fix: x", "body": "Fixes #1440"}, closed=[1440]), 1440)

    def test_prose_mentioning_another_pr_is_not_the_owner(self):
        # the real #1485 shape: prose names the symptom PR, GitHub closes none
        self.assertIsNone(self.owner_for(
            {"number": 1485, "head": {"ref": "maint/repair-consumed-without-push"},
             "title": "fix(conveyor): a comment-only repair consumed its verdict",
             "body": "Fixes why **#1482** was marked stuck when it had been "
                     "repaired twice."}))

    def test_ambiguous_closing_references_are_not_guessed(self):
        self.assertIsNone(self.owner_for(
            {"number": 1485, "head": {"ref": "maint/hand-delivery"},
             "title": "", "body": "Closes #1440, #1441"}, closed=[1440, 1441]))


class TestRegistryCandidateMachineryAuthor(unittest.TestCase):
    """#1482: the anchored-entry self-heal only accepted github-actions[bot],
    so an unanchored entry posted by the machinery App (ADR-0020) was
    invisible — the registry never landed and the review blocker never
    cleared, across two full repair rounds."""

    BODY = ('Registry entry for audits/findings.json\n\n```json\n'
            '{"providers": {"MissAV": {"verdict": "ok-drift"}}}\n```')

    def candidate(self, login):
        thread = [{"body": self.BODY, "user": {"login": login}}]
        return orchestrator._registry_candidate(thread)

    def test_machinery_app_comment_is_a_candidate(self):
        self.assertIsNotNone(self.candidate("rjbiermann-machinery[bot]"))

    def test_legacy_bot_login_still_works(self):
        self.assertIsNotNone(self.candidate("github-actions[bot]"))

    def test_human_comment_is_not_a_candidate(self):
        self.assertIsNone(self.candidate("RjBiermann"))

    def test_anchored_comment_is_not_a_candidate(self):
        thread = [{"body": orchestrator.REGISTRY_ANCHOR + "\n" + self.BODY.split("\n", 2)[2],
                   "user": {"login": "rjbiermann-machinery[bot]"}}]
        self.assertIsNone(orchestrator._registry_candidate(thread))


class TestReadinessClearsRepairState(unittest.TestCase):
    """A repair's status/fixing must not survive the pass verdict.

    07 step 4 tells the agent to remove the label after its push, but on a
    relay-fired run gh-aw resolves remove-labels to the *story* issue: PR
    #1516's repair issued DELETE issues/1448/labels/status%2Ffixing, got 404,
    logged "not present, skipping", and left the PR labelled status/fixing.
    Round 2's pass then added status/ready-to-merge on top (#1405)."""

    def run_readiness(self, labels, verdict, clear_rc=0):
        pr = {"number": 1516,
              "user": {"login": "rjbiermann-machinery[bot]"},
              "head": {"ref": "devloop/issue-1448-x", "sha": "5891d79"}}
        cmds = []
        added = []
        thread = ([] if verdict is None else
                  [{"body": verdict, "created_at": "2026-11-23T03:06:52Z",
                    "user": {"login": "github-actions[bot]"}}])

        def fake_run(cmd, **kw):
            cmds.append(cmd)
            return ""

        def fake_run_rc(cmd, **kw):
            cmds.append(cmd)
            return clear_rc

        def fake_api(path, *a, **kw):
            if path.startswith("pulls/"):
                return {"mergeable_state": "clean", "head": {"sha": "5891d79"}}
            return thread

        with mock.patch.object(orchestrator, "open_pulls", lambda: [pr]), \
                mock.patch.object(orchestrator, "_agent_pr", lambda p: True), \
                mock.patch.object(orchestrator, "_pr_labels_map",
                                  lambda nums: {1516: set(labels)}), \
                mock.patch.object(orchestrator, "api", fake_api), \
                mock.patch.object(orchestrator, "run", fake_run), \
                mock.patch.object(orchestrator, "run_rc", fake_run_rc), \
                mock.patch.object(orchestrator, "_label_applied_at",
                                  lambda n, name: "2026-11-23T02:55:00Z"), \
                mock.patch.object(orchestrator, "_effective_ready",
                                  lambda n, m: True), \
                mock.patch.object(orchestrator, "AUTO_MERGE", False), \
                mock.patch.object(orchestrator, "_has_marker",
                                  lambda cs, m: False), \
                mock.patch.object(orchestrator, "comments", lambda n: []), \
                mock.patch.object(orchestrator, "label_issue",
                                  lambda n, lbl: added.append(lbl)), \
                mock.patch.object(orchestrator, "post_comment",
                                  lambda n, b: None):
            orchestrator.merge_readiness()
        return [" ".join(c) for c in cmds], added

    def test_pass_verdict_clears_status_fixing(self):
        cmds, _ = self.run_readiness({"status/fixing"},
                                     "**Verdict:** no blockers")
        self.assertTrue(any("status%2Ffixing" in c for c in cmds), cmds)

    def test_blocker_verdict_keeps_status_fixing(self):
        # a live blocker means the repair round is exactly what is wanted —
        # the label must not be cleared on that path.
        cmds, _ = self.run_readiness({"status/fixing"},
                                     "**Verdict: blocker** — Codacy red.")
        self.assertFalse(any("status%2Ffixing" in c for c in cmds), cmds)

    def test_fixing_stuck_prevents_clearing(self):
        cmds, added = self.run_readiness(
            {"status/fixing", "status/fixing-stuck"}, "**Verdict:** no blockers")
        self.assertFalse(any("status%2Ffixing" in c for c in cmds), cmds)
        self.assertNotIn("status/ready-to-merge", added)

    def test_no_verdict_leaves_status_fixing(self):
        # review finding 1: without an explicit verdict the repair state must
        # not be cleared, and readiness must not be published over it.
        cmds, added = self.run_readiness({"status/fixing"}, None)
        self.assertFalse(any("status%2Ffixing" in c for c in cmds), cmds)
        self.assertNotIn("status/ready-to-merge", added)

    def test_failed_clear_does_not_publish_readiness(self):
        # review finding 2: a failed DELETE must not be reported as success,
        # and readiness must not add status/ready-to-merge on top of the
        # surviving label (a later sweep retries the clear).
        cmds, added = self.run_readiness({"status/fixing"},
                                         "**Verdict:** no blockers",
                                         clear_rc=1)
        self.assertTrue(any("status%2Ffixing" in c for c in cmds), cmds)
        self.assertNotIn("status/ready-to-merge", added)


class TestConflictFilesUnknown(unittest.TestCase):
    """merge-tree rc>=2 is 'unknown', never 'no conflict'.

    Returning an empty set for a git error deleted status/conflict and skipped
    the 14-rebase handoff on a conflict that was still live."""

    def test_git_error_is_unknown(self):
        with mock.patch.object(orchestrator, "_merge_tree",
                               lambda b: (128, None, frozenset())):
            self.assertIsNone(orchestrator._conflict_files("devloop/x"))

    def test_clean_merge_is_an_empty_set(self):
        with mock.patch.object(orchestrator, "_merge_tree",
                               lambda b: (0, None, frozenset())):
            self.assertEqual(orchestrator._conflict_files("x"), frozenset())


class TestReadinessStateLabelGuards(unittest.TestCase):
    """Round-2 findings on the consolidated branch: a half-done transition
    must not publish readiness, and a pass older than the label is not one."""

    def run_readiness(self, labels, verdict, clear_rc=0, label_at=None):
        pr = {"number": 1516,
              "user": {"login": "rjbiermann-machinery[bot]"},
              "head": {"ref": "devloop/issue-1448-x", "sha": "5891d79"}}
        cmds, added = [], []
        thread = ([] if verdict is None else
                  [{"body": verdict, "created_at": "2026-11-23T03:06:52Z",
                    "user": {"login": "github-actions[bot]"}}])

        def fake_run_rc(cmd, **kw):
            cmds.append(cmd)
            return clear_rc

        def fake_api(path, *a, **kw):
            if path.startswith("pulls/"):
                return {"mergeable_state": "clean", "head": {"sha": "5891d79"}}
            return thread

        with mock.patch.object(orchestrator, "open_pulls", lambda: [pr]), \
                mock.patch.object(orchestrator, "_agent_pr", lambda p: True), \
                mock.patch.object(orchestrator, "_pr_labels_map",
                                  lambda nums: {1516: set(labels)}), \
                mock.patch.object(orchestrator, "api", fake_api), \
                mock.patch.object(orchestrator, "run", lambda cmd, **kw: ""), \
                mock.patch.object(orchestrator, "run_rc", fake_run_rc), \
                mock.patch.object(orchestrator, "_label_applied_at",
                                  lambda n, name: label_at), \
                mock.patch.object(orchestrator, "_effective_ready",
                                  lambda n, m: True), \
                mock.patch.object(orchestrator, "AUTO_MERGE", False), \
                mock.patch.object(orchestrator, "_has_marker",
                                  lambda cs, m: False), \
                mock.patch.object(orchestrator, "comments", lambda n: []), \
                mock.patch.object(orchestrator, "label_issue",
                                  lambda n, lbl: added.append(lbl)), \
                mock.patch.object(orchestrator, "post_comment",
                                  lambda n, b: None):
            orchestrator.merge_readiness()
        return [" ".join(c) for c in cmds], added

    def test_failed_conflict_clear_does_not_publish_readiness(self):
        cmds, added = self.run_readiness({"status/conflict"},
                                         "**Verdict:** no blockers", clear_rc=1)
        self.assertTrue(any("status%2Fconflict" in c for c in cmds), cmds)
        self.assertEqual(added, [], "two state labels on a failed clear")

    def test_pass_older_than_the_fixing_label_is_not_a_clear(self):
        # 07 also fires from a human review; the bot pass may predate the
        # repair. Clearing then drops a live repair's state.
        cmds, _ = self.run_readiness({"status/fixing"},
                                     "**Verdict:** no blockers",
                                     label_at="2026-11-23T04:00:00Z")
        self.assertFalse(any("status%2Ffixing" in c for c in cmds), cmds)

    def test_unknown_label_time_keeps_the_repair_state(self):
        # `None` = the labeled event was unreadable or paged out of the feed.
        # Unknown must keep the state, never clear it (round-2 review).
        cmds, _ = self.run_readiness({"status/fixing"},
                                     "**Verdict:** no blockers", label_at=None)
        self.assertFalse(any("status%2Ffixing" in c for c in cmds), cmds)


class TestMachineryNarrationIsNotAVerdict(unittest.TestCase):
    def test_repair_narration_without_marker_is_ignored(self):
        # the App posts 07's repair narration; its prose matches the blocker
        # predicate but it carries no verdict heading (#1411/#1420/#1466).
        c = {"body": "Repair pushed; the blocker on Codacy is cleared.",
             "created_at": "2026-11-23T04:00:00Z",
             "user": {"login": "rjbiermann-machinery[bot]", "type": "Bot"}}
        self.assertEqual(orchestrator._latest_verdict([c]), ("", ""))


class TestSweepCeiling(unittest.TestCase):
    """The workflow_run arm must not buy an unbounded number of sweeps.

    On 2026-11-23 the 05:20 sweep logged 544 "rate limit already exceeded"
    lines. The predicate counts real sweeps in a sliding hour, and "real" means
    JOB wall time: `startedAt` is queue time here (run 45199527098 queued 9m50s
    and worked 14 s), so a duration floor over it counts declined wakes."""

    NOW = __import__("datetime").datetime(2026, 11, 23, 6, 0, 0)

    def row(self, started_min_ago, run_id=1, status="completed",
            conclusion="success"):
        fmt = "%Y-%m-%dT%H:%M:%SZ"
        start = self.NOW - __import__("datetime").timedelta(minutes=started_min_ago)
        return {"databaseId": run_id, "status": status,
                "conclusion": conclusion,
                "startedAt": start.strftime(fmt),
                "createdAt": start.strftime(fmt),
                "updatedAt": start.strftime(fmt)}

    def spent(self, rows, job_minutes, **kw):
        kw.setdefault("max_per_hour", 3)
        kw.setdefault("now", self.NOW)
        with mock.patch.object(orchestrator, "_job_duration_min",
                               side_effect=lambda rid: job_minutes):
            return orchestrator._sweep_budget_spent(rows, **kw)

    def test_three_real_sweeps_spend_the_allowance(self):
        self.assertTrue(self.spent([self.row(5), self.row(25), self.row(45)], 13.0))

    def test_two_sweeps_leave_a_slot(self):
        self.assertFalse(self.spent([self.row(5), self.row(25)], 13.0))

    def test_sweeps_outside_the_window_do_not_count(self):
        self.assertFalse(self.spent([self.row(5), self.row(25), self.row(90)], 13.0))

    def test_declined_wakes_are_not_sweeps(self):
        # queued 10 min, worked 14 s — the run 45199527098 shape
        self.assertFalse(self.spent(
            [self.row(5), self.row(25), self.row(45)], 0.23))

    def test_failed_runs_are_not_sweeps(self):
        self.assertFalse(self.spent(
            [self.row(5, conclusion="failure"), self.row(25), self.row(45)], 13.0))

    def test_unreadable_timing_counts_the_run(self):
        # blind AND late is the combination that starves the agent lanes
        self.assertTrue(self.spent([self.row(5), self.row(25), self.row(45)], None))

    def test_unreadable_run_list_fails_open(self):
        self.assertFalse(orchestrator._sweep_budget_spent(
            None, max_per_hour=3, now=self.NOW))

    def test_zero_disables_the_ceiling(self):
        self.assertFalse(self.spent(
            [self.row(5), self.row(25), self.row(45)], 13.0, max_per_hour=0))

    def test_below_the_allowance_costs_no_job_lookups(self):
        with mock.patch.object(orchestrator, "_job_duration_min") as jd:
            self.assertFalse(orchestrator._sweep_budget_spent(
                [self.row(5), self.row(25)], max_per_hour=3, now=self.NOW))
        jd.assert_not_called()

    def test_production_path_uses_the_module_constant(self):
        rows = [self.row(5), self.row(25), self.row(45)]
        with mock.patch.object(orchestrator, "SWEEP_MAX_PER_HOUR", 3):
            self.assertTrue(self.spent(rows, 13.0, max_per_hour=None))
        with mock.patch.object(orchestrator, "SWEEP_MAX_PER_HOUR", 4):
            self.assertFalse(self.spent(rows, 13.0, max_per_hour=None))

    def test_fetch_is_bounded_by_created_not_limit(self):
        # a busy hour holds ~86 runs; a fixed --limit undercounts the window
        with mock.patch.object(orchestrator, "run", return_value="[]") as r:
            orchestrator._recent_sweep_runs(now=self.NOW)
        argv = r.call_args[0][0]
        self.assertIn("--created", argv)
        since = argv[argv.index("--created") + 1]
        self.assertEqual(since, ">=2026-11-23T04:00:00Z")   # 2x the 60 min window

    def test_unreadable_fetch_fails_open(self):
        with mock.patch.object(orchestrator, "run", return_value="not json"):
            self.assertIsNone(orchestrator._recent_sweep_runs(now=self.NOW))

    def test_job_duration_sums_job_spans(self):
        payload = ('{"jobs":[{"started_at":"2026-11-23T05:00:00Z",'
                   '"completed_at":"2026-11-23T05:12:00Z"},'
                   '{"started_at":"2026-11-23T05:12:00Z",'
                   '"completed_at":"2026-11-23T05:18:00Z"}]}')
        with mock.patch.object(orchestrator, "run", return_value=payload):
            self.assertAlmostEqual(orchestrator._job_duration_min(42), 18.0)

    def test_job_duration_unreadable_is_none(self):
        with mock.patch.object(orchestrator, "run", return_value="{}"):
            self.assertIsNone(orchestrator._job_duration_min(42))
        with mock.patch.object(orchestrator, "run", return_value="nope"):
            self.assertIsNone(orchestrator._job_duration_min(42))

    def test_declined_wake_still_serves_repairs(self):
        # #1564: the ceiling declined the wake carrying a fresh blocker verdict
        # and nothing dispatched the repair for 30+ minutes
        with mock.patch.object(orchestrator, "approve_companion_runs"), \
             mock.patch.object(orchestrator, "_owner_configured", lambda: True), \
             mock.patch.object(orchestrator, "fix_surrogate") as fs:
            orchestrator.sweep_declined()
        fs.assert_called_once()

    def test_declined_wake_respects_the_owner_gate(self):
        # fix_surrogate is a mutation step: without the owner identity it must
        # not run, exactly as `available` filters it in main()
        with mock.patch.object(orchestrator, "approve_companion_runs"), \
             mock.patch.object(orchestrator, "_owner_configured", lambda: False), \
             mock.patch.object(orchestrator, "fix_surrogate") as fs:
            orchestrator.sweep_declined()
        fs.assert_not_called()

    def test_declined_wake_survives_a_failing_step(self):
        # a broken step must not turn a declined wake into a red run
        with mock.patch.object(orchestrator, "approve_companion_runs",
                               side_effect=RuntimeError("boom")), \
             mock.patch.object(orchestrator, "_owner_configured", lambda: True), \
             mock.patch.object(orchestrator, "fix_surrogate",
                               side_effect=RuntimeError("boom")):
            orchestrator.sweep_declined()      # must not raise

    def test_workflow_passes_the_ceiling_knob(self):
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            "workflows", "95-orchestrator.yml")
        with open(path) as fh:
            yml = fh.read()
        self.assertIn("GH_AW_SWEEP_MAX_PER_HOUR: ${{ vars.GH_AW_SWEEP_MAX_PER_HOUR }}",
                      yml)
        self.assertIn("GH_AW_SWEEP_EVENT: ${{ github.event_name }}", yml)


class TestEnvIntParsing(unittest.TestCase):
    """Unset/empty/typo must mean the default, never 0 and never a crash.

    An Actions repo variable that is not set renders as "" — the first shape
    here (`int(x or 0)`) turned that into "disabled" for every adopter, and a
    typo raised at import and killed all trigger arms."""

    def test_unset_and_empty_use_the_default(self):
        with mock.patch.dict(orchestrator.os.environ, {}, clear=False):
            orchestrator.os.environ.pop("GH_AW_SWEEP_MAX_PER_HOUR", None)
            self.assertEqual(orchestrator._env_int("GH_AW_SWEEP_MAX_PER_HOUR", 10), 10)
        with mock.patch.dict(orchestrator.os.environ,
                             {"GH_AW_SWEEP_MAX_PER_HOUR": ""}):
            self.assertEqual(orchestrator._env_int("GH_AW_SWEEP_MAX_PER_HOUR", 10), 10)

    def test_non_numeric_uses_the_default(self):
        with mock.patch.dict(orchestrator.os.environ,
                             {"GH_AW_SWEEP_MAX_PER_HOUR": "ten"}):
            self.assertEqual(orchestrator._env_int("GH_AW_SWEEP_MAX_PER_HOUR", 10), 10)

    def test_zero_is_honoured(self):
        with mock.patch.dict(orchestrator.os.environ,
                             {"GH_AW_SWEEP_MAX_PER_HOUR": "0"}):
            self.assertEqual(orchestrator._env_int("GH_AW_SWEEP_MAX_PER_HOUR", 10), 0)


class TestTransientDiagClasses(unittest.TestCase):
    """The watchdog is the only thing that stops transient `[aw]` noise from
    accumulating: each class it does not know costs a manual close (#1579)."""

    def _closed(self, title, later_success=True, created="2026-11-24T02:00:00Z"):
        issue = {"number": 7, "title": title, "created_at": created,
                 "labels": []}
        runs = "2026-11-24T03:00:00Z\n" if later_success else ""
        with mock.patch.object(orchestrator, "api", return_value=[issue]), \
             mock.patch.object(orchestrator, "run", return_value=runs), \
             mock.patch.object(orchestrator, "close_issue") as close:
            orchestrator.watchdog()
        return close

    def test_daily_credit_guardrail_is_transient(self):
        close = self._closed("[aw] Security scan (08+09 merged) could not "
                             "verify daily AI credits")
        close.assert_called_once()
        self.assertEqual(close.call_args[0][1], "not planned")

    def test_known_classes_still_close(self):
        for t in ("[aw] Triage produced no safe outputs",
                  "[aw] Review (findings only) reported incomplete result"):
            with self.subTest(title=t):
                self._closed(t).assert_called_once()

    def test_real_failure_is_never_closed(self):
        self._closed("[aw] Implement failed").assert_not_called()

    def test_no_later_success_keeps_it_open(self):
        self._closed("[aw] Security scan could not verify daily AI credits",
                     later_success=False).assert_not_called()


class TestRegistryClosedWindow(unittest.TestCase):
    """#1579: an entry posted during Implement was only unioned while its
    story was open, and 12-post-merge closes it — the delivered pass went
    invisible to the next booking and the deep tier was paid twice."""

    NOW = calendar.timegm((2026, 11, 24, 16, 0, 0, 0, 0, 0))

    def test_just_closed_issue_is_scanned(self):
        closed = [{"number": 1446, "closed_at": "2026-11-22T20:53:00Z"}]
        got = orchestrator._registry_issue_set([], closed, now=self.NOW)
        self.assertEqual([i["number"] for i in got], [1446])

    def test_issue_closed_outside_the_window_is_not(self):
        closed = [{"number": 1, "closed_at": "2026-10-01T00:00:00Z"}]
        self.assertEqual(orchestrator._registry_issue_set([], closed,
                                                          now=self.NOW), [])

    def test_pull_requests_are_skipped_on_both_arms(self):
        got = orchestrator._registry_issue_set(
            [{"number": 2}, {"number": 3, "pull_request": {}}],
            [{"number": 4, "closed_at": "2026-11-24T00:00:00Z",
              "pull_request": {}}], now=self.NOW)
        self.assertEqual([i["number"] for i in got], [2])


class TestRegistryLastDeepFlag(unittest.TestCase):
    """#1579 S15 shape: a correctly anchored entry whose last_deep was the
    PREVIOUS deep date while its own reason named the new run. #1583 review:
    a run date proves a run, not a deep pass — flag, never rewrite."""

    def test_stale_last_deep_is_reported(self):
        entry = {"last_runs": [{"date": "2026-11-23"}],
                 "providers": {"AllClassicPorn": {"last_deep": "2026-10-16"}}}
        with mock.patch.object(orchestrator, "print") as out:
            orchestrator._registry_flag_last_deep(entry)
        self.assertIn("AllClassicPorn", out.call_args[0][0])

    def test_stale_last_deep_is_never_rewritten(self):
        entry = {"last_runs": [{"date": "2026-11-23"}],
                 "providers": {"AllClassicPorn": {"last_deep": "2026-10-16"}}}
        with mock.patch.object(orchestrator, "print"):
            orchestrator._registry_flag_last_deep(entry)
        self.assertEqual(entry["providers"]["AllClassicPorn"]["last_deep"],
                         "2026-10-16")

    def test_multi_provider_entry_is_reported_too(self):
        entry = {"last_runs": [{"date": "2026-11-23"}],
                 "providers": {"A": {"last_deep": "2026-10-16"},
                               "B": {"last_deep": "2026-11-24"}}}
        with mock.patch.object(orchestrator, "print") as out:
            orchestrator._registry_flag_last_deep(entry)
        self.assertIn("A last_deep", out.call_args[0][0])
        self.assertNotIn("B last_deep", out.call_args[0][0])

    def test_fresh_last_deep_is_silent(self):
        entry = {"last_runs": [{"date": "2026-11-23"}],
                 "providers": {"X": {"last_deep": "2026-11-24"}}}
        with mock.patch.object(orchestrator, "print") as out:
            orchestrator._registry_flag_last_deep(entry)
        out.assert_not_called()

    def test_entry_without_runs_is_silent(self):
        entry = {"providers": {"X": {"last_deep": "2026-10-16"}}}
        with mock.patch.object(orchestrator, "print") as out:
            orchestrator._registry_flag_last_deep(entry)
        out.assert_not_called()


class TestRegistryKnownIssuesPreempt(unittest.TestCase):
    """#1583 review: the closed arm mostly re-derives answers the registry on
    main already holds (87 closes vs 28 opens in a 7-day window), and each
    candidate costs a `comments()` fetch against the secondary rate limit."""

    DOC = {"last_runs": [{"id": "run1450", "issue": 1450},
                         {"id": "run1", "issue": None}, {"bad": "shape"}],
           "providers": {"PandaMovies": {"issue": 1447},
                         "Neporn": {"issue": None}}}

    def test_recorded_story_numbers_are_known(self):
        self.assertEqual(orchestrator._registry_known_issues(self.DOC),
                         {1450, 1447})

    def test_nested_findings_issue_counts_as_recorded(self):
        """44 of 50 `last_runs` carry no top-level `issue` — the mapping lives
        in `findings[].issue`, and missing it re-pended landed entries over
        newer data (#1598 review)."""
        doc = {"last_runs": [{"id": "run9",
                             "findings": [{"issue": 9, "provider": "X"}]}]}
        self.assertEqual(orchestrator._registry_known_issues(doc), {9})

    def test_missing_or_empty_doc_is_not_an_error(self):
        self.assertEqual(orchestrator._registry_known_issues(None), set())
        self.assertEqual(orchestrator._registry_known_issues({}), set())


class TestRegistryClosedPaging(unittest.TestCase):
    """#1583 review: closed issues are fetched `sort=updated desc` while the
    window filter is `closed_at`, so one page can fill with old threads that
    were merely updated recently — the just-closed story is then never
    scanned by the very arm that exists for it."""

    NOW = calendar.timegm((2026, 11, 24, 16, 0, 0, 0, 0, 0))

    @staticmethod
    def _fetch(pages, seen):
        def fetch(url):
            seen.append(url)
            return pages[len(seen) - 1]
        return fetch

    def test_later_page_is_fetched_while_items_are_inside_the_window(self):
        pages = [
            [{"number": 1, "closed_at": "2026-11-23T00:00:00Z",
              "updated_at": "2026-11-23T00:00:00Z"}],
            [{"number": 2, "closed_at": "2026-11-22T00:00:00Z",
              "updated_at": "2026-11-22T00:00:00Z"}],
            [],
        ]
        seen = []
        got = orchestrator._registry_closed_issues(
            now=self.NOW, fetch=self._fetch(pages, seen))
        self.assertEqual([i["number"] for i in got], [1, 2])
        self.assertEqual(len(seen), 3)
        self.assertIn("page=2", seen[1])

    def test_scan_stops_once_a_page_predates_the_window(self):
        pages = [[
            {"number": 1, "closed_at": "2026-11-23T00:00:00Z",
             "updated_at": "2026-11-23T00:00:00Z"},
            {"number": 2, "closed_at": "2026-10-01T00:00:00Z",
             "updated_at": "2026-10-01T00:00:00Z"},
        ]]
        seen = []
        got = orchestrator._registry_closed_issues(
            now=self.NOW, fetch=self._fetch(pages, seen))
        self.assertEqual([i["number"] for i in got], [1])
        self.assertEqual(len(seen), 1)

    def test_pull_requests_are_dropped_from_the_paged_arm(self):
        pages = [[{"number": 9, "pull_request": {},
                   "closed_at": "2026-11-23T00:00:00Z",
                   "updated_at": "2026-11-23T00:00:00Z"}], []]
        got = orchestrator._registry_closed_issues(
            now=self.NOW, fetch=self._fetch(pages, []))
        self.assertEqual(got, [])


class TestRegistryPendOrder(unittest.TestCase):
    """#1583 review: `_union_findings` lets b win scalars, so the newest
    entry must be applied last. The widened scan mixes open and just-closed
    threads and the API returns newest-first on both arms."""

    def test_newest_entry_is_applied_last(self):
        older = {"last_runs": [{"date": "2026-11-20"}],
                 "providers": {"X": {"last_deep": "2026-11-20",
                                     "reason": "old"}}}
        newer = {"last_runs": [{"date": "2026-11-24"}],
                 "providers": {"X": {"last_deep": "2026-11-24",
                                     "reason": "new"}}}
        pend = orchestrator._registry_pend_order([(1446, newer), (1439, older)])
        merged = {}
        merged, _ = orchestrator._registry_apply({}, pend)
        self.assertEqual(merged["providers"]["X"]["last_deep"], "2026-11-24")
        self.assertEqual(merged["providers"]["X"]["reason"], "new")

    def test_entries_without_a_run_date_sort_by_issue_number(self):
        a = {"providers": {"X": {"last_deep": "2026-10-16"}}}
        b = {"providers": {"Y": {"last_deep": "2026-10-16"}}}
        got = orchestrator._registry_pend_order([(7, b), (3, a)])
        self.assertEqual([n for n, _ in got], [3, 7])


class TestRegistryPendingScan(unittest.TestCase):
    """#1583 review: the self-heal repost took a second append path that
    skipped the stale-stamp check, so exactly the unanchored-but-correct
    entry that has to be healed went unchecked."""

    ENTRY = ('{"last_runs": [{"date": "2026-11-23"}],'
             ' "providers": {"X": {"last_deep": "2026-10-16"}}}')

    def _bot(self, body):
        return {"body": body, "user": {"login": "github-actions[bot]",
                                      "type": "Bot"}}

    def test_self_healed_entry_goes_through_the_same_path(self):
        unanchored = [self._bot("```json\n" + self.ENTRY + "\n```")]
        anchored = [self._bot(orchestrator.REGISTRY_ANCHOR
                              + "\n```json\n" + self.ENTRY + "\n```")]
        posts = []

        def fetch(num):
            return anchored if posts else unanchored

        with mock.patch.object(orchestrator, "post_comment",
                               side_effect=lambda n, b: posts.append((n, b))), \
             mock.patch.object(orchestrator, "print"):
            got = orchestrator._registry_pending([{"number": 5}],
                                                 fetch_comments=fetch)
        self.assertEqual(len(posts), 1)
        self.assertEqual(orchestrator.REGISTRY_ANCHOR in posts[0][1], True)
        self.assertEqual(got[0][1]["providers"]["X"]["last_deep"],
                         "2026-10-16")

    MARKED = {"body": f"{orchestrator.MARK} registry entry applied",
              "user": {"login": "github-actions[bot]", "type": "Bot"}}

    def test_a_recorded_issue_is_skipped_marker_or_not(self):
        entry = ('{"last_runs": [{"id": "run5", "issue": 5}],'
                 ' "providers": {}}')
        anchored = {"body": orchestrator.REGISTRY_ANCHOR
                    + "\n```json\n" + entry + "\n```",
                    "user": {"login": "github-actions[bot]", "type": "Bot"}}
        for cs in ([self.MARKED, anchored], [anchored]):
            with self.subTest(marker=len(cs) == 2):
                got = orchestrator._registry_pending([{"number": 5}],
                                                     fetch_comments=lambda n: cs,
                                                     applied={5})
                self.assertEqual(got, [])

    def test_marker_without_a_recorded_entry_re_pends(self):
        """#1581/#1587 shape: the marker is written when the registry PR is
        OPENED, so a PR that later conflicts strands its entry while the
        issue reads as done. The registry, not the marker, is the evidence."""
        body = ('{"last_runs": [{"id": "run1457", "issue": 1457,'
                ' "date": "2026-11-24"}], "providers": {}}')
        cs = [{"body": f"{orchestrator.REGISTRY_ANCHOR}\n```json\n{body}\n```",
               "user": {"login": "github-actions[bot]", "type": "Bot"}},
              {"body": f"{orchestrator.MARK} registry entry applied",
               "user": {"login": "github-actions[bot]", "type": "Bot"}}]
        got = orchestrator._registry_pending([{"number": 1457}],
                                             fetch_comments=lambda n: cs,
                                             applied=set())
        self.assertEqual([n for n, _ in got], [1457])

    def test_unanchored_entry_without_anchorable_shape_is_skipped(self):
        cs = [self._bot("```json\n{\"note\": \"not an entry\"}\n```")]
        with mock.patch.object(orchestrator, "post_comment") as post:
            got = orchestrator._registry_pending([{"number": 5}],
                                                 fetch_comments=lambda n: cs)
        post.assert_not_called()
        self.assertEqual(got, [])


class TestApprovalQueryIsOneCall(unittest.TestCase):
    """#1588: a `--branch <b> --limit 40` history walk per open agent PR held
    one sweep for 889 s of its 929 s step. `status=action_required` is a
    server-side filter, so the sweep pays one paged fetch, not one query per
    PR. `per_page=100` alone capped that fetch at its first page, so paging
    is bounded — present, cheap, and never unbounded (#1594 review)."""

    def _run(self, parked, pulls, pat="tok"):
        """Runs approve_companion_runs with api()/open_pulls mocked.

        `parked` is one page (a list) or a {page: workflow_runs} mapping.
        Returns (api calls, approve urls, open_pulls mock): the mock is
        returned because an api-call count cannot observe PR *scanning* —
        the regression `test_no_parked_runs_costs_no_pr_scan` is named for
        (#1594 review)."""
        calls, approved = [], []
        pages = parked if isinstance(parked, dict) else {1: parked}

        def api(path):
            calls.append(path)
            page = int(path.rsplit("page=", 1)[-1]) if "page=" in path else 1
            if "actions/runs" not in path:
                return []
            return {"workflow_runs": pages.get(page, [])}

        def run(cmd):
            if "curl" in cmd[0]:
                approved.append(cmd[-1])
            return "{}"

        open_pulls = mock.Mock(return_value=pulls)
        with mock.patch.object(orchestrator, "PROXY_PAT", pat), \
             mock.patch.object(orchestrator, "api", side_effect=api), \
             mock.patch.object(orchestrator, "open_pulls", open_pulls), \
             mock.patch.object(orchestrator, "run", side_effect=run), \
             mock.patch.object(orchestrator, "print"):
            orchestrator.approve_companion_runs()
        return calls, approved, open_pulls

    @staticmethod
    def _pr(num, branch):
        return {"number": num, "head": {"ref": branch},
                "user": {"login": "rjbiermann-machinery[bot]"}}

    def test_advisory_runs_are_fetched_once_not_per_pr(self):
        pairs = [self._pr(1, "devloop/issue-1-a"), self._pr(2, "devloop/issue-2-b")]
        calls, approved, open_pulls = self._run(
            [{"id": 11, "name": "Lint", "head_branch": "devloop/issue-1-a"}], pairs)
        self.assertEqual([c for c in calls if "actions/runs" in c],
                         ["actions/runs?status=action_required&per_page=100&page=1"])
        open_pulls.assert_called_once()
        self.assertTrue(approved and approved[0].endswith("/runs/11/approve"))

    def test_parked_run_on_an_unrelated_branch_is_ignored(self):
        calls, approved, _ = self._run(
            [{"id": 12, "name": "Lint", "head_branch": "someone-elses-branch"}],
            [self._pr(1, "devloop/issue-1-a")])
        self.assertEqual(approved, [])

    def test_non_allowlisted_workflow_is_left_for_human(self):
        calls, approved, _ = self._run(
            [{"id": 13, "name": "Deploy Secrets", "head_branch": "devloop/issue-1-a"}],
            [self._pr(1, "devloop/issue-1-a")])
        self.assertEqual(approved, [])

    def test_no_parked_runs_costs_no_pr_scan(self):
        calls, approved, open_pulls = self._run([], [self._pr(1, "devloop/issue-1-a")])
        self.assertEqual(approved, [])
        self.assertEqual(len([c for c in calls if "actions/runs" in c]), 1)
        open_pulls.assert_not_called()

    def test_a_full_page_is_paged_through_not_truncated(self):
        """A parked run past the first page still gets approved — `per_page=100`
        without paging dropped it silently (#1594 review)."""
        page_one = [{"id": 100 + i, "name": "Lint",
                     "head_branch": "someone-elses-branch"} for i in range(100)]
        page_two = [{"id": 300, "name": "Lint",
                     "head_branch": "devloop/issue-1-a"}]
        calls, approved, _ = self._run({1: page_one, 2: page_two},
                                       [self._pr(1, "devloop/issue-1-a")])
        self.assertEqual(len([c for c in calls if "actions/runs" in c]), 2)
        self.assertTrue(approved and approved[0].endswith("/runs/300/approve"))

    def test_paging_is_capped_so_a_backlog_cannot_rebuild_the_stall(self):
        full = [{"id": 1, "name": "Lint", "head_branch": "x"} for _ in range(100)]
        calls, _, _ = self._run({1: full, 2: full, 3: full, 4: full}, [])
        self.assertEqual(len([c for c in calls if "actions/runs" in c]), 3)


class TestRegistryUnionNewestPerProvider(unittest.TestCase):
    """#1598 review: neither blanket direction is right. A stranded entry that
    is OLDER than the file must not revert a later pass, and a genuinely newer
    entry must not be dropped in favour of a stale record — so the per-provider
    `last_deep` decides, and the entry's run record appends either way."""

    MAIN = {"providers": {"X": {"last_deep": "2026-11-24", "reason": "new"}},
            "last_runs": [{"id": "run9999"}]}

    def _entry(self, date):
        return {"last_runs": [{"id": "run1457", "date": date}],
                "providers": {"X": {"last_deep": date, "reason": "entry"}}}

    def test_older_entry_keeps_the_files_newer_provider_state(self):
        merged = orchestrator._registry_union(self.MAIN, self._entry("2026-11-20"))
        self.assertEqual(merged["providers"]["X"]["last_deep"], "2026-11-24")
        self.assertEqual(merged["providers"]["X"]["reason"], "new")

    def test_newer_entry_wins_over_the_files_stale_provider_state(self):
        merged = orchestrator._registry_union(self.MAIN, self._entry("2026-11-30"))
        self.assertEqual(merged["providers"]["X"]["last_deep"], "2026-11-30")
        self.assertEqual(merged["providers"]["X"]["reason"], "entry")

    def test_a_stale_entry_last_deep_never_rewinds_a_later_deep_pass(self):
        """#1599 (round-4 blocker on #1598): the entry's own claim is the
        floor, not its run date. #1458's entry carries run 2026-11-24 with a
        stale porntrex.last_deep 2026-10-16, so a file record in between was
        being rewound — a deep rotation deferred silently."""
        main = {"providers": {"X": {"last_deep": "2026-11-20", "reason": "file"}},
                "last_runs": [{"id": "run1450"}]}
        entry = {"last_runs": [{"id": "run1458", "date": "2026-11-24"}],
                 "providers": {"X": {"last_deep": "2026-10-16",
                                     "verdict": "blocked", "reason": "S20"}}}
        merged = orchestrator._registry_union(main, entry)
        self.assertEqual(merged["providers"]["X"]["last_deep"], "2026-11-20")
        self.assertEqual(merged["providers"]["X"]["verdict"], "blocked")
        self.assertEqual(merged["providers"]["X"]["reason"], "S20")

    def test_the_run_record_lands_in_both_directions(self):
        for date in ("2026-11-20", "2026-11-30"):
            with self.subTest(date=date):
                merged = orchestrator._registry_union(self.MAIN, self._entry(date))
                self.assertEqual({r["id"] for r in merged["last_runs"]},
                                 {"run1457", "run9999"})

    def test_a_no_op_union_reports_no_change(self):
        """The guard that stops a demoted marker from opening an empty
        registry PR every sweep (#1598 review round 1)."""
        same = {"last_runs": [{"id": "run9999"}],
                "providers": {"X": {"last_deep": "2026-11-24", "reason": "new"}}}
        merged, changed = orchestrator._registry_apply(same, [(1457, same)])
        self.assertFalse(changed)
        self.assertEqual(merged, same)

    def test_a_real_union_reports_a_change(self):
        _merged, changed = orchestrator._registry_apply(
            self.MAIN, [(1457, self._entry("2026-11-30"))])
        self.assertTrue(changed)

class TestRegistrySingletonPR(unittest.TestCase):
    """#1600/#1601: timestamped branches made every sweep inside a PR's
    lifetime open another byte-identical PR, and the twins then conflicted
    with each other (#1581/#1587). One branch means one PR, which each sweep
    force-updates from current main — no mergeability heuristic, no waiting
    (#1603 review)."""

    @staticmethod
    def _pr(num, branch, repo=None):
        return {"number": num,
                "head": {"ref": branch,
                         "repo": {"full_name": repo or orchestrator.REPO}}}

    def test_the_registry_pr_is_found_by_its_singleton_branch(self):
        pulls = [self._pr(1585, "devloop/issue-1458-abc"),
                 self._pr(1603, "orch-registry/sync")]
        with mock.patch.object(orchestrator, "open_pulls", return_value=pulls):
            self.assertEqual(orchestrator._open_registry_pr(), 1603)

    def test_no_open_registry_pr_means_create(self):
        pulls = [self._pr(1585, "devloop/issue-1458-abc")]
        with mock.patch.object(orchestrator, "open_pulls", return_value=pulls):
            self.assertIsNone(orchestrator._open_registry_pr())

    def test_a_fork_branch_with_the_same_name_is_not_the_lane(self):
        """A branch name is not an identity: fork PRs list `head.ref` too, and
        adopting one would push the lane's commit into a stranger's repo."""
        pulls = [self._pr(999, "orch-registry/sync", repo="someone/fork")]
        with mock.patch.object(orchestrator, "open_pulls", return_value=pulls):
            self.assertIsNone(orchestrator._open_registry_pr())

    def test_the_same_payload_is_not_pushed_again(self):
        """Every sweep re-pends until the PR lands; re-pushing the identical
        union restarts the checks the PR needs to merge. Feed the shapes the
        call site really sees: `_pl()` strips `git show` output while
        `payload` keeps its trailing newline (#1603 review)."""
        self.assertFalse(orchestrator._registry_push_needed('{"a": 1}', '{"a": 1}\n'))
        self.assertFalse(orchestrator._registry_push_needed("same\n", "same"))
        self.assertTrue(orchestrator._registry_push_needed("older", "new"))
        self.assertTrue(orchestrator._registry_push_needed("", "new"))

    def test_an_update_push_rides_the_app_identity(self):
        """A GITHUB_TOKEN push fires no `synchronize`, so an open singleton's
        replaced head would carry none of its required checks and could never
        merge — the update must go through the App identity, forced (the
        commit is re-parented on origin/main)."""
        with mock.patch.object(orchestrator, "PROXY_PAT", "app-token"), \
             mock.patch.object(orchestrator, "_push_ref_via_api",
                               return_value=True) as api_push, \
             mock.patch.object(orchestrator, "_git_push") as git_push:
            ok, _ = orchestrator._push_registry_branch(
                1604, "orch-registry/sync", "sha")
        self.assertTrue(ok)
        api_push.assert_called_once_with("orch-registry/sync", "sha", force=True)
        git_push.assert_not_called()

    def test_an_update_without_the_app_identity_fails_closed(self):
        """Degraded adopter (no App/PAT): a gh-api push would ride
        GITHUB_TOKEN and wedge the head, so refuse it and retry next sweep."""
        with mock.patch.object(orchestrator, "PROXY_PAT", ""), \
             mock.patch.object(orchestrator, "_push_ref_via_api") as api_push, \
             mock.patch.object(orchestrator, "_git_push") as git_push:
            ok, _ = orchestrator._push_registry_branch(
                1604, "orch-registry/sync", "sha")
        self.assertFalse(ok)
        api_push.assert_not_called()
        git_push.assert_not_called()

    def test_a_new_branch_push_uses_the_checkout(self):
        """No open PR yet, so `gh pr create` fires `opened` and the checks run
        on the pushed head — the checkout token is enough here."""
        with mock.patch.object(orchestrator, "_push_ref_via_api") as api_push, \
             mock.patch.object(orchestrator, "_git_push",
                               return_value=(True, "")) as git_push:
            ok, _ = orchestrator._push_registry_branch(
                None, "orch-registry/sync", "sha")
        self.assertTrue(ok)
        git_push.assert_called_once_with("orch-registry/sync", "sha", force=True)
        api_push.assert_not_called()

    def test_a_timestamped_legacy_branch_is_not_the_lane(self):
        """Legacy `orch-registry/<ts>` twins are NOT the singleton and have no
        automated cleanup: `orch-registry/*` is outside AGENT_BRANCHES, so
        `conflict_watch` never sees them, and `stale.yml` never touches PRs —
        a human closes them (#1603 review)."""
        pulls = [self._pr(1600, "orch-registry/1795548223")]
        with mock.patch.object(orchestrator, "open_pulls", return_value=pulls):
            self.assertIsNone(orchestrator._open_registry_pr())

class TestMidSweepRepairBlocksReadiness(unittest.TestCase):
    """#1585 carried `status/fixing` and `status/ready-to-merge` at once: 07
    added the repair label at 01:42:23 and the sweep, whose `open_pulls()`
    snapshot predated it, stamped readiness at 01:51:38."""

    def test_a_repair_label_in_the_fresh_set_blocks(self):
        for label in ("status/fixing", "status/fixing-stuck"):
            with self.subTest(label=label):
                self.assertTrue(orchestrator._repair_label_present(
                    ["status/in-review", label]))

    def test_a_clean_fresh_set_does_not_block(self):
        self.assertFalse(orchestrator._repair_label_present(
            ["status/in-review", "status/ready-to-merge"]))
        self.assertFalse(orchestrator._repair_label_present([]))
        self.assertFalse(orchestrator._repair_label_present(None))

    def test_the_labels_are_read_fresh_from_the_api(self):
        with mock.patch.object(orchestrator, "api",
                               return_value=[{"name": "status/fixing"}]) as a:
            self.assertEqual(orchestrator._pr_labels_now(1585), ["status/fixing"])
        a.assert_called_once_with("issues/1585/labels")

    def test_an_unreadable_label_set_never_wedges_readiness(self):
        with mock.patch.object(orchestrator, "api", side_effect=RuntimeError):
            self.assertEqual(orchestrator._pr_labels_now(1585), [])
