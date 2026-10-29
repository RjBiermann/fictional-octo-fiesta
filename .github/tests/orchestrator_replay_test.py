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
import time
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


class TestDeliveryRecognition(ReplayCase):
    """#915 + #1358, both recorded as live mis-fires.

    #915: an owner hand-delivery (`maint/s2-grammar`, PR #914) carries no
    `issue-N-` segment, so `_merged_pr_for_story` never saw it and Implement
    re-fired for a story that was already merged.

    #1358: `/plan` round 1 fired nine minutes *after* the review lane had
    stamped `status/in-review` and the delivery PR was open for review, because
    the fresh-trigger branch had no delivery guard.

    Fixtures here are synthetic on purpose: both are shapes the recorded wave
    did not contain (the wave's deliveries all used agent branch names)."""

    def setUp(self):
        story = {"number": 900, "state": "open", "title": "story", "body": "no parent here",
                 "labels": [{"name": "ai-fix"}]}
        self.world = {"owner": "RjBiermann", "issues": {"900": story},
                      "pulls": {"open": [], "closed": []},
                      "comments": {}}
        super().setUp(loader=lambda: self.world)
        self.fires = []
        self.comments = []
        def _api(path, *a, **k):
            # the harness's own list projection drops labels, and the fresh-trigger
            # branch is driven by them
            if path.startswith("issues?state=open"):
                return [story]
            return self._fake_api(path, *a, **k)

        self.patches += [
            mock.patch.object(orchestrator, "api", _api),
            # no network in a unit test: `parent_of`'s sub-issue fallback shells
            # out to `gh api graphql` per story per pass (#1376 round-7 nit)
            mock.patch.object(orchestrator, "parent_of", lambda *a, **k: None),
            mock.patch.object(orchestrator, "all_stories", lambda *a, **k: [story]),
            mock.patch.object(orchestrator, "comments", lambda n, *a, **k: []),
            mock.patch.object(orchestrator, "post_comment",
                              lambda n, b, *a, **k: self.comments.append((n, b))),
            mock.patch.object(orchestrator, "_fire_workflow",
                              lambda lock, t, n, *a, **k: self.fires.append((lock, t, n))),
        ]
        for p in self.patches[-6:]:
            p.start()
        self.addCleanup(mock.patch.stopall)

    def _pr(self, board, ref, body="", merged_at=None, base="main"):
        pr = {"number": 914, "head": {"ref": ref}, "body": body, "base": {"ref": base},
              "user": {"login": self.owner}, "draft": False}
        if merged_at:
            pr["merged_at"] = merged_at
        self.world["pulls"][board].append(pr)
        return pr

    # --- #915: owner hand-delivery is a delivery -------------------------
    def test_owner_merged_pr_closing_the_story_counts_as_delivered(self):
        self._pr("closed", "maint/s2-grammar", body="Implements the story.\n\nFixes #900",
                 merged_at="2026-10-23T05:00:00Z")
        self.assertEqual(orchestrator._merged_pr_for_story(900), 914,
                         "an owner PR merged with `Fixes #900` is the story's delivery (#915)")

    def test_merged_pr_without_branch_shape_or_keyword_is_not_a_delivery(self):
        self._pr("closed", "maint/unrelated", body="Refactor only, no story closed",
                 merged_at="2026-10-23T05:00:00Z")
        self.assertIsNone(orchestrator._merged_pr_for_story(900),
                          "widening must not claim an unrelated merged PR")

    def test_negated_keyword_is_prose_not_a_delivery(self):
        """`This does not fix #900` must not retire an open story: closeout_healer
        closes what `_merged_pr_for_story` reports, so an over-match is destructive."""
        self._pr("closed", "maint/unrelated", merged_at="2026-10-23T05:00:00Z",
                 body="Unrelated refactor. This does not fix #900; tracked separately.")
        self.assertIsNone(orchestrator._merged_pr_for_story(900))
        self.assertNotIn(900, orchestrator._closing_keyword_nums(
            "Unrelated refactor. This does not fix #900; tracked separately."))

    def test_comma_separated_closing_list_covers_every_story(self):
        """`Closes #900, #901` is two deliveries — missing the second is #915 again."""
        self.assertEqual(orchestrator._closing_keyword_nums("Closes #900, #901"), {900, 901})
        self.assertEqual(orchestrator._closing_keyword_nums("Closes #900 and #901"), {900, 901})
        # a cross-repo ref closes that repo's issue, never a story here
        self.assertEqual(orchestrator._closing_keyword_nums("Fixes \nopa/other#900"), set())

    def test_adverb_between_negation_and_keyword_is_still_prose(self):
        """A 24-char window before the keyword is defeated by one adverb; negation
        is read across the clause instead (#1376 round-2 blocker)."""
        for body in ("This does not fully fix #900",
                     "This won't fully close #900",
                     "This is not enough to fix #900",
                     "it does not actually fix #900",
                     # negation by class, not by an enumerated list (#1376 round 3)
                     "I couldn't fix #900",
                     "We haven\u2019t fixed #900",
                     "This wasn't going to fix #900",
                     "We mustn't close #900",
                     "Failed to fix #900",
                     "Unable to close #900"):
            self.assertEqual(orchestrator._closing_keyword_nums(body), set(), body)
        self._pr("closed", "maint/unrelated", merged_at="2026-10-23T05:00:00Z",
                 body="Unrelated refactor. This does not fully fix #900; tracked separately.")
        self.assertIsNone(orchestrator._merged_pr_for_story(900))

    def test_negation_of_one_issue_does_not_swallow_another(self):
        """Clause-scoped negation must not discard a real delivery the #915 guard
        depends on (#1376 round-3 blocker)."""
        self.assertEqual(orchestrator._closing_keyword_nums(
            "This does not fix #900, but it fixes #901"), {901})
        self.assertEqual(orchestrator._closing_keyword_nums(
            "This is not related to #899, but fixes #900"), {900})
        self.assertEqual(orchestrator._closing_keyword_nums(
            "Does not fix #900; fixes #901"), {901})
        self._pr("closed", "maint/unrelated", merged_at="2026-10-23T05:00:00Z",
                 body="Does not fix #899; fixes #900")
        self.assertEqual(orchestrator._merged_pr_for_story(900), 914)

    def test_ref_list_needs_an_explicit_same_line_separator(self):
        """Whitespace alone must not extend the list: a bare `#N` on the next line is
        a different topic, and absorbing it retires a story the body says stays open
        (#1376 round-5 blocker)."""
        self.assertEqual(orchestrator._closing_keyword_nums(
            "Closes #900\n\n#901 is a follow-up, not closed here."), {900})
        self.assertEqual(orchestrator._closing_keyword_nums("Closes #900\n#901"), {900})
        self.assertEqual(orchestrator._closing_keyword_nums("Closes #900 #901"), {900})
        # explicit separators still collect the list
        for body in ("Closes #900, #901", "Closes #900; #901", "Closes #900 and #901",
                     "Closes #900, and #901"):
            self.assertEqual(orchestrator._closing_keyword_nums(body), {900, 901}, body)
        # and through the decision boundary: the follow-up is not delivered
        self._pr("closed", "maint/unrelated", merged_at="2026-10-23T05:00:00Z",
                 body="Implements the story.\n\nCloses #900\n\n#901 is a follow-up, untouched.")
        self.assertEqual(orchestrator._merged_pr_for_story(900), 914)
        self.assertIsNone(orchestrator._merged_pr_for_story(901))

    def test_open_owner_hand_delivery_blocks_planning(self):
        """The open arm must see the #915 shape too: a Delivery exists while its PR
        is open, so a fresh trigger must not plan it again (#1376 round-5 carried)."""
        self._pr("open", "maint/s2-grammar", body="Fixes #900")
        self.assertEqual(orchestrator._open_pr_for_story(900), 914)
        orchestrator.plan_loop_surrogate()
        self.assertNotIn(("04-plan.lock.yml", "issue", 900), self.fires)

    def test_merged_pr_without_base_is_not_a_delivery(self):
        """Missing `base` is missing evidence on the destructive path (#1376 nit)."""
        pr = self._pr("closed", "maint/unrelated", body="Fixes #900",
                      merged_at="2026-10-23T05:00:00Z")
        pr.pop("base")
        self.assertIsNone(orchestrator._merged_pr_for_story(900))

    def test_quoted_anchor_is_not_a_delivery(self):
        """A body that *describes* `Closes #900` is not one that uses it — this PR's
        own description is the live instance (#1376 round-4 blocker)."""
        for body in ("```\nCloses #900\n```",
                     "~~~\nCloses #900\n~~~",
                     "use `Closes #900` there",
                     "<!-- Closes #900 -->",
                     "| `Closes #900, #901` | recognised only 900 | both |"):
            self.assertEqual(orchestrator._closing_keyword_nums(body), set(), body)
        # a real closing line alongside a quoted one still counts
        self.assertEqual(orchestrator._closing_keyword_nums(
            "| `Closes #900` | quote |\n\nCloses #915\nCloses #1358"), {915, 1358})

    def test_contrastive_conjunction_scopes_the_negation(self):
        """`I can't reproduce it but this fixes #900` is positive (#1376 round-4 nit)."""
        self.assertEqual(orchestrator._closing_keyword_nums(
            "I can't reproduce it but this fixes #900"), {900})
        self.assertEqual(orchestrator._closing_keyword_nums(
            "Not the cause, however it fixes #901"), {901})

    def test_absurd_digit_run_does_not_raise(self):
        """An unbounded int() would degrade a whole orchestrator pass (#1376 nit)."""
        self.assertEqual(orchestrator._closing_keyword_nums("Fixes #" + "9" * 5000), set())

    def test_cross_repo_ref_is_not_a_local_delivery(self):
        """`Fixes opa/other#900` closes the *other* repo's #900 (#1376 round-2)."""
        self.assertEqual(orchestrator._closing_keyword_nums("Fixes opa/other#900"), set())
        self._pr("closed", "maint/unrelated", merged_at="2026-10-23T05:00:00Z",
                 body="Fixes upstream/other#900")
        self.assertIsNone(orchestrator._merged_pr_for_story(900))

    def test_closing_keyword_on_a_side_branch_is_not_a_delivery(self):
        """GitHub only auto-closes on the default branch, so a side-branch merge
        carrying the keyword leaves the story open — and re-firing is then correct."""
        self._pr("closed", "maint/side", body="Fixes #900",
                 merged_at="2026-10-23T05:00:00Z", base="release/1.x")
        self.assertIsNone(orchestrator._merged_pr_for_story(900))

    def test_closing_keyword_for_another_story_does_not_match(self):
        self._pr("closed", "maint/unrelated", body="Fixes #901", merged_at="2026-10-23T05:00:00Z")
        self.assertIsNone(orchestrator._merged_pr_for_story(900))

    # --- #1358: no plan round for a story already in delivery ------------
    def test_plan_round_not_fired_when_delivery_pr_is_open(self):
        self._pr("open", "devloop/issue-900-abc123")
        orchestrator.plan_loop_surrogate()
        self.assertNotIn(("04-plan.lock.yml", "issue", 900), self.fires,
                         "a fresh trigger must not plan a story whose delivery PR is open (#1358)")
        self.assertFalse([c for c in self.comments if "/plan fired" in c[1]],
                         "no plan-round marker either")

    def test_plan_round_not_fired_when_owner_delivery_already_merged(self):
        """The merged half of the guard is the #915 shape: delete it and this fails."""
        self._pr("closed", "maint/s2-grammar", body="Fixes #900",
                 merged_at="2026-10-23T05:00:00Z")
        orchestrator.plan_loop_surrogate()
        self.assertNotIn(("04-plan.lock.yml", "issue", 900), self.fires,
                         "a merged owner delivery must not be planned again")

    def test_plan_round_still_fires_without_any_delivery(self):
        orchestrator.plan_loop_surrogate()
        self.assertIn(("04-plan.lock.yml", "issue", 900), self.fires,
                      "an undelivered fresh trigger must still plan")


# PR #914 — the delivery #915 actually records, verbatim from the API: an OWNER
# hand-delivery on `maint/s2-grammar` that carries **no** closing keyword and no
# agent branch segment (`closingIssuesReferences: []`). Round 6's blocker was
# that fixtures were synthetic while this recorded shape stayed unrecognised.
PR_914 = {
    "number": 914,
    "title": "[impl] S2 (#898, spec #847): pin the machine-verdict producer grammar",
    "head": {"ref": "maint/s2-grammar"},
    "base": {"ref": "main"},
    "user": {"login": "RjBiermann"},
    "merged_at": "2026-10-23T04:43:44Z",
    "closingIssuesReferences": [],
    "body": (
        "## Provenance\n\n"
        "Hand-delivered (owner carve-out, maintenance round): S2's lane run failed "
        "**twice** with silent-empty agent exits (03:11Z, 04:18Z — `content: []`, zero "
        "safe-outputs; the failure issue is #904). Empty runs post no budget stamps, so "
        "the attempt ledger is blind to this loop — the ledger gap is filed separately "
        "(#913); this story's work lands now.\n\n"
        "## The change\n\n"
        "One paragraph in `05-implement.md` + recompiled lock: conditional fan-out "
        "stories post the machine-verdict anchor the S1 branch executor (#901, merged) "
        "parses:\n\n"
        "`## Probe verdict — <chain> (#<probe>): **<fixable|not fixable>**`\n\n"
        "## Refs\n\n"
        "#898 (this story) #847 (parent spec) #901 (S1 executor) "
        "#904 (failure issue — closes via this merge's closeout) #913 (ledger gap)"
    ),
}


class TestRecordedOwnerHandDelivery(ReplayCase):
    """#915's recorded delivery (PR #914) must be recognised at both states.

    The closing keyword covers PRs that say `Closes #N`; #914 says nothing of
    the sort. What it does carry is the lane's own announcement of the story it
    delivers — the parenthesised title anchor and the `#N (this story)` marker —
    and recognising *that* is what closes the #915 loop. Everything here is the
    recorded payload, not a synthetic shape."""

    def setUp(self):
        db = {n: {"number": n, "state": "open", "title": f"issue {n}",
                  "body": "", "labels": []} for n in (898, 913, 847, 901, 904)}
        db[898]["labels"] = [{"name": "ai-fix"}]
        self.world = {"owner": PR_914["user"]["login"], "issues": {str(k): v for k, v in db.items()},
                      "pulls": {"open": [], "closed": []}, "comments": {}}
        super().setUp(loader=lambda: self.world)
        self.fires = []
        self.comments = []
        def _api(path, *a, **k):
            if path.startswith("issues?state=open"):
                return [self.world["issues"][str(n)] for n in sorted(db)]
            return self._fake_api(path, *a, **k)

        self.patches += [
            mock.patch.object(orchestrator, "api", _api),
            # no network in a unit test: `parent_of`'s sub-issue fallback shells
            # out to `gh api graphql` per story per pass
            mock.patch.object(orchestrator, "parent_of", lambda *a, **k: None),
            mock.patch.object(orchestrator, "all_stories",
                              lambda *a, **k: [self.world["issues"][str(n)] for n in sorted(db)]),
            mock.patch.object(orchestrator, "comments", lambda n, *a, **k: []),
            mock.patch.object(orchestrator, "post_comment",
                              lambda n, b, *a, **k: self.comments.append((n, b))),
            mock.patch.object(orchestrator, "_fire_workflow",
                              lambda lock, t, n, *a, **k: self.fires.append((lock, t, n))),
        ]
        for p in self.patches[-6:]:
            p.start()
        self.addCleanup(mock.patch.stopall)

    def _record(self, board, **overrides):
        pr = copy.deepcopy(PR_914)
        pr.update(overrides)
        self.world["pulls"][board].append(pr)
        return pr

    def test_recorded_pr_914_is_story_898s_delivery(self):
        """The behaviour #915 records: #914 merged, #898 recognised, no re-fire."""
        self._record("closed")
        self.assertEqual(orchestrator._merged_pr_for_story(898), 914)

    def test_recorded_pr_914_open_is_story_898s_delivery(self):
        """Same recognition one state earlier: a Delivery exists while open."""
        self._record("open", merged_at=None)
        self.assertEqual(orchestrator._open_pr_for_story(898), 914)
        orchestrator.plan_loop_surrogate()
        self.assertNotIn(("04-plan.lock.yml", "issue", 898), self.fires,
                         "#914's shape must not be planned again (#1358 + #915)")

    def test_recorded_pr_914_refs_line_does_not_retire_its_other_refs(self):
        """The Refs line names five issues and delivers one. Only `(this story)`
        and the title anchor count — `#913 (ledger gap)` is explicitly not closed,
        and closeout_healer closes what this returns."""
        self._record("closed")
        for other in (913, 847):
            self.assertIsNone(orchestrator._merged_pr_for_story(other),
                              f"#914 does not deliver #{other}")

    def test_title_anchor_alone_recognises_the_story(self):
        """The `(issue #N, attempt M)` era of hand-delivery titles announces the
        story without any body marker — this arm is load-bearing on its own."""
        self._record("closed", title="[impl] Cat3Movie deep pass (issue #645, attempt 2)",
                     body="No story marker in this body.")
        self.assertEqual(orchestrator._merged_pr_for_story(645), 914)

    def test_body_story_marker_alone_recognises_the_story(self):
        """And the `#N (this story)` marker does the same for titles that carry no
        paren anchor at all."""
        self._record("closed", title="maintenance: fold the producer grammar in",
                     body="Refs\n\n#898 (this story) #847 (parent spec)")
        self.assertEqual(orchestrator._merged_pr_for_story(898), 914)

    def test_story_ref_after_the_impl_prefix_is_a_delivery(self):
        self._record("closed", title="[impl] S2: pin the producer grammar (#898)",
                     body="No story marker in this body.")
        self.assertEqual(orchestrator._merged_pr_for_story(898), 914)

    def test_a_repair_round_mention_in_a_title_is_not_a_delivery(self):
        """`(#1376 round-4 nit)` names the thing being repaired, so the paren
        anchor must be a closed reference list, not prose after `(#N`."""
        self._record("closed", title="fix(orchestrator): repair (#1376 round-4 nit)")
        self.assertIsNone(orchestrator._merged_pr_for_story(1376))

    def test_foreign_author_hand_delivery_is_not_a_delivery(self):
        """OWNER is the trust anchor (`_fixable_pr`'s); a fork PR shaped like
        #914 must not retire a story."""
        self._record("closed", user={"login": "someone-else"})
        self.assertIsNone(orchestrator._merged_pr_for_story(898))

    def test_hand_delivery_on_a_side_branch_is_not_a_delivery(self):
        self._record("closed", base={"ref": "release/1.x"})
        self.assertIsNone(orchestrator._merged_pr_for_story(898))

    def test_quoted_story_marker_is_not_a_delivery(self):
        """A doc PR *quoting* the marker delivers nothing (round-4 class)."""
        self._record("closed", title="docs: hand-delivery example",
                     body="```\n#898 (this story)\n```")
        self.assertIsNone(orchestrator._merged_pr_for_story(898))

    def test_prose_describing_the_story_marker_is_not_a_delivery(self):
        """Only fenced/inline quoting was stripped, so a *sentence* describing
        `#898 (this story)` still counted — the marker is a statement, and here it
        is the object of one (#1376 round-7). The recorded #914 Refs line starts
        with the marker (`#898 (this story) #847 …`), so statement position is
        what separates use from description."""
        self._record("closed", title="fix(orchestrator): recognise the marker",
                     body="PR #914 announced #898 (this story) so the guard now "
                          "recognises hand-deliveries.")
        self.assertIsNone(orchestrator._merged_pr_for_story(898))

    # --- round 7: the title arm must carry a *delivery* signal -----------
    def test_owner_title_that_merely_mentions_an_issue_is_not_a_delivery(self):
        """The live false positives: #1370 (`… (#1353)`) and #1374 (`… (#1373)`)
        are merged OWNER PRs that name an issue without delivering it, both with
        `closingIssuesReferences: []`. `closeout_healer` closes what this returns,
        so a bare mention on the destructive path is the round-1 over-match class
        again (#1376 round-7 blocker)."""
        self._record("closed", title="ci(emulator): gate non-http stream URLs; "
                                      "make the load-check lane actually run (#1353)",
                     body="Partially addresses the lane; the rest is follow-up.")
        self.assertIsNone(orchestrator._merged_pr_for_story(1353))
        self._record("closed", title="fix(load-check): keep dedup working after a "
                                      "human relabels a lane issue (#1373)")
        self.assertIsNone(orchestrator._merged_pr_for_story(1373))

    def test_owner_title_without_the_lane_delivery_prefix_is_not_a_delivery(self):
        """The reviewer's acceptance shape: not `[impl]`-family ⇒ no title anchor."""
        self._record("closed", title="maintenance: document the open question (#900)")
        self.assertIsNone(orchestrator._merged_pr_for_story(900))

    def test_revert_of_a_delivery_is_not_a_delivery(self):
        """`Revert "[impl] S2 (#898, spec #847) …"` names the reverted delivery, so
        reverting one must not count as delivering the story again (#1376 round-7)."""
        self._record("closed", title='Revert "[impl] S2 (#898, spec #847): pin the '
                                      'machine-verdict producer grammar" (#914)',
                     body="This reverts commit abc1234.")
        self.assertIsNone(orchestrator._merged_pr_for_story(898))


class TestUntrustedBodyCost(unittest.TestCase):
    """`_closing_keyword_nums` runs on PR bodies, and since round 5 that
    includes *open* PRs from forks — so an adversarial 65 KB body must stay
    cheap (the security scan measured 20-50 s at `67e46d2`)."""

    def _elapsed(self, body):
        start = time.time()
        orchestrator._closing_keyword_nums(body)
        return time.time() - start

    def test_pathological_bodies_stay_linear(self):
        cap = 65536
        for body in ("Fixes #1 " * (cap // 9),
                     "Fixes " + " " * cap,
                     "Fixes #900" + " " * cap,
                     "<!-- " * (cap // 5),
                     "```\n" * (cap // 4),
                     "Closes #1," * (cap // 10)):
            self.assertLess(self._elapsed(body), 2.0,
                            f"{len(body)}-byte body took too long")

    def test_realistic_body_still_parses(self):
        self.assertEqual(orchestrator._closing_keyword_nums(
            "## Notes\n\nDoes not fix #900; fixes #901.\n\nCloses #902\n"), {901, 902})

    # --- round 7 nit: a pass multiplies bodies × stories ------------------
    def _prs(self, body, n=50):
        return [{"number": 1000 + i, "head": {"ref": f"patch-{i}"},
                 "base": {"ref": "main"}, "user": {"login": "drive-by"},
                 "title": "drive-by", "body": body} for i in range(n)]

    def test_drive_by_bodies_that_never_mention_the_story_cost_nothing(self):
        """50 open fork PRs (the `per_page=50` ceiling) with 70 KB adversarial
        bodies that name no #900 must not cost a parse each: `_open_pr_for_story`
        is called several times per story per pass, and the pass is
        `cancel-in-progress` under a 10-minute window. Bodies are distinct, so
        the per-body memo cannot be what makes this cheap."""
        prs = self._prs("Fixes #1 " * 7000)
        for i, pr in enumerate(prs):
            pr["body"] += "x" * i
        with mock.patch.object(orchestrator, "open_pulls", lambda: prs):
            start = time.time()
            self.assertIsNone(orchestrator._open_pr_for_story(900))
            elapsed = time.time() - start
        self.assertLess(elapsed, 1.0, f"50 non-matching bodies took {elapsed:.1f}s")

    def test_a_body_is_parsed_once_per_pass_not_once_per_call_site(self):
        """An adversarial body that *does* name the story is parsed once and the
        result reused, so the multiplication is by body, not by call site."""
        slow = "Fixes #1 " * 5000 + "\nsee #900\n"
        prs = self._prs(slow)
        with mock.patch.object(orchestrator, "open_pulls", lambda: prs):
            start = time.time()
            for _ in range(3):  # three call sites in one pass
                self.assertIsNone(orchestrator._open_pr_for_story(900))
            elapsed = time.time() - start
        self.assertLess(elapsed, 3.0, f"50 PRs × 3 call sites took {elapsed:.1f}s")


if __name__ == "__main__":
    unittest.main()
