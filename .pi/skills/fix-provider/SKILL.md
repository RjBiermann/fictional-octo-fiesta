---
name: fix-provider
description: Repair a broken CloudStream provider from an issue report — reproduce on current master via live probing, fix, bump version, rebuild, verify. Composes site-probe + new-provider + verify-provider. Use when fixing a broken provider.
---

# Fix Provider

Issue-driven repair. Order matters: **reproduce before fixing** — a fix for a bug you haven't
seen live is a guess.

## Loop

1. **Reproduce** (site-probe skill): scrape the failing flow from the issue (search, listing, or
   video page) against the *current* site. The site may have moved, restructured, or the
   selectors/URLs may simply be stale — FINDINGS-format evidence for what changed goes into the
   fix. If the site blocks the probing machine, that's a Blocked outcome: report it in the PR
   with the in-app-verification note instead of guessing.
2. **Diagnose**: diff what the provider's Kotlin expects (its selectors/URL patterns) against
   what the live site serves. The gap is the bug list.
3. **Fix**: minimal change inside the provider's directory only. Update selectors/URLs/parsing
   per the evidence. Follow the new-provider skill's implementation rules (real API signatures,
   per-item try/catch, extractor ladder). **Also restore missing data** the fresh FINDINGS
   exposes: if the provider doesn't populate `recommendations` (or other `LoadResponse` fields)
   or doesn't emit sources FINDINGS shows the site serving, fix that too — a fix PR must leave
   the provider data-complete, not just unbroken. **Bump `version` in `build.gradle.kts` — repo
   rule, no exceptions.** When the fix changes parsing, update the Fixture from fresh FINDINGS
   and take the Parse-function test red → green before touching the provider (TDD-first,
   ADR-0005).
4. **Rebuild + verify** (new-provider build loop, verify-provider skill): `./gradlew
   <Name>:make` clean and `./gradlew <Name>:test` green, then `verify.sh` PASS with ≥5 varied
   video URLs (and `--related-selector` when the site exposes related videos) against the fixed
   selectors — plus homepage page 1+2 (`--home-url`), the quick-search endpoint when FINDINGS
   records one, and every exposed field selector, all from the fresh FINDINGS.
5. **Deliver**: commit on the fix branch, PR referencing the issue (`Fixes #N`) with the
   before/after evidence — what broke, what changed, verification transcript.

## Rules

- Never fix what you can't reproduce or observe (no speculative rewrites).
- If the whole site is gone or moved domains, say so in the PR — that's a finding, not a failure.
- Keep the change self-contained: one provider directory, one `version` bump.
