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
from datetime import datetime, timedelta
from urllib.parse import quote

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

def _env_int(name, default):
    """Integer env knob: unset, empty and non-numeric all mean `default`.

    `int(os.environ.get(name, "") or 0)` was the shape here first, and it
    turned an unset repo variable (which Actions renders as "") into 0 —
    silently DISABLING the feature for adopters — while a typo raised at import
    and killed every trigger arm of the conveyor, not just this one."""
    try:
        return int(os.environ.get(name, ""))
    except (TypeError, ValueError):
        return default


# Hourly ceiling on real sweeps. A review completing is the cheapest wake this
# lane has; it is also the one that fires repeatedly while PRs are active, and
# every sweep spends the machinery App's API budget for the whole process
# (GH_TOKEN is the App token, since writes need the owner actor). 0 disables
# the ceiling.
#
# 3, not 10: sweeps run 11-24 min on one serialised concurrency group, so ~3/h
# is already the physical rate — a higher ceiling would never bind and the
# exhaustion #1528 measured came from ~3-4 sweeps an hour spending the budget
# in parallel with the agent lanes.
SWEEP_MAX_PER_HOUR = _env_int("GH_AW_SWEEP_MAX_PER_HOUR", 3)
SWEEP_WINDOW_MIN = 60

# A run shorter than this is a declined/throttled wake or a skipped churn run,
# not a sweep: real ones take 11-24 min on this repo. Without the floor the
# gate counts its own no-ops as proof of a recent sweep and slides forever.
SWEEP_MIN_DURATION_MIN = 5

# What a 06-review verdict comment looks like — the gate for the thread scan.
# It must admit EVERY verdict shape, pass or blocker: a gate narrower than
# _blocker_verdict() makes a clearing verdict invisible, so `_latest_verdict()`
# falls back to the older blocker and a passed PR keeps re-firing (#1469 R5).
VERDICT_HINT = re.compile(
    # Verdict line in every markdown dress: **Verdict:** / ## Verdict: / - Verdict:
    r"(?im)^[\s#>*_`+-]*\**\s*verdict\b"
    # A review heading: ## Review — / ## 06-review — / ### Blockers
    r"|^\s*#{1,4}\s*(?:\d{2}[a-z]?-\s*)?(?:review|blockers?)\b"
    # Free-form review prose that opens with it: "Round 2 review — 0 blockers."
    r"|^\s*(?:round\s+\d+\s+)?review\b"
    r"|^\s*\**blockers?\b"
)

# 06-review states its verdict in the opening block; the heading/count
# fallbacks below read only that far, so a blocker quoted deep in the body
# cannot re-fire a repair (PR #1438 rounds 3-4).
VERDICT_HEADER_CHARS = 600

# The verdict contract: 06-review opens with a machine-readable marker.
# Prose mentions of the word are not assertions ("`negated_blocker` was
# body-wide", "not a blocker", "blocker resolved"), and a false fire is NOT
# cheap — two of them apply status/fixing-stuck, a terminal needs-human
# label. So the predicate requires a positive marker, and only free-form
# verdicts without one fall back to heading/count shapes.
VERDICT_MARKER = re.compile(
    r"(?im)^[\s#>*_`-]*verdict[\s*_`:]*"
    r"(?:[\u2014\u2013-]+\s*)?\s*\**\s*"
    r"(?P<neg>no\s+)?(?:(?:one|two|three|[1-9]\d*)\s+)?blockers?\b(?!-free)")
POSITIVE_BLOCKER = (
    # **Blocker (1)** / **Blockers:** — but not **Blockers: none** and not
    # a resolved/fixed phrase (#1438 rounds 7-8).
    re.compile(r"(?i)\*\*blockers?\b(?!-free)"
               r"(?![*_`]*\s*[:(]?\s*0\b)"
               r"(?![*_`]*\s*[:\u2014\u2013-]?\s*"
               r"(?:none\b|no\b|resolved\b|fixed\b|cleared\b|addressed\b))"),
    re.compile(r"(?i)\bblockers?\s*[:(]\s*[1-9]\d*"),        # Blocker (2) / Blockers: 2
    re.compile(r"(?<![\w.-])[1-9]\d*\s+blockers?\b"),        # 2 blockers
)
# A section heading that opens with the word: bare ("### Blockers") is read
# by its content line, with prose ("### Blocker — the rows parse empty") is
# an assertion in itself.
BLOCKER_HEADING = re.compile(r"(?im)^\s*#{2,4}\s*blockers?\b(.*)$")
BARE_HEADING = re.compile(r"(?i)^[\s:>*_`-]*$")
# The content line must BE the negation ("None.", "**None.**"), not merely
# start with it: "No rows parse from the listing." is a blocker (#1438 R10).
HEADING_SAYS_NONE = re.compile(
    r"(?i)^[\W_]*(?:none|no|nothing|zero|0)[\W_]*$")
# A heading tail that only reports a pass. "resolved/fixed/…" may lead the
# tail and then carry prose ("— the endpoint now 200s"); a bare negation may
# not ("— no fallback exists" is an assertion, #1438 R10).
HEADING_CLEARS_IT = re.compile(
    r"(?i)^[\s:\u2014\u2013-]*(?:(?:resolved|fixed|cleared|addressed)\b"
    r"|(?:none|no|nothing|zero|0)[\W_]*$)")


def _blocker_verdict(verdict_low):
    """True when the verdict asserts a blocker.

    Marker first (the contract), then explicit positive shapes, then a
    `### Blocker(s)` heading read by its content line. Silently ignores
    prose that merely contains the word — see the comment above.
    """
    # Header only, and never a blockquote: a quoted prior-round verdict
    # ("carry prior findings between rounds") must not outvote the current
    # one (PR #1438 round 7).
    for line in verdict_low[:VERDICT_HEADER_CHARS].splitlines():
        if line.lstrip().startswith(">"):
            continue
        m = VERDICT_MARKER.match(line)
        if m:
            return not m.group("neg")
    for rx in POSITIVE_BLOCKER:
        if rx.search(verdict_low[:VERDICT_HEADER_CHARS]):
            return True
    lines = verdict_low[:VERDICT_HEADER_CHARS].splitlines()
    for i, line in enumerate(lines):
        m = BLOCKER_HEADING.match(line)
        if not m:
            continue
        tail = m.group(1)
        if not BARE_HEADING.match(tail):
            if HEADING_CLEARS_IT.search(tail):
                continue
            return True                      # heading carries the assertion
        after = next((l.strip() for l in lines[i + 1:] if l.strip()), "")
        if not HEADING_SAYS_NONE.match(after):
            return True
    return False
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
# every body shape parent_of() accepts — the cheap "is this a story?" probe
# all_stories() uses before paying parent_of's sub-issue-graph API fallback.
KID_LINK_RES = (PARENT_RE, EPIC_KID_RE,
                re.compile(r"[Pp]arent(?:\s+issue)?:?\s+#(\d+)"),
                re.compile(r"[Pp]art of #(\d+)"))
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


def _exec(cmd, **kw):
    """The single subprocess entry point: one call, one rc log line.

    Callers need different halves of the result — `run` the stdout, `run_rc`
    the exit status — and having each build its own subprocess.run duplicated
    the logging (CodeFactor flagged exactly that)."""
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        sys.stderr.write(f"[orchestrator] rc={r.returncode} on {' '.join(cmd[:5])}: {r.stderr.strip()[:300]}\n")
    return r


def run(cmd, **kw):
    return _exec(cmd, **kw).stdout


def run_rc(cmd, **kw):
    """Exit status of a command whose stdout carries no success signal.

    `run` returns stdout, so `rc, _ = run([...])` unpacked a string instead of
    an exit code (and raised on the empty body of a successful DELETE). Label
    deletes need the real code to decide whether readiness may proceed.
    """
    return _exec(cmd, **kw).returncode


def dispatch_ok(cmd, env=None):
    """True iff a fire-and-forget command exited 0. `gh workflow run` prints
    nothing to stdout on success and errors only to stderr, so an
    out.strip()=="" check would report failures as fired — and the pre-posted
    dedupe marker would then block the retry permanently.

    Dispatches go out under GITHUB_TOKEN (github-actions[bot]) regardless of
    the ambient GH_TOKEN: gh-aw's activation gate authorizes dispatches only
    via the trusted-bot path — github-actions[bot] has implicit repo write,
    while an installed App is never a collaborator, so the gate resolves the
    machinery actor to permission 'none' and fails closed (#797 round-3
    strand; verified against check_membership.cjs). Every caller is a fire
    site (_fire_workflow, conflict_watch's 14-rebase, fix_surrogate's
    07-fix-loop), so the override lives here, not at the call sites. Labels
    and comments keep the App identity."""
    gh_token = os.environ.get("GITHUB_TOKEN", "")
    if gh_token and env is None:
        env = dict(os.environ, GH_TOKEN=gh_token)
    r = subprocess.run(cmd, capture_output=True, text=True, env=env)
    if r.returncode != 0:
        sys.stderr.write(f"[orchestrator] dispatch rc={r.returncode}: {r.stderr.strip()[:300]}\n")
    return r.returncode == 0


def api(path):
    # Secondary rate limits (403 after ~100 req/min — the sweep makes 100+)
    # returned None silently and every downstream step no-oped (the stale
    # status/conflict label on #851-853 was never cleared for exactly this
    # reason). gh api already honors Retry-After internally? It does not;
    # retry here with the documented window.
    for attempt in range(4):
        out = run(["gh", "api", f"repos/{REPO}/{path}"])
        if out.strip():
            try:
                data = json.loads(out)
            except ValueError:
                data = None
            # gh api prints error bodies (403/404/422) on stdout as JSON —
            # returning them as data poisoned every step (open_pulls grew a
            # 'message' dict, steps iterated strings). Error shape = exact
            # {message, documentation_url, status} trio → retry.
            if isinstance(data, dict) and set(data) == {"message", "documentation_url", "status"}:
                data = None
            if data is not None:
                return data
        time.sleep(5 + attempt * 10)
    return None


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


def story_num(s):
    """Fire order (1-based) of a plan story. The title contract is
    `[spec] S<N> — …`, but 04-plan has shipped whole chains without it
    (#910/#864/#796, 2026-10-23) — those kids get `_ordinal` from the
    parent's round-3 story list in all_stories()."""
    m = STORY_RE.search(s.get("title") or "")
    return int(m.group(1)) if m else s.get("_ordinal")


def _issue_list_rows(state):
    # limit 1000, not 100: 'gh issue list --state all' sorts by CREATED
    # (not updated), so the old 100-row window was a newest-created slice
    # that systematically missed closed predecessors of older chains —
    # e.g. S16 (#825, pred of the S17–S26 audit chain) fell outside and
    # S17+ could never fire (pred '-' forever). ~740 issues total: fetch
    # them all, one paginated call per state.
    out = run(["gh", "issue", "list", "--state", state, "--limit", "1000",
               "--json", "number,title,state,body,labels"])
    if not out.strip():
        return []
    return json.loads(out)


def all_stories():
    # Two windows, deduped by number (open wins): every OPEN issue — the
    # close-burst class (2026-10-23): ~300 same-day bulk closes pushed the
    # open, chain-approved #906–#909 out of the recent-100 recency window
    # so the chain driver enumerated only closed rows and fired nothing —
    # plus the most recently updated items so _story_state() can still
    # resolve closed predecessors. gh issue list returns state UPPERCASE
    # (REST api() returns lowercase); normalize here so every state
    # comparison downstream is one convention.
    raw = {i["number"]: i for i in _issue_list_rows("open")}
    for i in _issue_list_rows("all"):
        raw.setdefault(i["number"], i)
    for i in raw.values():
        i["state"] = str(i.get("state", "")).lower()
    stories = [i for i in raw.values()
               if i.get("pull_request") is None and story_num(i)]
    # Ordinal-less chains (#910/#864/#796): the kids declared their parent in
    # the body only, so STORY_RE dropped them and every driver downstream —
    # chain_driver, the claim/reconcile relays — saw no story at all and
    # fired nothing. Recover the ordinals from the parent's round-3 list.
    by_parent = {}
    for i in raw.values():
        if i.get("pull_request") is not None or story_num(i) is not None:
            continue
        # Plan kids only. 05-implement / 07-fix-loop / 12-post-merge also
        # file `[impl]`/`[from-impl]`/`[aw]` follow-ups that name their spec
        # as parent; ranking those would let chain_driver fire — and
        # _demote_out_of_order cancel runs — on another lane's work order.
        if not (i.get("title") or "").lstrip().lower().startswith("[spec]"):
            continue
        if not any(rx.search(i.get("body") or "") for rx in KID_LINK_RES):
            continue
        pnum = parent_of(i)
        if pnum:
            by_parent.setdefault(pnum, set()).add(i["number"])
    for pnum, kids in by_parent.items():
        for n, ordinal in _plan_ordinals(pnum, kids).items():
            raw[n]["_ordinal"] = ordinal
            stories.append(raw[n])
    return stories


_R3_BODY_CACHE = {}


def _plan_ordinals(pnum, kids):
    """Story ordinals for a chain that skipped the `[spec] S<N>` title
    contract: rank the kids by first mention in the parent's round-3
    finalize comment — the plan loop's own story list, in fire order, in
    whatever markup the agent chose (numbered list, bullets, trailing
    `(#NNN)`). Kids the comment never named rank last, by number. No
    round-3 comment = not a plan chain: rank nothing, so prose that merely
    mentions a parent elsewhere never resurrects an old chain.
    The round-3 body is cached per process: all_stories() runs in five
    drivers per pass and 04-plan posts that comment from its own workflow,
    so it cannot change under a single orchestrator run."""
    if pnum not in _R3_BODY_CACHE:
        _R3_BODY_CACHE[pnum] = (_round_comment(comments(pnum), 3)
                                or {}).get("body") or ""
    body = _R3_BODY_CACHE[pnum]
    if not body:
        return {}
    rank = {}
    for m in re.finditer(r"#(\d+)", body):
        n = int(m.group(1))
        if n in kids:
            rank.setdefault(n, len(rank) + 1)
    for n in sorted(kids - set(rank)):
        rank[n] = len(rank) + 1
    return rank


def parent_of(story):
    body = story.get("body") or ""
    # 04-plan writes at least four link shapes across eras:
    # "approved #759 spec" (old waves), "parent #759, spec finalized…"
    # (Porntrex-style), "Parent: #664 (spec finalized…)" (spec-loop kids),
    # "Part of #804" (the shape finalize agents keep writing naturally —
    # seen on #804's 27 kids AND #840's 3 kids; made canonical here instead
    # of editing bodies per occurrence, #840).
    for rx in KID_LINK_RES:
        m = rx.search(body)
        if m:
            return int(m.group(1))
    # Fallback: the GitHub sub-issue graph. Finalize agents keep writing the
    # parent relationship ONLY via the sub-issue link (no body anchor —
    # #845/#844 are the third recurrence of this class), so ask the graph:
    # Issue.parent is a first-class GraphQL field.
    out = run(["gh", "api", "graphql", "-f",
               "query=query($o:String!,$r:String!,$n:Int!){repository(owner:$o,"
               "name:$r){issue(number:$n){parent{number}}}}",
               "-f", f"o={REPO.split('/', 1)[0]}",
               "-f", f"r={REPO.split('/', 1)[-1]}",
               "-F", f"n={story['number']}"])
    try:
        data = json.loads(out or "{}")
        p = ((data.get("data") or {}).get("repository") or {}).get("issue") or {}
        if (p.get("parent") or {}).get("number"):
            return int(p["parent"]["number"])
    except (ValueError, TypeError):
        pass
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


def _parked_runs():
    """Runs parked on approval, paginated, indexed by head branch.

    This used to be a `gh run list --branch <b> --limit 40` history walk per
    open agent PR, on every sweep, whether or not the PR's head moved. In
    run 45455221600 that single call held the sweep for 889 s of its 929 s
    step against a ~2,830-run/day history (#1588). `status=action_required`
    is a server-side filter, so the answer is one request per page regardless
    of how many PRs exist.

    Paging is bounded at 3 pages (300 parked runs): a single `per_page=100`
    silently dropped every parked run past the first page, while unbounded
    paging would rebuild the stall it was introduced to fix (#1594 review)."""
    parked = {}
    for page in (1, 2, 3):
        data = api(f"actions/runs?status=action_required&per_page=100&page={page}")
        runs = (data or {}).get("workflow_runs") or []
        for r in runs:
            parked.setdefault(r.get("head_branch") or "", []).append(
                (r.get("id"), r.get("name") or ""))
        if len(runs) < 100:
            break
    return parked


def approve_companion_runs():
    if not PROXY_PAT:
        print("[orchestrator] GH_AW_PROXY_PAT unset — approvals stay manual")
        return
    parked = _parked_runs()
    if not parked:
        return
    for pr in open_pulls():
        if not _agent_pr(pr):
            continue
        branch = pr.get("head", {}).get("ref", "")
        for rid, wname in parked.get(branch, []):
            if wname in APPROVE_ALLOWLIST or (wname in AGENT_RUN_ALLOWLIST
                                              and branch.startswith(AGENT_BRANCHES)):
                out = run(["curl", "-s", "-X", "POST",
                           "-H", f"Authorization: Bearer {PROXY_PAT}",
                           "-H", "Accept: application/vnd.github+json",
                           f"https://api.github.com/repos/{REPO}/actions/runs/{rid}/approve"])
                print(f"[orchestrator] approve {rid} ({wname}): {out[:90]}")
            else:
                print(f"[orchestrator] run {rid} ({wname}) outside allowlist — left for human")


# ---------------------------------------------------------- merge readiness
# Check-runs the conveyor itself creates on PR events. Its concurrency
# group cancels them mid-run on agent PRs, and `cancelled` is in the bad
# set below — so one cancelled `orchestrate` check pinned readiness off
# for the whole PR (2026-11-18 #1411, and #1389 since 2026-11-04). The
# conveyor's own run says nothing about the diff, so it is not readiness
# evidence.
SELF_CHECK_NAMES = frozenset({"orchestrate"})


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
        if (c.get("name") or "") in SELF_CHECK_NAMES:
            continue
        # REST check-run status is lowercase (queued/in_progress/completed);
        # comparing raw "COMPLETED" never matched, so the blocked/unstable
        # readiness path always failed and no readiness ping ever fired.
        if (c.get("status") or "").lower() != "completed":
            return False
        if (c.get("conclusion") or "").lower() in bad:
            return False
    return True


def _pr_labels_map(nums):
    """One GraphQL call for all open PR labels. The per-PR REST fallback
    died two ways in real sweeps (#851-853): pulls list objects carry no
    labels field, and per-PR GETs mid-sweep hit GitHub's secondary rate
    limit, silently returning no labels — the stale status/conflict label
    then never got cleared. One batched call, no per-PR chatter."""
    if not nums:
        return {}
    parts = "".join(f"p{n}: pullRequest(number:{n}){{number "
                    f"labels(first:30){{nodes{{name}}}}}}" for n in nums)
    out = run(["gh", "api", "graphql", "-f",
               f"query=query($o:String!,$r:String!){{repository(owner:$o,"
               f"name:$r){{{parts}}}}}",
               "-f", f"o={REPO.split('/', 1)[0]}",
               "-f", f"r={REPO.split('/', 1)[-1]}"])
    try:
        repo = ((json.loads(out or "{}").get("data") or {}).get("repository") or {})
    except ValueError:
        return {}
    m = {}
    for v in repo.values():
        if isinstance(v, dict) and v.get("number"):
            m[v["number"]] = {l["name"] for l in v["labels"]["nodes"]}
    return m


def merge_readiness():
    prs = [pr for pr in open_pulls() if _agent_pr(pr)]
    label_map = _pr_labels_map([p["number"] for p in prs])
    for pr in prs:
        num = pr["number"]
        branch = pr.get("head", {}).get("ref", "")
        d = api(f"pulls/{num}") or {}
        labels = label_map.get(num, set())
        mstate = d.get("mergeable_state")
        # Stale conflict label: conflict_watch applies status/conflict, but
        # only a 14-rebase agent run removes it — conflicts unstuck by the
        # mechanical resolver (or by hand) left the label on forever
        # (#851/#852/#853). "dirty" = conflict live; "u" = GitHub still
        # computing (the FIRST GET returns u and warms the cache — this
        # fetch is the warm one). Clear only on definitive not-dirty states.
        if "status/conflict" in labels and mstate in ("blocked", "clean", "unstable"):
            rc = run_rc(["gh", "api", "-X", "DELETE",
                         f"repos/{REPO}/issues/{num}/labels/status%2Fconflict"])
            if rc == 0:
                print(f"[orchestrator] PR #{num}: stale status/conflict label "
                      f"cleared (state={mstate})")
            else:
                # The label is still there. Publishing readiness now would
                # leave two state labels on the PR (#1405) — the same rule the
                # status/fixing block below enforces. Retry next sweep.
                print(f"[orchestrator] PR #{num}: status/conflict clear rc={rc} "
                      f"— retry next sweep")
                continue
        thread = api(f"issues/{num}/comments?per_page=100")
        if thread is None:
            # unreadable thread: readiness must not read that as "no findings"
            print(f"[orchestrator] PR #{num}: comment thread unreadable — not ready")
            continue
        verdict_body, vtime = _latest_verdict(thread)
        if _blocker_verdict(verdict_body.lower()):
            # readiness reads check conclusions; a live blocker verdict is
            # not a check. A "ready" label over it is actively misleading —
            # PR #1466 was labelled 3 minutes after its blocker (2026-11-21)
            # and kept the label when the repair round failed.
            if "status/ready-to-merge" in labels:
                run(["gh", "api", "-X", "DELETE",
                     f"repos/{REPO}/issues/{num}/labels/status%2Fready-to-merge"])
                print(f"[orchestrator] PR #{num}: stale status/ready-to-merge "
                      f"cleared — blocker verdict outstanding")
            else:
                print(f"[orchestrator] PR #{num}: blocker verdict outstanding — not ready")
            continue
        if "status/fixing-stuck" in labels:
            # the lane declared this needs a human; readiness must not
            # override that with green checks (#1405)
            print(f"[orchestrator] PR #{num}: status/fixing-stuck — not ready")
            continue
        if "status/fixing" in labels:
            # 07 step 4 tells the agent to drop this after its push, but on a
            # relay-fired run gh-aw resolves remove-labels to the *story*
            # issue: #1516's repair deleted `issues/1448/labels/status%2Ffixing`
            # (404, logged as "not present, skipping"), so the PR kept the
            # label and round 2's pass added status/ready-to-merge on top —
            # two state labels on a clean PR (#1405). A non-blocker verdict
            # means the repair round is over; clear it here, where the encoded
            # DELETE and the rc already exist.
            applied = _label_applied_at(num, "status/fixing")
            # Unknown (`None`) means the label event was unreadable or paged
            # out of the events feed — keep the state and wait for a sweep that
            # can read it, never clear on unknown.
            if not verdict_body or applied is None or vtime <= applied:
                # No verdict at all, or a pass that PREDATES the label: 07 is
                # also fired by a human review, and `_latest_verdict` only
                # reads bot comments — clearing on an older pass would drop a
                # live repair's state.
                print(f"[orchestrator] PR #{num}: status/fixing without a newer "
                      f"verdict — repair in flight, not ready")
                continue
            rc = run_rc(["gh", "api", "-X", "DELETE",
                         f"repos/{REPO}/issues/{num}/labels/status%2Ffixing"])
            if rc != 0:
                # A half-done transition must not publish readiness: the label
                # is still there, so adding status/ready-to-merge would leave
                # two state labels (#1405). A later sweep retries the clear.
                print(f"[orchestrator] PR #{num}: status/fixing clear rc={rc} — "
                      f"retry next sweep")
                continue
            print(f"[orchestrator] PR #{num}: status/fixing cleared — verdict "
                  f"is not a blocker")
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
        # Anchored on the verdict, not just the sha: after a blocker clears
        # the label, a re-review at the SAME head must be able to label again
        # (the stale "ready … at <sha>" comment otherwise suppresses it
        # forever — #1469 R4's deadlock).
        marker = (f"ready @{OWNER} — all checks green at `{sha}`; "
                  f"verdict {vtime or 'none'}")
        if _has_marker(comments(num), marker):
            continue
        label_issue(num, "status/ready-to-merge")
        post_comment(num, f"{MARK} {marker} — please review and merge when ready.")
        print(f"[orchestrator] PR #{num}: tagged {OWNER} for merge")


# ------------------------------------------------------------- chain driver
def chain_driver():
    stories = all_stories()
    for s in stories:
        if s["state"] != "open":
            continue
        num_story = story_num(s)
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
        if not num_story:
            # `[spec] S0 — …` baseline: no predecessor to wait on, and
            # indexing _story_state(-1) would stall on a phantom '-'.
            continue
        pstate = _story_state(num_story - 1, stories, parent_of(s))
        if str(pstate).lower() == "closed":
            _fire_implement(s["number"])
            print(f"[orchestrator] story {num_story} (issue {s['number']}): trigger stamped (story {num_story-1} closed)")
        else:
            print(f"[orchestrator] story {num_story} (issue {s['number']}): predecessor still {pstate}")

    _demote_out_of_order(stories)
    claims = _claim_planned_fires(stories)
    _reconcile_pass(stories, claims)
    _relay_untransitioned(stories, claims)

    # Parent spec issue: close once every story referencing it is closed.
    # Enumerate over kid_count (ALL parents with kids) — iterating only the
    # open-kid map made the close path unreachable a second time (the stub
    # was dead code; review #872 round 1 caught the first rewrite repeating
    # the same class).
    by_parent = {}
    kid_count = {}
    for s in stories:
        pnum = parent_of(s)
        if not pnum:
            continue
        kid_count[pnum] = kid_count.get(pnum, 0) + 1
        if s.get("state") == "open":
            by_parent.setdefault(pnum, []).append(s["number"])
    for pnum, total in sorted(kid_count.items()):
        open_kids = by_parent.get(pnum, [])
        if open_kids:
            print(f"[orchestrator] parent #{pnum}: {len(open_kids)} of {total} stories still open")
            continue
        pb = api(f"issues/{pnum}") or {}
        if str(pb.get("state", "")).lower() != "open":
            continue
        close_issue(pnum, "completed",
                    f"{MARK} parent spec closed: all {total} stories referencing it are closed")
        print(f"[orchestrator] parent #{pnum}: closed (all stories closed)")

# ------------------------------------------------- branch executor (#897)
# Conditional fan-out, consumer side (parent spec #847, S1 #897): after the
# probe story posts its verdict, exactly one branch story fires — with no
# human click. Pure decision layer (regexes + _branch_decision, no API) plus
# step wiring (branch_executor). Everything undeterminable fails closed:
# the stories stay open for the human.


def _strip_md(text):
    """Bold-insensitive matching: headings/bold in the story bodies are
    decoration (**Conditional: runs only if ...** is bold). The shortest
    robust move for every declaration regex here."""
    return re.sub(r"[*_`~]", "", text or "")


FANOUT_EXACTLY_ONE_RE = re.compile(r"exactly one", re.I)
FANOUT_CONDITIONAL_RE = re.compile(r"runs\s+only\s+if\b", re.I)
FANOUT_VERDICTS_RE = re.compile(r"if\b.{0,120}?\bverdicts\b", re.I | re.S)

BRANCH_DECL_RE = re.compile(
    # 'runs only if the probe (#846) verdicts (not fixable)' — the #N is
    # the probe link (NOT 'lowest-numbered sibling', which mis-resolves on
    # chains with extra stories). Case- and bold-insensitive; bare and
    # parenthesized both accepted. A branch edit that keeps this one
    # declaration sentence stays routed; dropping/mangling it fails closed.
    r"runs\s+only\s+if\s+(?:the\s+)?probe\s*\(?#?(\d+)\)?\s+verdicts?\s+"
    r"(not\s+fixable|fixable)\b", re.I)

PROBE_VERDICT_HEAD_RE = re.compile(r"^##\s*Probe\s+verdict\b", re.I | re.M)


def _fanout_gate(parent_texts):
    """Text-evidence gate on the parent, same style as #837's AUDIT_CHAIN_RE:
    'exactly one' AND the conditional wording ('runs only if' / 'if …
    verdicts'). Scans the parent body AND all parent comments — on the
    live #840 chain the match rendered in the round-3 finalize comment,
    not the issue body. Returns the matched text (the gate evidence for
    the log line) or None."""
    for t in parent_texts or []:
        if FANOUT_EXACTLY_ONE_RE.search(t):
            tl = _strip_md(t)
            if FANOUT_CONDITIONAL_RE.search(tl) or FANOUT_VERDICTS_RE.search(tl):
                return t
    return None


def _probe_verdict(pcs):
    """Parse a probe's verdict from its comment thread. The verdict is a
    bot comment whose body has a '## Probe verdict — <chain name>'
    heading; the outcome is exactly one bold token inside it.
    Resolving '**not fixable**' BEFORE '**fixable**' keeps any future
    emphasis variant from snapping a stricter ('not fixable') answer to
    the optimistic one. Returns (outcome | None, heading_matched | None).
    The historical #846 comment was free-form prose ('FINDINGS-840 — probe
    verdict … : FIXABLE.') with no heading and no bold token — that class
    is NOT parseable evidence: fail closed.
    """
    for c in reversed(pcs or []):
        body = c.get("body") or ""
        if not PROBE_VERDICT_HEAD_RE.search(body):
            continue
        if (c.get("user") or {}).get("type") != "Bot":
            continue   # human prose: never the mechanical verdict
        if "**not fixable**" in body:
            return "not fixable", body
        if "**fixable**" in body:
            return "fixable", body
        return None, body   # anchored heading but no bold token: fail closed
    return None, None


def _branch_decision(parent_texts, stories, probe_comments):
    """Pure decision layer for the conditional fan-out (parent spec #847).
    parent_texts: parent body + all parent comments (strings).
    stories: the chain's sibling story dicts (number/state/labels/body).
    probe_comments: {probe_issue_number: [comment dicts]}. No API calls.

    Returns:
      None                     — no fan-out gate: not this executor's business
      {…, "fire": None, "fail": reason} — fail closed; leave everything open
      {…, "fire": story, "close": [story, …]} — the mechanical action.
    Never mutates; the executor owns all mutation via existing helpers.
    """
    gate = _fanout_gate(parent_texts)
    if not gate:
        return None
    decision = {"gate": (gate or "")[:120], "probe": None, "verdict": None,
                "fire": None, "close": [], "fail": None}

    branches, probes = {}, set()
    for s in stories:
        if str(s.get("state", "")).lower() != "open":
            continue
        m = BRANCH_DECL_RE.search(_strip_md(s.get("body") or ""))
        if m:
            probes.add(int(m.group(1)))
            branches[m.group(2).lower()] = s
    if not branches:
        decision["fail"] = "fan-out gate matched but no open story declares a branch"
        return decision
    if len(probes) != 1:
        decision["fail"] = ("branch declarations reference different probes "
                            f"({sorted(probes)}) — ambiguous probe")
        return decision
    if sorted(branches) != ["fixable", "not fixable"]:
        decision["fail"] = ("branches must declare exactly one fixable + one "
                            f"not fixable (declared: {sorted(branches)})")
        return decision   # both-or-neither / partial: fail closed
    probe_n = probes.pop()
    decision["probe"] = probe_n
    verdict, vbody = _probe_verdict(probe_comments.get(probe_n))
    if vbody is not None and verdict is None:
        decision["fail"] = (f"verdict on probe #{probe_n} present but the outcome "
                            "token is not exactly one bold fixable/not fixable")
        return decision
    if verdict is None:
        decision["fail"] = f"no anchored '## Probe verdict' bot comment on probe #{probe_n}"
        return decision
    decision["verdict"] = verdict
    decision["fire"] = branches[verdict]
    decision["close"] = [s for o, s in branches.items() if o != verdict]
    return decision


def branch_executor(stories=None):
    """#847 S1: resolve every approved chain's conditional fan-out from the
    probe's verdict comment — exactly one branch fires, the others close
    'not planned', with no human click. Pure decision via _branch_decision;
    mutation via the existing helpers (_fire_implement carries the #797-class
    delivered/IN_FLIGHT guards so a fired branch is never a duplicate PR).
    Fail closed: every unresolvable chain is left open for the human."""
    stories = stories if stories is not None else all_stories()
    by_parent = {}
    for s in stories:
        pnum = parent_of(s)
        if pnum:
            by_parent.setdefault(pnum, []).append(s)
    for pnum, kids in by_parent.items():
        parent_texts = [(api(f"issues/{pnum}") or {}).get("body") or ""]
        parent_texts += [c.get("body") or "" for c in comments(pnum)]
        probe_comments = {s["number"]: comments(s["number"]) for s in kids}
        d = _branch_decision(parent_texts, kids, probe_comments)
        if d is None:
            continue
        if d["fail"]:
            print(f"[orchestrator] parent #{pnum}: branch decision FAIL-CLOSED "
                  f"(gate: {d['gate'][:60]!r}) — {d['fail']}; left open for the human")
            continue
        print(f"[orchestrator] parent #{pnum}: conditional fan-out resolved "
              f"(gate matched: {d['gate'][:60]!r}; probe #{d['probe']} verdict "
              f"**{d['verdict']}**) — fire #{d['fire']['number']}, "
              f"close {[s['number'] for s in d['close']]}")
        probe_pr = _open_pr_for_story(d["probe"])
        if not probe_pr:
            probe_pr = _merged_pr_for_story(d["probe"])
        _fire_implement(d["fire"]["number"])
        for s in d["close"]:
            n = s["number"]
            if _open_pr_for_story(n) or _merged_pr_for_story(n):
                print(f"[orchestrator] branch #{n}: delivered via PR — not auto-closed "
                      f"(closing would discard real work); left for the human")
                continue
            close_issue(n, "not planned",
                        f"{MARK} branch resolved by #{d['probe']} verdict "
                        f"(**{d['verdict']}**) — the other branch fired instead.")
            print(f"[orchestrator] branch #{n}: closed not-planned "
                  f"(unselected by probe #{d['probe']} verdict)")
        if probe_pr:
            print(f"[orchestrator] probe #{d['probe']}: evidence PR #{probe_pr} exists "
                  f"— stays open for 12-post-merge/closeout; branches resolved anyway")
        else:
            close_issue(d["probe"], "completed",
                        f"{MARK} probe closed completed: verdict resolved its fan-out "
                        f"(no open/merged PR around the probe — #837 discipline)")
            print(f"[orchestrator] probe #{d['probe']}: closed completed (no PR around it)")


#861: verdict headings may carry a story-number prefix — "## S03 audit
# verdict — …", "## [S06]audit verdict — …" (bracketed, glued — agents
# copy the prompt pin's bracket notation literally), with or without a
# trailing space. The old anchor rejected any prefix, which left clean
# stories open with only the owner's click as a close path. Still fails
# closed: prose must contain the bolded verdict word.
AUDIT_VERDICT_RE = re.compile(r"^##\s*\[?[Ss]?\d*\]?\s*audit verdict\b.*\*\*clean\*\*", re.I | re.M)
AUDIT_CHAIN_RE = re.compile(r"clean providers.{0,80}maintainer close", re.I | re.S)


def audit_story_closeout(stories=None):
    """#837: auto-close clean audit stories — evidence-level gates, fails
    closed. A story whose parent spec declares verdict-only closeout and
    whose only deliverable is a clean verdict comment is mechanically
    closable: the human decision was the parent's round-3 approval; the
    verdict comment is the work. Finding stories, PR-delivered stories and
    anything undeterminable stay open."""
    stories = stories if stories is not None else all_stories()
    for s in stories:
        if s.get("state") != "open":
            continue
        n = s["number"]
        labels = [l["name"] for l in s.get("labels", [])]
        if "status/in-review" not in labels:
            continue
        if _open_pr_for_story(n) or _merged_pr_for_story(n):
            continue
        pnum = parent_of(s)
        if not pnum:
            continue
        parent_texts = [c.get("body") or "" for c in comments(pnum)]
        pb = api(f"issues/{pnum}") or {}
        parent_texts.insert(0, pb.get("body") or "")
        if not any(AUDIT_CHAIN_RE.search(t) for t in parent_texts):
            continue
        if not any(AUDIT_VERDICT_RE.search(c.get("body") or "")
                   for c in comments(n)):
            continue
        close_issue(n, "completed",
                    f"{MARK} clean-audit closeout (evidence: `## Audit verdict … clean` comment, "
                    f"no PR, parent #{pnum} declares verdict-only closeout) — chain advance per story-N rule")
        print(f"[orchestrator] story (issue {n}): clean-audit auto-closed")


def subissue_latcher(stories=None):
    """#840 follow-up: self-healing sub-issue linking — any open issue whose
    body carries a Parent: #N link but whose parent does not actually list
    it as a sub-issue gets the addSubIssue mutation applied. Heals both
    safe-output link failure modes (#804 counter exhaustion, #840 relay
    target) on the next sweep instead of silently stalling a chain.
    Idempotent: already-linked pairs are skipped."""
    stories = stories if stories is not None else all_stories()
    for s in stories:
        if s.get("state") != "open":
            continue
        pnum = parent_of(s)
        if not pnum:
            continue
        n = s["number"]
        linked = api(f"issues/{pnum}/sub_issues?per_page=100") or []
        if any(x.get("number") == n for x in linked):
            continue
        pid = api(f"issues/{pnum}") or {}
        sid = api(f"issues/{n}") or {}
        if not (pid.get("id") and sid.get("id")):
            print(f"[orchestrator] issue {n}: parent #{pnum} link pending — node lookup failed, retried next pass")
            continue
        out = run(["gh", "api", "graphql", "-f", "query=mutation($p:ID!,$s:ID!){"
                   "addSubIssue(input:{issueId:$p, subIssueId:$s}){ subIssue { id } } }",
                   "-f", f"p={pid['id']}", "-f", f"s={sid['id']}"])
        if out.strip():
            print(f"[orchestrator] issue {n}: latched to parent #{pnum} (was unlinked)")
        else:
            print(f"[orchestrator] issue {n}: addSubIssue rc!=0 for parent #{pnum} — retried next pass")


def _parse_ts(s):
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()
    except Exception:
        return 0.0


def _implement_fires():
    """Implement fires (label events + dispatch relays), newest first.
    Both channels count: post-#875 the workflow_dispatch relay IS the fire
    that executes (label events from the App actor trigger only hollow
    runs) — filtering to issues-events alone made the claim blind to the
    executed dispatch, so every sweep re-fired a stamp that HAD been
    served (S07: re-stamped 15:59/16:23/16:37/16:49 in a loop).
    Hollow runs are excluded downstream by _claim_planned_fires.
    Each row: {id, status, createdAt} — id lets the conveyor cancel a run
    it demotes (out-of-order mass stamps), status filters terminal ones."""
    out = run(["gh", "run", "list", "--workflow", "Implement", "--limit", "60",
               "--json", "databaseId,status,conclusion,createdAt,event"])
    try:
        rows = json.loads(out or "[]")
    except Exception:
        return []
    return sorted((r for r in rows if r.get("event") in ("issues", "workflow_dispatch")),
                  key=lambda r: r.get("createdAt") or "", reverse=True)


def _claim_planned_fires(stories):
    """Map open status/planned issues to the Implement run their label event
    fired. gh-aw run payloads carry no issue attribution, so claim by
    proximity: a run within 150s after the stamp claims at most one issue
    (greedy nearest match). Returns {issue_number: run-row or None}; the
    row carries id/status/createdAt for the #860 transition relay.
    (This replaces the old reconcile check against the workflow's global
    latest run, which any sibling issue's run suppressed.)
    Hollow runs (activation-gated-out: gh-aw marks them overall success
    with the agent skipped) do NOT count as fires — they claim nothing
    (#797/#875 class: the App-actor re-stamp fired a run that ran nothing,
    and the claim made stall_watch never re-fire)."""
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
        # Candidates nearest-first. Claim bar splits by terminality:
        # - in-progress/queued: activation ran (a long-running implement
        #   must claim early or every sweep re-fires it — #884 r1)
        # - terminal: success + activation ran + MACHINERY OUTPUT exists
        #   after the run start — a silent-empty exit (#904 class: content
        #   [], zero safe-outputs) fired no work and must not claim
        cands = []
        for i, rt in enumerate(runs):
            if i in taken or not rt.get("createdAt"):
                continue
            delta = _parse_ts(rt["createdAt"]) - _parse_ts(ts)
            if 0 <= delta <= 150:
                cands.append((delta, i))
        for delta, i in sorted(cands):
            rt = runs[i]
            if ((rt.get("status") or "") == "completed"
                    and (rt.get("conclusion") or "") != "success"):
                continue   # terminal failure/skip: no claim, no API call
            activated = _activated(rt.get("databaseId") or rt.get("id") or 0)
            if _claims_fire(rt, activated, comments(s["number"])):
                best = (i, delta)
                taken.add(i)
                break
        # Row (not bool) so the #860 relay can read the run's completion
        # status; _reconcile_pass only tests truthiness, so rows drop in.
        claims[s["number"]] = runs[best[0]] if best is not None else None
    return claims


def _activated(run_id):
    """True iff the run's activation job ran (not skipped). gh-aw marks
    activation-gated-out runs (role/bot gate denial) as overall 'success'
    with every downstream job skipped — hollow runs. One API call."""
    out = run(["gh", "run", "view", str(run_id), "-R", REPO, "--json", "jobs"])
    try:
        jobs = json.loads(out or "{}").get("jobs", [])
    except Exception:
        return False   # undeterminable -> not a claim
    act = [j for j in jobs if j.get("name") == "activation"]
    return bool(act) and act[0].get("conclusion") == "success"


def _claims_fire(rt, activated, output_comments):
    """PURE claim-bar predicate (#905 r1 blocker 1 — the bar's semantics
    live here, tested in orchestrator_test.py, not in prose).
    rt: run row {databaseId, status, conclusion, createdAt}; activated:
    the network-resolved activation-job check; output_comments: the
    story's comments (Bot-type author + created_at after the run count).
    - non-terminal (queued/in_progress): claims on activation alone — a
      long-running implement must claim early or every sweep re-fires it
      (duplicate-billing loop, #884 r1).
    - terminal: claims only on success + activated + machinery output
      after the run start — hollow (#879), failed (S07's AIC death) and
      silent-empty (#904: content [], zero safe-outputs) runs fired no
      work and must not claim."""
    if (rt.get("status") or "") == "completed":
        if (rt.get("conclusion") or "") != "success":
            return False
        return bool(activated) and _machinery_output(
            output_comments, _parse_ts(rt.get("createdAt") or ""))
    return bool(activated)


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


def _machinery_output(comments, after_ts):
    """True iff some comment after after_ts is real machinery OUTPUT —
    tightened per #905 r1 (a sibling fire's Bot chatter must not bless a
    silent-empty run when two fires share the 150s window): Bot-type
    author AND one of the lane's output signatures: budget stamp,
    heading comment (verdicts/specs), or a registry entry."""
    for c in comments:
        if (c.get("user") or {}).get("type") != "Bot":
            continue
        if _parse_ts(c.get("created_at") or "") <= after_ts:
            continue
        body = c.get("body") or ""
        if (body.startswith("gh-aw budget:")
                or body.lstrip().startswith("## ")
                or body.lstrip().startswith("[orchestrator]")
                or "findings-registry-entry" in body[:120]):
            return True
    return False


def _relay_untransitioned(stories, claims):
    """#860 watchdog relay: an open status/planned story whose claimed
    Implement run completed and posted machinery output (work report /
    verdict) but skipped its own status/in-review transition gets the
    transition applied mechanically — the run's work exists, the label
    state is the only thing missing (observed S3 #812, chain idle ~13h).
    Idempotent: the relay moves the story out of status/planned, so the
    next pass skips it; runs that posted nothing are never claimed."""
    for s in stories:
        n = s["number"]
        row = claims.get(n)
        if not row or (row.get("status") or "") != "completed":
            continue
        if "status/planned" not in [l["name"] for l in s.get("labels", [])]:
            continue
        ran_at = _parse_ts(row.get("createdAt") or "")
        ts = _parse_ts(_label_applied_at(n, "status/planned") or "")
        if time.time() - max(ran_at, ts) < 600:   # grace: run output may still be posting
            continue
        if not _machinery_output(comments(n), ran_at):
            continue
        run(["gh", "api", "-X", "DELETE", f"repos/{REPO}/issues/{n}/labels/status%2Fplanned"])
        label_issue(n, "status/in-review")
        print(f"[orchestrator] issue {n}: transition relayed (implement run completed with output, transition skipped)")


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

def _story_state(num, stories, parent=None):
    """State of story `num` — scoped to `parent`'s chain when given.
    Unscoped lookups leak across chains: two chains each having a
    Story 1 made Porntrex S2 fire while its own S1 was open (#790)."""
    for s in stories:
        if story_num(s) == num \
                and (parent is None or parent_of(s) == parent):
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
    as the owner and needs no PAT secret.

    The dispatch MUST go out under GITHUB_TOKEN (github-actions[bot]), not
    the machinery App token: gh-aw's activation gate authorizes dispatches
    only via the trusted-bot path — github-actions[bot] has implicit repo
    write, while an installed App is never a collaborator, so the gate
    resolves it to permission 'none' and fails closed (#797 round-3 relays
    stranded exactly there; verified against check_membership.cjs). Labels
    and comments keep the App identity — the override itself is centralized
    in dispatch_ok, which every fire site uses."""
    ctx = json.dumps({"item_type": item_type, "item_number": item_number})
    ok = dispatch_ok(["gh", "workflow", "run", lock, "--ref", "main", "-R", REPO,
                      "-f", f"aw_context={ctx}"])
    print(f"[orchestrator] {lock} dispatch for {item_type} #{item_number}: "
          f"{'fired' if ok else 'FAILED'}")
    return ok


def _delivered_branch_pat(num):
    """Agent-delivery branch shapes: the gh-aw lanes name branches
    `devloop/issue-N-<hash>`, `ai/issue-N-<slug>-<hash>`, and
    `fix/N-<slug>-<hash>` (fix sessions). Matches all three; a stray
    over-match is fail-closed for the delivered-guard (no re-fire),
    never a duplicate PR."""
    import re as _re
    return _re.compile(rf"(?:issue-{num}-|/{num}-)")


# Agent PR head-branch prefixes (devloop/issue-N survived the gh-aw pilot
# as the implement lane's naming; fix/ + ai/issue- are the fix-session and
# selfcheck-session shapes). machinery identifies agent PRs by these
# prefixes AND the bot author — a human PR never matches both.
AGENT_BRANCHES = ("devloop/issue-", "ai/issue-", "fix/")


def _machinery_login(pr):
    """ADR-0020 step 3: machinery safe-output deliveries (push-to-pull-
    request-branch / create-pull-request) land as the GitHub App identity
    (<slug>-machinery[bot]), not github-actions[bot]. The App is a trusted
    pipeline identity by design (ADR-0020 migration-design §2) — the
    trust anchor must learn it or every machinery delivery falls out of
    the delivered/fixable guards (recorded: #1307/#1310 sat on blocker
    verdicts with zero 07-fix-loop fires, 2026-10-23). Env override
    GH_AW_MACHINERY_LOGIN pins the exact adopter slug; default is the
    '<anything>-machinery[bot]' suffix shape."""
    login = ((pr.get("user") or {}).get("login") or "").lower()
    pinned = os.environ.get("GH_AW_MACHINERY_LOGIN", "").lower()
    if pinned:
        return login == pinned
    return login.endswith("-machinery[bot]")


def _agent_pr(pr):
    """True for agent-authored PRs in a known delivery branch shape:
    github-actions[bot] (legacy GITHUB_TOKEN safe-outputs) and the
    machinery GitHub App identity (ADR-0020 step 3)."""
    return (not pr.get("draft")
            and ((pr.get("user") or {}).get("login") == "github-actions[bot]"
                 or _machinery_login(pr))
            and pr.get("head", {}).get("ref", "").startswith(AGENT_BRANCHES))


def _fixable_pr(pr):
    """What the fix surrogate may push repairs to: machinery deliveries
    (agent PRs) plus OWNER-authored PRs — the maintainer's 2026-10-22
    carve-out. The trust anchor is the author identity, not the branch
    shape: unknown authors can never reach this code (gh-aw's roles gate
    bars them from triggering any pipeline step, and gh-aw's own
    check_membership explicitly allows allowlisted bots to synchronize
    same-repo PRs whose author is trusted). Machinery commits land as the
    App identity — attribution stays structural (ADR-0019); merge stays
    the human gate. Other human authors stay out — fail closed."""
    if _agent_pr(pr):
        return True
    return (pr.get("user") or {}).get("login") == OWNER


DELIVERY_REVIEW_LABELS = ("status/in-review", "status/ready-to-merge", "status/conflict")


def _delivery_record(num, cs=None, labels=()):
    """The delivery record for story `num`, or None: open PR, merged PR, fallback
    review issue, or a review-family label. One predicate because `_fire_implement`
    and the plan loop must not drift — they had already (round-1 finding on #1378).

    The labels are here because a delivery branch need not carry a hash:
    `_delivered_branch_pat` requires `issue-N-<hash>`, while this lane's own
    branches (`devloop/issue-1358`, #1359, #1312) are hashless. A review-family
    label cannot exist before a delivery, so it is evidence, not a guess.

    `_fire_implement` does not use this: its merged arm must first honour the
    owner re-arm carve-out (`_stamped_after_merge`), so its ordering is
    deliberately different rather than drifted."""
    open_pr = _open_pr_for_story(num)
    if open_pr:
        return open_pr
    merged = _merged_pr_for_story(num)
    if merged:
        return merged
    fb = _fallback_delivery(num, cs)
    if fb:
        return fb
    for name in DELIVERY_REVIEW_LABELS:
        if name in labels:
            return name
    return None


# pr number -> the numbers GitHub reports it closing, scoped to one orchestrator pass.
# Without it every story re-queries the same PR (7 subprocess calls per closeout pass,
# scaling with open issues — #1380 round-2 nit).
_CLOSES_CACHE = {}


def _closes_cache_reset():
    _CLOSES_CACHE.clear()


def _github_closing_issues(pr):
    """The story numbers GitHub reports `pr` closing, empty when unknown.

    One query, cached per pass: `closingIssuesReferences` is the field GitHub
    computes from the PR body (`Fixes #N`), scoped to same-repo refs and the
    default branch — the record to trust instead of re-parsing prose (#1376
    spent nine review rounds discovering every way that parse can be wrong;
    #1379 chose this). GraphQL because REST's pull payload does not carry the
    field (checked: `has("closingIssuesReferences") == false`).

    The parse is defensive: a GraphQL error payload (`{"data": null}`, e.g. a
    rate limit) or an invisible repository (`{"repository": null}`) reads as
    "reports closing nothing" — never as a crash that aborts a whole conveyor
    pass. Same safe shape as `parent_of()`."""
    number = pr["number"]
    cached = _CLOSES_CACHE.get(number)
    if cached is not None:
        return cached
    out = run(["gh", "api", "graphql", "-f",
               "query=query($o:String!,$r:String!,$n:Int!){repository(owner:$o,"
               "name:$r){pullRequest(number:$n){closingIssuesReferences(first:50)"
               "{nodes{number}}}}}",
               # the repository owner, not OWNER (the owner-*actor* config, empty
               # when GH_AW_ORCH_OWNER is unset)
               "-f", f"o={REPO.split('/', 1)[0]}", "-f", f"r={REPO.split('/', 1)[-1]}",
               "-F", f"n={number}"])
    try:
        data = json.loads(out or "{}")
        pull = ((data.get("data") or {}).get("repository") or {}).get("pullRequest") or {}
        nodes = (pull.get("closingIssuesReferences") or {}).get("nodes") or []
    except (ValueError, TypeError, AttributeError):
        # still cached: fail-closed for this pass, but one query per PR, not one per
        # (PR, story) during a rate-limit episode
        _CLOSES_CACHE[number] = frozenset()
        return _CLOSES_CACHE[number]
    closed = frozenset(n["number"] for n in nodes
                       if isinstance(n, dict) and isinstance(n.get("number"), int))
    _CLOSES_CACHE[number] = closed
    return closed


def _github_closes(pr, num):
    """True when GitHub itself reports that `pr` closes story `num`.

    Only asked for a PR whose body or title names the story at all — `#N` or the
    `/issues/N` URL form (`Fixes https://github.com/o/r/issues/N` carries no
    `#N`, and a pre-filter must never veto the authority it fronts) — so an
    unrelated PR costs no API call."""
    body = pr.get("body") or ""
    title = pr.get("title") or ""
    # every closing form GitHub accepts: `#900`, `GH-900`, a full issue URL
    forms = (f"#{num}", f"/issues/{num}")
    if not any(form in body or form in title for form in forms) \
            and f"GH-{num}".lower() not in (body + title).lower():
        return False
    return num in _github_closing_issues(pr)


def _merged_pr_for_story(num):
    """The merged delivery of story `num` — ADR-0007's closeout record. Two
    shapes count: the agent branch pattern, and GitHub's own closing record
    (`_github_closes`) — a maintainer hand-delivery on `maint/…` carries no
    issue segment, and its `Fixes #N` is the Delivery (#915)."""
    pat = _delivered_branch_pat(num)
    merged = api("pulls?state=closed&per_page=60") or []
    for pr in merged:
        if not pr.get("merged_at"):
            continue
        if pat.search(pr.get("head", {}).get("ref", "")) or _github_closes(pr, num):
            return pr["number"]
    return None


def closeout_healer():
    """ADR-0007 closeout, healed by the conveyor (ADR-0019): an open issue
    whose agent-delivery PR is merged to main IS delivered — close it.
    Heals 12-post-merge safe-output failures AND ends the re-stamp loop:
    delivered issues must never re-arm (the [aw] post-merge re-stamp
    pattern, backlog-report #797). The merge itself is the human/machinery
    verdict; closing here executes it, same carve-out as 12-post-merge.
    Enumeration is ALL open issues, not just story-numbered ones (#863
    follow-up: #809/#781/#777 carry no S<n> prefix in their titles and
    were invisible to the story filter — the straggler class persists
    whenever 12-post-merge fails, which is exactly when this heals)."""
    _closes_cache_reset()
    if not OWNER:
        return
    open_issues = [i for i in (api("issues?state=open&per_page=100") or [])
                   if not i.get("pull_request")]
    for i in open_issues:
        if str(i.get("state", "")).lower() != "open":
            continue
        n = i["number"]
        merged = _merged_pr_for_story(n)
        if not merged:
            # Fallback-receipt closeout (ADR-0020 class, #1282): a story whose
            # delivery was routed to a fallback review issue #N closes out
            # once the carrier itself closes (delivered/reviewed) — same
            # ADR-0007 semantics as the merged-PR path.
            receipt = _fallback_delivery(n)
            if not receipt:
                continue
            fb_at, fb_num = receipt
            if _rearmed_after(n, fb_at):
                print(f"[orchestrator] {n}: deliberately re-armed after fallback receipt #{fb_num} — closeout skipped")
                continue
            fb = api(f"issues/{fb_num}") or {}
            if str(fb.get("state", "")).lower() != "closed":
                continue
            post_comment(n, f"{MARK} delivered — fallback review issue #{fb_num} closed; closing out.")
            close_issue(n, "completed", f"delivered via fallback review issue #{fb_num}")
            print(f"[orchestrator] {n}: closed out (fallback review issue #{fb_num} closed)")
            continue
        if _stamped_after_merge(n, (api(f"pulls/{merged}") or {}).get("merged_at")):
            print(f"[orchestrator] {n}: deliberately re-armed after merge — closeout skipped")
            continue
        post_comment(n, f"{MARK} delivered — PR #{merged} merged; closing out (ADR-0007, "
                        f"closeout-healed after the 12-post-merge miss recorded in #797).")
        close_issue(n, "completed", f"delivered via PR #{merged}")
        print(f"[orchestrator] {n}: closed out (PR #{merged} merged)")


# ADR-0020 protected-files receipt: gh-aw's deterministic safe-output NOTE
# stamped on a story when the delivery could not become a PR and was routed
# to a fallback review issue instead. The linked issue #N is the delivery
# carrier; the receipt is machine-readable state (not prose to ignore).
FALLBACK_REVIEW_RE = re.compile(
    r"fallback review issue was created .*?due to protected .{0,60}?changes?",
    re.I | re.S)
FALLBACK_LINK_RE = re.compile(r"\[#(\d+)\]")


def _fallback_delivery(num, cs=None):
    """The machinery fallback-delivery receipt on story `num` (ADR-0020
    protected-files class, live instance #1264→#1271 recorded in #1282).
    Returns (receipt_ts, carrier_issue_number) or None. Receipts are
    matched narrowly to the gh-aw NOTE template; a stray over-match is
    fail-closed for the delivered-guard (no re-fire), never a lost fire."""
    for c in reversed(cs if cs is not None else comments(num)):
        body = c.get("body") or ""
        if FALLBACK_REVIEW_RE.search(body):
            link = FALLBACK_LINK_RE.search(body)
            if link:
                return (c.get("created_at") or "", int(link.group(1)))
    return None


def _open_pr_for_story(num):
    """An open Delivery of story `num` — same two shapes as the merged arm: a
    Delivery exists in both states, so a shape recognised in one is recognised
    in the other (#915/#1358)."""
    pat = _delivered_branch_pat(num)
    for pr in open_pulls():
        # `_fixable_pr` is the trust anchor for BOTH routes: a stranger's fork PR can
        # be named `devloop/issue-N-evil` as easily as it can say `Fixes #N`, and
        # either would stall the conveyor while it stays open (#1380 rounds 2-3).
        # Real deliveries are agent PRs or owner hand-deliveries, both `_fixable_pr`,
        # and `05-implement` creates them with `draft: false`, so no delivery drops.
        if _fixable_pr(pr) and (pat.search(pr.get("head", {}).get("ref", ""))
                                or _github_closes(pr, num)):
            return pr["number"]
    return None


def _stamped_after_merge(num, merged_at):
    """Deliberate human re-arm: status/planned applied by the OWNER after
    the story's delivery merged. A human stamp overrides the merged-guard
    ("run the work again") — machinery re-stamps never do."""
    return _rearmed_after(num, merged_at)


def _rearmed_after(num, ts):
    """Deliberate human re-arm after delivery evidence timestamp `ts`:
    status/planned applied by the OWNER after `ts` (merged PR or fallback
    receipt). A human stamp overrides a delivered-guard ("run the work
    again") — machinery re-stamps never do (the #797 wave and the
    16:36:11Z re-stamp of #1282 were both bot-applied). Generic overload
    of _stamped_after_merge for both delivery-evidence classes."""
    if not ts:
        return False
    latest = None
    for e in api(f"issues/{num}/events?per_page=100") or []:
        if e.get("event") == "labeled" and (e.get("label") or {}).get("name") == "status/planned":
            latest = e
    if not latest:
        return False
    return ((latest.get("created_at") or "") > ts
            and (latest.get("actor") or {}).get("login") == OWNER)


def _fire_implement(num):
    """Stamp status/planned (display state) + fire Implement by dispatch —
    a bot-applied label event is loop-dead, so the dispatch IS the fire.
    Delivered-guard: a story delivered via an open OR merged PR is never
    re-fired (the duplicate-PR class, ADR-0014's 'cannot tell delivered
    from next-in-line'; the merged case drove the re-stamp loop of #797)."""
    delivered = _open_pr_for_story(num)
    if delivered:
        print(f"[orchestrator] {num}: already delivered via PR #{delivered} — not re-fired")
        return
    merged = _merged_pr_for_story(num)
    if merged:
        if _stamped_after_merge(num, api(f"pulls/{merged}").get("merged_at")):
            print(f"[orchestrator] {num}: human re-arm after merged PR #{merged} — re-delivering")
        else:
            print(f"[orchestrator] {num}: already delivered via merged PR #{merged} — not re-fired")
            return
    receipt = _fallback_delivery(num)
    if receipt:
        fb_at, fb_num = receipt
        if _rearmed_after(num, fb_at):
            print(f"[orchestrator] {num}: human re-arm after fallback delivery #{fb_num} — re-delivering")
        else:
            # #1282/#1264: the machinery re-stamped status/planned 4 minutes
            # after the lane's in-review receipt and re-fired — the receipt
            # IS the delivery record; only a human re-arm overrides it.
            print(f"[orchestrator] {num}: already delivered via fallback review issue #{fb_num} — not re-fired")
            return
    label_issue(num, "status/planned")
    _fire_workflow("05-implement.lock.yml", "issue", num)


def _plan_round_state(n, rnd):
    """fresh / served / in-flight / stranded for a plan round (#887 r1).
    - fresh: no fire marker
    - served: the round's own OUTPUT comment ('Plan round N —', the
      delta-16 heading contract) posted after the marker — per-issue and
      exact; also covers the owner's manual /plan (same heading)
    - in-flight: plan dispatch runs exist after the marker with one
      queued/in_progress (repo-wide listing, conservative: any recent
      dispatch defers one sweep — false in-flight delays a heal, false
      served would never heal)
    - stranded: marker but no output and no live run — the dead-channel
      class (#797 r3, #847 r2, #796 r2, #864 r1)"""
    cs = comments(n)
    mk = _fire_marker(rnd)
    mkc = [c for c in cs if mk in (c.get("body") or "")]
    if not mkc:
        return "fresh"
    after = mkc[-1]["created_at"] or ""
    out_c = _round_comment(cs, rnd)
    if out_c and (out_c.get("created_at") or "") > after:
        return "served"
    out = run(["gh", "run", "list", "--workflow", "04-plan.lock.yml",
               "--limit", "100", "-R", REPO,
               "--json", "databaseId,status,createdAt,event"])
    try:
        rows = json.loads(out or "[]")
    except Exception:
        return "in-flight"   # undeterminable -> never double-fire
    # Both fire channels count: the surrogate's dispatch AND a live run
    # from the owner's manual /plan comment (issue_comment event) — a
    # manual unstrand in flight must not be double-fired (#887 r2
    # blocker). --limit 100: the 30 cap evicted the real row on chatty
    # threads (29/30 chaff observed live).
    recent = [r for r in rows
              if r.get("event") in ("workflow_dispatch", "issue_comment")
              and (r.get("createdAt") or "") > after]
    if not recent:
        return "stranded"
    if any((r.get("status") or "") in ("queued", "in_progress") for r in recent):
        return "in-flight"
    return "stranded"   # all terminal: denied/skipped/failed/walled


def _fire_plan(n, rnd):
    """Record the round marker (dedupe + audit trail; bot identity) and
    fire the plan loop by dispatch with the round carried by aw_context."""
    state = _plan_round_state(n, rnd)
    if state in ("served", "in-flight"):
        return False
    if state == "stranded":
        print(f"[orchestrator] {n}: round {rnd} stranded (marker, no output, no live run) — re-firing")
    else:   # fresh
        post_comment(n, _fire_marker(rnd) + _FIRE_SUFFIX.get(rnd, ""))
    _fire_workflow("04-plan.lock.yml", "issue", n)
    print(f"[orchestrator] {n}: /plan round {rnd} fired (dispatch)")
    return True


_FIRE_SUFFIX = {
    1: " (fresh trigger; ADR-0019 machine relay)",
    2: " (round-1 answers received; ADR-0019 machine relay)",
    3: " (finalize; round-2 approved; ADR-0019 machine relay)",
}


# ------------------------------------------------- plan-loop surrogate (/plan)
def _decl_probe(story):
    m = BRANCH_DECL_RE.search(_strip_md(story.get("body") or ""))
    return int(m.group(1)) if m else None


def _branch_pending(story, stories, comments_fn):
    """True when `story` is a conditional branch (declaration present) whose
    probe verdict is unresolved: the branch executor owns it, so a manual
    ai-fix stamp must not fire it directly (direct-implement bypass guard,
    #897). The probe is resolved by the declaration's own #N link (same
    resolver as the decision layer — never 'lowest-numbered sibling')."""
    probe_n = _decl_probe(story)
    if not probe_n:
        return False
    verdict, _v = _probe_verdict(comments_fn(probe_n))
    return verdict is None


def plan_loop_surrogate():
    _closes_cache_reset()
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
        # ADR-0021 (#839): a finalized story of an already-approved parent
        # chain is planned — fire Implement directly instead of re-running
        # the plan loop. Idempotence: _fire_implement stamps status/planned;
        # an IN_FLIGHT label present means a fire is already out the door.
        pnum_i = parent_of(i)
        if fresh_trigger and pnum_i and _chain_approved(pnum_i) \
                and "status/planned" not in labels \
                and "status/in-review" not in labels:
            if _branch_pending(i, stories, lambda pn: api(f"issues/{pn}/comments?per_page=100") or []):
                print(f"[orchestrator] {n}: conditional branch of probe "
                      f"#{_decl_probe(i)} — verdict unresolved; NOT fired "
                      "directly (branch-executor gate, #897)")
                continue
            _fire_implement(n)
            print(f"[orchestrator] {n}: parent #{pnum_i} chain approved — "
                  "implement fired directly (plan loop skipped)")
            continue
        if fresh_trigger and not r1:
            # A label that lands while the fix lane is already shipping is not a reason to
            # plan: /plan round 1 fired nine minutes after review stamped status/in-review
            # with the delivery PR waiting (#1358). Guarded on delivery existence, not on
            # labels — status/queued is both an IN_FLIGHT label and ADR-0018's trigger stamp,
            # so a label guard would silence planning for every fresh story.
            # All three delivery records `_fire_implement` honours, or the #1358 race
            # stays live on the protected-files path: a fix that gh-aw routes to a fallback
            # review issue (ADR-0020, #1264→#1271) has neither an open nor a merged PR.
            if _delivery_record(n, cs, labels):
                print(f"[orchestrator] {n}: fresh trigger but a delivery exists — /plan NOT fired")
                continue
            _fire_plan(n, 1)
            continue
        if not r1:
            continue
        # round 2: human answered after round 1
        if not r2 and not kid_stories:
            human_after = [c for c in cs
                           if c.get("user", {}).get("login") == OWNER
                           and MARK not in (c.get("body") or "")
                           and c["created_at"] > r1["created_at"]]
            if human_after:
                _fire_plan(n, 2)   # dedupe/heal lives inside _fire_plan now
        # round 3: owner approved after round 2
        if r2 and not kid_stories and not r3:
            appr = [c for c in cs if c.get("user", {}).get("login") == OWNER
                    and c["created_at"] > r2["created_at"]
                    and (c.get("body") or "").strip().lower() in ("approved", "approve", "lgtm", "yes", "ok")]
            if appr:
                _fire_plan(n, 3)   # dedupe/heal lives inside _fire_plan now


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
# Diagnostic titles that are transient BY CONSTRUCTION: they name a skipped
# run, not missing work. The daily-credit guardrail fails closed on a GitHub
# API hiccup and gh-aw's own text says so ("the guardrail will retry
# automatically on the next run; no action is required unless the failures
# persist") — four of them (#1542, #1572, #1571, #1582) each needed a manual
# NOT_PLANNED close because this list only knew the first two classes.
TRANSIENT_DIAG_TITLES = ("no safe outputs", "reported incomplete",
                         "could not verify daily AI credits")


def watchdog():
    openi = api("issues?state=open&per_page=50") or []
    ml = []
    for i in openi:
        t = i.get("title", "")
        if i.get("pull_request"):
            continue
        if t.startswith("[aw]") and any(x in t for x in TRANSIENT_DIAG_TITLES):
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


def _registry_candidate(cs):
    """Un-anchored fenced-```json``` providers delta from a bot author —
    the shape agents keep posting when they botch the anchor (#777). Only
    objects with a top-level providers dict qualify; anything else stays
    invisible to the registry (strict parse preserved)."""
    for c in reversed(cs or []):
        body = c.get("body") or ""
        if REGISTRY_ANCHOR in body:
            continue
        # ADR-0020 moved safe-output deliveries to the machinery App, so the
        # legacy login pair missed every unanchored entry it posts — the
        # self-heal was blind to exactly the agent that strands them (#1482).
        if not (_machinery_login(c)
                or (c.get("user") or {}).get("login") in ("github-actions[bot]",
                                                          "github-actions")):
            continue
        m = re.search(r"```json\s*(\{.*?\})\s*```", body, re.S)
        if not m:
            continue
        try:
            obj = json.loads(m.group(1))
        except ValueError:
            continue
        if isinstance(obj, dict) and isinstance(obj.get("providers"), dict):
            return m.group(1)
    return None


# How far back a CLOSED issue is still scanned for a registry entry. An
# entry posted during Implement was unioned only while its story stayed
# open; 12-post-merge closes the story, so a sweep landing after the close
# dropped the entry silently — five of eighteen delivered #1354 stories
# left the registry stale (#1579). A window (not "all closed") keeps the
# scan bounded and the applied-marker keeps it idempotent.
REGISTRY_CLOSED_WINDOW_DAYS = 7
# Bound on the closed-issue scan: the window is 7 days, so deeper pages only
# matter under extraordinary churn. 10 pages = 500 threads.
REGISTRY_CLOSED_MAX_PAGES = 10
REGISTRY_CLOSED_PAGE_SIZE = 50


def _registry_cutoff(now=None):
    """Oldest `closed_at`/`updated_at` still inside the scan window."""
    now = time.time() if now is None else now
    return time.strftime("%Y-%m-%dT%H:%M:%SZ",
                         time.gmtime(now - REGISTRY_CLOSED_WINDOW_DAYS * 86400))


def _registry_issue_set(openi, closedi, now=None):
    """Open issues plus the ones closed inside the window."""
    cutoff = _registry_cutoff(now)
    out = [i for i in (openi or []) if "pull_request" not in i]
    out += [i for i in (closedi or [])
            if "pull_request" not in i and (i.get("closed_at") or "") >= cutoff]
    return out


def _registry_closed_issues(now=None, fetch=None):
    """Closed issues inside the window, across pages.

    The list is `sort=updated&direction=desc` because GitHub has no
    server-side `closed_at` sort — and a single page of that order fills up
    with OLD threads that were merely *updated* recently, dropping the
    just-closed story this arm exists for (#1583 review). Pagination is
    finite and safe: `closed_at <= updated_at`, so the first item older
    than the cutoff proves every remaining page is older too."""
    cutoff = _registry_cutoff(now)
    fetch = fetch or api
    out = []
    for page in range(1, REGISTRY_CLOSED_MAX_PAGES + 1):
        batch = fetch("issues?state=closed&sort=updated&direction=desc"
                      f"&per_page={REGISTRY_CLOSED_PAGE_SIZE}&page={page}") or []
        if not batch:
            break
        out += batch
        if min((i.get("updated_at") or "") for i in batch) < cutoff:
            break
    return [i for i in out
            if "pull_request" not in i and (i.get("closed_at") or "") >= cutoff]


def _registry_entry_date(entry):
    """Latest run date an entry carries ('' when it has no run record)."""
    runs = [r.get("date") for r in ((entry or {}).get("last_runs") or [])
            if isinstance(r, dict) and isinstance(r.get("date"), str)]
    return max(runs) if runs else ""


def _registry_flag_last_deep(entry):
    """Report — never rewrite — a `last_deep` that predates the entry's own
    run date (S15 #1453 posted the PREVIOUS deep date beside `reason: ...
    (S15, 2026-11-23)`).

    A run date proves a RUN, not a deep pass: SKILL.md keeps the sweep
    (Phase 1) apart from the deep stamp (Phase 2), `05-implement.md` lets
    any implement run post an entry, and the registry's own `tier` field
    records the transport ("plain-curl"), not the depth. Advancing
    `last_deep` on that evidence would defer a provider's deep rotation by
    a cycle, silently — the #1354 re-payment inverted (#1583 review
    blocker). The stale stamp needs a corrected entry, which stays a
    human/agent decision; the flag makes it visible instead of silent."""
    if not isinstance(entry, dict):
        return entry
    run_date = _registry_entry_date(entry)
    if not run_date:
        return entry
    stale = sorted(n for n, p in (entry.get("providers") or {}).items()
                   if isinstance(p, dict)
                   and str(p.get("last_deep") or "") < run_date)
    if stale:
        print(f"[orchestrator] registry: {', '.join(stale)} last_deep predates "
              f"the entry's run date {run_date} — flagged, not rewritten "
              f"(a run date does not prove a deep pass)")
    return entry


def _registry_known_issues(doc):
    """Issue numbers the registry on main already records a run for.
    Most closed-window candidates are synced audit stories whose verdict is
    already in the file, and `comments()` per candidate is the sweep's
    dominant API cost — skip those without a fetch (#1583 review).

    The run→issue mapping mostly lives in the NESTED `findings[].issue`
    (44 of 50 `last_runs` records carry no top-level `issue`), so harvesting
    only the top level made landed entries look unrecorded and re-pend them
    over newer data (#1598 review)."""
    nums = []
    for r in (doc or {}).get("last_runs") or []:
        if not isinstance(r, dict):
            continue
        nums.append(r.get("issue"))
        nums += [f.get("issue") for f in (r.get("findings") or [])
                 if isinstance(f, dict)]
    nums += [p.get("issue")
             for p in ((doc or {}).get("providers") or {}).values()
             if isinstance(p, dict)]
    return {n for n in nums if isinstance(n, int)}


def _registry_union(base, entry):
    """Union one entry into the registry — newest wins PER PROVIDER.

    16-f6b shape: list items append, entry scalars win. That is right for a
    fresh entry and wrong for a replay older than the file, so each provider
    is settled on its own evidence: when the file's `last_deep` is NEWER than
    the entry's run date, the file keeps the provider. A blanket direction
    (always replay, always overwrite) drops a genuinely newer payload or
    reverts a later pass (#1598 review)."""
    merged = _union_findings(base, entry)
    run_date = _registry_entry_date(entry)
    if not run_date:
        return merged
    cur_provs = (base.get("providers") or {}) if isinstance(base, dict) else {}
    for name, p in ((entry or {}).get("providers") or {}).items():
        cur = cur_provs.get(name)
        if not isinstance(p, dict) or not isinstance(cur, dict):
            continue
        cur_deep = str(cur.get("last_deep") or "")
        # The file keeps the provider when its own `last_deep` beats the
        # entry's RUN date. An entry may also carry a STALE `last_deep`
        # (`_registry_flag_last_deep` reports that shape, never rewrites it),
        # so adopting the entry wholesale would rewind a deep pass the file
        # already recorded — the #1354 re-payment. Keep the newer date and
        # take the entry's verdict/reason: its run is the newer evidence.
        ent_deep = str(p.get("last_deep") or "")
        if cur_deep > run_date:
            merged["providers"][name] = cur
        elif cur_deep > ent_deep:
            settled = dict(merged["providers"].get(name) or p)
            settled["last_deep"] = cur_deep
            merged["providers"][name] = settled
    return merged


def _registry_apply(base, pend):
    """Union every pending entry. Returns (document, changed).

    `changed` is what keeps a demoted marker from opening an empty registry
    PR on every sweep: an entry the file already holds unions to itself."""
    merged = base
    for _, e in pend:
        merged = _registry_union(merged, e)
    return merged, merged != base


def _registry_pend_order(pend):
    """Oldest first: `_union_findings` lets b win scalars, so the NEWEST
    entry must be applied last. The widened scan mixes open and just-closed
    threads and the issues API is newest-first on both arms, so applying in
    scan order let an older closed-window entry overwrite a newer
    `last_deep`/`reason` (#1583 review). Entries without a run record sort
    by issue number, which is creation order."""
    return sorted(pend, key=lambda ne: (_registry_entry_date(ne[1]), ne[0]))


def _registry_pending(issues, fetch_comments=None, applied=None):
    """[(issue number, entry)] in apply order, stale stamps flagged.

    One append path for anchored entries and for the self-heal repost, so a
    healed entry goes through the same checks as an anchored one — the
    unanchored shape was exactly the #1439 case that kept a stale
    `last_deep` (#1583 review).

    The `applied` marker is written when the registry PR is OPENED, not when
    it lands, so a registry PR that later conflicts against a newer one
    strands its entry while its issue reads as done — #1581 (run1457) and
    #1587 (run1458) both went CONFLICTING/DIRTY when #1595 rewrote the file,
    and the sweep would never look at them again. The registry itself is the
    evidence: skip only when the entry is actually recorded there (issue
    number in `applied`, from `_registry_known_issues`).

    A marker without a recorded entry means the PR that carried it died: the
    entry re-pends, and `_registry_union` settles per provider whether its
    scalars are newer than the file's (#1598 review)."""
    read = fetch_comments or comments
    applied = applied or frozenset()
    pend = []
    for i in issues:
        # The registry is the evidence: an issue whose entry is recorded needs
        # neither a re-post of its anchor (#777 self-heal) nor a union.
        if i["number"] in applied:
            continue
        cs = read(i["number"])
        e = _registry_entry(cs)
        if not e:
            # Self-heal the recurring agent-formatting defect (#777, three
            # strandings): a fix-loop/review agent posts the fenced ```json```
            # providers delta WITHOUT the REGISTRY_ANCHOR line, while its own
            # fix report claims it is anchored — _registry_entry can never
            # see it. Repost the candidate verbatim, anchored, as the owner.
            # The posted comment is a real anchored entry; the union below
            # makes any later repost a no-op.
            cand = _registry_candidate(cs)
            if not cand:
                continue
            post_comment(i["number"],
                         f"{REGISTRY_ANCHOR}\n```json\n{cand}\n```")
            e = _registry_entry(read(i["number"]))
            if not e:
                continue
        pend.append((i["number"], _registry_flag_last_deep(e)))
    return _registry_pend_order(pend)


def registry_sync():
    """ADR-0013: agent diffs carry no registry. The agent posts its
    would-be registry entry as an add-comment starting with
    REGISTRY_ANCHOR and a fenced ```json``` object; this step unions it
    into GH_AW_REGISTRY_PATH on main (owner actor).

    Dedupe is by EVIDENCE, not by the marker comment: an issue the registry
    already records is skipped, and one it does not is re-pended however its
    thread reads (the marker is written when the PR opens, so a PR that later
    conflicts strands its entry, #1581/#1587). A corrected same-issue entry
    therefore re-applies on the next sweep, as long as the registry does not
    record it yet — `applied` skips before the thread is read. No
    marker-clearing ritual, and the append-only union keeps the retry
    idempotent."""
    if not REGISTRY_PATH:
        return  # feature off for this adopter
    # ADR-0019: no PAT needed — the push rides the checkout's actions token
    # (contents: write); the commit author is the conveyor identity.
    # The registry on main is read FIRST: it is the union base and the
    # pre-empt list for the closed arm (below).
    if _pl(["git", "fetch", "origin", "main"])[0] > 1:
        return
    _, cur_body = _pl(["git", "show", f"origin/main:{REGISTRY_PATH}"])
    try:
        merged = json.loads(cur_body or "{}")
    except Exception as e:
        sys.stderr.write(f"[orchestrator] {REGISTRY_PATH} unparsable on main: {e}\n")
        return
    known = _registry_known_issues(merged)
    pend = _registry_pending(
        _registry_issue_set(
            api("issues?state=open&per_page=60"),
            [i for i in _registry_closed_issues() if i["number"] not in known]),
        applied=known)
    if not pend:
        return
    applying = [n for n, _ in pend]
    merged, changed = _registry_apply(merged, pend)
    if not changed:
        # Every entry was already in the file (the marker is a cache, so a
        # landed-but-unrecorded one re-pends): a no-op union must not open an
        # empty registry PR every sweep.
        return
    # The file's own freshness field was frozen at the pre-run date, so
    # nothing in the registry recorded that any union happened (#1579).
    merged["updated"] = time.strftime("%Y-%m-%d", time.gmtime())
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
                               f"#{', #'.join(str(n) for n in applying)} "
                               "(orchestrator registry_sync, ADR-0013)"])
        # Branch protection on main (7 required status checks, 2026-10-21)
        # kills both direct paths: GITHUB_TOKEN cannot push main, and a
        # commit synthesized inside this run never had a push event so its
        # checks stay "expected" — the PATCH endpoint refuses it (runs
        # 39607504429, 39610433015). PRs are the surviving route: required
        # checks fire/skip on the PR head, and squash auto-merge lands when
        # green (no review requirement on main).
        branch = f"orch-registry/{int(time.time())}"
        ok, out = _git_push(branch, commit)
        if ok:
            pr_out = run(["gh", "pr", "create", "--base", "main",
                          "--head", branch,
                          "--title", "[registry] apply findings entries "
                          f"from #{', #'.join(str(n) for n in applying)}",
                          "--body", f"Mechanical union of anchored registry "
                          f"entries into `{REGISTRY_PATH}` (ADR-0013 — the "
                          f"registry never travels in an agent diff). "
                          f"Auto-merge armed; purely mechanical payload."])
            pm = re.search(r"pull/(\d+)", pr_out or "")
            if pm:
                run(["gh", "api", "-X", "PUT",
                     f"repos/{REPO}/pulls/{pm.group(1)}/auto-merge",
                     "-f", "merge_method=squash"])
            else:
                ok = False
        if ok:
            for n in applying:
                post_comment(n, f"{MARK} registry entry queued for `{REGISTRY_PATH}` via "
                                f"registry PR. Landing is NOT proof: the next sweep "
                                f"re-pends this entry if the registry does not record "
                                f"it (the marker is a cache). The registry never "
                                f"travels in an agent diff (ADR-0013).")
            print(f"[orchestrator] registry_sync applied entries from {applying} -> PR branch {branch}")
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
    if rc == 1:
        print(f"[orchestrator] merge-tree rc=1 for {branch}: {sorted(names)}")
        return names
    if rc == 0:
        return frozenset()
    # rc>=2 is a git error / missing ref / shallow checkout: the conflict
    # state is UNKNOWN. Returning an empty set here read as "conflict gone",
    # which deleted status/conflict and skipped the 14-rebase handoff on a
    # conflict that was still live.
    print(f"[orchestrator] merge-tree rc={rc} for {branch}: conflict state unknown")
    return None


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
        # Same number, differing trailing bump-comment: the bodies are
        # identical apart from this line and the numbers match, so either
        # pick is content-lossless — main (ours) wins. Holding this for a
        # human stalled the #845/#780/#779 rebases (every fleet bump that
        # ties a provider fix deadlocks the conveyor).
        return a
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
    pulls = [pr for pr in open_pulls() if _agent_pr(pr)]
    label_map = _pr_labels_map([p["number"] for p in pulls])
    for pr in pulls:
        num = pr["number"]
        branch = pr.get("head", {}).get("ref", "")
        if not branch:
            continue
        # Labeled but no longer conflicted? The Actions GITHUB_TOKEN sees
        # mergeable_state=unknown forever (actor-cold mergeability cache),
        # so the truth is a LOCAL merge-tree against main — no GitHub cache
        # involved. Clears the label when the conflict is genuinely gone
        # (#851-853: label outlived the resolution).
        if "status/conflict" in label_map.get(num, set()):
            _pl(["git", "fetch", "origin", branch])
            files = _conflict_files(branch)
            if files is None:
                continue          # merge-tree unreadable: never read as "gone"
            if not files:
                rc = run_rc(["gh", "api", "-X", "DELETE",
                             f"repos/{REPO}/issues/{num}/labels/status%2Fconflict"])
                print(f"[orchestrator] PR #{num}: conflict gone per local "
                      f"merge-tree — status/conflict label cleared" if rc == 0 else
                      f"[orchestrator] PR #{num}: conflict gone but label "
                      f"clear rc={rc} — retry next sweep")
                continue
        d = api(f"pulls/{num}") or {}
        mstate = (d.get("mergeable_state") or "").lower()
        # "unknown" is the actor-cold mergeability cache, not "no conflict":
        # GITHUB_TOKEN reads it indefinitely, so a strictly-dirty gate means
        # the conflict is never even looked at (PR #1466 sat conflicted across
        # sweeps while every log line only reported readiness). Fall through
        # to the local merge-tree — which is cache-free — and let it decide.
        if mstate not in ("dirty", "unknown"):
            continue
        _pl(["git", "fetch", "origin", branch])
        files = _conflict_files(branch)
        if files is None:
            # merge-tree errored (missing ref, shallow checkout). That is the
            # same "unreadable" shape the NB below keeps on the handoff path —
            # a dirty PR must still reach 14-rebase, never fall through in
            # silence. Only the label-clear probe above may treat it as "keep
            # the label and retry".
            files = frozenset()
        if not files and mstate == "unknown":
            continue          # cache-free read says clean
        # NB: "dirty" with an empty file set is NOT a no-op — that is the
        # unreadable-merge-tree case, and it keeps the handoff path below.
        if files and all(f == FINDINGS_PATH
                         or any(f.endswith(suf) for suf in REGISTRY_VERSION_SUFFIXES)
                         for f in files):
            if _auto_resolve_conflict(branch):
                # The merge commit went out with the checkout's GITHUB_TOKEN,
                # so no `synchronize` event fires (loop prevention). That
                # leaves a head newer than the verdict with no review run and
                # no repair, and `_branch_moved_after` then reads the blocker
                # as served — silently stranded. Re-review the new head
                # explicitly (dispatch is the documented exception).
                #
                # A failed dispatch must not end in silence: the head moved,
                # so nothing else re-reads this PR until a human notices.
                # Retry, then page.
                fired = False
                for attempt in range(3):
                    fired = _fire_workflow("06-review.lock.yml", "pull_request", num)
                    if fired:
                        break
                    time.sleep(2 * (attempt + 1))
                if not fired:
                    post_comment(num,
                                 f"{MARK} mechanical conflict resolve pushed "
                                 f"`{branch}`, but the review re-dispatch "
                                 f"failed (3 attempts) — this head has no "
                                 f"review. @{OWNER}: re-run `/review` or "
                                 f"re-apply the blocker verdict.")
                continue
        # ADR-0019: the handoff needs no owner identity — the label is
        # display state and the fire is a workflow_dispatch (bot lane,
        # verified live for this trigger shape).
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
        # list form, no shell: pass the JSON verbatim (no percent-encoding —
        # the lock's fromJSON expects raw JSON, round-2 review blocker).
        ok = dispatch_ok(["gh", "workflow", "run", "14-rebase.lock.yml",
                          "--ref", "main", "-R", REPO,
                          "-f", f"pr_number={num}",
                          "-f", f"aw_context={_ctx}"])
        print(f"[orchestrator] PR #{num}: 14-rebase dispatch {'fired' if ok else 'FAILED'}")
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
        num_story = story_num(s)
        if not num_story or num_story <= 1:
            continue
        labels = [l["name"] for l in s.get("labels", [])]
        if "status/planned" not in labels:
            continue
        if str(_story_state(num_story - 1, stories, parent_of(s))).lower() == "closed":
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


def _latest_verdict(thread):
    """(body, created_at) of the newest 06-review verdict comment, or ("", "").

    A verdict is recognised by its heading ("## Review — …", "**Verdict:** …"),
    never by a mention: the work-attempt stamp also says "06-review round 1",
    and reading it as the verdict shadows the blocker it points at (#1420's
    shape, again, on PR #1466).
    """
    for c in reversed(thread):
        login = (c.get("user") or {}).get("login")
        body = c.get("body") or ""
        # ADR-0020 step 3: once 06 posts through the machinery App the verdict
        # arrives as <slug>-machinery[bot], not github-actions[bot]. But the
        # App also posts 07's repair narration, whose prose matches the
        # blocker predicate — so the App arm additionally requires the
        # structural marker (#1411/#1420/#1466 shadowing shape).
        if login != "github-actions[bot]" and not (
                _machinery_login(c) and VERDICT_MARKER.search(body)):
            continue
        if "fix-loop re-fired for the verdict at" in body:
            continue
        # The gate admits every verdict shape (above); the predicate decides
        # only when the shape is unrecognised. The single bot comment that is
        # NOT a verdict is 06-review's work-attempt stamp — unless the verdict
        # is embedded in it (#1411 posted stamp and verdict combined).
        is_stamp = body.lstrip().startswith("gh-aw budget:")
        if is_stamp:
            # The stamp's own summary line ("Review round at head `sha` …;
            # blocker on …") matches the free-form hint, so at face value it
            # shadows the verdict it points at (PR #1511: blocker 21:34:13,
            # stamp 21:34:14, every later sweep read "no blocker"). Only the
            # structural shapes count when the stamp IS the verdict (#1411).
            if not (VERDICT_MARKER.search(body) or _blocker_verdict(body.lower())):
                continue
            return body, c.get("created_at", "")
        if VERDICT_HINT.search(body) or _blocker_verdict(body.lower()):
            return body, c.get("created_at", "")
    return "", ""


def _branch_moved_after(num, iso_ts):
    """True when PR `num`'s head commit is newer than `iso_ts`.

    Pushing is one way a repair consumes a verdict — the one visible here.
    ADR-0013 also allows a comment-only repair (registry entry lands on the
    story issue, head untouched); `_repair_consumed()` is the second signal
    for exactly that shape, and it is where abort handling now lives. This
    function no longer claims to be the only consumption test.

    Reads the head commit, not the commit list: `pulls/{n}/commits` pages
    at 100 and a long-lived PR would never look served.

    Ceiling: compares commit dates, not the sha; a rewrite that keeps
    older dates would read as unserved (re-fires once more, never wrong
    about having pushed).
    """
    if not iso_ts:
        return False
    pr = api(f"pulls/{num}")
    if pr is None:
        return True          # unreadable: skip the sweep, never re-fire blind
    head = (pr.get("head") or {}).get("sha") or ""
    if not head:
        return True          # unexpected payload: same fail-closed rule
    raw = api(f"commits/{head}")
    if raw is None:
        return True          # same fail-closed reason
    commit = raw.get("commit") or {}
    dates = [((commit.get(k) or {}).get("date") or "")
             for k in ("committer", "author")]
    return max(dates or [""]) > iso_ts


ABORT_STAMP = "ABORT (Rule 0"
RUN_URL_RE = re.compile(r"/actions/runs/(\d+)")
_BRANCH_STORY_RE = re.compile(r"(?:issue-|/)(\d+)(?:-|$)")


def _pr_owner_issue(pr):
    """Owner/story issue a PR delivers, or None when not derivable.

    The branch's `issue-N-` segment is the delivery shape; an owner
    hand-delivery on `maint/…` carries none, so fall back to the story GitHub
    itself reports this PR closing (`closingIssuesReferences`, via
    `_github_closing_issues`). That field — not a prose parse — is the
    repo's settled Delivery contract (#1379); a looser regex here derived
    the *symptom* PR from prose (`Fixes why #1482 …` → #1482) and then read
    another PR's thread as this one's evidence (#1485 review round 2).

    Ambiguous (several closing stories) or absent → None: Rule 0 then stamps
    the PR itself, which `_repair_consumed()` already reads, so abort
    detection still works without guessing.

    Rule 0 posts an abort stamp on THIS issue — never on the PR — so finding
    it is what makes abort detection see the real timeline."""
    ref = (pr.get("head") or {}).get("ref") or ""
    m = _BRANCH_STORY_RE.search(ref)
    if m:
        return int(m.group(1))
    closed = _github_closing_issues(pr)
    return next(iter(closed)) if len(closed) == 1 else None


def _run_ids(threads, abort_only=False):
    """Run ids whose own safe-output landed on these threads.

    Every gh-aw comment carries its run link — `run=<url>` on a budget stamp,
    `run: <url>` in the agent footer — and a Rule 0 abort carries `Run: <url>`.
    That link is the PR identity a repo-wide `gh run list` lacks: a run for a
    different PR never appears on this PR's (or its owner issue's) timeline.
    A Rule 0 stamp names its own run ("this run's URL"), so `abort_only`
    returns the runs whose own stamp is an abort — never "this PR aborted at
    some point" (an earlier abort must not suppress a later repair)."""
    ids = set()
    for thread in threads:
        for c in thread or ():
            body = c.get("body") or ""
            if abort_only and ABORT_STAMP.lower() not in body.lower():
                continue
            ids.update(int(x) for x in RUN_URL_RE.findall(body))
    return ids


def _repair_consumed(num, iso_ts, cs=None, pr=None):
    """True when a repair run for THIS PR consumed the verdict without pushing.

    ADR-0013 keeps the findings registry OUT of agent diffs: a registry fix
    travels as an anchored comment the orchestrator unions into main, so the
    PR's head never moves and `_branch_moved_after()` cannot see the work
    (#1482: two full repair rounds, zero pushes, then the marker cap applied
    status/fixing-stuck to a repaired PR).

    A run counts only with PR identity: its own safe-output must appear on
    this PR's thread (or its owner issue's — a registry entry or abort stamp
    lands there), and it must be created after this verdict's fire marker.
    `gh run list` is repo-wide, so without that identity another PR's repair
    would mark this verdict served (#1485 review). An earlier abort stamp
    aborts only the run it names.

    Only `agent == success` counts: `failure` / `cancelled` / `timed_out` /
    unfinished never reach `safe_outputs`, so they are no-push, no-comment
    shapes, not consumption (07's shared cancel-in-progress group makes
    cancellation a real race, not an exotic one).

    `--limit 15` is a bounded window: if >15 repair runs land between the
    verdict and a later sweep the consuming run can fall out and the verdict
    re-fires (false negative — one extra round, never a stranded verdict).

    An unreadable run list or jobs payload is "unknown", not "not consumed":
    it skips this sweep (returns True), the same fail-closed rule
    `_repair_in_flight()` uses one line away in the caller. Re-firing blind on
    a transient 403 is what starts the second dispatch that ends in
    status/fixing-stuck on an already-repaired PR.

    Known operating cost: consumption is silent and permanent — no push means
    no `synchronize`, so 06-review does not re-fire until a human types
    `/review` (the PR body plans exactly that for #1482).
    """
    if not iso_ts:
        return False
    cs = comments(num) if cs is None else cs
    marker = f"{MARK} fix-loop re-fired for the verdict at {iso_ts}"
    fired = [c.get("created_at") or "" for c in cs
             if marker in (c.get("body") or "")]
    # No marker means this PR was never dispatched for this verdict; a later
    # fire's marker must not let an older run read as consumption.
    after = max(fired) if fired else iso_ts
    owner = _pr_owner_issue(pr) if pr else None
    threads = [cs]
    if owner and owner != num:
        # `since` keeps a long-lived story issue brief: only comments updated
        # after the verdict can belong to this repair round.
        owned = api(f"issues/{owner}/comments?per_page=100&since={quote(iso_ts)}")
        if owned is None:
            # Unreadable is unknown, not "not consumed" — the same rule the
            # run-list read below applies. Failing open here re-fires a repair
            # that already landed as a comment (#1482 shape) and ends in
            # status/fixing-stuck on a repaired PR.
            return True
        threads.append(owned)
    evidence = _run_ids(threads)
    aborted = _run_ids(threads, abort_only=True)
    out = run(["gh", "run", "list", "--workflow", "07-fix-loop.lock.yml",
               "--limit", "15", "--json", "databaseId,createdAt"]).strip()
    # An empty payload is a failed `gh` call, not an empty list (the command
    # prints `[]`): unknown must never read as "no consuming run".
    try:
        rows = json.loads(out) if out else None
    except ValueError:
        rows = None
    if rows is None:
        return True          # unreadable: skip this sweep, never re-fire blind
    for r in rows:
        rid = r.get("databaseId")
        # `<`, not `<=`: the marker and the run it dispatches share a
        # second-resolution clock, and the run cannot predate its own fire.
        if (r.get("createdAt") or "") < after or rid not in evidence:
            continue
        jobs = api_run_jobs(rid)
        if jobs is None:
            return True      # unknown: same fail-closed rule as above
        if not any(j.get("name") == "agent"
                   and (j.get("conclusion") or "") == "success" for j in jobs):
            continue
        if rid in aborted:
            continue
        return True
    return False


def api_run_jobs(run_id):
    """Jobs of a workflow run (actions read scope). None on any failure.

    `None` is deliberately distinct from `[]`: callers must skip the sweep on
    an unknown payload, never read it as "no agent job ran" (#1485 review)."""
    out = run(["gh", "api",
               f"repos/{REPO}/actions/runs/{run_id}/jobs?per_page=100"])
    if not out.strip():
        return None
    try:
        jobs = json.loads(out).get("jobs")
    except (ValueError, AttributeError):
        return None
    return jobs if isinstance(jobs, list) else None


# gh-aw's own activation gate (check_cooldown.cjs in 07's pre_activation) skips
# the agent when the same workflow completed inside this window. A dispatch
# there still creates a run, still exits `success`, and posts nothing — the
# surrogate's fire marker counts it all the same, and two such markers escalate
# a PR that never had a single attempt (#1540: verdict 15:18:26, markers at
# 15:31:35 `500 s remaining` and 16:08:11 `82 s remaining`, both skipped, then
# status/fixing-stuck).
FIX_LOOP_COOLDOWN_SEC = 600


def _repair_in_flight(now=None):
    """A 07-fix-loop run is still running — or finished too recently to start one.

    The surrogate's fire marker is itself an issue_comment on the PR, so
    95 re-enters the sweep seconds later. Without this guard the second
    pass dispatches again, and the marker cap then escalates a PR that was
    never actually repaired once (#1415 review).

    Same-PR re-entry is the correctness half and survives 07's per-PR group
    (`repair-<N>`, `07-fix-loop.lock.yml`): a re-dispatch for the same PR
    would still cancel the repair that has not pushed yet — the marker cap
    then stamps `status/fixing-stuck` on a PR that was never repaired. The
    guard stays global (repo-wide, every 07 run in the window, not just this
    PR's) as a spend limit too: one repair at a time. Narrowing it to the PR's
    own runs would let N repairs run concurrently — a cost decision, not a
    correctness one.

    The time filter is `FIX_LOOP_COOLDOWN_SEC` and it is not optional: a run
    that finished inside gh-aw's activation cooldown still blocks, because a
    dispatch now cannot start and its fire marker would count as an attempt
    anyway. Callers that treat this as "something is running right now" are
    reading it one notch too literally.
    """
    out = run(["gh", "run", "list", "--workflow", "07-fix-loop.lock.yml",
               "--limit", "10", "--json", "status,updatedAt"]).strip()
    # Fail closed: an unreadable run list must not read as "nothing running".
    # Skipping one sweep costs hours; dispatching over a live repair kills it.
    try:
        rows = json.loads(out or "") if out.strip() else None
    except ValueError:
        rows = None
    if rows is None:
        print("[orchestrator] fix surrogate: cannot read 07-fix-loop runs — "
              "skipping this sweep (never dispatch over a live repair)")
        return True
    now = now or datetime.utcnow()
    for r in rows:
        if (r.get("status") or "") != "completed":
            return True
        # A just-finished run is not "in flight" for concurrency, but it does
        # hold gh-aw's activation cooldown: dispatching now produces a run that
        # cannot start, and the marker it leaves inflates the stuck count.
        try:
            done = datetime.strptime(r.get("updatedAt") or "",
                                     "%Y-%m-%dT%H:%M:%SZ")
        except ValueError:
            # Fail open (a finished run is not evidence of a pending window),
            # but say so: a renamed field would otherwise disable the guard in
            # silence and the false-stuck class comes back with no trace.
            print(f"[orchestrator] fix surrogate: unreadable updatedAt on 07 "
                  f"run — cooldown not applied for it ({r.get('updatedAt')!r})")
            continue
        if (now - done).total_seconds() < FIX_LOOP_COOLDOWN_SEC:
            print(f"[orchestrator] fix surrogate: a 07 run finished "
                  f"{(now - done).total_seconds():.0f}s ago — inside gh-aw's "
                  f"{FIX_LOOP_COOLDOWN_SEC}s activation cooldown, dispatch would "
                  f"be swallowed; holding")
            return True
    return False


# --------------------------------------------------- fix-loop surrogate
def fix_surrogate():
    """GitHub's loop-prevention swallows `pull_request_review` events for
    reviews posted with GITHUB_TOKEN — so agent verdicts never trigger the
    fix loop (observed 2026-10-16: 4 verdicts, zero fix-loop runs).
    ADR-0019: the surrogate fires by workflow_dispatch (GITHUB_TOKEN's
    documented dispatch exception) — no owner-PAT stub review, no owner
    identity. Dedupe via the marker comment on the issue thread; bounded
    by the fix loop's own cycle cap + status/fixing-stuck."""
    # Dispatch registration is asynchronous, so a PR handled later in this
    # same sweep cannot see the run a moment-earlier PR just started — and
    # 07's concurrency group is shared and cancel-in-progress, so that
    # second dispatch would kill the first repair. At most one per sweep.
    dispatched = False
    for pr in open_pulls():
        if not _fixable_pr(pr):
            continue
        num = pr["number"]
        # Verdict source: 06-review posts as an ISSUE comment (safe-output
        # add_comment) — the PR-review submission is optional/absent for
        # GITHUB_TOKEN-authored verdicts, so no review gate here. The last
        # bot comment IS the thread state; when it contains a blocker the
        # fix loop fires, else the PR is pass or already served (the
        # fire marker itself is a later bot comment without "blocker", so
        # it is its own dedupe).
        # NOTE: no status/ready-to-merge label gate — a later review round
        # can find a blocker while the readiness label from an earlier
        # zero-findings round is still applied (#851 round 3: "hold
        # ready-to-merge", blocker outstanding). The last bot comment is
        # the verdict; the label is stale display state.
        cs = comments(num)
        verdict, vtime = _latest_verdict(cs)
        low = verdict.lower()
        if not _blocker_verdict(low):
            continue
        # Dedupe on consumption, and never dispatch over a live repair:
        # the fire marker is an issue_comment, so the sweep re-enters
        # seconds later and a second dispatch would cancel the first
        # (shared cancel-in-progress group).
        fire_marker = f"{MARK} fix-loop re-fired for the verdict at {vtime}"
        markers = sum(1 for c in cs if fire_marker in (c.get("body") or ""))
        # The escalation arm runs before the sweep-local dispatch budget:
        # needing a human is a fact about this PR, not about whether an
        # earlier PR in the same pass already spent the one dispatch. Sitting
        # behind `dispatched` made the arm unreachable whenever any earlier
        # PR had been served — the state that must page a human was the one
        # most likely to stay silent. A live repair still suppresses it (that
        # run may yet resolve the verdict — the guard is repo-wide), as does a
        # served verdict.
        if markers >= 2:
            if (_repair_in_flight()
                    or _branch_moved_after(num, vtime)
                    or _repair_consumed(num, vtime, cs=cs, pr=pr)):
                continue
            plabels = {l.get("name") for l in (api(f"issues/{num}") or {}).get("labels", [])}
            if "status/fixing-stuck" not in plabels:
                # same clearable set as 90-sweeper / 07 step 2 (#1405): an
                # earlier pass's readiness label must not survive a terminal
                # needs-human stamp
                run(["gh", "api", "-X", "POST", f"repos/{REPO}/issues/{num}/labels",
                     "-f", "labels[]=status/fixing-stuck"])
                # then drop what it replaces — the add above is the retry
                # point, so a failure never leaves the item stateless
                for lb in sorted(plabels & {"status/in-review", "status/fixing",
                                            "status/ready-to-merge"}):
                    # 'status/x' carries a '/', which is a path separator in
                    # the REST route — unencoded it 404s and run() only logs
                    run(["gh", "api", "-X", "DELETE",
                         f"repos/{REPO}/issues/{num}/labels/{quote(lb, safe='')}"])
                print(f"[orchestrator] PR #{num}: 2 unserved fix-loop fires — status/fixing-stuck")
            continue
        if (dispatched or _repair_in_flight()
                or _branch_moved_after(num, vtime)
                or _repair_consumed(num, vtime, cs=cs, pr=pr)):
            continue
        post_comment(num, fire_marker + " — dispatch fires 07 (machine relay, ADR-0019)")
        ok = dispatch_ok(["gh", "workflow", "run", "07-fix-loop.lock.yml",
                          "--ref", "main", "-R", REPO,
                          "-f", f"aw_context={json.dumps({'item_type': 'pull_request', 'item_number': num})}"])
        dispatched = dispatched or ok
        print(f"[orchestrator] fix surrogate on PR #{num}: "
              f"{'fired' if ok else 'dispatch FAILED'}")


def _sweep_budget_spent(rows, max_per_hour=None, now=None,
                        min_duration_min=None, window_min=None):
    """True when the workflow_run arm has already spent its sweep allowance.

    Only the `workflow_run` arm consults this — `issues`, `issue_comment`,
    `pull_request`, `pull_request_review` and `schedule` still sweep
    unconditionally, so this is a WAKE-SUPPRESSION threshold, not a global cap.

    Why a count: a review completing is the cheapest wake this lane has and the
    one that fires repeatedly while PRs are active, and every sweep spends ~150
    API calls against the machinery App's hourly budget (GH_TOKEN is the App
    token for the whole process — writes need the owner actor). On 2026-11-23
    the 05:20 sweep logged 544 "rate limit already exceeded" lines and ran
    blind.

    Why a count and not "a sweep ran recently": recency drops the wake that
    matters — on that same day a blocker verdict landed at 16:14:34 while a
    sweep that started 16:05 was still running, so the 16:15:04 wake was
    declined and #1550's repair waited for the next event. A sweep that began
    *after* a wake is unreachable (the wake's event is the run that just
    completed), so event-time reasoning cannot fix it either.

    What the count guarantees: once the allowance is spent, no full sweep runs
    until a counted sweep falls out of the window (a counted run holds its slot
    until `startedAt + 60`, so up to ~38 min of read-staleness). It does NOT
    strand work: `sweep_declined()` still runs `fix_surrogate()` on every
    declined wake, so a fresh blocker verdict is dispatched without waiting for
    an unrelated event — which is exactly what failed on #1564 at 23:35.

    A "sweep" is measured by JOB wall time, not `run_started_at`: in this repo
    `run_started_at == created_at`, i.e. queue time, and a declined wake queued
    9m50s behind a running sweep then did 14 s of work (run 45199527098) —
    counting that as a sweep would let no-ops consume the allowance and then
    hold it (a no-op only ever happens once the allowance is spent, so
    over-counting livelocks the arm).
    """
    if not isinstance(rows, list):
        return False
    ceiling = SWEEP_MAX_PER_HOUR if max_per_hour is None else max_per_hour
    if ceiling <= 0:
        return False
    floor = SWEEP_MIN_DURATION_MIN if min_duration_min is None else min_duration_min
    window = SWEEP_WINDOW_MIN if window_min is None else window_min
    now = now or datetime.utcnow()
    candidates = []
    for r in rows:
        if (r.get("conclusion") or "") != "success":
            continue
        try:
            started = datetime.strptime(r.get("startedAt") or "",
                                        "%Y-%m-%dT%H:%M:%SZ")
        except ValueError:
            continue
        if (now - started).total_seconds() < window * 60:
            candidates.append(r)
    if len(candidates) < ceiling:
        return False          # cheap path: allowance clearly not spent
    # Only now pay for job timings — this is the decision that actually costs
    # a sweep, and the cheap count cannot tell a declined wake from work.
    real = 0
    for r in candidates:
        mins = _job_duration_min(r.get("databaseId"))
        if mins is None:
            # Unknown timing (API error): count it. Blind and late is the one
            # combination that leaves the budget starved for the agent lanes.
            real += 1
        elif mins >= floor:
            real += 1
    return real >= ceiling


def _job_duration_min(run_id):
    """Wall time of the jobs in one run, in minutes; None when unreadable.

    `gh run list`'s `startedAt` is `run_started_at`, which equals `createdAt`
    here — queue time, not work time. The jobs endpoint is the only place the
    real span exists (run 45199527098: 10m05s queued, 14 s of work).
    """
    if not run_id:
        return None
    out = run(["gh", "api",
               f"repos/{REPO}/actions/runs/{run_id}/jobs?per_page=50"])
    try:
        jobs = json.loads(out or "{}").get("jobs") or []
    except (ValueError, AttributeError):
        return None
    total = 0.0
    for j in jobs:
        try:
            s = datetime.strptime(j.get("started_at") or "",
                                  "%Y-%m-%dT%H:%M:%SZ")
            f = datetime.strptime(j.get("completed_at") or "",
                                  "%Y-%m-%dT%H:%M:%SZ")
        except ValueError:
            return None
        total += max(0.0, (f - s).total_seconds())
    return total / 60.0 if jobs else None


def sweep_declined():
    """The hourly ceiling is spent — serve the one thing a wake can carry.

    A full sweep is ~17 steps of App-token reads; that is what the ceiling
    bounds. But declining the whole wake strands a fresh blocker verdict: on
    2026-11-23 the round-2 verdict on #1564 landed at 23:35:11, the wake at
    23:35:50 was declined ("3 sweeps in the last 60 min"), and with no further
    event the PR sat on `status/fixing` for 30+ minutes with no repair
    dispatched. The carrier "next event" is not a schedule.

    `fix_surrogate()` is the step that turns a verdict into a 07 dispatch, and
    it is one thread scan rather than seventeen steps' worth of reads. So a
    declined wake runs exactly that, and nothing else.
    """
    try:
        approve_companion_runs()
    except Exception as e:
        # Approvals are a human decision already made, not a state read the
        # ceiling may skip.
        sys.stderr.write(f"[orchestrator] approvals failed: {e}\n")
    if not _owner_configured():
        print(f"[orchestrator] {SWEEP_MAX_PER_HOUR} sweeps in the last "
              f"{SWEEP_WINDOW_MIN} min — full sweep declined, no owner identity "
              f"so repairs are not dispatched")
        return
    print(f"[orchestrator] {SWEEP_MAX_PER_HOUR} sweeps in the last "
          f"{SWEEP_WINDOW_MIN} min — full sweep declined, repairs only")
    try:
        fix_surrogate()
    except Exception as e:
        sys.stderr.write(f"[orchestrator] fix_surrogate failed: {e}\n")


def _recent_sweep_runs(now=None):
    """`gh run list` bounded by --created, not by --limit.

    A busy hour produces ~86 runs of this workflow (2026-11-23 15:00), so a
    fixed --limit reads a truncated page and undercounts the window it claims
    to count — on that day the gate saw 2 sweeps where the hour held 3, i.e.
    the ceiling did nothing on exactly the shape it exists for. The `--created`
    bound is what makes the count match the window; the limit is only a sanity
    cap above the busiest observed hour.
    """
    now = now or datetime.utcnow()
    # 2x the window: `--created` bounds by created_at while the predicate counts
    # startedAt, so a re-run that reports a later run_started_at must still be
    # on the page (a superset, never a truncated count).
    since = (now - timedelta(minutes=2 * SWEEP_WINDOW_MIN)).strftime("%Y-%m-%dT%H:%M:%SZ")
    out = run(["gh", "run", "list", "--workflow", "95-orchestrator.yml",
               "--created", f">={since}", "--limit", "200",
               "--json", "databaseId,conclusion,startedAt,updatedAt"])
    try:
        return json.loads(out or "[]")
    except ValueError:
        return None


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
    if os.environ.get("GH_AW_SWEEP_EVENT") == "workflow_run":
        # A review completing is the wake this lane needed (#1516); it is also
        # several wakes an hour while PRs are active, and each is a full sweep
        # against one API budget. Collapse them — the sweep re-derives all
        # state, so dropping a wake costs latency, never accuracy.
        if _sweep_budget_spent(_recent_sweep_runs()):
            sweep_declined()
            sys.exit(0)
    steps = {
        "approvals": approve_companion_runs,
        "merge_readiness": merge_readiness,
        "branch_executor": branch_executor,
        "audit_story_closeout": audit_story_closeout,
        "subissue_latcher": subissue_latcher,
        "chain_driver": chain_driver,
        "closeout_healer": closeout_healer,
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
    mutation_steps = {"merge_readiness", "branch_executor", "chain_driver", "closeout_healer", "audit_story_closeout", "subissue_latcher", "queue_to_pipeline",
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
