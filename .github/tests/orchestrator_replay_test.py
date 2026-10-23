#!/usr/bin/env python3
"""Replay harness for the #797 post-merge re-stamp bug class (spec #889,
parent #797). Drives the recorded 2026-10-20 label/state-event history of
#771/#768/#767/#775 (fixtures/labels_2026-10-20.json, captured raw from the
live REST API) through .github/orchestrator.py and proves the invariant
class stays fixed:

  - closeout_healer(): an open issue whose delivery PR is merged closes out
    with the ADR-0007 record; no-delivery and human-reopened issues stay
    open; OWNER unset fails closed with zero side effects.
  - _fire_implement(): a merged-delivered story is never re-fired (the
    duplicate-PR class behind #797); a no-delivery story and a
    human-reopened story legitimately fire.
  - _stamped_after_merge(): only a post-merge `status/planned` applied by
    the OWNER login re-arms a delivered story; machinery re-stamps never do.

TDD in spirit: the actor-agnostic reading of the same recorded history
(any post-merge planned stamp re-arms) re-fires #771 — the recorded
duplicate implement pass (run 39368254975). That red is asserted in
TestRedBugClass; the shipped actor-aware guard rejecting the same history
is the green acceptance evidence.

The replay's api() double raises on any REST path outside the modeled set,
so an orchestrator drift to new endpoints fails the replay loudly instead
of silently no-oping. Stdlib unittest only, no network.

Run: python3 -m unittest discover -s .github/tests -p 'orchestrator_replay_test.py'
"""

import copy
import importlib.util
import json
import os
import re
import unittest

_HERE = os.path.dirname(os.path.abspath(__file__))
_GITHUB_DIR = os.path.dirname(_HERE)
FIXTURE_PATH = os.path.join(_HERE, "fixtures", "labels_2026-10-20.json")

_spec = importlib.util.spec_from_file_location(
    "orchestrator", os.path.join(_GITHUB_DIR, "orchestrator.py"))
orchestrator = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(orchestrator)

with open(FIXTURE_PATH) as fh:
    RAW_FIXTURE = json.load(fh)

OWNER_LOGIN = RAW_FIXTURE["meta"]["owner"]
FREEZE = RAW_FIXTURE["meta"]["freeze"]
BOT = "github-actions[bot]"
MERGED_791 = "2026-10-20T04:32:10Z"   # delivery of #768
MERGED_792 = "2026-10-20T04:35:04Z"   # delivery of #771

ISO_Z = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")


def actor_agnostic_rearm(events, merged_at):
    """The pre-mitigation reading (#797's bug class): the latest
    `status/planned` stamp after the merge re-arms the story regardless of
    who applied it. Under this reading the machinery's own re-stamp waves
    are loop fuel — every wave re-fires a delivered story."""
    latest = None
    for e in events:
        if e["event"] == "labeled" \
                and (e.get("label") or {}).get("name") == "status/planned":
            latest = e
    return bool(latest) and (latest["created_at"] or "") > merged_at


class Replay:
    """In-memory replay of the REST surface the three replay targets read,
    plus a mutation recorder for everything they write. Any API path
    outside the modeled set raises — the replay never silently no-ops."""

    CLOSED_PULLS = "pulls?state=closed&per_page=60"
    OPEN_PULLS = "pulls?state=open&per_page=50"
    OPEN_ISSUES = "issues?state=open&per_page=100"

    def __init__(self, fixture):
        self.fixture = fixture
        self.api_calls = []
        self.mutations = []

    def api(self, path):
        self.api_calls.append(path)
        if path == self.CLOSED_PULLS:
            return self.fixture["pulls"]
        if path == self.OPEN_PULLS:
            return self.fixture["open_pulls"]
        if path == self.OPEN_ISSUES:
            return [{"number": int(n), "state": "open"}
                    for n, i in self.fixture["issues"].items()]
        m = re.fullmatch(r"pulls/(\d+)", path)
        if m:
            num = int(m.group(1))
            for pr in self.fixture["pulls"]:
                if pr["number"] == num:
                    return pr
            raise AssertionError(f"unmodeled REST path in replay: {path}")
        m = re.fullmatch(r"issues/(\d+)/events\?per_page=100", path)
        if m:
            issue = self.fixture["issues"].get(m.group(1))
            if issue is None:
                raise AssertionError(f"unmodeled REST path in replay: {path}")
            return issue["events"]
        raise AssertionError(f"unmodeled REST path in replay: {path}")

    def open_pulls(self):
        return self.api(self.OPEN_PULLS)

    def post_comment(self, num, body):
        self.mutations.append({"op": "comment", "number": num, "body": body})

    def close_issue(self, num, reason, body):
        self.mutations.append(
            {"op": "close", "number": num, "reason": reason, "body": body})

    def label_issue(self, num, name):
        self.mutations.append({"op": "label", "number": num, "label": name})

    def fire_workflow(self, lock, item_type, item_number):
        # "number" doubles the item number under the uniform mutation key
        # so ops_of()/ops_for() filtering works across ops.
        self.mutations.append({"op": "dispatch", "workflow": lock,
                               "item_type": item_type,
                               "item_number": item_number,
                               "number": item_number})

    def ops_of(self, op, number=None):
        return [m for m in self.mutations
                if m["op"] == op
                and (number is None or m["number"] == number)]

    def ops_for(self, number):
        return [m for m in self.mutations if m["number"] == number]


class ReplayTest(unittest.TestCase):
    """Base: fresh fixture copy + orchestrator channels wired to the replay."""

    def setUp(self):
        self.replay = Replay(copy.deepcopy(RAW_FIXTURE))
        names = ("api", "open_pulls", "post_comment", "close_issue",
                 "label_issue", "_fire_workflow", "OWNER")
        self._saved = {n: getattr(orchestrator, n) for n in names}
        orchestrator.api = self.replay.api
        orchestrator.open_pulls = self.replay.open_pulls
        orchestrator.post_comment = self.replay.post_comment
        orchestrator.close_issue = self.replay.close_issue
        orchestrator.label_issue = self.replay.label_issue
        orchestrator._fire_workflow = self.replay.fire_workflow
        orchestrator.OWNER = OWNER_LOGIN
        self.addCleanup(self._restore)

    def _restore(self):
        for name, value in self._saved.items():
            setattr(orchestrator, name, value)

    def events_of(self, num):
        return self.replay.fixture["issues"][str(num)]["events"]

    def with_event(self, num, event):
        """Append a synthetic event to an issue's recorded history
        (used only for the documented deliberate-re-arm shapes)."""
        self.replay.fixture["issues"][str(num)]["events"].append(event)
        return event

    def set_owner(self, login):
        orchestrator.OWNER = login


# --------------------------------------------------------------------------
# Recorded-shape pins: the fixture must stay the raw REST capture.
# --------------------------------------------------------------------------
class TestRecordedFixtureShapes(ReplayTest):
    def test_freeze_boundary(self):
        # The modeled world freezes at 2026-10-20T10:08:00Z — after the last
        # recorded re-stamp wave; every recorded event predates the freeze
        # and the latest one is #775's 10:07:21Z unlabeled planned.
        latest = ""
        for issue in RAW_FIXTURE["issues"].values():
            for e in issue["events"]:
                self.assertTrue(ISO_Z.match(e["created_at"]), e)
                self.assertLess(e["created_at"], FREEZE)
                latest = max(latest, e["created_at"])
        self.assertEqual(latest, "2026-10-20T10:07:21Z")

    def test_raw_rest_event_shape(self):
        # Label events carry exactly the raw REST field paths; state events
        # (closed/reopened) carry no label key.
        for issue in RAW_FIXTURE["issues"].values():
            for e in issue["events"]:
                self.assertIn("event", e)
                self.assertIn("created_at", e)
                self.assertTrue(e.get("actor", {}).get("login"))
                if e["event"] in ("labeled", "unlabeled"):
                    self.assertEqual(
                        set(e), {"event", "created_at", "actor", "label"})
                    self.assertIsInstance(e["label"]["name"], str)
                else:
                    self.assertEqual(set(e), {"event", "created_at", "actor"})
                    self.assertIn(e["event"], ("closed", "reopened"))

    def test_restamp_waves_on_merged_deliveries_are_bot(self):
        # #771: five labeled-planned waves; every one bot-applied; the
        # post-merge waves (after #792 merged 04:35:04Z) are the loop fuel.
        waves = [e["created_at"] for e in self.events_of(771)
                 if e["event"] == "labeled"
                 and e["label"]["name"] == "status/planned"]
        self.assertEqual(waves, ["2026-10-20T03:54:43Z", "2026-10-20T04:11:27Z",
                                 "2026-10-20T04:26:01Z", "2026-10-20T04:35:15Z",
                                 "2026-10-20T05:08:43Z", "2026-10-20T10:02:51Z"])
        post_merge = [t for t in waves if t > MERGED_792]
        self.assertEqual(post_merge, ["2026-10-20T04:35:15Z",
                                      "2026-10-20T05:08:43Z",
                                      "2026-10-20T10:02:51Z"])
        actors = {e["actor"]["login"] for e in self.events_of(771)
                  if e["event"] == "labeled"
                  and e["label"]["name"] == "status/planned"}
        self.assertEqual(actors, {BOT})

    def test_merged_delivery_timestamps_recorded(self):
        pulls = {p["number"]: p for p in RAW_FIXTURE["pulls"]}
        self.assertEqual(pulls[792]["merged_at"], MERGED_792)
        self.assertEqual(pulls[791]["merged_at"], MERGED_791)
        self.assertIsNone(pulls[787]["merged_at"])
        self.assertIsNone(pulls[790]["merged_at"])
        # every modeled head matches the orchestrator's own delivery-branch
        # pattern for its story
        for num, pr in pulls.items():
            story = {792: 771, 791: 768, 787: 767, 790: 768}[num]
            self.assertTrue(
                orchestrator._delivered_branch_pat(story).search(
                    pr["head"]["ref"]),
                f"PR #{num} head does not match the delivery pattern")

    def test_owner_premerge_stamp_is_the_negative_case(self):
        # #768's OWNER stamp (02:15:24Z) predates the merge of #791
        # (04:32:10Z) and every bot re-stamp wave — it must never count as
        # a post-merge re-arm.
        owner_stamps = [e["created_at"] for e in self.events_of(768)
                        if e["event"] == "labeled"
                        and e["actor"]["login"] == OWNER_LOGIN
                        and e["label"]["name"] == "status/planned"]
        self.assertEqual(owner_stamps, ["2026-10-20T02:15:24Z"])
        self.assertLess(owner_stamps[0], MERGED_791)
        bot_waves = [e["created_at"] for e in self.events_of(768)
                     if e["event"] == "labeled"
                     and e["actor"]["login"] == BOT
                     and e["label"]["name"] == "status/planned"]
        self.assertTrue(all(owner_stamps[0] < t for t in bot_waves))


# --------------------------------------------------------------------------
# closeout_healer(): ADR-0007 closeout over the recorded open-issue set.
# --------------------------------------------------------------------------
class TestCloseoutHealer(ReplayTest):
    def test_closes_open_merged_delivered_issues_with_adr0007_record(self):
        orchestrator.closeout_healer()
        for num, merged in ((771, 792), (768, 791)):
            comments = self.replay.ops_of("comment", number=num)
            self.assertEqual(len(comments), 1, f"#{num} comment")
            self.assertTrue(comments[0]["body"].startswith("[orchestrator]"))
            self.assertIn(f"delivered — PR #{merged} merged",
                          comments[0]["body"])
            closes = self.replay.ops_of("close", number=num)
            self.assertEqual(len(closes), 1, f"#{num} close")
            self.assertEqual(closes[0]["reason"], "completed")
            self.assertEqual(closes[0]["body"], f"delivered via PR #{merged}")
        # the closeout never relabels anything
        self.assertEqual(self.replay.ops_of("label"), [])

    def test_leaves_no_delivery_and_human_reopen_open(self):
        orchestrator.closeout_healer()
        # #775: no delivery PR at the freeze; #767: the maintainer's
        # deliberate reopen (05:08:56Z) is the modeled truth — the healer
        # must not fight it.
        self.assertEqual(self.replay.ops_for(775), [])
        self.assertEqual(self.replay.ops_for(767), [])

    def test_owner_rearm_after_merge_skips_closeout(self):
        # The intended deliberate re-arm: the OWNER re-stamps status/planned
        # after the merge ("run the work again") — the healer stands down.
        self.with_event(771, {"event": "labeled",
                              "created_at": "2026-10-20T11:00:00Z",
                              "actor": {"login": OWNER_LOGIN},
                              "label": {"name": "status/planned"}})
        orchestrator.closeout_healer()
        self.assertEqual(self.replay.ops_for(771), [])
        # the unaffected recorded stories still close out
        self.assertEqual(len(self.replay.ops_of("close", number=768)), 1)

    def test_fails_closed_without_owner(self):
        self.set_owner("")
        orchestrator.closeout_healer()
        self.assertEqual(self.replay.api_calls, [])
        self.assertEqual(self.replay.mutations, [])


# --------------------------------------------------------------------------
# _fire_implement(): the merged-delivered guard.
# --------------------------------------------------------------------------
class TestMergedDeliveredGuard(ReplayTest):
    def guard_fire(self, num):
        """(refired, labels, dispatches) for one _fire_implement call."""
        orchestrator._fire_implement(num)
        labels = self.replay.ops_of("label", number=num)
        dispatches = self.replay.ops_of("dispatch", number=num)
        return (bool(dispatches), labels, dispatches)

    def test_never_refires_merged_delivered(self):
        # #771 (delivered via #792) and #768 (delivered via #791): the
        # recorded bot re-stamp waves re-arm nothing — no label, no dispatch.
        for num in (771, 768):
            refired, labels, dispatches = self.guard_fire(num)
            self.assertFalse(refired, f"#{num} re-fired")
            self.assertEqual(labels + dispatches, [],
                             f"#{num} mutated despite the delivered guard")

    def test_reopened_human_case_fires(self):
        # #767's maintainer reopen (05:08:56Z) makes it live work in the
        # modeled world — the fire path mirrors the recorded 05:09–05:11
        # wave (stamp + dispatch).
        refired, labels, dispatches = self.guard_fire(767)
        self.assertTrue(refired)
        self.assertEqual(labels, [{"op": "label", "number": 767,
                                   "label": "status/planned"}])
        self.assertEqual(dispatches, [{"op": "dispatch",
                                       "workflow": "05-implement.lock.yml",
                                       "item_type": "issue",
                                       "item_number": 767,
                                       "number": 767}])

    def test_no_delivery_story_fires(self):
        # #775 had no delivery PR at the freeze — it sits outside the
        # guarded class and implement legitimately fires.
        refired, labels, dispatches = self.guard_fire(775)
        self.assertTrue(refired)
        self.assertEqual(len(labels), 1)
        self.assertEqual(len(dispatches), 1)

    def test_owner_rearm_redelivers_merged(self):
        # The intended deliberate re-arm on a merged delivery: OWNER stamps
        # after the merge → re-deliver.
        self.with_event(771, {"event": "labeled",
                              "created_at": "2026-10-20T11:00:00Z",
                              "actor": {"login": OWNER_LOGIN},
                              "label": {"name": "status/planned"}})
        refired, labels, dispatches = self.guard_fire(771)
        self.assertTrue(refired)
        self.assertEqual(len(labels), 1)
        self.assertEqual(len(dispatches), 1)


# --------------------------------------------------------------------------
# _stamped_after_merge(): the owner-only re-arm reading.
# --------------------------------------------------------------------------
class TestOwnerOnlyReArm(ReplayTest):
    def test_bot_restamps_never_rearm(self):
        # every recorded post-merge re-stamp is bot-applied → re-arms nothing
        self.assertFalse(orchestrator._stamped_after_merge(771, MERGED_792))
        self.assertFalse(orchestrator._stamped_after_merge(768, MERGED_791))

    def test_owner_premerge_stamp_never_counts(self):
        # #768's recorded OWNER stamp is pre-merge — even though the actor
        # IS the configured owner, it must not count as a post-merge re-arm.
        self.assertFalse(orchestrator._stamped_after_merge(768, MERGED_791))

    def test_owner_postmerge_stamp_redelivers(self):
        self.with_event(771, {"event": "labeled",
                              "created_at": "2026-10-20T11:00:00Z",
                              "actor": {"login": OWNER_LOGIN},
                              "label": {"name": "status/planned"}})
        self.assertTrue(orchestrator._stamped_after_merge(771, MERGED_792))

    def test_unrelated_human_never_rearms(self):
        self.with_event(771, {"event": "labeled",
                              "created_at": "2026-10-20T11:00:00Z",
                              "actor": {"login": "unrelated-human"},
                              "label": {"name": "status/planned"}})
        self.assertFalse(orchestrator._stamped_after_merge(771, MERGED_792))

    def test_bot_identity_owner_misconfig_is_load_bearing(self):
        # Tripwire: with OWNER misconfigured to the bot identity, every
        # recorded machinery wave would read as a deliberate re-arm — the
        # exact opposite of the invariant. The real owner login is what
        # makes the reading owner-only.
        self.set_owner(BOT)
        self.assertTrue(orchestrator._stamped_after_merge(771, MERGED_792))
        self.set_owner(OWNER_LOGIN)
        self.assertFalse(orchestrator._stamped_after_merge(771, MERGED_792))


# --------------------------------------------------------------------------
# The bug class itself: red (actor-agnostic) vs green (shipped guard) on the
# SAME recorded history.
# --------------------------------------------------------------------------
class TestRedBugClass(ReplayTest):
    def test_actor_agnostic_replay_refires_771_the_recorded_duplicate(self):
        # RED: the actor-agnostic reading of the recorded #771 history
        # re-fires the merged-delivered story — exactly the duplicate
        # implement pass the #797 record shows (run 39368254975, re-stamped
        # by the 10:02:51Z wave).
        self.assertTrue(actor_agnostic_rearm(self.events_of(771), MERGED_792))
        # GREEN: the shipped actor-aware guard rejects the same history.
        self.assertFalse(orchestrator._stamped_after_merge(771, MERGED_792))
        # and the guard's rejection is what keeps _fire_implement silent:
        orchestrator._fire_implement(771)
        self.assertEqual(self.replay.ops_for(771), [])

    def test_actor_agnostic_replay_refires_768_too(self):
        # #768's latest recorded planned stamp (05:08:54Z) also postdates
        # its merge (04:32:10Z) — the actor-agnostic reading would loop it
        # as well; the shipped guard does not.
        self.assertTrue(actor_agnostic_rearm(self.events_of(768), MERGED_791))
        self.assertFalse(orchestrator._stamped_after_merge(768, MERGED_791))


if __name__ == "__main__":
    unittest.main()
