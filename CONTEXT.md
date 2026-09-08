# Context

CloudStream extension repo (18+ providers). This context covers the AI automation pipeline that turns labeled GitHub issues into reviewed pull requests, built by coding agents in CI.

## Language

### Agents

**Builder**:
A Builder agent run (pi) that takes a labeled issue, scrapes the live site, and produces a pull request. Never merges.
_Avoid_: worker, fixer, build agent

**Reviewer**:
A Reviewer agent run (opencode) that reviews a pull request and may push fix commits to its branch. Independent of the Builder by design — never the same run, never merges.
_Avoid_: critic, checker, self-review

**Agent run**:
One headless agent invocation, bounded by hard caps (max turns, wall-clock timeout). The unit of cost and retry.
_Avoid_: session, job (job = the CI wrapper, not the agent invocation)

**Round**:
One Reviewer pass over a pull request. Bounded: 2 rounds maximum, tracked by a round label. A Round is spent only by a completed pass — a Superseded or failed run spends nothing. Fixes pushed by a Reviewer may trigger a new Round but never a new PR.
_Avoid_: iteration, loop

**Superseded**:
An Agent run cancelled because a newer trigger replaced it (a new push to the PR, a re-fired run). Not a failure: no findings, no Round spent, and the superseding run owns the outcome. Distinct from Blocked (the site refused) and a failure (the models failed).
_Avoid_: cancellation, aborted run

### Triggers & artifacts

**Trigger label**:
A maintainer-applied issue label (`ai-fix`, `ai-new-site`, or `ai-remove-site`) that starts a Builder run. Issues without one are never processed.
_Avoid_: auto label, bot label

**Command**:
A maintainer comment on an issue or PR (`/retry`, `/review`, `/triage`) that the pipeline dispatches. A Command can only re-fire work a Trigger label already created — it never creates work, never spends a Round itself. Issues and PRs alike accept Commands; trigger labels live on issues only.
_Avoid_: slash command, bot command, retrigger

**Re-fire**:
Re-running an Agent run for an existing issue or PR, via a Command. The new run Supersedes the previous one. A Builder Re-fire clears the PR's Rounds (a fresh build gets a fresh review budget); a Reviewer Re-fire still respects the Round cap.
_Avoid_: retrigger, retry (retry is the command name, not the concept)

**Removal**:
A Builder run executing an `ai-remove-site` issue: probe the site for evidence only, delete the provider directory, open a removal PR. The label is the decision — the agent never gates on the probe's outcome; deletion is git-reversible.
_Avoid_: deletion task, teardown (the deliverable is a removal PR, not a direct deletion)

**FINDINGS**:
The ground-truth record of a site probe: exact selectors, endpoints, headers, curl transcripts. Every selector in shipped Kotlin code must exist in FINDINGS evidence. Committed as part of the PR.
_Avoid_: research notes, scrape log

**Verification**:
Live-site checks (via the verify script) proving the code's selectors and stream URLs actually work. Distinct from a **build**, which only proves compilation. A PR requires both.
_Avoid_: testing, validation, smoke test

**Blocked**:
The live site refused the runner (Cloudflare, IP ban). A PR may open as Blocked with an explicit note; the human verifies in-app instead.
_Avoid_: failure (a failure stops the run; Blocked completes it with a caveat)

**Task run**:
An Agent run executing a fully specified issue (labeled `ready-for-agent`) with a generic prompt — the issue body is the task spec. Uses the Builder runtime; may apply non-trigger labels only.
_Avoid_: audit run (an audit is one kind of task run)

**Audit**:
A Task run measuring every provider against its live site on two independent axes: drift (broken selectors/streams vs the site) and Data-completeness (code vs what the site exposes). Each axis reports separately — a drift-OK provider can still be incomplete, and only providers that are drift-OK and incomplete surface as new fix requests.
_Avoid_: health check (that is the Monitor's Drift probe), review

**Drift probe**:
A cheap scheduled per-provider live check (search + one video page + one stream) that detects provider rot. Distinct from a full **FINDINGS** probe: no evidence transcription, verdict-only output. Broken providers surface as triage issues, not automatic fixes.
_Avoid_: monitoring, health check (the report is a Health report; the probe is the action)

**Health report**:
The weekly verdict table the Monitor posts on the `provider-health` tracking issue: per provider, OK / drift / Blocked.
_Avoid_: status update, monitor output

**Triage**:
An agent classification of a new user issue (broken-provider report, new-site request, needs info, duplicate) with a live-site probe behind it. Suggests a trigger label; never applies one.
_Avoid_: labeling, classification

**Model chain**:
The ordered fallback every agent run follows: GLM 5.3 Flash → DeepSeek V4 Flash, both served through the OpenCode Go gateway (paid key) and driven by pi. The Reviewer's primary must come from a different model family than the Builder's primary (independence). Cost discipline: mechanical agent runs (Monitor, Triage) run with thinking off; the Monitor does a full per-provider sweep twice a week (Mon + Thu).
_Avoid_: free model chain, model pool, provider list, LLM stack

### Provider data

**Recommendations**:
Related videos shown on a video page, surfaced on that video's load screen in the app. Distinct from search results: same card shape, different origin — the video page, not a listing or query.
_Avoid_: related items, suggested videos, related (use the full term in docs)

**Data-complete**:
A provider whose `load` populates every `LoadResponse` field the site exposes and whose `loadLinks` emits every source FINDINGS recorded for the site's videos. The floor fields are recommendations, tags, plot, duration, year, and actors — required wherever the site exposes them; score and posters are populated opportunistically. The bar a fix or new-provider PR must clear.
_Avoid_: fully populated, feature-complete
