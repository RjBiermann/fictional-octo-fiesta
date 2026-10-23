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
