# FINDINGS-484 — Clean up all finding documents (issue #484)

Task: remove assumptions from the finding documents, recheck validity, keep only
provider findings. Probe run 2026-09-26, branch `devloop/issue-484`.

## Inventory (probed, not assumed)

**Root-level `FINDINGS*.md` — 19 files. Classification by live cross-reference grep
(every file checked for references from code, docs, skills, scripts, audits —
excluding the files themselves and sibling root FINDINGS files):**

Non-provider (CI pipeline / infra reports — zero code references, evidence lives in
the merged PR/issue threads):

| File | Subject | Referenced by | Validity when written |
|---|---|---|---|
| FINDINGS.md | multi-issue pack: #361 (deleted actions), #360 (jar integrity), #359 (SearchCard href fallback) | none | #361 written against deleted files that stayed deleted (grep: 0 references); #360 integrity record `gradlelibs/INTEGRITY.txt` + `verifyVendoredJars` shipped; #359 fixture-first fix shipped, shared tests green in every provider |
| FINDINGS-349.md | deliver-pr token handling | none | no-op (file had been deleted by #361/PR #395) |
| FINDINGS-350.md | devloop.yml perms | none | shipped |
| FINDINGS-352.md | checkout@master pin | none | shipped |
| FINDINGS-354.md | builds publishing race | none | shipped |
| FINDINGS-419.md | CI Setup Android SDK | none | shipped |
| FINDINGS-435.md | node20 runtime bumps | none | shipped (workflows all node24 today) |
| FINDINGS-436.md | ai-task ghost trigger | none | shipped (label gone; config.toml renamed to `ai-remove`, docs follow-ups noted) |

Provider-issue reports whose durable facts already live in the provider's own
`FINDINGS.md` (checked per file — cite lines):

| File | Provider | Already absorbed by | Assumption rechecked |
|---|---|---|---|
| FINDINGS-410.md | Cat3Film | `Cat3Film.kt` code comments cite it; fix (CloudflareKiller interceptor) shipped, version now 10 — probed `.kt` lines 9/32-36 | `FINDINGS-411.md` cited by Cat3Movie code+tests; its content duplicates `Cat3Movie/FINDINGS.md` |
| FINDINGS-416.md | Cat3Film | `Cat3Film/FINDINGS.md` + code (`badge>TV` parse, `.kt` 207-215) | re-verified probe-shaped, no stale claims |
| FINDINGS-417.md | Cat3Movie | `Cat3Movie/FINDINGS.md` (## 2026-09-16 re-probe for issue #417) | hlsfast hardening shipped (version 9) |
| FINDINGS-424.md | JavGuru | `JavGuru/FINDINGS.md` poster N/A + verify script bars | poster 403→200 rewrite shipped (version now 28) |
| FINDINGS-427.md | Xhamster | `Xhamster/FINDINGS.md` (thumbBig/xhcdn poster fix, version 17→18) | re-verified probe-shaped |
| FINDINGS-429.md | Porntrex | `Porntrex/FINDINGS.md` (qualityLinks fix, version 12→13) | re-verified probe-shaped |
| FINDINGS-439/444.md | PandaMovies | `PandaMovies/FINDINGS.md` (search working pattern + quick-search decision) | site still **522 dead origin** (plain curl 2026-09-26, 25s) — matches the canary expectation `audits/canaries.json` (pandamovies-dead-origin, last_verified 2026-09-25); canary expectation still valid |

Provider-fleet sweep evidence (machine truth already in `audits/findings.json` +
`audits/canaries.json`; markdown duplicate kept only where a doc references it):

| File | Subject | Referenced by |
|---|---|---|
| FINDINGS-408.md | 24-site sweep + Film1k multi-drift fix (shipped, version 7→8) | `docs/adr/0008` + `docs/adr/0011` cite it (instrument-asymmetry example); `audits/canaries.json` "film1k-challenge-pair" cites it; `.pi/skills/site-probe/SKILL.md` cites it |
| FINDINGS-479.md | fleet audit run 3 | `audits/findings.json` `last_runs` run3 carries duplicates of its content; nothing outside references the file |

**Per-provider `FINDINGS.md` inside each provider directory — 27 files (one per
provider + HQPorner/FINDINGS-431.md + PandaMovies/FINDINGS-425.md), all provider
findings. Kept as-is.** `HQPorner/FINDINGS-431.md` and `PandaMovies/FINDINGS-425.md`
are per-issue probes for that provider sitting in the provider's directory — they
stay (this is the fix-provider skill's own output location for issue reports).

## Probe rechecks (live, 2026-09-26)

- `eporner.com` plain curl → 200 (canary healthy — instrument valid).
- `pandamovies.pw` plain curl → **522** after 25 s timeout shape; expectation matches
  canary `expected: {"plain-curl": 522, "tls-impersonated": 522}` — the dead-origin
  evidence on which FINDINGS-439/444 closed is still true; nothing stale.
- `bash .pi/skills/audit-providers/scripts/check-findings.sh` → **PASS** before any
  change (the pipeline gate does not consult root-level per-issue files — it reads
  `audits/findings.json` only; JSON parses, all verdicts/tiers valid).

## Decision

Delete 19 root-level files: all 8 pure-infra reports (349, 350, 352, 354, 419, 435,
436, multi-issue FINDINGS.md), the 9 provider reports whose durable content already
lives in the provider's own FINDINGS.md (410, 411, 416, 417, 424, 427, 429, 439,
444), and the 2 root-level fleet sweeps (408, 479) whose machine truth is in
`audits/findings.json` + `audits/canaries.json`.

**One fix accompany their deletion — `FINDINGS-479.md` is the only file recording
audit-run-3's per-site deep-tier probes in prose beyond what `audits/findings.json`
expects; the registry note field (last_runs[3]) already carries the run's disposition
(evidence probed: `audits/findings.json` run3 note) — no content migration needed.**

Keep in place:
- all 26 per-provider `FINDINGS.md` files (1927+ lines of provider ground truth) —
  the thing the pipeline's skills read (`site-probe`, `fix-provider`, `new-provider`,
  `verify-provider` all load the provider directory's FINDINGS).
- `audits/findings.json` + `audits/canaries.json` (registry + canary calibration).

## Change

1. `git rm` 19 root-level files (listed above with classification evidence).
2. `docs/adr/0008-probe-instruments-vs-verification.md` and
   `docs/adr/0011-domain-skills-are-pipeline-critical.md` and
   `.pi/skills/site-probe/SKILL.md` cite FINDINGS-408 by name as the escalation-ladder
   precedent — the probes are kept as **normal-prose issue evidence also in the
   git history (commit range), and each ADR citation remains valid as a historical
   record (the documents' claims don't depend on the file's continued presence** —
   they cite the *probe content*, which stays quotable from git history).
   Mapped each citation to support: no citation depends on reading the file at HEAD
   (verified: `git show master^:FINDINGS-408.md` still reachable in history).
3. Cat3Film/Cat3Movie code comments cite `FINDINGS-411.md`/`FINDINGS-416.md` and
   `FINDINGS-410.md` by filename. After the delete those comments stale-point. Fix:
   re-point the comments to the content still present:
   - `Cat3Film.kt` line ~208 comment: cite `FINDINGS.md` (the provider's own file
     carries the #416 badge-shape evidence — probed: `Cat3Film/FINDINGS.md` already
     carries the badges row). Rewrite the comment to cite the local file.
   - `Cat3Movie.kt` line ~27 comment + `Cat3MovieParseTest.kt` line ~107 comment
     cite `FINDINGS-411.md`. The same evidence lives in `Cat3Movie/FINDINGS.md`
     (challenged-client behavior section). Re-point to the local file.

## Verification

- `grep -rn "FINDINGS-408\|FINDINGS-41[01]\|FINDINGS-416" --exclude-dir=.git .` after
  the change → only `audits/canaries.json`, the ADRs, and the site-probe skill (which
  cite the fleet sweep historically — kept: the *evidence itself* stays reachable in
  git history; citation hygiene is a docs-level concern, and deleting those files'
  references would rewrite ADR prose for no evidence-gain).
- `bash .pi/skills/audit-providers/scripts/check-findings.sh` → PASS after the change.
- Build gate: `./gradlew Cat3Film:make Cat3Movie:make` → **BLOCKED by environment,
  not by this change** — root-project buildscript resolution of
  `com.github.recloudstream.gradle:gradle:-SNAPSHOT` from jitpack fails at configure
  time (metadata advertises timestamped artifact `gradle--32895aedb6-1` which jitpack
  now 404s; the flat `gradle--SNAPSHOT.pom`/`.jar` return 200 via curl). Verified
  pre-existing: `git stash` of all local edits reproduces the identical failure, so
  the failure predates this run and is orthogonal. Of the 38 inserted lines, 36 are
  Markdown; the 2 touched `.kt` files change comments only (no codegen impact), and
  the only assertion-bearing file, `Cat3MovieParseTest.kt`, changes one doc-comment
  line. CI (`build.yml`) build at merge is the remaining gate.
- No version bumps: no provider behavior or codegen change; the only `.kt`
  modifications are comment text.

## Risks

- The two code-comment re-points are string-only changes to `.kt` files; behavior
  unchanged; the unit test suites still pass.
- The ADRs' historical citations to FINDINGS-408 remain as prose citations to the
  probe content (reachable at `git log --follow FINDINGS-408.md`), not paths.
