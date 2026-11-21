#!/usr/bin/env python3
"""Unit tests for the orchestrator's branch-executor decision layer
(parent spec #847, S1 #897). Python-stdlib unittest only — orchestrator.py
had no test harness before this. Fixtures are carved from the real
#840/#846/#845/#844 story texts (conditional fan-out on the PerverZija
chain) so the regression fixtures stay anchored to live data shapes.

Run: python3 -m unittest .github.orchestrator_test  (or:
     python3 -m unittest discover -s .github -p 'orchestrator_test.py')
"""

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
                      verdict=None):
        posted, fired, cmds = [], [], []
        pr = {"number": 1411, "user": {"login": "rjbiermann-machinery[bot]"},
              "head": {"ref": "devloop/issue-1407-c141da7ff2421900"}}
        thread = [bot_comment(verdict or self.VERDICT)]
        thread += [bot_comment(f"{orchestrator.MARK} fix-loop re-fired for "
                              f"the verdict at 2026-11-18T23:49:02Z")] * markers
        thread += list(tail)
        with mock.patch.object(orchestrator, "open_pulls", lambda: [pr]), \
                mock.patch.object(orchestrator, "_fixable_pr", lambda p: True), \
                mock.patch.object(orchestrator, "comments", lambda n: thread), \
                mock.patch.object(orchestrator, "_repair_in_flight",
                                  lambda: in_flight), \
                mock.patch.object(orchestrator, "_branch_moved_after",
                                  lambda n, ts: branch_moved), \
                mock.patch.object(orchestrator, "api",
                                  lambda p: {"labels": []}), \
                mock.patch.object(orchestrator, "post_comment",
                                  lambda n, b: posted.append(b)), \
                mock.patch.object(orchestrator, "run",
                                  lambda cmd, **kw: cmds.append(cmd) or ""), \
                mock.patch.object(orchestrator, "dispatch_ok",
                                  lambda cmd, **kw: fired.append(cmd) or True):
            orchestrator.fix_surrogate()
        return posted, fired, cmds

    def test_aborted_repair_refires(self):
        posted, fired, _ = self.run_surrogate(markers=1, branch_moved=False)
        self.assertEqual(len(fired), 1, "unserved verdict must re-fire")
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
                mock.patch.object(orchestrator, "run", lambda cmd, **kw: ""), \
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

    def in_flight(self, *rows):
        payload = json.dumps([{"status": s} for s in rows])
        with mock.patch.object(orchestrator, "run", lambda cmd, **kw: payload):
            return orchestrator._repair_in_flight()

    def test_running_repair_blocks(self):
        self.assertTrue(self.in_flight("in_progress"))

    def test_queued_repair_blocks(self):
        self.assertTrue(self.in_flight("queued"))

    def test_finished_repair_does_not_block(self):
        self.assertFalse(self.in_flight("completed"))

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
                mock.patch.object(orchestrator, "api", lambda p: {"mergeable_state": "clean"}), \
                mock.patch.object(orchestrator, "label_issue",
                                  lambda n, l: self.fail("labelled a needs-human PR")), \
                mock.patch.object(orchestrator, "post_comment",
                                  lambda n, b: self.fail("pinged a needs-human PR")), \
                mock.patch.object(orchestrator, "print", printouts.append, create=True):
            orchestrator.merge_readiness()
        self.assertEqual(len(printouts), 1, printouts)
        self.assertIn("fixing-stuck", printouts[0])
