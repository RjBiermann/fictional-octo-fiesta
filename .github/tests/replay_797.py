#!/usr/bin/env python3
"""Replay harness for the #797 bug class (spec #889, parent #797).

Drives the recorded 2026-10-20 label-event history (fixtures_797.py)
through .github/orchestrator.py's post-merge defenses:

- closeout_healer()          — open issue + merged delivery PR → close out
- _fire_implement()          — merged-delivered guard (never re-fire)
- _stamped_after_merge()     — OWNER-only deliberate-re-arm check

Invariant class asserted (ADR-0019 machinery, backlog-report #797):

1. A merged-delivered story is NEVER re-fired — the recorded 05:08Z
   re-stamp on #771 (which historically re-fired duplicate implement run
   39368254975) is a no-op today.
2. Only a post-merge `status/planned` applied by the OWNER login counts
   as a deliberate re-arm; machinery re-stamps never do, and unknown
   human logins fail closed.
3. Open issues with merged delivery PRs close out on the healer pass —
   including #767's closeout-then-human-reopen — while undelivered
   issues (#775, no merged PR) and the report issue itself (#797) are
   untouched, and the healer pass is idempotent.

The fixture replay defines the bug class: against the PRE-mitigation
behavior (no merged-delivered guard) the fixtures go RED —
test_fixtures_discriminate_pre_mitigation proves the class is
discriminating by stubbing the guard to the pre-mitigation no-op and
showing the duplicate fire WOULD happen. Against current master the
whole suite is GREEN — that green is the acceptance evidence (TDD in
spirit, spec #889; no HTTP mocking: only the gh transport is replaced).

Run: python3 .github/tests/replay_797.py [-v]
     (or: python3 -m unittest discover -s .github/tests -p 'replay_797.py')
"""

import copy
import importlib.util
import os
import re
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


orchestrator = _load(
    "orchestrator_797_replay",
    os.path.join(os.path.dirname(_HERE), "orchestrator.py"))
fx = _load("fixtures_797", os.path.join(_HERE, "fixtures_797.py"))


class ReplayWorld:
    """Fixture-backed stand-in for the gh transport + side-effect
    recorder..install() swaps the orchestrator's I/O surface (api,
    open_pulls, post_comment, close_issue, label_issue, _fire_workflow)
    for fixture reads and recorded writes; restore() puts the originals
    back so a shared interpreter stays unpolluted."""

    PATCHED = ("api", "open_pulls", "post_comment", "close_issue",
               "label_issue", "_fire_workflow")

    def __init__(self):
        self.comments = []      # (issue, body)
        self.closures = []      # (issue, reason, body)
        self.labels = []        # (issue, label)
        self.dispatches = []    # (lock, item_type, item_number)
        self._open = copy.deepcopy(fx.OPEN_ISSUES)
        self._events = {}       # issue -> override event list (derived fixtures)
        self._saved = {}

    # -- fixture-backed reads ------------------------------------------------
    def api(self, path):
        if path.startswith("pulls?state=closed"):
            return copy.deepcopy(fx.CLOSED_PULLS)
        if path.startswith("pulls/"):
            num = int(re.match(r"pulls/(\d+)", path).group(1))
            for pr in fx.CLOSED_PULLS:
                if pr["number"] == num:
                    return copy.deepcopy(pr)
            return None
        if path.startswith("issues?state=open"):
            return copy.deepcopy(self._open)
        m = re.match(r"issues/(\d+)/events", path)
        if m:
            num = int(m.group(1))
            src = self._events.get(num, fx.LABEL_EVENTS.get(num, []))
            return copy.deepcopy(src)
        return None

    def open_pulls(self):
        return copy.deepcopy(fx.OPEN_PULLS)

    # -- recorded writes -----------------------------------------------------
    def post_comment(self, num, body):
        self.comments.append((num, body))

    def close_issue(self, num, reason, body):
        self.closures.append((num, reason, body))
        self._open = [i for i in self._open if i["number"] != num]

    def label_issue(self, num, name):
        self.labels.append((num, name))

    def _fire_workflow(self, lock, item_type, item_number):
        self.dispatches.append((lock, item_type, item_number))

    # -- install/restore -----------------------------------------------------
    def install(self):
        o = orchestrator
        for name in self.PATCHED:
            self._saved[name] = getattr(o, name)
            setattr(o, name, getattr(self, name))
        self._saved_owner = o.OWNER
        o.OWNER = fx.OWNER
        return self

    def restore(self):
        o = orchestrator
        for name, fn in self._saved.items():
            setattr(o, name, fn)
        o.OWNER = self._saved_owner

    # -- convenience ---------------------------------------------------------
    def closed_numbers(self):
        return {n for n, _, _ in self.closures}

    def fired_issues(self):
        return {n for _, _, n in self.dispatches}


class ReplayTestCase(unittest.TestCase):
    def setUp(self):
        self.world = ReplayWorld().install()
        self.addCleanup(self.world.restore)


class TestStampedAfterMerge(ReplayTestCase):
    """_stamped_after_merge — the owner-only re-arm check."""

    def test_machinery_restamps_after_merge_never_rearm(self):
        # recorded #771: latest planned stamp 05:08Z > merge 04:35Z, but
        # actor is the machinery — NOT a deliberate re-arm
        merged = orchestrator._merged_pr_for_story(771)
        self.assertEqual(merged, 792)
        self.assertFalse(orchestrator._stamped_after_merge(
            771, "2026-10-20T04:35:00Z"))

    def test_pre_merge_restamps_never_rearm(self):
        # recorded #768: post-merge wave stamps are machinery too; and a
        # pre-merge-only history would fail on time even if owner-authored
        self.assertFalse(orchestrator._stamped_after_merge(
            768, "2026-10-20T04:32:00Z"))

    def test_owner_stamp_after_merge_rearms(self):
        # synthetic control #990: the only recorded-class shape that
        # counts as a deliberate re-arm
        merged = orchestrator._merged_pr_for_story(990)
        self.assertEqual(merged, 991)
        self.assertTrue(orchestrator._stamped_after_merge(
            990, "2026-10-20T04:35:00Z"))

    def test_outsider_stamp_after_merge_fails_closed(self):
        # synthetic control #992: same shape, non-owner human actor —
        # unknown/undeterminable authorization = out
        self.assertFalse(orchestrator._stamped_after_merge(
            992, "2026-10-20T04:35:00Z"))

    def test_nonatomic_wave_resolves_to_latest_labeled_add(self):
        # the recorded 05:08–05:11Z wave on #768 has add/remove pairs;
        # the check reads the LAST labeled event, not the first — prove
        # it by re-shaping only the last wave add to the OWNER (derived
        # fixture): if the scan stopped at the earlier bot adds, the
        # re-arm would be missed
        evs = fx.derived_events(768, actor={"login": fx.OWNER})
        merged_at = orchestrator.api("pulls/791").get("merged_at")
        # route the derived event list through the world's transport: if
        # the scan stopped at the earlier bot adds, the re-arm would be
        # missed — reading the LAST labeled add is what makes the
        # non-atomic wave resolve correctly
        self.world._events[768] = evs
        self.assertTrue(orchestrator._stamped_after_merge(768, merged_at))

    def test_no_planned_stamp_no_rearm(self):
        self.assertFalse(orchestrator._stamped_after_merge(
            797, "2026-10-20T04:35:00Z"))


class TestFireImplementGuard(ReplayTestCase):
    """_fire_implement — the merged-delivered guard (spec #889
    invariant 1: a merged-delivered story is never re-fired)."""

    def test_recorded_restamp_never_refires_771(self):
        # the bug class itself: post-merge machinery re-stamp on #771
        # must not re-fire (historically: run 39368254975)
        orchestrator._fire_implement(771)
        self.assertEqual(self.world.dispatches, [])
        self.assertEqual(self.world.labels, [])

    def test_merged_delivery_768_never_refires(self):
        orchestrator._fire_implement(768)
        self.assertEqual(self.world.dispatches, [])
        self.assertEqual(self.world.labels, [])

    def test_owner_rearm_fires_again(self):
        orchestrator._fire_implement(990)
        self.assertEqual(self.world.dispatches,
                         [("05-implement.lock.yml", "issue", 990)])
        self.assertEqual(self.world.labels, [(990, "status/planned")])

    def test_undelivered_story_still_fires(self):
        # #775: no merged delivery in the record — the guard must not
        # over-block next-in-line work
        orchestrator._fire_implement(775)
        self.assertEqual(self.world.dispatches,
                         [("05-implement.lock.yml", "issue", 775)])
        self.assertEqual(self.world.labels, [(775, "status/planned")])

    def test_open_delivery_blocks_refire(self):
        # the open-PR half of the guard: a delivery PR still open means
        # in-flight work, never a second pass (derived fixture)
        saved = fx.OPEN_PULLS
        fx.OPEN_PULLS = [{"number": 800, "state": "open",
                          "head": {"ref": "devloop/issue-775-derived"}}]
        try:
            orchestrator._fire_implement(775)
        finally:
            fx.OPEN_PULLS = saved
        self.assertEqual(self.world.dispatches, [])
        self.assertEqual(self.world.labels, [])

    def test_fixtures_discriminate_pre_mitigation(self):
        # RED check: with the guard stubbed to the pre-mitigation shape
        # (no merged-PR knowledge), the exact recorded 05:08Z re-stamp
        # on #771 WOULD fire a duplicate implement pass. If this fails,
        # the fixtures no longer capture the bug class.
        saved_merged = orchestrator._merged_pr_for_story
        saved_stamp = orchestrator._stamped_after_merge
        orchestrator._merged_pr_for_story = lambda num: None
        orchestrator._stamped_after_merge = lambda num, at: False
        try:
            orchestrator._fire_implement(771)
        finally:
            orchestrator._merged_pr_for_story = saved_merged
            orchestrator._stamped_after_merge = saved_stamp
        self.assertEqual(self.world.dispatches,
                         [("05-implement.lock.yml", "issue", 771)])


class TestCloseoutHealer(ReplayTestCase):
    """closeout_healer — spec #889 invariant 3: open issues with merged
    delivery PRs close out; undelivered and guarded issues don't."""

    def pass_(self):
        orchestrator.closeout_healer()

    def test_merged_delivered_open_issues_close_out(self):
        self.pass_()
        # #771 (never closed by ADR-0007 closeout), #768, and #767 (the
        # closeout-then-human-reopen) all delivered via merged PRs →
        # healed closed; #990 guarded by the owner re-arm; #992's
        # outsider stamp is not a re-arm → closes like the recorded trio
        self.assertEqual(self.world.closed_numbers(),
                         {771, 768, 767, 992})
        # every closure carries the delivered marker naming its PR
        by_num = {n: (r, b) for n, r, b in self.world.closures}
        self.assertEqual(by_num[771][0], "completed")
        self.assertIn("PR #792", by_num[771][1])
        self.assertIn("PR #791", by_num[768][1])
        self.assertIn("delivered", by_num[767][1])

    def test_undelivered_issue_untouched(self):
        # #775: no merged delivery PR in the record — the healer must
        # not guess a delivery that never merged
        self.pass_()
        self.assertNotIn(775, self.world.closed_numbers())
        self.assertNotIn(775, [n for n, _ in self.world.comments])

    def test_healer_never_touches_non_delivered_specials(self):
        # the #797 report issue itself: open, no delivery PR, no S<n>
        # story prefix — enumeration is all open issues, but only the
        # merged-delivery predicate may close
        self.pass_()
        self.assertNotIn(797, self.world.closed_numbers())
        self.assertNotIn(990, self.world.closed_numbers())  # owner re-arm
        self.assertNotIn(990, [n for n, _ in self.world.comments])

    def test_healer_pass_is_idempotent(self):
        self.pass_()
        first = self.world.closed_numbers()
        # second pass: closed issues are gone from the open set (the
        # world models the real close), guarded ones stay guarded
        self.pass_()
        self.assertEqual(self.world.closed_numbers(), first)
        self.assertEqual(len(self.world.closures), len(first))


if __name__ == "__main__":
    unittest.main()
