#!/usr/bin/env python3
"""Replay harness for the #797 bug class (spec #889, parent #797).

Drives the recorded 2026-10-20 label-event / delivery history
(fixtures/labels_2026-10-20.json, captured from the live API) through
.github/orchestrator.py with every side channel (gh api, comments,
labels, closes, dispatches) monkeypatched to the fixtures — stdlib
unittest only, no network, non-determinism free.

Assertions encode the invariant class the fixes (#905 and the
orchestrator's actor-aware delivered-guard / closeout-healer) are
supposed to make permanent:

  - a merged-delivered story is never re-fired (the duplicate-implement
    class — run 39368254975 re-fired #771's implement pass from a
    bot-applied re-stamp);
  - only a post-merge `status/planned` applied by the OWNER login counts
    as a deliberate re-arm — every recorded machinery re-stamp re-arms
    nothing (fail closed);
  - open issues with a merged delivery PR close out on the healer pass
    (ADR-0007 translated to machinery);
  - a merged-delivered story MUST be closeable, no-delivery stories and
    the maintainer's deliberate reopen stay open.

The bug class itself is encoded red: an actor-agnostic post-merge
re-stamp interpretation re-fires #771 on the recorded history; the real
actor-aware guard rejects exactly that history. Green is the
acceptance evidence (TDD in spirit, per spec).

Run: python3 -m unittest discover -s .github/tests -p 'orchestrator_replay_test.py'
"""

import copy
import importlib.util
import json
import os
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "orchestrator", os.path.join(_HERE, "..", "orchestrator.py"))
orchestrator = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(orchestrator)

with open(os.path.join(_HERE, "fixtures", "labels_2026-10-20.json")) as _f:
    FXT = json.load(_f)

OWNER = FXT["modeled_world"]["owner"]
BOT = FXT["modeled_world"]["bot"]


def merged_at(issue_num):
    """The merge timestamp of `issue_num`'s merged delivery PR (None for
    no-delivery stories — exactly the guard's own classification)."""
    for pr in FXT["pulls_closed"]:
        if f"issue-{issue_num}-" in pr["head_ref"] and pr["merged_at"]:
            return pr["merged_at"]
    return None


class Recorder:
    """Side-channel stand-in that records every call; prints nothing."""

    def __init__(self, name):
        self.name = name
        self.calls = []

    def __call__(self, *args, **kwargs):
        self.calls.append(args)
        return None


def make_api(events_override=None, issues=None):
    """Fixture-backed stand-in for orchestrator.api(). Dispatches on the
    exact path shapes orchestrator.py issues; anything else asserts — a
    replay that reaches a live endpoint (or an unmodeled shape) must
    fail the test, never silently touch the network."""

    def api(path):
        if path == "issues?state=open&per_page=100":
            return issues if issues is not None else [
                {"number": int(n), "state": i["state"],
                 "title": i["title"]} for n, i in FXT["issues"].items()]
        if path == "pulls?state=closed&per_page=60":
            return [{"number": p["number"],
                     "head": {"ref": p["head_ref"]},
                     "merged_at": p["merged_at"],
                     "state": p["state"],
                     "user": {"login": p["user"]}}
                    for p in FXT["pulls_closed"]]
        if path == "pulls?state=open&per_page=50":
            return FXT["pulls_open"]
        if path.startswith("pulls/"):
            num = int(path[len("pulls/"):])
            for p in FXT["pulls_closed"]:
                if p["number"] == num:
                    return {"number": num, "merged_at": p["merged_at"],
                            "head": {"ref": p["head_ref"]}}
            return None
        if path.startswith("issues/") and path.endswith("/events?per_page=100"):
            num = int(path[len("issues/"):].split("/")[0])
            if events_override is not None and num in events_override:
                return events_override[num]
            return FXT["issues"][str(num)]["events"]
        raise AssertionError(f"replay hit an unmodeled endpoint: {path}")

    return api


class FixtureBacked(unittest.TestCase):
    """Base: patch the side channels to the fixture world, restore after."""

    def setUp(self):
        self._saved = {n: getattr(orchestrator, n)
                       for n in ("api", "post_comment", "close_issue",
                                 "label_issue", "_fire_workflow", "OWNER")
                       }
        self.post_comment = Recorder("post_comment")
        self.close_issue = Recorder("close_issue")
        self.label_issue = Recorder("label_issue")
        self.fire_workflow = Recorder("_fire_workflow")
        orchestrator.api = make_api()
        orchestrator.post_comment = self.post_comment
        orchestrator.close_issue = self.close_issue
        orchestrator.label_issue = self.label_issue
        orchestrator._fire_workflow = self.fire_workflow
        orchestrator.OWNER = OWNER

    def tearDown(self):
        for n, v in self._saved.items():
            setattr(orchestrator, n, v)

    def numbers_hit(self, recorder):
        return sorted({c[0] for c in recorder.calls})


def would_refire_actor_agnostic(num):
    """The BUG CLASS as an executable interpretation: any post-merge
    `status/planned` stamp on the issue counts as a deliberate re-arm,
    regardless of WHICH actor applied it. This is how the machinery
    behaved on 2026-10-20 (the #797 report) — the interpretation must
    reproduce the bug on the recorded history (red)."""
    ma = merged_at(num)
    if not ma:
        return False
    last = None
    for e in FXT["issues"][str(num)]["events"]:
        if e["event"] == "labeled" and e["label"]["name"] == "status/planned":
            last = e
    return bool(last and last["created_at"] > ma)


# --- closeout_healer(): the ADR-0007 closeout, healed ----------------------

class TestCloseoutHealerReplay(FixtureBacked):

    def test_merged_delivered_771_closes_out(self):
        # PR #792 merged 04:35:04Z; every re-stamp bot-applied -> the
        # healer closes the delivered story (the 13:58Z aftermath class).
        orchestrator.closeout_healer()
        self.assertIn(771, self.numbers_hit(self.close_issue))
        for call in self.close_issue.calls:
            if call[0] == 771:
                self.assertEqual(call[1], "completed")
                self.assertIn("delivered via PR #792", call[2])
        for call in self.post_comment.calls:
            if call[0] == 771:
                self.assertIn("PR #792 merged", call[1])

    def test_merged_delivered_768_closes_out(self):
        orchestrator.closeout_healer()
        self.assertIn(768, self.numbers_hit(self.close_issue))
        for call in self.close_issue.calls:
            if call[0] == 768:
                self.assertEqual(call[1], "completed")
                self.assertIn("delivered via PR #791", call[2])

    def test_775_no_merged_delivery_stays_open(self):
        # duplicates #802/#803 are closed unmerged — nothing to heal;
        # the healer must neither comment nor close.
        orchestrator.closeout_healer()
        self.assertNotIn(775, self.numbers_hit(self.close_issue))
        self.assertNotIn(775, self.numbers_hit(self.post_comment))

    def test_767_human_reopen_deliberate_stays_open(self):
        # the maintainer deliberately reopened #767 (05:08:56Z) and its
        # delivery #787 is unmerged — no merged delivery record exists,
        # so the healer leaves the issue to the human decision.
        orchestrator.closeout_healer()
        self.assertNotIn(767, self.numbers_hit(self.close_issue))
        self.assertNotIn(767, self.numbers_hit(self.post_comment))

    def test_owner_unset_fails_closed_no_mutation(self):
        # OWNER unset = read-only mode: the heal is a mutation, so it
        # degrades to a no-op — zero comments, zero closes.
        orchestrator.OWNER = ""
        orchestrator.closeout_healer()
        self.assertEqual(self.close_issue.calls, [])
        self.assertEqual(self.post_comment.calls, [])


# --- _fire_implement(): the merged-delivered guard --------------------------

class TestFireImplementMergedDeliveredGuard(FixtureBacked):

    def delivered_never_refired(self, num):
        orchestrator._fire_implement(num)
        self.assertNotIn(num, self.numbers_hit(self.label_issue))
        self.assertEqual(self.fire_workflow.calls, [],
                         f"#{num} is delivered via a merged PR — "
                         f"an implement dispatch re-fired it")

    def test_771_delivered_never_refired(self):
        # PR #792 merged; the 10:02:51Z re-stamp is github-actions[bot].
        # Recordable bug: this re-stamp re-fired run 39368254975 live.
        self.delivered_never_refired(771)

    def test_768_delivered_never_refired(self):
        self.delivered_never_refired(768)

    def test_767_unmerged_live_work_fires(self):
        # #767's delivery #787 never merged and the maintainer deliberately
        # reopened the issue: the delivered-guard does not apply — the
        # fire must proceed (label + dispatch).
        orchestrator._fire_implement(767)
        self.assertIn((767, "status/planned"), self.label_issue.calls)
        fired = [c for c in self.fire_workflow.calls if c[-1] == 767]
        self.assertEqual(len(fired), 1)
        self.assertEqual(fired[0][:2], ("05-implement.lock.yml", "issue"))

    def test_775_outside_guarded_class_fires_mechanically(self):
        # no merged delivery, no open PR: the guard's boundary. The class
        # protects MERGED deliveries; #775's racing duplicates #802/#803
        # died unmerged, so the fire is not the 1979-blocked class. This
        # pins the guard's exact edge — it must not over-block live work.
        orchestrator._fire_implement(775)
        self.assertIn((775, "status/planned"), self.label_issue.calls)
        self.assertEqual(
            [c for c in self.fire_workflow.calls if c[-1] == 775][0][0],
            "05-implement.lock.yml")


# --- _stamped_after_merge(): the owner-only re-arm gate ---------------------

class TestStampedAfterMergeOwnerOnly(FixtureBacked):

    def test_771_bot_restamps_never_rearm(self):
        # every recorded re-stamp is bot-applied; actor-aware check -> False
        self.assertEqual(
            FXT["issues"]["771"]["events"][-1]["actor"]["login"], BOT,
            "fixture sanity: #771's last re-stamp is the machinery")
        self.assertFalse(
            orchestrator._stamped_after_merge(771, merged_at(771)))

    def test_768_bot_restamps_never_rearm(self):
        self.assertFalse(
            orchestrator._stamped_after_merge(768, merged_at(768)))

    def test_derived_owner_actor_rearm_accepts(self):
        # the ONLY shape the gate accepts: the OWNER applied the latest
        # post-merge stamp — a deliberate human "run the work again".
        events = copy.deepcopy(FXT["issues"]["771"]["events"])
        # the scan takes the last LABELED stamp — that one must carry the
        # owner actor (the trailing unlabeled event is irrelevant).
        last = [e for e in events if e["event"] == "labeled"
                and e["label"]["name"] == "status/planned"][-1]
        last["actor"] = {"login": OWNER}
        orchestrator.api = make_api({771: events})
        self.assertTrue(
            orchestrator._stamped_after_merge(771, merged_at(771)))

    def test_non_owner_actor_fails_closed(self):
        # any NON-owner actor — bot, unknown human — never re-arms.
        events = copy.deepcopy(FXT["issues"]["771"]["events"])
        last = [e for e in events if e["event"] == "labeled"
                and e["label"]["name"] == "status/planned"][-1]
        last["actor"] = {"login": "some-other-human"}
        orchestrator.api = make_api({771: events})
        self.assertFalse(
            orchestrator._stamped_after_merge(771, merged_at(771)))

    def test_premerge_stamp_never_rearms(self):
        # a stamp that predates the merge is baseline rearming from the
        # original fire, not a post-merge decision — even OWNER-applied
        # it must NOT count. 768 carries the recorded shape: the owner
        # stamped 02:15:24Z, the PR merged 04:32:10Z.
        pre_owner = [e for e in FXT["issues"]["768"]["events"]
                     if e["event"] == "labeled"
                     and e["label"]["name"] == "status/planned"
                     and e["created_at"] <= merged_at(768)
                     and e["actor"]["login"] == OWNER]
        self.assertNotEqual(pre_owner, [],
                            "fixture sanity: the pre-merge owner stamp exists")
        self.assertEqual([e["created_at"] for e in pre_owner],
                         ["2026-10-20T02:15:24Z"])
        orchestrator.api = make_api({768: pre_owner})
        self.assertFalse(
            orchestrator._stamped_after_merge(768, merged_at(768)))


# --- the bug class itself: red interpretation vs green guard ----------------

class TestBugClassEncodedRed(FixtureBacked):

    def test_actor_agnostic_interpretation_refires_771(self):
        # RED encoding (TDD in spirit, per spec): the naive
        # actor-agnostic reading of the 2026-10-20 history re-fires
        # #771 — the exact duplicate implement pass the machinery ran
        # live (run 39368254975, re-fired from the bot-applied re-stamp).
        self.assertTrue(would_refire_actor_agnostic(771))
        self.assertTrue(would_refire_actor_agnostic(768))

    def test_actor_aware_guard_rejects_recorded_history(self):
        # GREEN acceptance: the real orchestrator's actor-aware guard
        # rejects the SAME recorded history — no re-arm (bot actor),
        # so a merged-delivered story stays quiet. The differential is
        # the fix: same data, different actor discipline.
        orchestrator._fire_implement(771)
        orchestrator._fire_implement(768)
        self.assertEqual(self.fire_workflow.calls, [])
        self.assertFalse(
            orchestrator._stamped_after_merge(771, merged_at(771)))
        self.assertFalse(
            orchestrator._stamped_after_merge(768, merged_at(768)))


if __name__ == "__main__":
    unittest.main()
