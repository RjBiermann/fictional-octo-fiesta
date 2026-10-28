# Context

CloudStream extension repo (18+ providers). This context covers the AI automation pipeline that turns labeled GitHub issues into reviewed pull requests, built by coding agents in CI.

> **Historical machinery note:** entries below describing the old pipeline's
> runtime machinery — Builder/Reviewer runs, Rounds, Commands, the Monitor
> cadence, the Model chain, `deliver-pr`, Acks — describe the pre-devloop
> workflows, which have been removed. The pipeline is now compiled gh-aw
> workflows (AGENTS.md, "AI pipeline"); the live engine vocabulary is
> **Engine lane**, below. The domain concepts (FINDINGS, Triage, Provider
> health, Expiry, audit vocabulary) remain current.

## Language

### Agents

**Builder**:
A Builder agent run (pi) that takes a labeled issue, scrapes the live site, and produces a pull request. Never merges.
_Avoid_: worker, fixer, build agent

**Reviewer**:
A Reviewer agent run (opencode) that reviews a pull request and may push fix commits to its branch. Independent of the Builder by design — never the same run, never merges.
_Avoid_: critic, checker, self-review

**Agent run**:
One headless agent invocation, bounded by hard caps (max turns, wall-clock timeout). The unit of cost and retry. Issue-scoped runs (build, spec) budget at `build_timeout` (3600s), PR-scoped runs (review, repair) at `timeout` (1800s); every timeout spends an Attempt, and a Daily cap bounds Attempts per issue per day.
_Avoid_: session, job (job = the CI wrapper, not the agent invocation)

**Daily cap**:
Per-issue ceiling on Attempts per calendar day (`max_per_day`; 0 = unlimited). The runaway-spend protection behind the per-issue Attempt cap — a time-bombed issue can't burn sweeps all day without a human re-label.
_Avoid_: rate limit, quota

**Round**:
One Reviewer pass over a pull request. Bounded: 2 rounds maximum, tracked by a round label. A Round is spent only by a completed pass — a Superseded or failed run spends nothing. Fixes pushed by a Reviewer may trigger a new Round but never a new PR.
_Avoid_: iteration, loop

**Superseded**:
An Agent run cancelled because a newer trigger replaced it (a new push to the PR, a re-fired run). Not a failure: no findings, no Round spent, and the superseding run owns the outcome. Distinct from Blocked (the site refused) and a failure (the models failed).
_Avoid_: cancellation, aborted run

### Triggers & artifacts

**Trigger label**:
A maintainer-applied issue label (`ai-fix`, `ai-new-site`, `ai-remove`) that starts a build. Issues without one are never processed. Renames stay inside the reserved `ai-` prefix — the contract the lane filters match on (`startsWith(label, 'ai-')`), so a rename can never orphan the trigger. Since ADR-0018, *machinery* (95-orchestrator) applies `ai-fix` to `status/queued` issues automatically — agents still never touch them.
_Avoid_: auto label, bot label

**Command**:
A maintainer comment on an issue or PR (`/retry`, `/review`) that the pipeline dispatches. A Command can only re-fire work a Trigger label already created — it never creates work, never spends a Round itself. Issues and PRs alike accept Commands; trigger labels live on issues only. (Historical: `/repair` re-fired Repair on a delivered PR — it died with the devloop lane, pilot #568; repair now happens through review → fix-loop rounds.)
_Avoid_: slash command, bot command, retrigger

**Rebuild**:
A pipeline-initiated redo of a build whose branch can no longer land cleanly: the sweep's rebase against `main` conflicts, the PR closes, and the issue re-enters the queue on fresh `main`. Counts against the attempt budget — unlike `/retry`, the human Command that resets it. Never triggered by agent output — only the sweep's rebase stage.
_Avoid_: retry (that is the human Command), re-run

**Re-fire**:
Re-running an Agent run for an existing issue or PR, via a Command. The new run Supersedes the previous one. A Builder Re-fire clears the PR's Rounds (a fresh build gets a fresh review budget); a Reviewer Re-fire still respects the Round cap.
_Avoid_: retrigger, retry (retry is the command name, not the concept)

**Removal**:
A Builder run executing an `ai-remove` issue: probe the site for evidence only, delete the provider directory, open a removal PR. The label is the decision — the agent never gates on the probe's outcome; deletion is git-reversible.
_Avoid_: deletion task, teardown (the deliverable is a removal PR, not a direct deletion)

**FINDINGS**:
The ground-truth record of a site probe: exact selectors, endpoints, headers, curl transcripts. Every selector in shipped Kotlin code must exist in FINDINGS evidence. Committed as part of the PR.
_Avoid_: research notes, scrape log

**Verification**:
Live-site checks (via the verify script) proving the code's selectors and stream URLs actually work. Plain HTTP only — it mirrors what the app's runtime can do, never a probe instrument (ADR-0008); the player layer it cannot mirror is covered by **Player probe**, and a provider's end-to-end playback by a **Playback scenario**. A source the app's HTTP-only datasource can never open (`data:`/`blob:`/`file:`) is a Verification FAIL, not a limitation. Distinct from a **build**, which only proves compilation. A PR requires both.
_Avoid_: testing, validation, smoke test

**Load check**:
The emulator lane (`.github/workflows/load-check.yml`, ADR-0012) and, specifically, its registration tier: bootstrap → compile every provider against the live unpinned pre-release jar → KVM emulator → install the prerelease app → load every `.cs3` via upstream's `deployWithAdb` mechanism. This tier asserts **registration only** (`PluginManager: Loaded plugin X successfully`), zero AI. A failing plugin set lands in one open issue per sorted failing-plugin list, appended by daily runs; a load failure is a **Correctness** finding — never Drift, never Chronic-counted. Never **Geo-gated** evidence: it shares the runner's IP.
_Avoid_: smoke test, emulator test (the lane's other tiers are **Player probe** and **Playback scenario**); in-app playback of a named provider is a Playback scenario, not a load check

**Player probe**:
The emulator lane's site-free tier: a generated 2 s H.264/TS fixture served on the runner's loopback (`10.0.2.2` from inside the emulator) is opened through the app's own `cloudstreamplayer://` deep link, asserting `Rendered first frame` and no datasource error. Proves the app's player path — datasource, headers, media3 wiring — with no provider, site, geo or UI selector. This is the layer where a `data:` source died as `net::ERR_UNKNOWN_URL_SCHEME` → 2001 (#1353), invisible to registration and to HTTP-side checks.
_Avoid_: in-app test (that is the Playback scenario), stream test, player test

**Playback scenario**:
The emulator lane's per-provider tier: a scripted `adb`/`uiautomator` walk (`.github/load-check/scenarios/`) that opens one provider's real video inside the app — through the `csshare:` deep link, one Play tap, no settings or search navigation — then requires that provider's own source in logcat (`ExtractorLink(name=<Provider>`, `kraptor_<Provider>`) **and** a rendered frame, so another provider's video can never pass the row. Dispatched (`scenario`/`scenario_arg`), never nightly; screen XML, screenshot and logcat are uploaded as evidence. Required for a playback-symptom `ai-fix` and for a Chronic provider before a removal decision — never for every provider PR, and datacenter-IP walls are false-reds here (Geo-gate reasoning applies).
_Avoid_: in-app testing (generic), e2e test, UI test

**Probe escalation**:
The ordered fallback of probe instruments: curl → TLS-impersonated curl → browser. Escalate one step only when the cheaper one fails or the HTTP body lacks content the page visibly has; the browser is a last-resort diagnosis tool, never the default.
_Avoid_: playwright (a tool, not the concept), browser testing

**Blocked**:
The live site refused the runner or serves nothing a provider could use **after the site-probe escalation ladder ran** (plain curl → TLS-impersonated curl → browser diagnosis, ADR-0011). A PR may open as Blocked with an explicit note; the human verifies in-app instead. FINDINGS records the exact **Blocked reason** so the dead end is never re-probed.
_Avoid_: failure (a failure stops the run; Blocked completes it with a caveat); using Blocked for a pre-escalation challenge

**Blocked reason**:
The recorded cause of a Blocked outcome — `client-rendered (JS-only)` (DOM has content, HTTP body doesn't, no SSR/API fallback) or `challenge, no unlock`. Recorded in FINDINGS Risks/blockers; a future agent reads it, not re-probes it.

**Suspected (challenge from datacenter probe IP)**:
A Cloudflare challenge (403/turnstile) received from the CI runner's datacenter IP, before any escalation ran. Cloudflare challenges datacenter IPs unconditionally on many sites, so this measures the runner's IP reputation, not site health — **never a finding on its own**. Evidence: the issue-#460 audit's five false Blocked verdicts (FullPorner, Film1k, FreePornVideos, XMoviesForYou, EPorner) vs the residential-IP differential (local 200s). Becomes Blocked only after the escalation ladder fails; otherwise escalate (site-probe) or record as Suspected with the response evidence.
_Avoid_: blocked type, block category

**Lead**:
A probe finding from an instrument the provider can't reproduce — rendered DOM, the player's network log, an age-gate click's cookie. Not evidence: it enters FINDINGS only after plain-curl re-proof (ADR-0008).
_Avoid_: browser evidence, rendered selector

**Age gate**:
An 18+/consent wall the runner can pass with a recorded unlock — a cookie or query parameter. FINDINGS records the unlock (Headers/referer section); the provider ships it in its request headers. A wall with no unlock is Blocked, not an Age gate.
_Avoid_: consent wall (that is the UI), 18+ check

**Geo-gate**:
Region-dependent catalog or streams — the runner's IP country sees different (or fewer) items than a user's device. FINDINGS records the probed region; PRs for geo-gated sites carry the in-app-verification note. Distinct from Blocked: the site answers, just differently.
_Avoid_: region lock, geo block (implies refusal; a Geo-gate varies content)

**Delivery**:
The mechanical commit-and-PR step performed by the workflow (the `deliver-pr` action), never by the agent: commit the working tree to `ai/issue-N`, force-push, create-or-update the PR. A run that produces changes but no Delivery is a failed run.
_Avoid_: push, publish, ship

**Closeout**:
The mechanical post-merge step that executes the human's merge verdict: find each story issue the merged PR closed out and close it with a receipt comment — never the agent's judgment (12-post-merge; the same carve-out as `/retry`'s close). Story issues whose PR did not merge stay open. A Closeout that fails at the machinery layer is a stall, not a verdict: the Chain driver holds story N+1 behind an open predecessor.
_Avoid_: review verdict (the human's decision that a merge executes), cleanup

**Maintenance round**:
A maintainer-ordered change to the machinery itself (workflow files, orchestrator, ADRs) — the class the trigger-label lane cannot self-deliver when the machinery needs fixing. Delivered either as a lane run when the spec is in the queue, or directly by the maintainer session for emergencies; in both cases the machinery's review lane reviews the diff and the human merge is the gate.
_Avoid_: hotfix (implies production pressure), self-repair (the machinery only auto-repairs its own agent deliveries — `fix-loop` — not maintainer rounds)

**Chain**:
The story-ordered execution of an approved spec: the conveyor stamps `status/planned` on story N only once story N−1 closed — dependencies flow forward through closeout, not simultaneity.
_Avoid_: pipeline (that is the whole loop), sequence of PRs

**Wave**:
A capped parallel batch of an armed epic's independent kids: the conveyor stamps `status/planned` up to `GH_AW_ORCH_WAVE_CAP` while fewer kids are in flight — parallelism with a spend ceiling (ADR-0014).
_Avoid_: batch, blast (the cap is the point)

**Armed epic**:
A spec epic the conveyor may run waves on: kids linked via `epic #N` in their bodies and the epic carrying an approval-of-record (owner one-worder, or the plan loop's round-3 marker — which the workflow posts as `github-actions[bot]` after your in-thread approval). Delivered-but-open kids get re-stamped by an armed epic — close them before re-arming (the **Re-stamp loop** guard set blocks the re-fire, not the stale state).
_Avoid_: enabled epic, active epic (arming is state on the epic, not the machine)

**Ack**:
The workflow-posted comment on the issue when an Agent run starts — a guaranteed narration floor produced by machinery, never by the agent. Agent-issued comments are counted beyond the Ack.
_Avoid_: progress comment (that is the agent's own narration), bot comment

**Machine-authored**:
Content the secret-guard may redact: authored by `github-actions[bot]`, or carrying the `[orchestrator]` marker. Machinery never holds the owner's identity (ADR-0019 retired the owner PAT); the marker filter remains as defense-in-depth. Human-authored content is never touched.
_Avoid_: bot-authored (ambiguous — machinery posts are the normal case now)

**Relay**:
The machinery mechanism that makes one pipeline stage trigger the next: a `workflow_dispatch` carrying an `aw_context` item, fired by the orchestrator under the bot identity. GitHub's only loop-prevention-exempt way for a machine to fire a gh-aw workflow; label and comment *events* by machines fire nothing.
_Avoid_: surrogate (the retired owner-PAT mechanism), PAT fire

**Secret-guard**:
The mechanical redaction layer (91-secret-guard.yml): credential-shaped strings in machine-authored issue/PR content are replaced with a redaction marker and logged to one rolling notification issue. Post-hoc by design — safe-outputs are the only write path an agent has, and the guard watches it.
_Avoid_: threat detection (the gh-aw feature this lane disables), secret scanning (a GitHub push-time feature)

**Registry**:
The append-only audit verdict record (`audits/findings.json`): provider verdicts, run history, scalar metadata. Conveyor-owned (ADR-0013) — the orchestrator's `registry_sync` applies entries the agent posts on the issue thread; the registry never travels inside an Agent diff. Distinct from **FINDINGS**, which is per-site evidence committed with the PR.
_Avoid_: findings file (FINDINGS is the per-issue evidence), state file

**Stranded delivery**:
An Agent run whose work died on the way to a Delivery — success verdict recorded, state transition applied, but no PR exists (observed: the run's registry edit dying on the rebase onto moved main). Loudest when the trigger issue cycles `planned → in-review` on re-fires; diagnose by checking whether a PR actually carries the work.
_Avoid_: lost run, silent failure (the strand is visible: fallback issue + run log)

**Fallback review issue**:
The gh-aw carrier created when the run's safe-output PR cannot be produced (e.g. protected-file `request-review` mode or a delivery failure): the bundle artifact and manual-landing instructions park on an issue instead. It is a holding pen, not a Delivery — a human must land it, and the trigger issue's state stays reconciled by the sweeper afterwards.
_Avoid_: review issue (that is the Review verdict thread), stale issue

**Re-stamp loop**:
The delivered-story re-fire class (spec #797, recorded 2026-10-20): machinery re-applied `status/planned` to stories whose delivery PR had already merged (#771, #768, #775 — four bot stamps inside ~75 min), and each stamp re-fired an Implement pass that saw the merged state, skipped, and still spent a paid run; the racing duplicates (#787, #790) belong to the same class. The primary ADR-0007 closeout fired **unevenly** in that wave (#767 closed at its merge, then reversed by a human reopen — #771/#768 never closed), which is the provenance gap the healer now covers. This is a *recognized* class, not a re-reportable one: the guards are live and replay-pinned (`.github/tests/orchestrator_replay_test.py`, the `orchestrator-replay` job in lint.yml, fixtures from the recorded label events), so a fresh sighting owes a new fingerprint — an actor or channel the owner-gate cannot see — never a replay of the 2026-10-20 events. Guard set: `closeout_healer()` (an open issue with a merged delivery PR, or a closed fallback carrier, closes out); the merged-delivered guard in `_fire_implement()` (a delivered story is never re-fired); the owner-gated `_stamped_after_merge()` re-arm check (machinery re-stamps are loop-dead — only the OWNER's post-merge `status/planned` means "run it again"); and `concurrency: orchestrator, cancel-in-progress: true` (orchestrator label batches are serialized, so add/remove pairs cannot interleave). **Accepted-cosmetic:** the pre-serialization 05:08–05:11Z non-atomic label batches are historical — documented, no further fix built. Sibling machinery-robustness class, orthogonal: PR #873's verdict-anchor regex normalization (bracketed story-tag headings stranded clean audits).
_Avoid_: duplicate-PR bug, re-arm bug (an owner re-arm is the legitimate override, not the fault), label race (the batches were the cosmetic part, not the class)

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
A provider that keeps failing the Drift probe after fixes merge — measured as four or more closed drift issues on its name. The Health report flags a Chronic provider as a removal candidate; the Removal decision stays maintainer-only. Before that threshold, recurring drift obliges a hardening fix (structural matching, multi-host fallback) instead of another point fix — see AGENTS.md, "Drift recurrence".
_Avoid_: repeat offender, unstable (rot is the site's doing, not the code's)

**Triage**:
An agent classification of a new user issue (broken-provider report, new-site request, needs info, duplicate) with a live-site probe behind it. Suggests a trigger label; never applies one.
_Avoid_: labeling, classification

**Expiry**:
A `needs-info` issue closed by the stale bot after the reporter stays silent past the grace window (mark-stale, then close). An Expiry ends a conversation, not a Triage verdict — the classification stands, the reporter just never answered. Any comment reopens it; trigger-labeled and ready-for-agent issues are exempt and never expire.
_Avoid_: stale (the bot's mark, not the outcome), auto-close, abandonment

**Engine lane**:
The single model/provider lane every agent workflow runs through — pi over the OpenCode Go gateway (`openai/deepseek-v4.1-flash`), declared once in `shared/engine-lane.md` with one secret, one network allowlist entry, and `reasoning: false` by deliberate choice (ADR-0016).
_Avoid_: model chain (historical), fallback chain, model pool, provider list

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
The deep shared module (`shared/.../JsonLdParse.kt`, com.kraptor) that owns the ISO-8601 `P[#D]T#H#M#S` grammar (optional day component — ixiporn's `P0DT0H41M45S`-shaped meta tags are the day-shaped callers) and the JSON-LD `datePublished`/`uploadDate` year — plain, `"`-escaped, or bare-token input. Providers call its two Parse functions (`minutes`, `year`) instead of maintaining their own RegEx copies; CloudStream `duration` is minutes here (repo convention), never seconds.
_Avoid_: duration helper, time parser, per-provider date regex

**Packed-JS unpack**:
The deep shared Parse function (`shared/.../PackedJs.kt`, com.kraptor) that owns the Dean-Edwards `eval(function(p,a,c,k,e,d){...})` unpacket grammar (radix keys, escaped quotes, unmapped-token preservation). Callers pass a whole embed page or bare script and get the unpacked payload, or null when nothing packed is present (caller falls back to raw input). Extractor adapters use it for the JWPlayer/JS packer embeds.
_Avoid_: unpacker helper, packer class, eval regex (per-adapter regex copies)

**Duration parse**:
The deep shared Parse module (`shared/.../DurationParse.kt`, com.kraptor) that owns the video-duration clock grammars — `h:m:s` colon clocks, `h/m/min/sec` tokens (HQPorner's badge shape), and plain seconds (`og:video:duration`, MissAV) — always returning minutes, floored (repo convention), null on junk/absent/sub-minute. Providers call `minutes(text)` / `fromSeconds(seconds)` instead of maintaining their own per-provider copies; ISO-8601 JSON-LD input stays with JSON-LD meta parse.
_Avoid_: seconds parser (duration is minutes here), per-provider clock regex, duration helper

**Card parse**:
The deep shared Parse module (`shared/.../SearchCard.kt`, com.kraptor) for the listing-card → per-video identity shape: `parse(card, selectors)` returns one `CardFields` (title/href/poster), `homeCards(document, selectors)` returns a pageful deduped by href. Owns the null-guards, lazyload poster fallback, `data:`-placeholder skip and the attr-title shape (`titleAttr`); the MainAPI adapter applies fixUrl + emission. Documented exceptions stay local: root-anchor cards (AllClassicPorn, Cat3Film, HQPorner), deep title fallback chains (Eroticmv, Sexfilm), gated-card skips (FreePornVideos).
_Avoid_: toSearchResult copy (per-provider private Element extension), card helper, home parser

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
