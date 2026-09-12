# FINDINGS — issue #361 (P1-8: orphaned composite actions)

## Probe

- `.github/actions/{agent-run,agent-runtime,deliver-pr}/action.yml` all exist
  (60 / 65 / 42 lines).
- Grep across the repo for `actions/agent-run|actions/agent-runtime|actions/deliver-pr`
  in all `*.yml`/`*.yaml` files: **0 references**. Only remaining callers were the
  removed Builder/Task/Reviewer workflows (`AGENTS.md`: "The old pipeline's
  Builder/Reviewer/Triage/Monitor workflows have been removed").
- `docs/adr/0004-agents-never-push.md` already carries a historical-banner blockquote
  (added in review #347 follow-ups) stating the `deliver-pr` action "belonged to the
  removed pre-devloop pipeline; no workflow invokes it today."
- `CONTEXT.md` historical-machinery note (top of file) already covers `deliver-pr`
  and the Model chain as pre-devloop vocabulary.
- P0-7 model-config divergence: `agent-runtime/action.yml` writes
  `"$OPENCODE_API_KEY"` unexpanded into models.json (heredoc unquoted-free string but
  no envsubst in that action) — divergent from `devloop.yml`, which writes a quoted
  heredoc plus `envsubst` (P0-7 fix). Moot once the action is deleted.

## Decision (per the issue's "decide" fork)

**Delete all three actions.** Rationale:
- Nothing invokes them; keeping dead token-handling code (`deliver-pr`) is an audit
  liability, and the stale model-config heredoc is a drift trap against devloop.yml.
- Wiring the actionlint pre-flight into `devloop.yml` is redundant: CI `lint.yml`
  already runs actionlint (reviewdog) on push, and per AGENTS.md agents run
  `actionlint` locally before pushing `.github/**` changes.
- ADR-0004's banner needs only a small amendment to record that the actions were
  deleted, not just orphaned.

## Change

1. `git rm -r .github/actions/` (all three composite actions).
2. `docs/adr/0004-agents-never-push.md`: banner updated to note the action files were
   deleted (the decision record stays; only the dead code is gone).

## Verification

- Repo-wide grep: 0 remaining references to the three actions.
- `actionlint` clean on all workflow files (local pre-flight per AGENTS.md).
- No Kotlin/provider code touched; no need to bump provider versions or run
  `verify.sh` (this is CI plumbing only, not a provider change).

# FINDINGS — issue #360 (P0-15: vendored jars have no integrity record)

## Probe

- `build.gradle.kts:19-31` (buildscript) loads two committed binaries:
  - `classpath(files("gradlelibs/cs-plugin-facade.jar"))` — the vendored CloudStream
    Gradle plugin (jitpack -SNAPSHOT no longer resolves under Gradle 8.12).
  - `maven("$rootDir/vendor")` repo serving `com/github/recloudstream/gradle/gradle/-SNAPSHOT/`
    (`gradle--SNAPSHOT.jar` + `.pom`) to the buildscript classpath.
- Every provider build depends on these; no checksum/provenance anywhere in the repo
  beyond inline comments (repo-wide grep for sha256/integrity/checksum in *.kts/*.md: none,
  apart from an unrelated PoW comment in Film1k/FINDINGS.md).
- Measured SHA-256 (sha256sum, 2024 snapshot checkout):
  - `5358ef5bfe9ea8fad162fe9c4895e5706a92659c34a38bbd91457d902fadcb21`
    — `gradlelibs/cs-plugin-facade.jar`
  - `5358ef5bfe9ea8fad162fe9c4895e5706a92659c34a38bbd91457d902fadcb21`
    — `vendor/com/github/recloudstream/gradle/gradle/-SNAPSHOT/gradle--SNAPSHOT.jar`
  - `203e5d3055690ccc65ff1eb47d16f9d4f71984ea02f51b8509c217fe5f7c8fbd`
    — `vendor/com/github/recloudstream/gradle/gradle/-SNAPSHOT/gradle--SNAPSHOT.pom`
- Probe note: jar and facade jar are **byte-identical copies** (same hash) — the facade
  jar *is* the vendored plugin jar, duplicated at a stable path for `files(...)` classpath
  loading. Worth flagging to reviewers but not deduplicating here (issue scope is the
  integrity record, not restructuring the classpath).

## Decision (minimal fix)

- Add a committed, diffable integrity record `gradlelibs/INTEGRITY.txt` (all three files
  above, one `SHA256 <path> <hash>` per line, provenance comments inline).
- Add a root-Gradle `verifyVendoredJars` task that recomputes each hash and fails on
  mismatch/absence; every provider subproject's `make`/`test`/`check` task depends on it,
  so the invariant runs with the repo's existing commands (no CI change — per AGENTS.md,
  workflows only change when the issue spec names them).
- Amend AGENTS.md Commands section with one line documenting the invariant.

## Verification

- `./gradlew verifyVendoredJars` → exit 0 on the untouched tree.
- Tamper test ( appended byte to `gradlelibs/cs-plugin-facade.jar`, restored after):
  task fails with the expected/actual SHA-256 diff, exit 1 — red → green.
- `./gradlew EPorner:test` → exit 0 (provider test path runs the gate as a dependency).
- No CI workflow change (issue spec doesn't name one; review remains the gate).

# FINDINGS — issue #359 (P0-14: SearchCard href fallback can bind a title to a non-video link)

## Probe

- `shared/src/main/kotlin/com/kraptor/SearchCard.kt` (pre-fix): fallback chain
  `hrefSel → title-anchor href → card.selectFirst("a")?.attr("href")`. The last leg
  grabs the card's *first* anchor unconditionally — in tag/actor-link-first card shapes
  that is a category link, so `parse` returns `CardFields(title=…, href="/tags/…")` and
  the emitted `SearchResponse` points at a tag page, not a video.
- Demonstrated against the real code (not assumed): ran the new fixture card
  (`first <a>` = `/tags/amateur/`, title `<span class="name">` with no link of its own)
  through the pre-fix `SearchCard.parse` → red with
  `expected null, but was: CardFields(title=Tagged First Scene, href=/tags/amateur/, poster=/thumbs/4242.jpg)`.
  The bug was live, exactly as P0-14 described.
- Callers that can reach the fallback (no `hrefSel`): EPorner (`p.mbtit a`),
  FreePornVideos (`a.thumb_title`), FullPorner (`div.video-title a`),
  Cat3Movie (via its own local Parse; site currently unreachable from this env).
  Callers with explicit `hrefSel` are unaffected by construction: PornXP
  (`hrefSel = "a[href*=videos]"`).
- Live-shape check (sites bot-blocked from this env; used web.archive.org snapshots of
  the exact selectors): FreePornVideos `a.thumb_title` anchors carry `href`
  (archive 2026-08-17); FullPorner `div.video-title > a[href=/watch/…]` carries `href`
  (archive 2026-07-03). EPorner's KVS shape (`p.mbtit a[href]`) is fixture-covered and
  green. → the fallback leg never fires for any *live* card shape today; the fix only
  changes the degenerate/broken-card case.
- EPorner.com age-verifies this environment outright (geo wall; every path returns the
  age-gate page, cookie attempts ineffective), so a live `verify.sh` run against
  EPorner itself is not possible from here — recorded as a Verification limitation;
  live Verification was run against PornXP instead (below), the one SearchCard caller
  reachable from this environment.

## Decision (minimal fix, fixture-first)

- Kept the documented card-root-wraps-the-link heuristic but made the first-`<a>`
  fallback evidence-based instead of positional. An `<a>` now counts as the card link
  only when it carries proof it is the video link:
  1. it wraps an `<img>` (thumb inside the anchor), or
  2. it wraps a title-ish child (`class`/`id` containing `title`/`name`, non-anchor), or
  3. its text equals the card title (the bare-sibling video-link shape the old
     fallback silently served).
  Otherwise the link is skipped (tag/actor/category anchors have none of these). When
  no qualifying link exists, `parse` returns null — the title is never bound to an
  unrelated link, matching `parse`'s existing "no real link → null" contract.
- A first attempt at an ancestor-walk heuristic (skip anchors whose ancestors hold the
  title) was wrong — at card root the title is a *sibling*, so every anchor passed and
  the fixture still failed. The evidence-based rule above is the minimal correct form;
  both intermediate red states are recorded in the test-results history.
- Cards whose title and video link are structurally unrelated already have the correct
  escape hatch: pass an explicit `hrefSel` (documented in the KDoc).

## Change

1. `shared/src/test/resources/search_card_fixture.html` — two new cards: tag-link-first
   (first `<a>` = tag link, `<span class="name">` title) and href-less title anchor
   (`<a class="name">` with no href, tag link as the only other anchor).
2. `shared/src/test/kotlin/com/kraptor/SearchCardTest.kt` — new test
   `tag-link-first card - title never binds to a tag link (P0-14)` asserting both new
   cards yield null; fixed the broken-card test's index shift (fixture grew by two
   cards → `cards[3]`).
3. `shared/src/main/kotlin/com/kraptor/SearchCard.kt` — `firstCardLink` replaces the
   bare `card.selectFirst("a")` leg (see Decision). No provider files touched (the
   root cause lives in `shared/`; per AGENTS.md, shared fixes do not require provider
   bumps unless behavior changes for live shapes — live shapes are provably
   unaffected, see Probe).

## Verification

- TDD loop: red (pre-fix code fails both new assertions with the exact
  `href=/tags/amateur/` binding P0-14 predicted) → green (post-fix, all 4 tests pass).
- `./gradlew EPorner:test Cat3Movie:test PornXP:test FullPorner:test` — BUILD
  SUCCESSFUL (shared suite runs in every provider; no cross-provider regressions).
- `./gradlew EPorner:make FreePornVideos:make` — clean `.cs3` builds;
  `verifyVendoredJars` gate green.
- Live Verification (`.pi/skills/verify-provider/scripts/verify.sh`) against PornXP
  (pxp.news, reachable; SearchCard caller): search (36 cards), homepage pages 1–2,
  video page, 4 stream URLs all serving `206 video/mp4` — **RESULT: PASS**. Check-5
  (search↔load agreement) NOTEs it found no agreement pair; the sampled video *is*
  present in the search page HTML (verified: `grep 189932411056` = 1) — the script's
  regex block parser cannot see inside the nested `div.item_cont` structure, so this
  NOTE is a script limitation, not a data failure.
- EPorner live Verification blocked by the site's geo age-wall from this environment
  (every request returns the age-verification page); its card shape is covered by
  fixture test 1 and unchanged by this fix.
