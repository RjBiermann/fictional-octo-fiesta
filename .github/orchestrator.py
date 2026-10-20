#!/usr/bin/env python3
"""Mechanical conveyor for the gh-aw pipeline (ADR-0017/0018/0019).

Non-AI, fails closed. Automates the mechanics of an already-approved chain
and surfaces every real decision as an @mention. It never decides a round,
never merges a PR, never touches ai-* trigger labels, and closes [aw]
diagnostics only when a later Implement/Plan run has succeeded.
Machinery acts as github-actions[bot] only (ADR-0019): relays fire by
workflow_dispatch; GH_AW_PROXY_PAT is retired (set, it's a legacy
identity override; unset, everything works under GITHUB_TOKEN).
  - merge readiness: default contract is HUMAN MERGES — label
    `status/ready-to-merge` + ping the owner with the head sha. If the
    adopter sets GH_AW_AUTO_MERGE=true, queue auto-merge instead
    (branch protection remains the real gate either way). Owner
    decision 2026-10: keep it FALSE — if ever enabled, harden the
    gate first: the approval receipt may predate the PR, so it proves
    the chain was approved, not that this diff was reviewed.
  - chain driver: stamp `status/planned` on story N when story N-1's
    sub-issue closed; stamp story 1 from the chain's round-3 approval;
    close the parent spec issue once every story is closed.
  - plan-loop surrogate: fire `/plan` mechanically across rounds
    (fresh ai-* -> round 1; human answers after round 1 -> round 2;
    owner 'approved' after round 2 -> round 3). Each firing carries a
    per-round dedupe marker so it never loops.
  - decision tags: when a round is waiting on the human, one
    "@owner decision needed" note per round (deduped).
  - watchdog: close transient [aw] diagnostics after a LATER successful
    Implement/Plan run exists.
"""

import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime

REPO = os.environ.get("GITHUB_REPOSITORY", "")
PROXY_PAT = os.environ.get("GH_AW_PROXY_PAT", "")
AUTO_MERGE = os.environ.get("GH_AW_AUTO_MERGE", "false") == "true"
FIRE_PLAN = os.environ.get("GH_AW_ORCH_PLAN", "true") == "true"
TAG_OWNER = os.environ.get("GH_AW_ORCH_TAG_OWNER", "true") == "true"
# REPO-AGNOSTIC contract: every adopter-specific value comes from config.
# NOTHING here names an adopter. OWNER is required for any owner-actor
# mutation; when unset, mutation steps degrade to read-only (prints
# advice once). Conflict classes auto-resolve only for paths configured
# via GH_AW_REGISTRY_PATH (a per-adopter append-only JSON registry) —
# unset means the feature is off for that adopter.
OWNER = os.environ.get("GH_AW_ORCH_OWNER", "")

MARK = "[orchestrator]"
REGISTRY_PATH = os.environ.get("GH_AW_REGISTRY_PATH", "")
# Adopters whose conflict surface is exactly an append-only JSON registry
# get the deterministic auto-merge; anything else stays human.
FINDINGS_PATH = REGISTRY_PATH if REGISTRY_PATH.endswith(".json") else None
REGISTRY_VERSION_SUFFIXES = tuple(os.environ.get(
    "GH_AW_REGISTRY_VERSION_FILES", "build.gradle.kts").split(",")) if os.environ.get(
    "GH_AW_REGISTRY_VERSION_FILES") else ("build.gradle.kts",)


def _pl(args, env=None):
    """Run a git plumbing command, return (rc, stdout). Non-trivial rc
    (clean=0, conflict=1) logs stderr — the round-3 hardening: without it
    rc=128 in the runner was indistinguishable from "fetched nothing"."""
    r = subprocess.run(args, capture_output=True, text=True,
                       env=(dict(os.environ, **env) if env else None))
    if r.returncode not in (0, 1):
        sys.stderr.write(f"[orchestrator] git rc={r.returncode} on {' '.join(args[:4])}: "
                         f"{r.stderr.strip()[:220]}\n")
    return r.returncode, r.stdout.strip()


def _git_push(branch, commit):
    """Push via the checked-out origin (checkout embeds the actions token);
    requires 95-orchestrator.yml `contents: write`. Falls back to gh-based
    ref update carrying the PROXY_PAT explicitly."""
    rc, out = _pl(["git", "push", "origin", f"{commit}:refs/heads/{branch}"])
    return rc == 0, out


def _push_ref_via_api(branch, commit):
    """Update refs/heads/<branch> to <commit> through the REST API with the
    owner-actor PAT (PATCH /repos/.../git/refs/heads/<branch>). Success is
    the returned ref JSON naming our commit."""
    out = run(["gh", "api", "-X", "PATCH", f"repos/{REPO}/git/refs/heads/{branch}",
               "-f", f"sha={commit}"])
    try:
        return commit in (json.loads(out).get("object") or {}).get("sha", "")
    except Exception:
        return False


def _union_findings(a, b):
    """Union-merge two findings.json documents (delta 16-f6b).
    Recursively: list fields (top-level last_runs AND nested entries)
    union by JSON-canonical identity — run records are append-only, so
    b's entries are appended, never dropped. Dicts deep-merge, b wins
    scalars. Returns a NEW document; inputs untouched."""
    out = json.loads(json.dumps(a))
    for k, v in b.items():
        cur = out.get(k)
        if isinstance(v, list):
            lst = list(cur) if isinstance(cur, list) else []
            seen = {json.dumps(x, sort_keys=True) for x in lst}
            out[k] = lst + [it for it in v
                            if json.dumps(it, sort_keys=True) not in seen]
        elif isinstance(v, dict) and isinstance(cur, dict):
            out[k] = _union_findings(cur, v)
        else:
            out[k] = v
    return out


def _fire_marker(rnd):
    """Body of a surrogate fire. MUST begin with /plan — gh-aw's
    slash-command parser only recognizes bodies that start with the
    command; the [orchestrator] marker trails for dedupe."""
    return f"/plan\n\n{MARK} /plan fired round {rnd}"
STORY_RE = re.compile(r"\[spec\].*?\b(?:Story\s+|S)(\d+)\b", re.I)
PARENT_RE = re.compile(r"approved\s+#(\d+)\s+spec", re.I)
EPIC_KID_RE = re.compile(r"epic #(\d+)", re.I)
ROUND_ANCH = {r: f"Plan round {r} —" for r in (1, 2, 3)}
IN_FLIGHT = ("status/planned", "status/in-review", "status/fixing",
             "status/fixing-stuck", "status/queued")

APPROVE_ALLOWLIST = [
    "99-ci", "Lint", "CodeQL Advanced", "CodeQL",
    "Security scan (08+09 merged)",
    "Automatic Dependency Submission (Gradle)",
]

# gh-aw's own pipeline runs on bot PR branches also land in
# `action_required` (pre_activation sees github-actions[bot] as
# permission:none). These are agent work already gated by trigger labels —
# safe to approve mechanically. Review is included per owner decision
# 2026-10-16: it is findings-only (never merges), so auto-running it just
# surfaces findings sooner; the human still reads them and merges.
AGENT_RUN_ALLOWLIST = {"Implement", "Plan",
                       "Review (findings only; ADR-0001 semantics preserved)"}


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        sys.stderr.write(f"[orchestrator] rc={r.returncode} on {' '.join(cmd[:5])}: {r.stderr.strip()[:300]}\n")
    return r.stdout


def api(path):
    out = run(["gh", "api", f"repos/{REPO}/{path}"])
    if not out.strip():
        return None
    return json.loads(out)


def comments(num):
    return api(f"issues/{num}/comments?per_page=100") or []


def post_comment(num, body):
    run(["gh", "api", "-X", "POST", f"repos/{REPO}/issues/{num}/comments", "-f", f"body={body}"])


def label_issue(num, name):
    run(["gh", "issue", "edit", str(num), "--add-label", name])


def close_issue(num, reason, body):
    run(["gh", "issue", "close", str(num), "--reason", reason, "-c", body])


def open_pulls():
    return api("pulls?state=open&per_page=50") or []


def story_num(title):
    m = STORY_RE.search(title or "")
    return int(m.group(1)) if m else None


def all_stories():
    out = run(["gh", "issue", "list", "--state", "all", "--limit", "100",
               "--json", "number,title,state,body,labels"])
    if not out.strip():
        return []
    raw = json.loads(out)
    # gh issue list returns state UPPERCASE (REST api() returns lowercase);
    # normalize here so every state comparison downstream is one convention
    for i in raw:
        i["state"] = str(i.get("state", "")).lower()
    return [i for i in raw if i.get("pull_request") is None and story_num(i.get("title", ""))]


def parent_of(story):
    body = story.get("body") or ""
    # 04-plan writes at least three link shapes across eras:
    # "approved #759 spec" (old waves), "parent #759, spec finalized…"
    # (Porntrex-style), "Parent: #664 (spec finalized…)" (spec-loop kids).
    for rx in (PARENT_RE, EPIC_KID_RE, re.compile(r"[Pp]arent:?\s+#(\d+)")):
        m = rx.search(body)
        if m:
            return int(m.group(1))
    return None


def _round_comment(cs, rnd):
    hit = None
    for c in cs or []:
        if ROUND_ANCH[rnd] in (c.get("body") or ""):
            hit = c
    return hit


def _has_marker(cs, text):
    return any(text in (c.get("body") or "") for c in cs or [])


# --------------------------------------------------------------- approvals
def _owner_configured():
    """repo-agnostic gate: owner-actor mutations need the adopter identified."""
    if not OWNER:
        print("[orchestrator] GH_AW_ORCH_OWNER unset — owner-actor mutations disabled (repo-agnostic default)")
        return False
    return True


def approve_companion_runs():
    if not PROXY_PAT:
        print("[orchestrator] GH_AW_PROXY_PAT unset — approvals stay manual")
        return
    for pr in open_pulls():
        branch = pr.get("head", {}).get("ref", "")
        if not branch.startswith("devloop/"):
            continue
        runs = run(["gh", "run", "list", "--branch", branch, "--limit", "40",
                    "--json", "databaseId,workflowName,conclusion",
                    "--jq", '.[] | select(.conclusion=="action_required") | "\(.databaseId)|\(.workflowName)"'])
        for line in runs.splitlines():
            if not line.strip():
                continue
            rid, _, wname = line.partition("|")
            if wname in APPROVE_ALLOWLIST or (wname in AGENT_RUN_ALLOWLIST
                                              and branch.startswith("devloop/issue-")):
                out = run(["curl", "-s", "-X", "POST",
                           "-H", f"Authorization: Bearer {PROXY_PAT}",
                           "-H", "Accept: application/vnd.github+json",
                           f"https://api.github.com/repos/{REPO}/actions/runs/{rid}/approve"])
                print(f"[orchestrator] approve {rid} ({wname}): {out[:90]}")
            else:
                print(f"[orchestrator] run {rid} ({wname}) outside allowlist — left for human")


# ---------------------------------------------------------- merge readiness
def _effective_ready(num, mstate):
    """A PR is effectively ready when every check finished green/skipped.
    Bot PRs sit `blocked`/`unstable` forever (skipped gh-aw head-sha runs,
    missing human approval), so `clean` alone never fires the readiness
    ping. Treat blocked/unstable as ready iff no check is pending or
    failed — the only thing left blocking is then the human approval."""
    if mstate == "clean":
        return True
    if mstate not in ("blocked", "unstable"):
        return False
    d = api(f"pulls/{num}") or {}
    sha = ((d.get("head") or {}).get("sha") or "")
    if not sha:
        return False
    cr = api(f"commits/{sha}/check-runs?per_page=100") or {}
    bad = ("failure", "timed_out", "cancelled", "action_required", "stale")
    for c in (cr.get("check_runs") or []):
        if c.get("status") != "COMPLETED":
            return False
        if (c.get("conclusion") or "").lower() in bad:
            return False
    return True


def merge_readiness():
    for pr in open_pulls():
        num = pr["number"]
        branch = pr.get("head", {}).get("ref", "")
        if pr.get("draft") or not branch.startswith("devloop/issue-"):
            continue
        d = api(f"pulls/{num}") or {}
        mstate = d.get("mergeable_state")
        if not _effective_ready(num, mstate):
            print(f"[orchestrator] PR #{num}: mergeable_state={mstate} — not ready")
            continue
        if AUTO_MERGE:
            # ADR-0017 gate decision (post-merge review): auto-merge may only
            # act on chains whose round-3 approval comment exists on the
            # parent spec issue (parsing "[spec] Story" detour not needed —
            # the gate is evidence-level: the owner decision comment must
            # exist on the PR's own thread or the parent found from the body).
            gate_ok = False
            body = (api(f"pulls/{num}") or {}).get("body") or ""
            import re as _re
            mm = _re.search(r"approved\s+#(\d+)\s+spec", body) or _re.search(r"Closes\s+#(\d+)", body)
            if mm:
                parent_n = mm.group(1)
                gate_ok = any(
                    (c.get("user", {}).get("login") == OWNER
                     and (c.get("body") or "").strip().lower() in ("approved", "approve", "lgtm", "yes", "ok"))
                    for c in (api(f"issues/{parent_n}/comments") or []))
            if not gate_ok:
                print(f"[orchestrator] PR #{num}: auto-merge gate requires the chain's owner approval — falling back to readiness ping")
            else:
                run(["gh", "pr", "merge", str(num), "--squash", "--auto"])
                print(f"[orchestrator] PR #{num}: auto-merge queued (spec approved)")
                continue
        sha = ((d.get("head") or {}).get("sha") or "")[:12]
        marker = f"ready @{OWNER} — all checks green at `{sha}`"
        if _has_marker(comments(num), marker):
            continue
        label_issue(num, "status/ready-to-merge")
        post_comment(num, f"{MARK} ready @{OWNER} — all checks green at `{sha}`; please review and merge when ready.")
        print(f"[orchestrator] PR #{num}: tagged {OWNER} for merge")


# ------------------------------------------------------------- chain driver
def chain_driver():
    stories = all_stories()
    for s in stories:
        if s["state"] != "open":
            continue
        num_story = story_num(s["title"])
        labels = [l["name"] for l in s.get("labels", [])]
        if "status/planned" in labels:
            continue
        if num_story == 1:
            pnum = parent_of(s)
            approved = pnum and _chain_approved(pnum)
            if approved:
                _fire_implement(s["number"])
                print(f"[orchestrator] story 1 (issue {s['number']}): trigger stamped (chain approved at round 3)")
            else:
                print(f"[orchestrator] story 1 (issue {s['number']}): no round-3 approval recorded — waiting on human")
            continue
        pstate = _story_state(num_story - 1, stories)
        if str(pstate).lower() == "closed":
            _fire_implement(s["number"])
            print(f"[orchestrator] story {num_story} (issue {s['number']}): trigger stamped (story {num_story-1} closed)")
        else:
            print(f"[orchestrator] story {num_story} (issue {s['number']}): predecessor still {pstate}")

    _demote_out_of_order(stories)
    _reconcile_pass(stories, _claim_planned_fires(stories))

    # Parent spec issue: close once every story referencing it is closed.
    by_parent = {}

def _parse_ts(s):
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()
    except Exception:
        return 0.0


def _implement_fires():
    """Implement runs triggered by issue events (label fires), newest first.
    Each row: {id, status, createdAt} — id lets the conveyor cancel a run
    it demotes (out-of-order mass stamps), status filters terminal ones."""
    out = run(["gh", "run", "list", "--workflow", "Implement", "--limit", "60",
               "--json", "databaseId,status,createdAt,event"])
    try:
        rows = json.loads(out or "[]")
    except Exception:
        return []
    return sorted((r for r in rows if r.get("event") == "issues"),
                  key=lambda r: r.get("createdAt") or "", reverse=True)


def _claim_planned_fires(stories):
    """Map open status/planned issues to the Implement run their label event
    fired. gh-aw run payloads carry no issue attribution, so claim by
    proximity: a run within 150s after the stamp claims at most one issue
    (greedy nearest match). Returns {issue_number: fired}.
    (This replaces the old reconcile check against the workflow's global
    latest run, which any sibling issue's run suppressed.)"""
    runs = _implement_fires()
    taken = set()
    claims = {}
    for s in stories:
        if str(s.get("state", "")).lower() != "open":
            continue
        if "status/planned" not in [l["name"] for l in s.get("labels", [])]:
            continue
        ts = _label_applied_at(s["number"], "status/planned")
        if not ts:
            continue
        best = None
        for i, rt in enumerate(runs):
            if i in taken or not rt.get("createdAt"):
                continue
            delta = _parse_ts(rt["createdAt"]) - _parse_ts(ts)
            if 0 <= delta <= 150 and (best is None or delta < best[1]):
                best = (i, delta)
        if best is not None:
            taken.add(best[0])
        claims[s["number"]] = best is not None
    return claims


def _has_stall_flag(cs):
    today = time.strftime("%Y-%m-%d", time.gmtime())
    return any(MARK in (c.get("body") or "") and "stalled" in c.get("body", "")
               and (c.get("created_at") or "").startswith(today) for c in cs)


def _reconcile_pass(stories, claims):
    # Reconcile: stamps whose Implement-side fire was role-gated away
    # (old bot-applied labels) get re-fired once under the owner actor.
    # Skips issues already flagged stalled today — re-firing into an AIC
    # guardrail just spams label events.
    for s in stories:
        n = s["number"]
        if str(s.get("state", "")).lower() != "open" or claims.get(n, True):
            continue
        ts = _label_applied_at(n, "status/planned")
        if not ts or time.time() - _parse_ts(ts) < 600:
            continue
        if _has_stall_flag(comments(n)):
            continue
        run(["gh", "api", "-X", "DELETE", f"repos/{REPO}/issues/{n}/labels/status%2Fplanned"])
        _fire_implement(n)
        print(f"[orchestrator] {n}: trigger re-fired (machine relay — stamp fired no run)")


def _chain_approved(pnum):
    """Approval-of-record, two receipts (ADR-0014):
    - exact one-word owner verdict (approved/approve/lgtm/yes/ok), or
    - the plan loop's round-3 marker (ROUND_ANCH[3]) — 04-plan posts it
      only after a human wrote `approved` in the thread, i.e. the
      machine-checkable receipt of the same human decision. The marker is
      authored by the workflow identity (safe-output add-comment posts as
      github-actions[bot], observed live 2026-10-18), NOT by OWNER — so
      the marker is accepted from either OWNER or the known machinery
      identity; the one-worder stays owner-only. Prose is never parsed
      (no fuzzy 'approved' inside sentences); the plan workflow dedupes
      its own markers, so re-reading is idempotent.
    (Review #760 blocker 4317230076: the first cut required owner-authored
    markers, which made this receipt unreachable on live data.)"""
    for c in comments(pnum):
        login = (c.get("user") or {}).get("login") or ""
        body = c.get("body") or ""
        if login == OWNER and body.strip().lower() in ("approved", "approve", "lgtm", "yes", "ok"):
            return True
        if login in (OWNER, "github-actions[bot]") and ROUND_ANCH[3] in body:
            return True
    return False


def _label_applied_at(num, name):
    evs = api(f"issues/{num}/events?per_page=100") or []
    if not isinstance(evs, list):
        return None
    ts = None
    for ev in evs:
        if ev.get("event") == "labeled" and (ev.get("label") or {}).get("name") == name:
            ts = ev.get("created_at") or ts
    return ts


def _latest_workflow_run_at(name):
    try:
        out = run(["gh", "run", "list", "--workflow", name, "--limit", "5",
                   "--json", "createdAt,event"])
        rows = json.loads(out or "[]")
    except Exception:
        return None
    hits = [r["createdAt"] for r in rows if r.get("event") in ("issues", "issue_comment")]
    return max(hits) if hits else None

def _story_state(num, stories):
    for s in stories:
        if story_num(s.get("title", "")) == num:
            return s.get("state", "?")
    return "-"


# ------------------------------------------------- event relay (ADR-0019)
def _fire_workflow(lock, item_type, item_number):
    """Machine-identity event relay: fire a gh-aw workflow via
    workflow_dispatch — GitHub's documented GITHUB_TOKEN loop-prevention
    exception (dispatch/repository_dispatch always create runs). The
    gh-aw-internal `aw_context` shape restores the target item natively
    (compiled ctx: item_type ∈ {issue, pull_request, discussion}). Replaces
    the ADR-0017 owner-PAT comment/label relays: the machinery never acts
    as the owner and needs no PAT secret."""
    ctx = json.dumps({"item_type": item_type, "item_number": item_number})
    out = run(["gh", "workflow", "run", lock, "--ref", "main", "-R", REPO,
               "-f", f"aw_context={ctx}"])
    if out.strip():
        print(f"[orchestrator] {lock} dispatched for {item_type} #{item_number} ({out[:90]})")
    else:
        print(f"[orchestrator] {lock} dispatched for {item_type} #{item_number}")


def _fire_implement(num):
    """Stamp status/planned (display state) + fire Implement by dispatch —
    a bot-applied label event is loop-dead, so the dispatch IS the fire."""
    label_issue(num, "status/planned")
    _fire_workflow("05-implement.lock.yml", "issue", num)


def _fire_plan(n, rnd):
    """Record the round marker (dedupe + audit trail; bot identity) and
    fire the plan loop by dispatch with the round carried by aw_context."""
    mk = _fire_marker(rnd)
    if _has_marker(comments(n), mk):
        return False
    post_comment(n, mk + _FIRE_SUFFIX.get(rnd, ""))
    _fire_workflow("04-plan.lock.yml", "issue", n)
    print(f"[orchestrator] {n}: /plan round {rnd} fired (dispatch)")
    return True


_FIRE_SUFFIX = {
    1: " (fresh trigger; ADR-0019 machine relay)",
    2: " (round-1 answers received; ADR-0019 machine relay)",
    3: " (finalize; round-2 approved; ADR-0019 machine relay)",
}


# ------------------------------------------------- plan-loop surrogate (/plan)
def plan_loop_surrogate():
    if not FIRE_PLAN:
        return
    issues = api("issues?state=open&per_page=50") or []
    triggers = ("ai-fix", "ai-build", "ai-remove", "ai-new-site")
    stories = all_stories()  # hoisted: one repo scan per pass (review nit)
    for i in issues:
        if i.get("pull_request"):
            continue
        n = i["number"]
        labels = [l["name"] for l in i.get("labels", [])]
        cs = comments(n)
        r1, r2, r3 = (_round_comment(cs, k) for k in (1, 2, 3))
        kid_stories = [s for s in stories if parent_of(s) == n]

        fresh_trigger = any(l in triggers for l in labels)
        if fresh_trigger and not r1:
            _fire_plan(n, 1)
            continue
        if not r1:
            continue
        # round 2: human answered after round 1
        if not r2 and not kid_stories:
            if not _has_marker(cs, _fire_marker(2)):
                human_after = [c for c in cs
                               if c.get("user", {}).get("login") == OWNER
                               and MARK not in (c.get("body") or "")
                               and c["created_at"] > r1["created_at"]]
                if human_after:
                    _fire_plan(n, 2)
        # round 3: owner approved after round 2
        if r2 and not kid_stories and not r3:
            if not _has_marker(cs, _fire_marker(3)):
                appr = [c for c in cs if c.get("user", {}).get("login") == OWNER
                        and c["created_at"] > r2["created_at"]
                        and (c.get("body") or "").strip().lower() in ("approved", "approve", "lgtm", "yes", "ok")]
                if appr:
                    _fire_plan(n, 3)


# -------------------------------------------------------- decision tags
def decision_tags():
    if not TAG_OWNER:
        return
    openi = api("issues?state=open&per_page=50") or []
    for i in openi:
        if i.get("pull_request"):
            continue
        n = i["number"]
        cs = comments(n)
        for rnd in (1, 2):
            r = _round_comment(cs, rnd)
            if not r:
                continue
            awaiting = _round_waiting_on(r)
            if not awaiting:
                continue
            if any(MARK in (c.get("body") or "") and c["created_at"] >= r["created_at"] for c in cs):
                continue
            post_comment(n, f"{MARK} decision needed @{OWNER} — plan round {rnd} is waiting on you ({awaiting}).")
            print(f"[orchestrator] {n}: tagged owner for round {rnd}")


def _round_waiting_on(round_comment):
    b = (round_comment.get("body") or "").lower()
    if "clarify" in b:
        return "answers to the clarifier questions"
    if "spec confirmed" in b or "breakdown" in b:
        return "reply `approved` to finalize the spec, or corrections"
    return None


# -------------------------------------------------------- transient watchdog
def watchdog():
    openi = api("issues?state=open&per_page=50") or []
    ml = []
    for i in openi:
        t = i.get("title", "")
        if i.get("pull_request"):
            continue
        if t.startswith("[aw]") and ("no safe outputs" in t or "reported incomplete" in t):
            ml.append((i["number"], i.get("created_at", "")))
    if not ml:
        return
    later = run(["gh", "run", "list", "--limit", "50", "--json", "name,conclusion,createdAt",
                 "--jq", '.[] | select((.conclusion=="success") and ((.name|contains("Implement")) or (.name|contains("Plan")))) | .createdAt']).splitlines()
    for num, created in ml:
        if any(t > created for t in later if t.strip()):
            close_issue(num, "not planned",
                        "Transient-class `[aw]` signal: at least one later Implement/Plan run succeeded after this diagnostic's timestamp (orchestrator watchdog, ADR-0017). If the underlying work is still missing, reopen with the specific evidence.")
            print(f"[orchestrator] closed transient diag #{num}")


# ------------------------------------------------- registry sync
# The append-only registry never travels in an agent diff: a registry
# blob in an implement branch dies rebasing onto moved main (the
# stranded-delivery class documented in #723, ADR-0013). The verdict
# channel is the issue thread — this step is the only registry writer.
REGISTRY_ANCHOR = "<!-- findings-registry-entry -->"


def _registry_entry(cs):
    """Latest anchored fenced-```json``` object in an issue thread."""
    for c in reversed(cs or []):
        body = c.get("body") or ""
        if REGISTRY_ANCHOR not in body:
            continue
        m = re.search(r"```json\s*(\{.*?\})\s*```", body, re.S)
        if not m:
            continue
        try:
            return json.loads(m.group(1))
        except Exception as e:
            sys.stderr.write(f"[orchestrator] anchored registry entry unparsable: {e}\n")
    return None


def registry_sync():
    """ADR-0013: agent diffs carry no registry. The agent posts its
    would-be registry entry as an add-comment starting with
    REGISTRY_ANCHOR and a fenced ```json``` object; this step unions it
    into GH_AW_REGISTRY_PATH on main (owner actor) and dedupes per
    issue. A same-issue later entry needs the marker cleared by a human
    or a fresh issue — union makes the common retry a no-op anyway."""
    if not REGISTRY_PATH:
        return  # feature off for this adopter
    if not PROXY_PAT:
        print("[orchestrator] GH_AW_PROXY_PAT unset — registry_sync needs the owner actor, skipping")
        return
    pend = []
    for i in api("issues?state=open&per_page=60") or []:
        if i.get("pull_request"):
            continue
        cs = comments(i["number"])
        if _has_marker(cs, f"{MARK} registry entry applied"):
            continue
        e = _registry_entry(cs)
        if e:
            pend.append((i["number"], e))
    if not pend:
        return
    if _pl(["git", "fetch", "origin", "main"])[0] > 1:
        return
    _, cur_body = _pl(["git", "show", f"origin/main:{REGISTRY_PATH}"])
    try:
        merged = json.loads(cur_body or "{}")
    except Exception as e:
        sys.stderr.write(f"[orchestrator] {REGISTRY_PATH} unparsable on main: {e}\n")
        return
    applied = [n for n, e in pend]
    for _, e in pend:
        # entry's list items append, entry's scalars win (draft 16-f6b shape)
        merged = _union_findings(merged, e)
    payload = json.dumps(merged, indent=2) + "\n"
    idx = f"{os.environ.get('RUNNER_TEMP', '/tmp')}/orch-regidx.{os.getpid()}"
    ienv = {"GIT_INDEX_FILE": idx}
    try:
        if _pl(["git", "read-tree", "origin/main"], env=ienv)[0] != 0:
            return
        blob = subprocess.run(["git", "hash-object", "-w", "--stdin"], input=payload,
                              capture_output=True, text=True)
        if blob.returncode != 0 or not blob.stdout.strip():
            sys.stderr.write(f"[orchestrator] hash-object rc={blob.returncode} for {REGISTRY_PATH}\n")
            return
        if _pl(["git", "update-index", "--cacheinfo",
                f"100644,{blob.stdout.strip()},{REGISTRY_PATH}"], env=ienv)[0] != 0:
            return
        _, tree = _pl(["git", "write-tree"], env=ienv)
        if not tree or len(tree) != 40:
            return
        _, msha = _pl(["git", "rev-parse", "origin/main"])
        _, commit = _pl(["git", *GIT_AUTHOR_IDENTITY, "commit-tree", tree,
                         "-p", msha,
                         "-m", "registry: apply verdict entries from issues "
                               f"#{', #'.join(str(n) for n in applied)} "
                               "(orchestrator registry_sync, ADR-0013)"])
        ok, _ = _git_push("main", commit)
        if not ok:
            ok = _push_ref_via_api("main", commit)
        if ok:
            for n in applied:
                post_comment(n, f"{MARK} registry entry applied to `{REGISTRY_PATH}` on main "
                                f"(`{commit[:10]}`). The registry never travels in an agent diff (ADR-0013).")
            print(f"[orchestrator] registry_sync applied entries from {applied} -> {commit[:10]}")
        else:
            print("[orchestrator] registry_sync push failed — entries stay pending on their issues")
    finally:
        try:
            os.remove(idx)
        except OSError:
            pass


# ------------------------------------------------- conflict watch
def _merge_tree(branch):
    """THE single merge-tree probe (round-3 consolidation: _conflict_files
    and _auto_resolve_conflict share one invocation shape so the name-only
    parser can never be fed full-mode output again — the exact regression
    the round-2 review caught). Returns (rc, tree_oid_or_None, names).
    git ≥2.40; --name-only output: OID line, then conflicted filenames,
    then a blank line, then informational messages ("Auto-merging…",
    "CONFLICT…"). Tolerant of rc≥2 (e.g. missing refs in shallow
    checkouts): empty names + stderr already logged by _pl."""
    rc, out = _pl(["git", "merge-tree", "--write-tree", "--name-only",
                   "origin/main", f"origin/{branch}"])
    if rc not in (1,) or not out:   # 0 = clean merge, rc>=2 = git error
        return rc, None, frozenset()
    lines = out.splitlines()
    tree = lines[0].strip()
    names = []
    for l in lines[1:]:
        if not l.strip() or l.startswith(("Auto-merging", "CONFLICT", "Removing")):
            break
        names.append(l.strip())
    if len(tree) != 40:
        tree = None
    return rc, tree, frozenset(names)


def _conflict_files(branch):
    """Conflict file set when merging main into branch (no worktree)."""
    rc, _, names = _merge_tree(branch)
    if rc != 1:
        return frozenset()
    print(f"[orchestrator] merge-tree rc=1 for {branch}: {sorted(names)}")
    return names


VERSION_RE = re.compile(r"^\s*version\s*=\s*(\d+)\s*(?://.*)?$", re.M)


def _resolve_version_pair(a, b):
    """Deterministic version-bump resolution (round-2 review hardening).
    Safe only when the two bodies differ solely in the `version = N` line
    (number and/or trailing bump-comment — the winner's line wins whole):
    then the higher version wins. Any other difference (or unparsable
    versions) returns None — degrade to the human path rather than
    silently discarding real content fixes on the losing side."""
    if (a or "") == (b or ""):
        return a
    va, vb = VERSION_RE.search(a or ""), VERSION_RE.search(b or "")
    if not va or not vb:
        return None
    if a[:va.start()] != b[:vb.start()] or a[va.end():] != b[vb.end():]:
        return None
    na, nb = int(va.group(1)), int(vb.group(1))
    if na == nb:
        return None  # same number, differing bump-comment — human decides
    return a if na > nb else b


GIT_AUTHOR_IDENTITY = ["-c", "user.name=orchestrator",
                       "-c", "user.email=orchestrator@users.noreply.github.com"]


def _auto_resolve_conflict(branch):
    """Resolve a main-into-branch merge conflict mechanically (delta 16-f6b,
    generalized f11). Known conflict classes only:
    - GH_AW_REGISTRY_PATH (adopter-configured append-only JSON registry)
      -> union-merge (records append, main wins scalars)
    - configurable version-file (default */build.gradle.kts)
      -> higher version wins when bodies differ solely there
    Anything else (incl. Kotlin test files — 14-rebase owns those under
    its gradlew gate) -> False; conflict_watch hands off to the AI rebase
    workflow, and ambiguity ends at the human.
    Plumbing only: merge-tree tree + swapped blobs via a throwaway index
    (GIT_INDEX_FILE); pushes a normal merge commit — no force, no rebase,
    review history preserved; the synchronize event re-fires review."""
    frc = _pl(["git", "fetch", "origin", branch, "main"])[0]
    if frc not in (0, 1):
        print(f"[orchestrator] fetch rc={frc} for {branch} — skipping auto-fix")
        return False
    rc, tree, files = _merge_tree(branch)
    if rc != 1 or not tree:
        return False
    # Files re-derived from THIS probe (round-2 review): a caller-derived
    # set can be stale if a push landed in between, and a path that now
    # merges cleanly must not be whole-blob overwritten.
    idx = f"{os.environ.get('RUNNER_TEMP', '/tmp')}/orch-idx.{os.getpid()}"
    ienv = {"GIT_INDEX_FILE": idx}
    try:
        if _pl(["git", "read-tree", tree], env=ienv)[0] != 0:
            return False
        for path in sorted(files):
            if path == FINDINGS_PATH:
                _, ours_body = _pl(["git", "show", f"origin/main:{path}"])
                _, side_body = _pl(["git", "show", f"origin/{branch}:{path}"])
                try:
                    payload = json.dumps(_union_findings(json.loads(side_body),
                                                         json.loads(ours_body)), indent=2) + "\n"
                except Exception:
                    return False
            elif any(path.endswith(suf) for suf in REGISTRY_VERSION_SUFFIXES):
                _, ours_body = _pl(["git", "show", f"origin/main:{path}"])
                _, side_body = _pl(["git", "show", f"origin/{branch}:{path}"])
                body = _resolve_version_pair(ours_body, side_body)
                if body is None:
                    return False
                payload = body if body.endswith("\n") else body + "\n"
            else:
                return False
            blob = subprocess.run(["git", "hash-object", "-w", "--stdin"], input=payload,
                                  capture_output=True, text=True)
            if blob.returncode != 0 or not blob.stdout.strip():
                sys.stderr.write(f"[orchestrator] hash-object rc={blob.returncode} "
                                 f"for {path}: {blob.stderr.strip()[:200]}\n")
                return False
            blob = blob.stdout.strip()
            if _pl(["git", "update-index", "--cacheinfo", f"100644,{blob},{path}"], env=ienv)[0] != 0:
                return False
        _, new_tree = _pl(["git", "write-tree"], env=ienv)
        if not new_tree or len(new_tree) != 40:
            return False
        _, bsha = _pl(["git", "rev-parse", f"origin/{branch}"])
        _, msha = _pl(["git", "rev-parse", "origin/main"])
        # Runners bake no git identity; without -c the commit fails
        # "Author identity unknown" (round-4 review blocker) and both f11
        # classes stay inert in exactly the environment they target.
        _, commit = _pl(["git", *GIT_AUTHOR_IDENTITY, "commit-tree", new_tree,
                         "-p", bsha, "-p", msha,
                         "-m", f"merge main into {branch}: mechanical conflict resolve "
                               f"({', '.join(sorted(files))}) (orchestrator 16-f6b)"])
        ok, out = _git_push(branch, commit)
        if not ok and PROXY_PAT:
            if _push_ref_via_api(branch, commit):
                ok, out = True, "pushed via git/refs PATCH (owner actor)"
        print(f"[orchestrator] conflict auto-resolved on {branch}: "
              f"{'pushed ' + commit[:10] if ok and commit else 'FAILED: ' + str(out)[:130]}")
        return bool(ok and commit)
    finally:
        try:
            os.remove(idx)
        except OSError:
            pass


def conflict_watch():
    """Merge conflicts on agent PRs: auto-resolve the mechanical classes
    (registry union, version bumps); everything else is handed to the AI
    rebase workflow (14-rebase) via the status/conflict label — with the
    owner ping kept on the thread as the last-resort call to action."""
    # merge-tree + plumbing need up-to-date refs; branch fetches happen per-PR
    _pl(["git", "fetch", "origin", "main"])
    for pr in open_pulls():
        branch = pr.get("head", {}).get("ref", "")
        if not branch.startswith("devloop/"):
            continue
        num = pr["number"]
        d = api(f"pulls/{num}") or {}
        if d.get("mergeable_state") != "dirty":
            continue
        _pl(["git", "fetch", "origin", branch])
        files = _conflict_files(branch)
        if files and all(f == FINDINGS_PATH
                         or any(f.endswith(suf) for suf in REGISTRY_VERSION_SUFFIXES)
                         for f in files):
            if _auto_resolve_conflict(branch):
                continue
        if not PROXY_PAT:
            print("[orchestrator] GH_AW_PROXY_PAT unset — conflict stays manual")
            continue
        marker = f"{MARK} merge conflict detected"
        if not _has_marker(comments(num), marker):
            names = ", ".join(sorted(files)) if files else "unresolvable via merge-tree"
            post_comment(num,
                f"{marker} on this PR — files: `{names}`. Mechanical classes didn't cover it; "
                f"the rebase workflow (14-rebase) picks this up via the status/conflict label. "
                f"If the rebase bot stalls, @{OWNER} — needs a human decision.")
        run(["gh", "api", "-X", "POST", f"repos/{REPO}/issues/{num}/labels",
             "-f", "labels[]=status/conflict"])
        # Explicit dispatch: pull_request.labeled events proved flaky here
        # (2 applications, 0 runs). workflow_dispatch guarantees the fire.
        # aw_context is the gh-aw internal shape for restoring the PR
        # target from a dispatched run (item_type/item_number).
        _ctx = json.dumps({"item_type": "pull_request", "item_number": num})
        # list form, no shell: pass the JSON verbatim (no percent-encoding —
        # the lock's fromJSON expects raw JSON, round-2 review blocker).
        _out = run(["gh", "workflow", "run", "14-rebase.lock.yml",
                    "--ref", "main", "-R", REPO,
                    "-f", f"pr_number={num}",
                    "-f", f"aw_context={_ctx}"])
        if _out.strip():
            print(f"[orchestrator] PR #{num}: 14-rebase dispatched ({_out[:90]})")
        else:
            print(f"[orchestrator] PR #{num}: 14-rebase dispatched")
        print(f"[orchestrator] PR #{num}: status/conflict applied + 14-rebase dispatched")


# ------------------------------------------------- stall watch
def stall_watch():
    """Make silent Implement-fire stalls visible. The usual cause is the
    daily-workflow-aic guardrail: failed runs bill worst-case per-run max
    until their usage artifacts resolve, so every re-fire is skipped with
    zero output. Flag (once/day/issue) rather than re-fire blindly."""
    stories = [i for i in (api("issues?state=open&per_page=50") or []) if not i.get("pull_request")]
    claims = _claim_planned_fires(stories)
    for s in stories:
        n = s["number"]
        if claims.get(n, True):
            continue
        ts = _label_applied_at(n, "status/planned")
        if not ts or time.time() - _parse_ts(ts) < 7200:
            continue
        if _has_stall_flag(comments(n)):
            continue
        post_comment(n, f"{MARK} stalled @{OWNER}: `status/planned` for >2h and no Implement run fired from this stamp. "
                        f"Usual cause: daily-workflow-aic guardrail — check `GH_AW_DEFAULT_MAX_DAILY_AI_CREDITS` and the "
                        f"`Check daily workflow token guardrail` step of recent Implement runs.")
        print(f"[orchestrator] {n}: stalled-fire flagged")


def _demote_out_of_order(stories):
    """Mass 'planned' stamps self-heal (ADR-0018): a story whose predecessor
    is not closed loses its stamp and the Implement run its label event
    fired (cancelled via actions:write) — chain_driver re-stamps it when
    the predecessor closes. Machinery serializes; a human bulk action on
    the whole spec stays legal."""
    if not OWNER:
        return
    fires = _implement_fires()
    for s in stories:
        if str(s.get("state", "")).lower() != "open":
            continue
        num_story = story_num(s.get("title", ""))
        if num_story <= 1:
            continue
        labels = [l["name"] for l in s.get("labels", [])]
        if "status/planned" not in labels:
            continue
        if str(_story_state(num_story - 1, stories)).lower() == "closed":
            continue
        n = s["number"]
        ts = _label_applied_at(n, "status/planned")
        if ts:
            # cancel the run this label fired (proximity claim, same rule
            # as _claim_planned_fires) while it is still cancellable
            for rt in fires:
                if rt.get("status") not in ("queued", "in_progress"):
                    continue
                delta = _parse_ts(rt.get("createdAt") or "") - _parse_ts(ts)
                if 0 <= delta <= 150:
                    run(["gh", "run", "cancel", str(rt["databaseId"])])
                    print(f"[orchestrator] {n}: cancelled out-of-order Implement run {rt['databaseId']}")
        s["labels"] = [l for l in s.get("labels", []) if l["name"] != "status/planned"]
        run(["gh", "api", "-X", "DELETE", f"repos/{REPO}/issues/{n}/labels/status%2Fplanned"])
        print(f"[orchestrator] {n}: demoted (story {num_story-1} not closed) — re-stamps when its predecessor closes")


def queue_to_pipeline():
    """ADR-0018: queue auto-entry — machinery applies ai-fix to status/queued
    issues (ADR-0006 precedent: machinery, never an agent, applies trigger
    labels; the human decision is the triage that produced the queue label).
    Agent-created work (triage/from-agent) and machinery reports stay
    human-curated: automation must not enlarge agent work on its own."""
    if not OWNER:
        return
    triggers = ("ai-fix", "ai-build", "ai-remove", "ai-new-site")
    reports = ("[status-report]", "[aw]", "[cost-report]", "[backlog-report]")
    openi = api("issues?state=open&per_page=50") or []
    for i in openi:
        if i.get("pull_request"):
            continue
        labels = [l["name"] for l in i.get("labels", [])]
        if "status/queued" not in labels or any(t in labels for t in triggers):
            continue
        if "triage/from-agent" in labels:
            continue
        title = i.get("title", "")
        if any(title.startswith(p) for p in reports):
            continue
        n = i["number"]
        label_issue(n, "ai-fix")
        print(f"[orchestrator] {n}: ai-fix applied (queue auto-entry; plan round 1 fires on next pass)")


# ------------------------------------------------------- epic wave driver
def _wave_batch(kids, cap):
    """Pure wave selection: kids is [(number, has_inflight_label)].
    Fill up to `cap` concurrent runs by stamping the lowest-numbered
    unlabeled kids. Returns numbers to stamp."""
    inflight = sum(1 for _, busy in kids if busy)
    if inflight >= cap:
        return []
    idle = sorted(n for n, busy in kids if not busy)
    return idle[:cap - inflight]


def wave_driver():
    """Keep an approved epic's independent sub-issues flowing: while fewer
    than GH_AW_ORCH_WAVE_CAP kids are in flight, stamp the next ones
    status/planned (owner actor). Verdicts, chronic-provider removals and
    policy calls stay human — this only schedules audits the epic already
    approved."""
    # ADR-0014 knob: a stray unit suffix or typo in the repo var must not
    # silently disable the wave driver for the pass (review #760 finding
    # 4317230094) — warn and fall back to the code default.
    cap_raw = os.environ.get("GH_AW_ORCH_WAVE_CAP", "8") or "8"
    try:
        cap = int(cap_raw)
    except ValueError:
        print(f"[orchestrator] ::warning::GH_AW_ORCH_WAVE_CAP='{cap_raw}' not an integer — using default 8")
        cap = 8
    openi = api("issues?state=open&per_page=60") or []
    by_epic = {}
    for i in openi:
        if i.get("pull_request"):
            continue
        m = EPIC_KID_RE.search(i.get("body") or "")
        if not m:
            continue
        labels = [l["name"] for l in i.get("labels", [])]
        by_epic.setdefault(int(m.group(1)), []).append(
            (i["number"], any(l in IN_FLIGHT for l in labels)))
    for epic, kids in by_epic.items():
        p = api(f"issues/{epic}")
        if not p or str(p.get("state", "")).lower() != "open":
            continue
        if not _chain_approved(epic):
            continue
        for n in _wave_batch(kids, cap):
            _fire_implement(n)
            print(f"[orchestrator] epic #{epic}: wave-stamped #{n} (cap {cap})")


# --------------------------------------------------- fix-loop surrogate
def fix_surrogate():
    """GitHub's loop-prevention swallows `pull_request_review` events for
    reviews posted with GITHUB_TOKEN — so agent verdicts never trigger the
    fix loop (observed 2026-10-16: 4 verdicts, zero fix-loop runs).
    ADR-0019: the surrogate fires by workflow_dispatch (GITHUB_TOKEN's
    documented dispatch exception) — no owner-PAT stub review, no owner
    identity. Dedupe via the marker comment on the issue thread; bounded
    by the fix loop's own cycle cap + status/fixing-stuck."""
    for pr in open_pulls():
        branch = pr.get("head", {}).get("ref", "")
        if not branch.startswith("devloop/"):
            continue
        num = pr["number"]
        revs = api(f"pulls/{num}/reviews?per_page=20") or []
        bots = [r for r in revs if (r.get("user") or {}).get("login") == "github-actions[bot]"]
        if not bots:
            continue
        last = bots[-1]
        # Deterministic pass contract first: a ready-to-merge label is the
        # pipeline's own verdict — never repair those (review bodies are
        # gh-aw boilerplate; the verdict text lives in the issue thread).
        labels = [l["name"] for l in (api(f"issues/{num}") or {}).get("labels", [])]
        if "status/ready-to-merge" in labels:
            continue
        verdict = ""
        for c in reversed(comments(num)):
            if (c.get("user") or {}).get("login") == "github-actions[bot]":
                verdict = (c.get("body") or "")
                break
        low = verdict.lower()
        if "blocker" not in low or any(s in low for s in ("0 blockers", "no blockers", "pass, no blockers")):
            continue
        # dedupe: one fire per bot verdict, recorded as a marker comment
        fire_marker = f"{MARK} fix-loop re-fired for review {last['id']}"
        cs = comments(num)
        if any(c.get("created_at", "") > (last.get("submitted_at") or "")
               and fire_marker in (c.get("body") or "") for c in cs):
            continue
        if any(r.get("submitted_at", "") > (last.get("submitted_at") or "") for r in bots[bots.index(last) + 1:]):
            continue
        post_comment(num, fire_marker + " — dispatch fires 07 (machine relay, ADR-0019)")
        out = run(["gh", "workflow", "run", "07-fix-loop.lock.yml",
                   "--ref", "main", "-R", REPO,
                   "-f", f"aw_context={json.dumps({'item_type': 'pull_request', 'item_number': num})}"])
        ok = out.strip() == ""
        print(f"[orchestrator] fix surrogate on PR #{num}: {'fired' if ok else 'failed: ' + out[:120]}")


def _preflight_labels():
    """One-time existence check: every stamp silently fails if a label is
    missing (rc is stderr-only in the Actions UI)."""
    labels = api("labels?per_page=100") or {}
    names = {l.get("name") for l in (labels if isinstance(labels, list) else [])}
    for need in ("status/planned", "status/ready-to-merge", "status/queued",
                 "status/in-review", "status/conflict"):
        if need not in names:
            # Create it — a missing label 404s the POST silently and the
            # workflow it triggers never fires (round-1 review blocker).
            rc = subprocess.run(["gh", "label", "create", need,
                                 "--description", "orchestrator conveyor state (auto-provisioned)",
                                 "--color", "ededed"], capture_output=True, text=True).returncode
            print(f"[orchestrator] ::warning::label '{need}' missing — "
                  f"{'auto-provisioned' if rc == 0 else 'CREATE FAILED (%d) — stamps will no-op' % rc}")


if __name__ == "__main__":
    if PROXY_PAT:
        # Mutations (labels, comments) act as the OWNER actor — the role-gate
        # state machine treats github-actions[bot]-applied labels as
        # unprivileged and gh-aw activation then rejects the fire. Using the
        # admin PAT keeps the actuator's identity the human's, not a bot's.
        os.environ["GH_TOKEN"] = PROXY_PAT
    steps = {
        "approvals": approve_companion_runs,
        "merge_readiness": merge_readiness,
        "chain_driver": chain_driver,
        "queue_to_pipeline": queue_to_pipeline,
        "plan_surrogate": plan_loop_surrogate,
        "decision_tags": decision_tags,
        "conflict_watch": conflict_watch,
        "stall_watch": stall_watch,
        "wave_driver": wave_driver,
        "fix_surrogate": fix_surrogate,
        "watchdog": watchdog,
        "registry_sync": registry_sync,
    }
    _preflight_labels()
    mutation_steps = {"merge_readiness", "chain_driver", "queue_to_pipeline",
                      "plan_surrogate", "decision_tags", "stall_watch", "wave_driver",
                      "fix_surrogate", "watchdog", "registry_sync"}
    available = {name: fn for name, fn in steps.items()
                 if name not in mutation_steps or _owner_configured()}
    only = [a for a in sys.argv[1:] if a in steps]
    for name, fn in {**available, "approvals": steps["approvals"]}.items():
        # approvals self-gates inside; conflict_watch resolves mechanical
        # classes via the checked-out token, BUT its non-mechanical handoff
        # (label POST → 14-rebase fire) only works with GH_AW_PROXY_PAT —
        # the status/conflict labeled event must carry the owner actor or
        # gh-aw activation rejects the fire. Do not drop this guard.
        if only and name not in only:
            continue
        try:
            fn()
        except Exception as e:
            sys.stderr.write(f"[orchestrator] {name} failed: {e}\n")
    print("[orchestrator] done")
