# TDD-first for provider code

This repo previously validated Kotlin changes only by `gradlew make` plus live-site Verification, which let scraping regressions surface late (at Review rounds or Monitor drift). We decided parsing/extraction logic is developed test-first (red → green) at a **Parse-function seam**: pure `parseXxx(html): List<…>` functions tested with checked-in **Fixtures** (HTML/JSON saved from FINDINGS evidence) under `src/test/resources/`, run via `gradlew <Provider>:test` — JUnit4, wired once in the root build, with `shared/src/test/kotlin` spliced into every provider's test source set. HTTP transport, `MainAPI` flows, workflow YAML, Gradle files, and bash scripts are deliberately outside the loop: liveness stays owned by Verification and the Monitor's Drift probe, so unit tests pin parser behavior but never pretend to prove the site still works.

Alternative rejected: an HTTP-mocking/injection seam to test `load()` end-to-end — real over-engineering against the CloudStream runtime and the global `app` HTTP object.

## Addendum (review #347 P1-3): test presence is not a merge requirement

Decided (issue #363): CI does **not** enforce test presence. A lint gate keyed to
`src/test` existence would fail every provider below on its next PR and force
refactors of working, Verification-covered providers — or the HTTP-mocking seam
rejected above. Providers whose extraction is inline in `load()` (no Parse seam
to test) are **grandfathered**:

- `FreePornVideos`, `FullPorner`, `HQPorner`, `XMoviesForYou`, `ixiporn`
  (the complete list as of this decision; all predate ADR-0005 with no
  Parse function).
- The exception closes itself through the base rule: **the next change that
  touches a provider's parsing/extraction logic must extract a Parse function
  and develop it red → green**, which retires the provider from the list.
  Unrelated fixes (headers, plugin shape, version bumps) do not trigger it.
- The list is drift-visible: a provider shipping no `src/test` without being
  in this list is a review finding, not a CI failure.
- Enforcement remains available if drift shows the addendum being ignored —
  the natural shape is a lint gate checking this list against the tree.
  Deliberately not built now; nothing in-tree violates the documented state.
