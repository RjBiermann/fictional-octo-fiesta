#!/usr/bin/env python3
"""Mechanical conveyor for the gh-aw pipeline (ADR-0008, amended 2026-10-15).

Non-AI, fails closed. Automates the mechanics of an already-approved chain
and surfaces every real decision as an @mention. It never decides a round,
never merges a PR, never touches ai-* trigger labels, and closes [aw]
diagnostics only when a later Implement/Plan run has succeeded.

  - approve companion (non-AI) runs stuck in `action_required` on
    devloop/* PR branches (needs GH_AW_PROXY_PAT; warns + no-ops if unset)
  - merge readiness: default contract is HUMAN MERGES — label
    `status/ready-to-merge` + ping the owner with the head sha. If the
    adopter sets GH_AW_AUTO_MERGE=true, queue auto-merge instead
    (branch protection remains the real gate either way).
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
OWNER = os.environ.get("GH_AW_ORCH_OWNER", "RjBiermann")

MARK = "[orchestrator]"
FINDINGS_PATH = "audits/findings.json"


def _pl(args, env=None):
    """Run a git plumbing command, return (rc, stdout)."""
    r = subprocess.run(args, capture_output=True, text=True, env=(dict(os.environ, **env) if env else None))
    return r.returncode, r.stdout.strip()


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
STORY_RE = re.compile(r"\[spec\]\s*(?:Story\s+|S)(\d+)", re.I)
PARENT_RE = re.compile(r"approved\s+#(\d+)\s+spec", re.I)
ROUND_ANCH = {r: f"Plan round {r} —" for r in (1, 2, 3)}

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
    return [i for i in raw if i.get("pull_request") is None and story_num(i.get("title", ""))]


def parent_of(story):
    m = PARENT_RE.search(story.get("body") or "")
    return int(m.group(1)) if m else None


def _round_comment(cs, rnd):
    hit = None
    for c in cs or []:
        if ROUND_ANCH[rnd] in (c.get("body") or ""):
            hit = c
    return hit


def _has_marker(cs, text):
    return any(text in (c.get("body") or "") for c in cs or [])


# --------------------------------------------------------------- approvals
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
            # ADR-0008 gate decision (post-merge review): auto-merge may only
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
                label_issue(s["number"], "status/planned")
                print(f"[orchestrator] story 1 (issue {s['number']}): trigger stamped (chain approved at round 3)")
            else:
                print(f"[orchestrator] story 1 (issue {s['number']}): no round-3 approval recorded — waiting on human")
            continue
        pstate = _story_state(num_story - 1, stories)
        if str(pstate).lower() == "closed":
            label_issue(s["number"], "status/planned")
            print(f"[orchestrator] story {num_story} (issue {s['number']}): trigger stamped (story {num_story-1} closed)")
        else:
            print(f"[orchestrator] story {num_story} (issue {s['number']}): predecessor still {pstate}")

    _reconcile_pass(stories, _claim_planned_fires(stories))

    # Parent spec issue: close once every story referencing it is closed.
    by_parent = {}

def _parse_ts(s):
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()
    except Exception:
        return 0.0


def _implement_fires():
    """Implement runs triggered by issue events (label fires), newest first."""
    out = run(["gh", "run", "list", "--workflow", "Implement", "--limit", "60",
               "--json", "createdAt,event"])
    try:
        rows = json.loads(out or "[]")
    except Exception:
        return []
    return sorted((r.get("createdAt") or "" for r in rows if r.get("event") == "issues"), reverse=True)


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
            if i in taken or not rt:
                continue
            delta = _parse_ts(rt) - _parse_ts(ts)
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
        run(["gh", "api", "-X", "POST", f"repos/{REPO}/issues/{n}/labels",
             "-f", "labels[]=status/planned"])
        print(f"[orchestrator] {n}: trigger re-fired under owner actor (stamp fired no run)")


def _chain_approved(pnum):
    for c in comments(pnum):
        if c.get("user", {}).get("login") == OWNER and \
                (c.get("body") or "").strip().lower() in ("approved", "approve", "lgtm", "yes", "ok"):
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
            mk = _fire_marker(1)
            if not _has_marker(cs, mk):
                post_comment(n, mk + " (fresh trigger; ADR-0008 surrogate)")
                print(f"[orchestrator] {n}: /plan round 1 fired")
            continue
        if not r1:
            continue
        # round 2: human answered after round 1
        if not r2 and not kid_stories:
            if not _has_marker(cs, _fire_marker(2)):
                human_after = [c for c in cs if c.get("user", {}).get("login") == OWNER and c["created_at"] > r1["created_at"]]
                if human_after:
                    post_comment(n, _fire_marker(2) + " (round-1 answers received; ADR-0008 surrogate)")
                    print(f"[orchestrator] {n}: /plan round 2 fired")
        # round 3: owner approved after round 2
        if r2 and not kid_stories and not r3:
            if not _has_marker(cs, _fire_marker(3)):
                appr = [c for c in cs if c.get("user", {}).get("login") == OWNER
                        and c["created_at"] > r2["created_at"]
                        and (c.get("body") or "").strip().lower() in ("approved", "approve", "lgtm", "yes", "ok")]
                if appr:
                    post_comment(n, _fire_marker(3) + " (finalize; round-2 approved; ADR-0008 surrogate)")
                    print(f"[orchestrator] {n}: /plan round 3 fired")


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
                        "Transient-class `[aw]` signal: at least one later Implement/Plan run succeeded after this diagnostic's timestamp (orchestrator watchdog, ADR-0008). If the underlying work is still missing, reopen with the specific evidence.")
            print(f"[orchestrator] closed transient diag #{num}")


# ------------------------------------------------- conflict watch
def _conflict_files(branch):
    """Conflict file set when merging main into branch (no worktree).
    git ≥2.40 plumbing: merge-tree --write-tree --name-only."""
    rc, out = _pl(["git", "merge-tree", "--write-tree", "--name-only",
                   "origin/main", f"origin/{branch}"])
    if rc == 0:
        return []
    lines = out.splitlines()
    # --name-only output: tree OID, then conflicted filenames, then a blank
    # line, then informational messages ("Auto-merging…", "CONFLICT…") —
    # stop at the blank line or the informational lines parse as filenames.
    names = []
    for l in lines[1:]:
        if not l.strip() or l.startswith(("Auto-merging", "CONFLICT", "Removing")):
            break
        names.append(l.strip())
    print(f"[orchestrator] merge-tree rc={rc} for {branch}: {names or lines[:3]}")
    return frozenset(names)


def _fix_findings_conflict(branch):
    """Resolve a main-into-branch conflict confined to audits/findings.json
    by union-merging and pushing a normal merge commit (no force push,
    no rebase — review history is preserved; the synchronize event re-fires
    review). Plumbing only: merge-tree tree + swapped blob via a throwaway
    index (GIT_INDEX_FILE), never touching the runner's checkout."""
    run(["git", "fetch", "origin", branch, "main"])
    rc, tree = _pl(["git", "merge-tree", "--write-tree", "origin/main", f"origin/{branch}"])
    if rc != 0:
        print("[orchestrator] merge-tree failed — skipping auto-fix")
        return False
    docs = {}
    for name, ref in (("theirs", f"origin/{branch}"), ("ours", "origin/main")):
        _, body = _pl(["git", "show", f"{ref}:{FINDINGS_PATH}"])
        try:
            docs[name] = json.loads(body)
        except Exception:
            print(f"[orchestrator] {FINDINGS_PATH} unreadable on {ref} — skipping auto-fix")
            return False
    # branch first, then main: main's scalars win, run records union in
    payload = json.dumps(_union_findings(docs["theirs"], docs["ours"]), indent=2) + "\n"
    blob = subprocess.run(["git", "hash-object", "-w", "--stdin"], input=payload,
                          capture_output=True, text=True).stdout.strip()
    if not blob:
        print("[orchestrator] hash-object failed — skipping auto-fix")
        return False
    idx = f"{os.environ.get('RUNNER_TEMP', '/tmp')}/orch-idx.{os.getpid()}"
    ienv = {"GIT_INDEX_FILE": idx}
    try:
        rc, _ = _pl(["git", "read-tree", tree], env=ienv)
        if rc != 0:
            print("[orchestrator] read-tree failed — skipping auto-fix")
            return False
        _pl(["git", "update-index", "--cacheinfo", f"100644,{blob},{FINDINGS_PATH}"], env=ienv)
        _, new_tree = _pl(["git", "write-tree"], env={"GIT_INDEX_FILE": idx})
        if not new_tree or len(new_tree) != 40:
            print("[orchestrator] write-tree failed — skipping auto-fix")
            return False
        _, bsha = _pl(["git", "rev-parse", f"origin/{branch}"])
        _, msha = _pl(["git", "rev-parse", "origin/main"])
        _, commit = _pl(["git", "commit-tree", new_tree, "-p", bsha, "-p", msha,
                         "-m", f"merge main into {branch}: union-resolve {FINDINGS_PATH} "
                               f"(orchestrator 16-f6b, mechanical)"])
        rc, out = _pl(["git", "push", "origin", f"{commit}:refs/heads/{branch}"])
        print(f"[orchestrator] findings conflict auto-resolved on {branch}: "
              f"{'pushed ' + commit[:10] if rc == 0 and commit else 'FAILED: ' + out[:130]}")
        return rc == 0 and bool(commit)
    finally:
        try:
            os.remove(idx)
        except OSError:
            pass


def conflict_watch():
    """Merge conflicts on agent PRs: auto-resolve when confined to the
    per-provider registry (the only conflict class observed), else notify
    with the recipe — one comment per PR."""
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
        if files == {FINDINGS_PATH}:
            if _fix_findings_conflict(branch):
                continue
        marker = f"{MARK} merge conflict detected"
        if _has_marker(comments(num), marker):
            continue
        names = ", ".join(sorted(files)) if files else "unresolvable via merge-tree"
        post_comment(num,
            f"{marker} on this PR — files: `{names}`. "
            f"@{OWNER} — outside the mechanical findings.json auto-merge scope; needs a human merge decision.")
        print(f"[orchestrator] PR #{num}: conflict flagged ({names})")


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


def _preflight_labels():
    """One-time existence check: every stamp silently fails if a label is
    missing (rc is stderr-only in the Actions UI)."""
    labels = api("labels?per_page=100") or {}
    names = {l.get("name") for l in (labels if isinstance(labels, list) else [])}
    for need in ("status/planned", "status/ready-to-merge", "status/queued", "status/in-review"):
        if need not in names:
            print(f"[orchestrator] ::warning::label missing on repo: '{need}' — stamps will no-op silently")


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
        "plan_surrogate": plan_loop_surrogate,
        "decision_tags": decision_tags,
        "conflict_watch": conflict_watch,
        "stall_watch": stall_watch,
        "watchdog": watchdog,
    }
    _preflight_labels()
    only = [a for a in sys.argv[1:] if a in steps]
    for name, fn in steps.items():
        if only and name not in only:
            continue
        try:
            fn()
        except Exception as e:
            sys.stderr.write(f"[orchestrator] {name} failed: {e}\n")
    print("[orchestrator] done")
