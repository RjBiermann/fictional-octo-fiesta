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
One Reviewer pass over a pull request. Bounded: 2 rounds maximum, tracked by a round label. Fixes pushed by a Reviewer may trigger a new Round but never a new PR.
_Avoid_: iteration, loop

### Triggers & artifacts

**Trigger label**:
A maintainer-applied issue label (`ai-fix` or `ai-new-site`) that starts a Builder run. Issues without one are never processed.
_Avoid_: auto label, bot label

**FINDINGS**:
The ground-truth record of a site probe: exact selectors, endpoints, headers, curl transcripts. Every selector in shipped Kotlin code must exist in FINDINGS evidence. Committed as part of the PR.
_Avoid_: research notes, scrape log

**Verification**:
Live-site checks (via the verify script) proving the code's selectors and stream URLs actually work. Distinct from a **build**, which only proves compilation. A PR requires both.
_Avoid_: testing, validation, smoke test

**Blocked**:
The live site refused the runner (Cloudflare, IP ban). A PR may open as Blocked with an explicit note; the human verifies in-app instead.
_Avoid_: failure (a failure stops the run; Blocked completes it with a caveat)
