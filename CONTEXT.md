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

**Age gate**:
An 18+/consent wall the runner can pass with a recorded unlock — a cookie or query parameter. FINDINGS records the unlock (Headers/referer section); the provider ships it in its request headers. A wall with no unlock is Blocked, not an Age gate.
_Avoid_: consent wall (that is the UI), 18+ check

**Geo-gate**:
Region-dependent catalog or streams — the runner's IP country sees different (or fewer) items than a user's device. FINDINGS records the probed region; PRs for geo-gated sites carry the in-app-verification note. Distinct from Blocked: the site answers, just differently.
_Avoid_: region lock, geo block (implies refusal; a Geo-gate varies content)

**Delivery**:
The mechanical commit-and-PR step performed by the workflow (the `deliver-pr` action), never by the agent: commit the working tree to `ai/issue-N`, force-push, create-or-update the PR. A run that produces changes but no Delivery is a failed run.
_Avoid_: push, publish, ship

**Ack**:
The workflow-posted comment on the issue when an Agent run starts — a guaranteed narration floor produced by machinery, never by the agent. Agent-issued comments are counted beyond the Ack.
_Avoid_: progress comment (that is the agent's own narration), bot comment

**Gate**:
The mechanical post-run check that issues an Agent run's verdict. Delivery is the verdict: a run whose Delivery succeeded is a success even if the agent narrated nothing. Missing agent narration (zero comments beyond the Ack) is a loud warning, never verdict-flipping.
_Avoid_: CI gate, quality gate

**Task run**:
An Agent run executing a fully specified issue (labeled `ready-for-agent`) with a generic prompt — the issue body is the task spec. Uses the Builder runtime; may apply non-trigger labels only.
_Avoid_: audit run (an audit is one kind of task run)

**Audit**:
An Agent run measuring providers against their live sites on three independent axes: Correctness (code vs the CloudStream contract), Drift (code vs the live site), and Data-completeness (code vs what the site exposes). Measure-only: it never fixes in-run — every finding becomes a Fix request, and the fixes ride separate Builder runs. Fired two ways: a maintenance dispatch (`scope` = one provider or empty for the repo) or a `ready-for-agent` issue; either way the run is one provider per agent, never a sweep inside one agent.
_Avoid_: health check (that is the Monitor's Drift probe), review, site audit (site is the thing audited, provider is the unit)

**Correctness**:
The audit axis measuring provider code against the CloudStream extension contract: wrong field mapping, URLs that don't resolve as the app expects, empty or misordered lists on valid pages, malformed links. A Correctness finding must be provable at code level against a real site response — never "the app shows it weird"; the fix must go red→green on a Fixture.
_Avoid_: bug sweep, code review (that is the Reviewer), behavior

**Fix request**:
An issue the Audit creates for one gap batch: Correctness+Drift share one issue (same fix run, same acceptance bar), Data-completeness gets its own. The body carries the FINDINGS evidence including raw HTML/curl transcripts, so the Builder never re-probes from scratch. A mechanical workflow step applies `ai-fix` to it (ADR-0006); dead, Blocked, or unreachable sites get reported without a Fix request.
_Avoid_: audit finding, auto-fix, bug report (that is Triage's output)

**Drift probe**:
A cheap scheduled per-provider live check (search + one video page + one stream) that detects provider rot. Distinct from a full **FINDINGS** probe: no evidence transcription, verdict-only output. Broken providers surface as triage issues, not automatic fixes.
_Avoid_: monitoring, health check (the report is a Health report; the probe is the action)

**Health report**:
The twice-weekly (Mon + Thu) verdict table the Monitor posts on the `provider-health` tracking issue: per provider, OK / GAP (a Drift finding) / Blocked.
_Avoid_: status update, monitor output

**Chronic**:
A provider that keeps failing the Drift probe after fixes merge — measured as four or more closed drift issues on its name. The Health report flags a Chronic provider as a removal candidate; the Removal decision stays maintainer-only.
_Avoid_: repeat offender, unstable (rot is the site's doing, not the code's)

**Triage**:
An agent classification of a new user issue (broken-provider report, new-site request, needs info, duplicate) with a live-site probe behind it. Suggests a trigger label; never applies one.
_Avoid_: labeling, classification

**Expiry**:
A `needs-info` issue closed by the stale bot after the reporter stays silent past the grace window (mark-stale, then close). An Expiry ends a conversation, not a Triage verdict — the classification stands, the reporter just never answered. Any comment reopens it; trigger-labeled and ready-for-agent issues are exempt and never expire.
_Avoid_: stale (the bot's mark, not the outcome), auto-close, abandonment

**Agent-run action**:
The composite action (`.github/actions/agent-run`) that executes the **Model chain** for every agent workflow: primary attempt → fallback attempt, with the skill set, thinking level, and bounded retries as inputs. The Model chain's only callers route through it; a chain change is a one-file edit.
_Avoid_: run pair, model runner, pi step

**Model chain**:
The ordered fallback every agent run follows: GLM 5.3 Flash → DeepSeek V4 Flash, both served through the OpenCode Go gateway (paid key) and driven by pi. The Reviewer's primary must come from a different model family than the Builder's primary (independence). Cost discipline: mechanical agent runs (Monitor, Triage) run with thinking off; the Monitor does a full per-provider sweep twice a week (Mon + Thu).
_Avoid_: free model chain, model pool, provider list, LLM stack

### Provider code

**Host registry**:
The shared module that registers every extractor adapter the repo ships; every provider's plugin calls it, so host coverage never varies by provider.
_Avoid_: shared extractors, extractor list

**Extractor adapter**:
One ExtractorApi per embed host family, dispatched through the framework's loadExtractor; hosts are never handled inline inside a provider's loadLinks.
_Avoid_: extractor class, host handler

**Mirror factory**:
A shared factory function per embed-host family (filemoon, vidHidePro, dood, …) that returns a fresh base adapter with name/mainUrl applied; the Host registry holds one (url, name) row per mirror domain instead of a one-line subclass. Adding a mirror = one row in HostRegistry.kt.
_Avoid_: mirror subclass, one-line adapter class

**JSON-LD meta parse**:
The deep shared module (`shared/.../JsonLdParse.kt`, com.kraptor) that owns the ISO-8601 `PT#H#M#S` grammar and the JSON-LD `datePublished`/`uploadDate` year — plain, `"`-escaped, or bare-token input. Providers call its two Parse functions (`minutes`, `year`) instead of maintaining their own RegEx copies; CloudStream `duration` is minutes here (repo convention), never seconds.
_Avoid_: duration helper, time parser, per-provider date regex

**Packed-JS unpack**:
The deep shared Parse function (`shared/.../PackedJs.kt`, com.kraptor) that owns the Dean-Edwards `eval(function(p,a,c,k,e,d){...})` unpacket grammar (radix keys, escaped quotes, unmapped-token preservation). Callers pass a whole embed page or bare script and get the unpacked payload, or null when nothing packed is present (caller falls back to raw input). Extractor adapters use it for the JWPlayer/JS packer embeds.
_Avoid_: unpacker helper, packer class, eval regex (per-adapter regex copies)

**Quick search**:
The live-typing/suggest search surface CloudStream calls while the user types, backed by `quickSearch` + `hasQuickSearch`. Distinct from **search**: a separate endpoint (often AJAX) that many sites don't have — when the site has none, `hasQuickSearch` stays `false` and the absence is recorded explicitly in FINDINGS, never faked.
_Avoid_: instant search, autocomplete (describes UI behavior, not the provider surface)

### Testing

**Parse function**:
A pure function (`parseXxx(html): List<…>`) that turns fetched HTML/JSON into data without touching the network — the only unit-tested layer of a provider.
_Avoid_: helper, parser class

**Fixture**:
A checked-in copy of live-site HTML/JSON under `src/test/resources/`, captured from FINDINGS evidence, that a Parse function is tested against. Pins parser behavior; never proves the site still works.
_Avoid_: sample page, mock

**Seam**:
The Parse-function boundary — the one place unit tests attach. HTTP transport and `MainAPI` flows have no Seam; Verification owns those.
_Avoid_: interface, injection point

### Provider data

**Recommendations**:
Related videos shown on a video page, surfaced on that video's load screen in the app. Distinct from search results: same card shape, different origin — the video page, not a listing or query.
_Avoid_: related items, suggested videos, related (use the full term in docs)

**Data-complete**:
A provider whose `load` populates every `LoadResponse` field the site exposes and whose `loadLinks` emits every source FINDINGS recorded for the site's videos. The floor fields are recommendations, tags, plot, duration, year, and actors — required wherever the site exposes them; score and posters are populated opportunistically. The bar a fix or new-provider PR must clear.
_Avoid_: fully populated, feature-complete

**Distinct**:
The uniqueness bar alongside Data-complete: across the sampled videos, every per-video identity field — title, plot, poster URL, stream URL — has a different value on every video, no search page lists the same video twice, recommendations never contain the video itself, and a video's search entry agrees with its load page on title and poster. Identity fields only: tags, actors, year, duration, and score legitimately repeat and are covered by Data-complete, never by Distinct. Checked live by Verification and at fixture level by Parse tests through the shared DistinctBar helper.
_Avoid_: unique (use the full term), freshness (staleness is Drift's concern)
