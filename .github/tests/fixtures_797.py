#!/usr/bin/env python3
"""Recorded fixture data for the #797 bug class (spec #889, parent #797).

The bug class: post-merge story issues re-stamped `status/planned` by the
machinery re-fire duplicate implement passes — the run detects the merged
state and skips, but the paid run and the racing duplicate PR are already
burned (run 39368254975 vs merged #792).

Everything below is the recorded 2026-10-20 label-event history from
backlog-report #797, with three provenance classes marked per item:

- **recorded** — number, timestamp, event or merge straight from the
  #797 evidence (minute precision as reported).
- **reconstructed-within-record** — sub-minute seconds inside a recorded
  minute (the 05:08–05:11Z non-atomic label add/remove batch wave: #797
  records the wave shape — "same-issue add/remove pairs within seconds" —
  not every per-label second).
- **standin** — the recorded evidence names the PR number but not its
  head branch (or names a chain member without a nubmered delivery);
  the ref string is a shaped stand-in, the merged/unmerged state is the
  recorded one.
- **synthetic** — not in the record at all: a positive/negative control
  that encodes one side of the invariant class the replay asserts.
  #990 (owner re-arm after merge) and #992 (non-owner human re-arm) are
  the only synthetic issues; #797 itself (the report issue, still open,
  never delivered) is carried for the must-not-touch case.

Actor attributions follow the #797 analysis: the re-stamp waves are the
machinery's (github-actions[bot]); the OWNER appears in the record only
as #767's reopener. #797 never recorded an owner-applied post-merge
`status/planned` — hence the synthetic re-arm controls.
"""

OWNER = "RjBiermann"          # GH_AW_ORCH_OWNER in production
BOT = "github-actions[bot]"   # the machinery identity (ADR-0019)
OUTSIDER = "unaffiliated-human"  # synthetic: neither owner nor machinery

def _labeled(ts, label, actor=BOT):
    return {"event": "labeled", "label": {"name": label},
            "actor": {"login": actor}, "created_at": ts}


def _unlabeled(ts, label, actor=BOT):
    return {"event": "unlabeled", "label": {"name": label},
            "actor": {"login": actor}, "created_at": ts}


def _system(event, ts, actor):
    return {"event": event, "actor": {"login": actor}, "created_at": ts}


# --------------------------------------------------------------------------
# Delivery PRs. `pulls?state=closed` shape (only fields the orchestrator
# reads: number, merged_at, head.ref).
# --------------------------------------------------------------------------
CLOSED_PULLS = [
    # recorded: PR #792 merged 04:35Z (squash commit 864a19e) — delivered #771
    {"number": 792, "state": "closed", "merged_at": "2026-10-20T04:35:00Z",
     "head": {"ref": "devloop/issue-771-standin"}},   # ref: standin
    # recorded: PR #791 merged 04:32Z (`devloop/issue-768-849af...`) — delivered #768
    {"number": 791, "state": "closed", "merged_at": "2026-10-20T04:32:00Z",
     "head": {"ref": "devloop/issue-768-849af"}},     # ref: recorded shape
    # standin: #797 records "#767 closed at merge 04:11Z per ADR-0007" — i.e.
    # some delivery PR merged 04:11Z — without naming the number. #789 is a
    # stand-in number/ref for that recorded merge.
    {"number": 789, "state": "closed", "merged_at": "2026-10-20T04:11:00Z",
     "head": {"ref": "devloop/issue-767-standin"}},
    # recorded: racing duplicate #787 closed unmerged 05:08Z (S1 #767)
    {"number": 787, "state": "closed", "merged_at": None,
     "head": {"ref": "devloop/issue-767-standin2"}},  # ref: standin
    # recorded: unmerged sister duplicates #786/#784 (S1 of the #680 chain).
    # The chain's S1 story number is not in the record; the refs are
    # stand-ins that deliberately collide with NO replayed story number.
    {"number": 786, "state": "closed", "merged_at": None,
     "head": {"ref": "devloop/issue-774-standin"}},   # ref: standin
    {"number": 784, "state": "closed", "merged_at": None,
     "head": {"ref": "fix/774-standin"}},             # ref: standin
    # synthetic control: merged delivery for #990 (owner re-arm positive case)
    {"number": 991, "state": "closed", "merged_at": "2026-10-20T04:35:00Z",
     "head": {"ref": "devloop/issue-990-standin"}},
    # synthetic control: merged delivery for #992 (outsider re-arm negative case)
    {"number": 993, "state": "closed", "merged_at": "2026-10-20T04:35:00Z",
     "head": {"ref": "devloop/issue-992-standin"}},
]

# Recorded end state of the 05:11Z wave: every delivery PR merged or closed;
# no open agent PRs (the healer's open-PR guard sees nothing).
OPEN_PULLS = []

# --------------------------------------------------------------------------
# Open issues at replay time. Per #797: "the story issues themselves were
# left open" (+ the explicit human reopen of #767).
# --------------------------------------------------------------------------
OPEN_ISSUES = [
    {"number": 767, "state": "open", "title": "[spec] S1 — parent #759",
     "labels": [{"name": "triage/from-agent"}, {"name": "status/in-review"}]},
    {"number": 768, "state": "open", "title": "[spec] S2/S1 — parent #759",
     "labels": [{"name": "status/planned"}, {"name": "status/in-review"}]},
    {"number": 771, "state": "open", "title": "[spec] S2 — parent #664",
     "labels": [{"name": "status/planned"}, {"name": "status/in-review"}]},
    {"number": 775, "state": "open", "title": "[spec] S2 — parent #680",
     "labels": [{"name": "status/planned"}]},
    # recorded: the report issue itself — open, never delivered, must never
    # be touched by the healer (it has no delivery PR of its own).
    {"number": 797, "state": "open", "title": "[backlog-report] [aw] post-merge story issues re-stamped status/planned ...",
     "labels": [{"name": "triage/from-agent"}]},
    # synthetic control: delivered + owner re-arm — must stay open
    {"number": 990, "state": "open", "title": "[spec] S2 — owner re-arm control (synthetic)",
     "labels": [{"name": "status/planned"}]},
    # synthetic control: delivered + outsider re-arm — must close out
    {"number": 992, "state": "open", "title": "[spec] S2 — outsider re-arm control (synthetic)",
     "labels": [{"name": "status/planned"}]},
]

# --------------------------------------------------------------------------
# Label-event histories (per issue, chronological). Only status/* events
# plus the recorded close/reopen lifecycle of #767 are carried.
# --------------------------------------------------------------------------
LABEL_EVENTS = {
    # #771 (S2 of #664): planned re-stamps 03:54 / 04:11 / 04:26 / 05:08Z —
    # the 05:08 stamp (post-merge: #792 merged 04:35Z) re-fired duplicate
    # implement run 39368254975. All machinery-authored.
    771: [
        _labeled("2026-10-20T03:54:00Z", "status/planned"),
        _labeled("2026-10-20T04:11:00Z", "status/planned"),
        _labeled("2026-10-20T04:26:00Z", "status/planned"),
        # recorded 05:08Z — the re-stamp that drove the bug class
        _labeled("2026-10-20T05:08:00Z", "status/planned"),
    ],
    # #768 (S2/S1 of #759): planned re-stamps 03:54 / 04:11 / 04:26Z;
    # in-review transitions 04:02 and 05:10Z; #791 merged 04:32Z. The
    # 05:08–05:11Z non-atomic batch wave rides on this issue: add/remove
    # pairs within seconds (recorded shape; per-label seconds are
    # reconstructed-within-record).
    768: [
        _labeled("2026-10-20T03:54:00Z", "status/planned"),
        _labeled("2026-10-20T04:02:00Z", "status/in-review"),
        _labeled("2026-10-20T04:11:00Z", "status/planned"),
        _labeled("2026-10-20T04:26:00Z", "status/planned"),
        _labeled("2026-10-20T05:08:00Z", "status/planned"),   # wave
        _unlabeled("2026-10-20T05:08:30Z", "status/planned"),  # wave (reconstructed-within-record)
        _labeled("2026-10-20T05:09:00Z", "status/planned"),    # wave (reconstructed-within-record)
        _labeled("2026-10-20T05:10:00Z", "status/in-review"),  # recorded 05:10Z
        _unlabeled("2026-10-20T05:10:30Z", "status/planned"),  # wave (reconstructed-within-record)
        _labeled("2026-10-20T05:11:00Z", "status/planned"),    # wave (reconstructed-within-record)
    ],
    # #767 (S1 of #759): closed at merge 04:11Z per ADR-0007; human reopen
    # 05:08Z (maintainer decision, respected); machinery re-stamp 05:09Z +
    # label batch 05:11–05:15Z; racing duplicate #787 closed unmerged 05:08Z.
    767: [
        _system("closed", "2026-10-20T04:11:00Z", BOT),          # recorded ADR-0007 closeout
        _system("reopened", "2026-10-20T05:08:00Z", OWNER),      # recorded human reopen
        _labeled("2026-10-20T05:09:00Z", "status/planned"),      # recorded stamp, machinery-attributed
        _labeled("2026-10-20T05:11:00Z", "triage/from-agent"),   # recorded
        _labeled("2026-10-20T05:15:00Z", "status/in-review"),    # recorded
    ],
    # #775 (S2 of #680 chain): planned re-added four times in ~75 min
    # (03:54 / 04:11 / 04:26 / 05:08Z), no other transitions, no delivery
    # PR in the record. Non-atomic add/remove pair rides here too.
    775: [
        _labeled("2026-10-20T03:54:00Z", "status/planned"),
        _labeled("2026-10-20T04:11:00Z", "status/planned"),
        _labeled("2026-10-20T04:26:00Z", "status/planned"),
        _labeled("2026-10-20T05:08:00Z", "status/planned"),   # wave
        _unlabeled("2026-10-20T05:09:00Z", "status/planned"),  # wave (reconstructed-within-record)
        _labeled("2026-10-20T05:11:00Z", "status/planned"),    # wave (reconstructed-within-record)
    ],
    # synthetic control #990: machinery stamp pre-merge, then the ONLY
    # shape that counts as a deliberate re-arm — the OWNER applies
    # status/planned after the delivery merged.
    990: [
        _labeled("2026-10-20T03:54:00Z", "status/planned"),
        _labeled("2026-10-20T05:20:00Z", "status/planned", actor=OWNER),  # synthetic: deliberate re-arm
    ],
    # synthetic control #992: same shape, but the post-merge stamp comes
    # from an unaffiliated human login — fails closed, never a re-arm.
    992: [
        _labeled("2026-10-20T03:54:00Z", "status/planned"),
        _labeled("2026-10-20T05:20:00Z", "status/planned", actor=OUTSIDER),  # synthetic: not a re-arm
    ],
}


def derived_events(num, **swap):
    """A fresh deep copy of issue `num`'s event list with `swap` applied
    to the LAST status/planned *labeled* event (dict fields, e.g.
    actor={"login": ...}). Lets a test re-shape the recorded latest stamp
    without mutating the shared fixture data."""
    import copy
    evs = copy.deepcopy(LABEL_EVENTS[num])
    for e in reversed(evs):
        if e.get("event") == "labeled" and (e.get("label") or {}).get("name") == "status/planned":
            e.update(swap)
            break
    return evs
