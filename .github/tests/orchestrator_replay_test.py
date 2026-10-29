#!/usr/bin/env python3
"""Replay harness for the #797 bug class (parent spec #797, S1 #889).

Drives the recorded 2026-10-20 label-event history (#771/#768/#775/#767)
and their PR delivery records through `.github/orchestrator.py` —
`closeout_healer()`, `_fire_implement()` (merged-delivered guard),
`_stamped_after_merge()` (owner-only re-arm check) — and asserts the
invariant class:

  1. A merged-delivered story is NEVER re-fired (duplicate implement
     pass / racing-PR class: #771's 05:08:43Z bot re-stamp re-fired run
     39368254975 under the pre-mitigation interpretation).
  2. Only a post-merge `status/planned` applied by the OWNER login counts
     as a deliberate re-arm; bot re-stamps never do (every recorded
     re-stamp in the wave is `github-actions[bot]`-applied).
  3. Open issues with a merged delivery PR close out on the healer pass;
     open issues whose delivery did NOT merge (duplicates, and the owner's
     human reopen of #767) stay open.

TDD in spirit: the fixtures define the bug class RED (a naive,
actor-agnostic post-merge-stamp interpretation re-fires #771 — that is
the recorded regression); current `master` must be GREEN — green here is
the acceptance evidence that the merged-delivered guard holds against
the recorded wave. Python-stdlib unittest only, no network: every
orchestrator side channel is monkeypatched to the fixtures.

Run: python3 -m unittest discover -s .github/tests -p 'orchestrator_replay_test.py'
"""

import copy
import importlib.util
import json
import os
import unittest
from unittest import mock

_HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURE_PATH = os.path.join(_HERE, "fixtures", "labels_2026-10-20.json")

# Import orchestrator.py by path (same technique as orchestrator_test.py;
# the module level is import-safe — the runner is behind __main__).
_spec = importlib.util.spec_from_file_location(
    "orchestrator", os.path.join(_HERE, os.pardir, "orchestrator.py"))
orchestrator = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(orchestrator)

IMPLEMENT_LOCK = "05-implement.lock.yml"


def load_world():
    with open(FIXTURE_PATH, "r", encoding="utf-8") as fh:
        return json.load(fh)


def load_fallback_world():
    """Recorded 2026-10-23 recovery-record class (#1282): a story completed
    via the protected-files `request-review` policy (safe-outputs NOTE:
    'a fallback review issue was created ... due to protected file
    changes: [#N]'), then re-armed by machinery 4 minutes after the lane
    set it in-review — and re-fired."""
    path = os.path.join(_HERE, "fixtures", "fallback_delivery.json")
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


class ReplayCase(unittest.TestCase):
    """Base: installs a fixture-driven fake for every orchestrator side
    channel (api / post_comment / close_issue / label_issue / open_pulls /
    _fire_workflow) and pins OWNER to the fixture's owner login."""

    def setUp(self, loader=load_world):
        self.world = loader()
        self.owner = self.world["owner"]

        self.comments = []   # (num, body)
        self.closes = []     # (num, reason, body)
        self.labels = []     # (num, name)
        self.fires = []      # (lock, item_type, item_number)

        self.patches = [
            mock.patch.object(orchestrator, "OWNER", self.owner),
            mock.patch.object(orchestrator, "open_pulls",
                              lambda: list(self.world["pulls"]["open"])),
            mock.patch.object(orchestrator, "post_comment",
                              lambda num, body, *a, **kw:
                                  self.comments.append((num, body))),
            mock.patch.object(orchestrator, "close_issue",
                              lambda num, reason, body, *a, **kw:
                                  self.closes.append((num, reason, body))),
            mock.patch.object(orchestrator, "label_issue",
                              lambda num, name, *a, **kw:
                                  self.labels.append((num, name))),
            mock.patch.object(orchestrator, "_fire_workflow",
                              lambda lock, item_type, item_number, *a, **kw:
                                  self.fires.append((lock, item_type, item_number))),
            mock.patch.object(orchestrator, "api", self._fake_api),
        ]
        for p in self.patches:
            p.start()
        self.addCleanup(mock.patch.stopall)

    # --- fixture routing -------------------------------------------------
    def _fake_api(self, path, *a, **kw):
        world = self.world
        if path.startswith("issues?state=open"):
            return [{"number": int(n), "state": data["state"], "title": data["title"]}
                    for n, data in sorted(world["issues"].items())
                    if data["state"] == "open"]
        if path.startswith("issues/") and "/events" in path:
            return list(world["issues"][path.split("/")[1]]["events"])
        if path.startswith("issues/") and "/comments" in path:
            num = int(path.split("/")[1])
            return list(world.get("comments", {}).get(str(num), []))
        if path.startswith("issues/"):
            num = path.split("/")[1]
            if num.isdigit() and num in world["issues"]:
                return copy.deepcopy(world["issues"][num])
        if path.startswith("pulls?state=closed"):
            return [copy.deepcopy(p) for p in world["pulls"]["closed"]]
        if path.startswith("pulls/"):
            for p in world["pulls"]["closed"]:
                if p["number"] == int(path.split("/")[1]):
                    return copy.deepcopy(p)
        return None

    # --- fixture access / derivation --------------------------------------
    def issue_events(self, num):
        return self.world["issues"][str(num)]["events"]

    def merged_pr_for(self, num):
        """Same resolution rule as `_merged_pr_for_story`, for fixture-side
        expectations."""
        pat = orchestrator._delivered_branch_pat(num)
        for p in self.world["pulls"]["closed"]:
            if pat.search(p["head"]["ref"]) and p.get("merged_at"):
                return p
        return None

    def derived_rearm_events(self, num, actor):
        """Invariant-class probe: the recorded history with the LATEST
        post-merge `status/planned` event's actor switched from the bot to
        another login. Not a recorded event — derived from the recorded
        shape on purpose (a deliberate human re-stamp was never captured
        live in the wave; that is exactly the shape the re-arm invariant
        is for)."""
        merged_at = self.merged_pr_for(num)["merged_at"]
        events = copy.deepcopy(self.issue_events(num))
        latest = None
        for e in events:
            if e["event"] == "labeled" and (e["label"] or {}).get("name") == "status/planned" \
                    and e["created_at"] > merged_at:
                latest = e
        assert latest is not None, f"fixture {num}: no post-merge re-stamp to derive from"
        latest["actor"]["login"] = actor
        return events

    def set_issue_events(self, num, events):
        self.world["issues"][str(num)]["events"] = events


class TestStampedAfterMergeOwnerOnlyRearm(ReplayCase):
    """Invariant 2: ONLY a post-merge `status/planned` applied by the OWNER
    login counts as a deliberate re-arm."""

    def test_recorded_post_merge_bot_restamps_771_not_rearm(self):
        merged_at = self.merged_pr_for(771)["merged_at"]
        self.assertFalse(orchestrator._stamped_after_merge(771, merged_at))

    def test_recorded_post_merge_bot_restamps_768_not_rearm(self):
        # #768's latest labeled status/planned (05:08:54Z) is post-merge
        # (merge 04:32:10Z) and lands through the non-atomic unlabeled/
        # labeled batch pair — the scan must take the labeled event's ts.
        merged_at = self.merged_pr_for(768)["merged_at"]
        self.assertFalse(orchestrator._stamped_after_merge(768, merged_at))

    def test_derived_owner_restamp_post_merge_is_rearm(self):
        self.set_issue_events(771, self.derived_rearm_events(771, self.owner))
        merged_at = self.merged_pr_for(771)["merged_at"]
        self.assertTrue(orchestrator._stamped_after_merge(771, merged_at))

    def test_other_human_actor_post_merge_not_rearm(self):
        # Fail closed for any non-OWNER actor landing post-merge.
        self.set_issue_events(771, self.derived_rearm_events(771, "SomeOtherHuman"))
        merged_at = self.merged_pr_for(771)["merged_at"]
        self.assertFalse(orchestrator._stamped_after_merge(771, merged_at))

    def test_pre_merge_stamp_wave_only_not_rearm(self):
        # The whole recorded wave happened BEFORE the merge — model a
        # merge at the end of the window; no post-merge stamp exists and
        # nothing re-arms.
        merged_at = "2026-10-20T10:30:00Z"
        self.assertFalse(orchestrator._stamped_after_merge(768, merged_at))

    def test_no_merged_at_never_rearm(self):
        self.assertFalse(orchestrator._stamped_after_merge(767, None))
        self.assertFalse(orchestrator._stamped_after_merge(775, None))


class TestFireImplementMergedDeliveredGuard(ReplayCase):
    """Invariant 1: a merged-delivered story is never re-fired."""

    def test_771_merged_delivery_never_refired(self):
        orchestrator._fire_implement(771)
        self.assertEqual(self.fires, [])
        self.assertEqual(self.labels, [])

    def test_768_merged_delivery_never_refired(self):
        orchestrator._fire_implement(768)
        self.assertEqual(self.fires, [])
        self.assertEqual(self.labels, [])

    def test_live_reopen_767_is_live_work_and_fires(self):
        # #767: NO merged delivery (racing duplicate #787 closed unmerged),
        # maintainer deliberately reopened — this is genuinely live work,
        # so the machinery relays the fire (a bot label event is loop-dead;
        # the dispatch IS the fire).
        orchestrator._fire_implement(767)
        self.assertEqual(self.fires, [(IMPLEMENT_LOCK, "issue", 767)])
        self.assertEqual(self.labels, [(767, "status/planned")])

    def test_775_unmerged_duplicates_out_of_guarded_class(self):
        # #775 has two UNmerged duplicate PRs (#802/#803) and no merged
        # delivery — outside the guarded class; the machinery may fire.
        orchestrator._fire_implement(775)
        self.assertEqual(self.fires, [(IMPLEMENT_LOCK, "issue", 775)])
        self.assertEqual(self.labels, [(775, "status/planned")])


class TestBugClassRegression(ReplayCase):
    """The bug class, red against the pre-mitigation interpretation:
    an actor-agnostic post-merge re-stamp check re-fires #771 — exactly
    the recorded duplicate implement pass (run 39368254975) and the
    racing-PR class of #797. The adjacent test proves the real guard is
    what makes it green on the same recorded history."""

    def test_naive_actor_agnostic_guard_refires_771_red(self):
        def naive_stamped_after_merge(num, merged_at):
            if not merged_at:
                return False
            latest = None
            for e in self.issue_events(num):
                if e["event"] == "labeled" and (e["label"] or {}).get("name") == "status/planned":
                    latest = e
            if not latest:
                return False
            return latest["created_at"] > merged_at   # actor IGNORED

        with mock.patch.object(orchestrator, "_stamped_after_merge",
                               lambda num, ma: naive_stamped_after_merge(num, ma)):
            orchestrator._fire_implement(771)
        self.assertEqual(self.fires, [(IMPLEMENT_LOCK, "issue", 771)],
                         "pre-mitigation actor-agnostic interpretation re-fires the "
                         "merged-delivered story — the red bug class of #797")

    def test_real_guard_rejects_same_history_green(self):
        orchestrator._fire_implement(771)
        self.assertEqual(self.fires, [],
                         "the actor-aware guard must keep the same recorded history un-fired")


class TestCloseoutHealer(ReplayCase):
    """Invariant 3: open issues with a merged delivery PR close out on the
    healer pass; everything else stays open."""

    def test_merged_delivered_open_issues_close_out(self):
        orchestrator.closeout_healer()
        closed = {num for num, _r, _b in self.closes}
        self.assertIn(771, closed)
        self.assertIn(768, closed)
        self.assertEqual({reason for _n, reason, _b in self.closes}, {"completed"})
        # the delivered note is recorded on each closed-out issue
        commented = {num for num, _b in self.comments}
        self.assertIn(771, commented)
        self.assertIn(768, commented)

    def test_no_delivery_and_human_reopen_stay_open(self):
        orchestrator.closeout_healer()
        closed = {num for num, _r, _b in self.closes}
        # #775: no merged delivery (both duplicates #802/#803 unmerged) —
        # the healer has no delivery record to act on.
        self.assertNotIn(775, closed)
        # #767: the maintainer deliberately reopened a closeout — the human
        # decision is respected; its delivery never merged anyway.
        self.assertNotIn(767, closed)

    def test_owner_rearm_skips_closeout(self):
        self.set_issue_events(771, self.derived_rearm_events(771, self.owner))
        orchestrator.closeout_healer()
        closed = {num for num, _r, _b in self.closes}
        self.assertNotIn(771, closed,
                         "a deliberate owner re-stamp post-merge overrides the closeout (re-arm)")
        # #768 in the same sweep still closes out.
        self.assertIn(768, closed)

    def test_owner_unset_fails_closed_no_mutation(self):
        with mock.patch.object(orchestrator, "OWNER", ""):
            orchestrator.closeout_healer()
        self.assertEqual(self.closes, [])
        self.assertEqual(self.comments, [])


class TestFallbackReceiptGuard(ReplayCase):
    """Invariant 4 (#1282 / #1185: the 2026-10-23 recovery-record re-fire
    wave): a story completed via the protected-files `request-review`
    policy carries a machinery receipt — the safe-outputs NOTE 'a fallback
    review issue was created ... due to protected file changes: [#N]'.
    That receipt IS the delivery record: the machinery must NEVER re-arm
    /re-fire a receipt-carrying record. Mirror of invariant 2: only an
    OWNER-applied status/planned AFTER the receipt counts as a deliberate
    re-arm; bot re-stamps never do (recorded: the 16:36:11Z machinery
    re-stamp on #1264 re-fired 4 minutes after the lane set in-review)."""

    def setUp(self):
        super().setUp(loader=load_fallback_world)

    def _rearm(self, num, actor, at):
        self.world["issues"][str(num)]["events"].append(
            {"event": "labeled", "label": {"name": "status/planned"},
             "actor": {"login": actor}, "created_at": at})

    def test_recorded_receipt_never_refires(self):
        orchestrator._fire_implement(1264)
        self.assertEqual(self.fires, [],
                         "a fallback-review receipt is a delivery record — "
                         "re-firing is the #1282 class")
        self.assertEqual(self.labels, [], "no re-arm stamp on a delivered record")

    def test_derived_owner_rearm_after_receipt_refires(self):
        self._rearm(1264, self.owner, "2026-10-23T17:10:00Z")
        orchestrator._fire_implement(1264)
        self.assertEqual(self.fires, [(IMPLEMENT_LOCK, "issue", 1264)])

    def test_bot_rearm_after_receipt_not_rearm(self):
        # The recorded wave's re-stamp was machinery-applied — loop-dead.
        self._rearm(1264, "github-actions[bot]", "2026-10-23T17:10:00Z")
        orchestrator._fire_implement(1264)
        self.assertEqual(self.fires, [])

    def test_rearm_before_receipt_not_rearm(self):
        # A pre-receipt planned stamp is stale queue state, not a re-arm.
        self._rearm(1264, self.owner, "2026-10-23T15:00:00Z")
        orchestrator._fire_implement(1264)
        self.assertEqual(self.fires, [])

    def test_healer_closes_receipt_record_when_carrier_closes(self):
        # #1271 fixture carrier is closed (delivered) — the story record
        # closes out on the healer pass, same ADR-0007 semantics as a
        # merged PR.
        orchestrator.closeout_healer()
        closed = {num for num, _r, _b in self.closes}
        self.assertIn(1264, closed)

    def test_healer_leaves_receipt_record_open_while_carrier_open(self):
        self.world["issues"]["1271"]["state"] = "open"
        orchestrator.closeout_healer()
        closed = {num for num, _r, _b in self.closes}
        self.assertNotIn(1264, closed)

    def test_healer_owner_rearm_after_receipt_skips_closeout(self):
        self._rearm(1264, self.owner, "2026-10-23T17:10:00Z")
        orchestrator.closeout_healer()
        closed = {num for num, _r, _b in self.closes}
        self.assertNotIn(1264, closed)


class TestPlanLoopDeliveryGuard(ReplayCase):
    """#1358, recorded live: a load-check issue carrying `ai-fix` got a /plan round 1
    nine minutes *after* the review lane stamped `status/in-review`, with its delivery PR
    open for review. The fresh-trigger branch had no delivery guard."""

    def setUp(self):
        story = {"number": 900, "state": "open", "title": "story", "body": "no parent here",
                 "labels": [{"name": "ai-fix"}]}
        self.world = {"owner": "RjBiermann", "issues": {"900": story},
                      "pulls": {"open": [], "closed": []}, "comments": {}}
        super().setUp(loader=lambda: self.world)
        self.fires = []
        self.comments = []

        def _api(path, *a, **k):
            if path.startswith("issues?state=open"):
                return [story]      # the harness's own projection drops labels
            return self._fake_api(path, *a, **k)

        extra = [
            # `parent_of()` falls through to `gh api graphql` for a body with no parent link;
            # this suite is network-free by contract (sibling suite patches `run` likewise).
            # real run() returns stdout as a string; the consumers swallow failures
            mock.patch.object(orchestrator, "run", lambda *a, **k: ""),
            mock.patch.object(orchestrator, "api", _api),
            mock.patch.object(orchestrator, "all_stories", lambda *a, **k: [story]),
            mock.patch.object(orchestrator, "comments", lambda n, *a, **k: []),
            mock.patch.object(orchestrator, "post_comment",
                              lambda n, b, *a, **k: self.comments.append((n, b))),
            mock.patch.object(orchestrator, "_fire_workflow",
                              lambda lock, t, n, *a, **k: self.fires.append((lock, t, n))),
        ]
        for p in extra:
            p.start()
        self.addCleanup(mock.patch.stopall)

    def _pr(self, board, ref):
        self.world["pulls"][board].append(
            {"number": 914, "head": {"ref": ref}, "base": {"ref": "main"}, "body": "",
             "user": {"login": self.owner}, "draft": False})

    def test_plan_round_not_fired_when_delivery_pr_is_open(self):
        self._pr("open", "devloop/issue-900-abc123")
        orchestrator.plan_loop_surrogate()
        self.assertNotIn(("04-plan.lock.yml", "issue", 900), self.fires,
                         "a fresh trigger must not plan a story whose delivery PR is open")
        self.assertFalse([c for c in self.comments if "/plan fired" in c[1]],
                         "no plan-round marker either")

    def test_plan_round_not_fired_when_delivery_was_a_fallback_review_issue(self):
        """The third delivery record: gh-aw routes a protected-files fix to a fallback
        review issue (ADR-0020, #1264→#1271), so no PR exists while the work is in
        review. Guarding on PRs alone leaves the #1358 race live on that path."""
        receipt = ("> [!NOTE]\n> The pull request was not created — a fallback review issue "
                   "was created instead due to protected file changes: [#1271]"
                   "(https://github.com/o/r/issues/1271)")
        patcher = mock.patch.object(orchestrator, "comments", lambda n, *a, **k: (
            [{"body": receipt, "created_at": "2026-10-23T04:00:00Z"}] if n == 900 else []))
        patcher.start()
        self.addCleanup(patcher.stop)
        orchestrator.plan_loop_surrogate()
        self.assertNotIn(("04-plan.lock.yml", "issue", 900), self.fires,
                         "a fallback-delivered story must not be planned again")

    def test_plan_round_not_fired_when_delivery_already_merged(self):
        """The merged arm of the predicate: deleting it must fail here."""
        self._pr("closed", "devloop/issue-900-abc123")
        self.world["pulls"]["closed"][0]["merged_at"] = "2026-10-23T05:00:00Z"
        orchestrator.plan_loop_surrogate()
        self.assertNotIn(("04-plan.lock.yml", "issue", 900), self.fires,
                         "a merged delivery must not be planned again")

    def test_plan_round_not_fired_when_review_label_present(self):
        """Hashless branches (`devloop/issue-1358`, #1359, #1312) match the delivery
        pattern never; a review-family label is the evidence a delivery exists."""
        self.world["issues"]["900"]["labels"] = [{"name": "ai-fix"},
                                                 {"name": "status/in-review"}]
        orchestrator.plan_loop_surrogate()
        self.assertNotIn(("04-plan.lock.yml", "issue", 900), self.fires,
                         "a story in review must not be planned")

    def test_plan_round_still_fires_without_any_delivery(self):
        orchestrator.plan_loop_surrogate()
        self.assertIn(("04-plan.lock.yml", "issue", 900), self.fires,
                      "an undelivered fresh trigger must still plan")


class TestHandDeliveryClosingRecord(ReplayCase):
    """#915 via the record GitHub computes (#1379 option A): a maintainer
    hand-delivery on `maint/…` has no issue segment, so its merge is a Delivery
    only if its body names the story — and `closingIssuesReferences` is where
    GitHub records that. Parsing the body ourselves is what #1376 spent nine
    review rounds getting wrong."""

    def setUp(self):
        self.world = {"owner": "RjBiermann", "issues": {}, "pulls": {"open": [], "closed": []},
                      "comments": {}}
        super().setUp(loader=lambda: self.world)
        self.queries = []
        self.closing = [900]
        self.payload = None

        def _run(cmd, **kw):
            self.queries.append(cmd)
            if self.payload is not None:
                return self.payload
            nodes = [{"number": n} for n in self.closing]
            return json.dumps({"data": {"repository": {"pullRequest":
                                                       {"closingIssuesReferences":
                                                        {"nodes": nodes}}}}})

        repo = mock.patch.object(orchestrator, "REPO", f"{self.owner}/fictional-octo-fiesta")
        repo.start()
        self.addCleanup(repo.stop)
        patcher = mock.patch.object(orchestrator, "run", _run)
        patcher.start()
        self.addCleanup(patcher.stop)

    def _pr(self, board, ref, body="", merged_at=None):
        pr = {"number": 914, "head": {"ref": ref}, "base": {"ref": "main"}, "body": body,
              "title": "S2 grammar", "user": {"login": self.owner}}
        if merged_at:
            pr["merged_at"] = merged_at
        self.world["pulls"][board].append(pr)

    def test_merged_hand_delivery_counts_when_github_says_it_closed_the_story(self):
        self._pr("closed", "maint/s2-grammar", "Ships S2 grammar.\n\nFixes #900",
                 merged_at="2026-10-23T05:00:00Z")
        self.assertEqual(orchestrator._merged_pr_for_story(900), 914)
        self.assertTrue(self.queries, "the closing record must be consulted")

    def test_merged_pr_that_github_does_not_report_closing_is_not_a_delivery(self):
        self.closing = []
        self._pr("closed", "maint/s2-grammar", "Mentions #900 in passing, closes nothing",
                 merged_at="2026-10-23T05:00:00Z")
        self.assertIsNone(orchestrator._merged_pr_for_story(900))

    def test_open_hand_delivery_counts_too(self):
        self._pr("open", "maint/s2-grammar", "Fixes #900")
        self.assertEqual(orchestrator._open_pr_for_story(900), 914)

    def test_unrelated_pr_costs_no_query(self):
        self._pr("closed", "maint/unrelated", "No story reference here",
                 merged_at="2026-10-23T05:00:00Z")
        self.assertIsNone(orchestrator._merged_pr_for_story(900))
        self.assertEqual(self.queries, [], "a PR that never names the story needs no API call")

    def test_query_names_the_owner_repo_and_pr(self):
        """The stub used to ignore its argv, so nothing pinned *which* PR was
        asked about — a wrong repo or PR number would have passed."""
        self._pr("closed", "maint/s2-grammar", "Fixes #900",
                 merged_at="2026-10-23T05:00:00Z")
        self.assertEqual(orchestrator._merged_pr_for_story(900), 914)
        cmd = " ".join(self.queries[0])
        self.assertIn(f"o={self.owner}", cmd)
        self.assertIn("r=fictional-octo-fiesta", cmd)
        self.assertIn("n=914", cmd)

    def test_url_form_closing_keyword_is_not_vetoed_by_the_prefilter(self):
        """`Fixes https://github.com/o/r/issues/900` carries no `#900`, and the
        cheap pre-filter must never answer in place of GitHub's record."""
        self._pr("closed", "maint/s2-grammar",
                 "Fixes https://github.com/RjBiermann/fictional-octo-fiesta/issues/900",
                 merged_at="2026-10-23T05:00:00Z")
        self.assertEqual(orchestrator._merged_pr_for_story(900), 914)

    def test_graphql_error_payloads_read_as_not_closing_not_a_crash(self):
        """`{"data": null}` (rate limit) and `{"repository": null}` (repo
        invisible to the token) must not raise AttributeError — that aborts the
        whole conveyor pass, not just this story."""
        for payload in ('{"data": null}',
                        '{"data": {"repository": null}}',
                        '{"data": {"repository": {"pullRequest": null}}}',
                        "not json"):
            self.payload = payload
            self._pr("closed", "maint/s2-grammar", "Fixes #900",
                     merged_at="2026-10-23T05:00:00Z")
            self.assertIsNone(orchestrator._merged_pr_for_story(900), payload)


if __name__ == "__main__":
    unittest.main()
