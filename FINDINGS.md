# FINDINGS — issue #363 (review #347 P1-3): TDD gate is provider-dependent — decide whether test presence is a merge requirement

## Probe (reality check)

- **Five providers have no `src/test`** (the issue names two of them):
  `FreePornVideos`, `FullPorner`, `HQPorner`, `XMoviesForYou`, `ixiporn`
  (`for d in */; do [ -d "$d/src/test" ] || echo "$d"; done`). The other 19
  providers plus `shared/` all ship Parse tests with fixtures.
- **All five are inline-`load()` extraction**: none import a shared Parse
  function (`grep -rn "shared" <their>.kt` → only comments/none; e.g.
  HQPorner's search/load/recommendations selectors live directly in
  `HQPorner.kt`). Their only extraction seam is jsoup calls inside
  `MainAPI` methods — exactly the layer ADR-0005 leaves untested
  ("HTTP transport, `MainAPI` flows … deliberately outside the loop").
- **Nothing enforces test presence, confirmed at all three layers**:
  - `lint.yml` gates are plugin-shape (issue #169/#366), Jackson version
    (#347 P1-7), and dependency review — none inspect `src/test`.
  - `build.yml` runs `./gradlew test make makePluginsJson`; for a provider
    with no own tests this is not a no-op (root `build.gradle.kts:64-68`
    splices `shared/src/test/kotlin` into every provider's test source set,
    so `HQPorner:test` runs the shared Parse tests) — but it never fails for
    *missing provider tests*.
  - `pipeline.verify` in `config.toml` is empty — review is the gate, and no
    review checklist in `docs/` requires test presence either.
- **So the reported asymmetry is real but bounded**: ADR-0005's rule is
  forward-looking ("parsing/extraction logic is developed test-first"), and
  the five no-test providers predate it with no Parse seam to test. Forcing
  tests onto them today would require either the HTTP-mocking seam ADR-0005
  explicitly rejected, or a refactor of working, Verification-covered
  providers — churn without a regression driver.

## Decision (recorded, not enforced — the minimal resolution)

**Test presence is not a merge requirement.** Enforcing it via CI would fail
all five providers on their next PR and demand the rejected seam; the actual
quality bar for these providers remains pipeline Verification (live-site
checks). The gap is closed by making the exception explicit and *bounded*:

- ADR-0005 gains a decision addendum: providers whose extraction is inline in
  `load()` with no Parse seam are grandfathered; the exception list is the
  five providers above; and the closing trigger is ADR-0005's own rule —
  **the next change that touches a provider's parsing/extraction logic must
  extract a Parse function and develop it red → green**, which organically
  retires providers from the list. The list is drift-visible: a provider
  appearing here without being in the ADR list is a review finding.
- Enforcement stays available as a future option if drift shows the addendum
  being ignored (a lint gate keyed to the ADR list is the natural shape) —
  deliberately not built now; nothing today violates the documented state.

## Fix (minimal)

One file: `docs/adr/0005-tdd-first-provider-code.md` — an addendum section
recording the maintainer decision (grandfathered providers, the five-name
exception list, the touch-triggers-tests rule, and the future enforcement
option). No workflow change (`lint.yml` untouched), no provider code change.

## Verification

- Docs-only change — no provider touched, so the verify-provider skill's
  live-site script does not apply (same bar as #368's ADR banners and #347
  P1-6/P1-10 findings: `.github/**` untouched, so actionlint is likewise
  not required — and indeed not run).
- **Exception-list accuracy (mechanical check)**: for every top-level dir
  with a `build.gradle.kts` and no `src/test`, the ADR addendum line was
  grepped for the name — all 5 no-test providers (`FreePornVideos`,
  `FullPorner`, `HQPorner`, `XMoviesForYou`, `ixiporn`) are listed; the
  reverse check (the 5 listed names now having `src/test`) found none.
  List matches the tree exactly.
- **Consistency check**: AGENTS.md's TDD paragraph already scopes the rule
  to "new parsing/extraction logic" — the addendum states the same scope
  and adds only the exception list and closing trigger, so the two
  documents agree.
- **Gradle sanity**: `./gradlew HQPorner:test --offline` → exit 0 (shared
  tests splice into the provider's test source set per root
  `build.gradle.kts:64-68`; nothing in this change affects builds).
