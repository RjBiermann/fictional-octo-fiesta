# FINDINGS-444 — PandaMovies: "search issue" (third report, hardening)

Third "search broken" report for the same provider (after #425, #439 — both closed as
non-repro). Per AGENTS.md this trigger is the hardening threshold, not another point-in-time probe.

## What was probed (2026-09-18, plain curl, UA Mozilla/5.0)

1. The exact query works from runner egress, on both endpoints:
   - `GET ?s=Rocco%27s+intimacy` → 200, 8 correct cards (Rocco’s Intimacy, … 2, Rocco’s 4 Cams POV…).
   - `GET /search/Rocco%27s+intimacy` (the URL the provider builds) → identical 8 cards.
   - Case variants, `%20` vs `+`, raw `'` — all 200 with correct cards.
2. **New evidence in this report**: the user-facing failure mode is now observable —
   the reported wrong list ("the best of taylor sands", "at-22", "picking up teen",
   "by appointment only") shares **zero query tokens** with "Rocco's intimacy". Probing
   near-miss encodings reproduces the class of response server-side: the WP search
   returns arbitrary fuzzy/random junk for mangled or poorly matched queries, e.g.
   - `?s=roccos+intimacy` → Penetrasians, Täglich juckt die Lederhose, … (no "rocco"/"intimacy")
   - `?s=rocco’+s+intimacy` (curly apo split) → Wicked POV, Fembot Academy, …
   - `?s=rocco%2527s+intimacy` (double-encoded) → Here To Do Dick and Hand, …
   The `/search/` endpoint behaves identically, so endpoint choice is not the bug;
   the site occasionally serves any of these for a query it fails to parse (UA/keyboard
   variance from a device, or a transient bad response — cannot be observed after the
   fact from the runner, per FINDINGS-439's standing gap).
3. The SearchWP quick-search endpoint (admin-ajax) returns exactly correct results
   *including* with a curly apostrophe — `hasQuickSearch` remains false; not used here.
4. `PandaMovies:test` green against current code; deployed v2 artifact current (FINDINGS-439 §4).

## Why a fix now

Three closed-frequency reports on the same provider + a user-visible mechanism that is
**detectable client-side**: garbage result pages have zero query/title token overlap,
while healthy result pages always overlap (every real result for the cited query shares
"rocco" or "intimacy"; fixtures confirm). AGENTS.md hardening menu: structural matching
/ multi-endpoint fallback. Implemented as both, in pure `Parse` functions.

- `Parse.normalizeQuery`: curly apostrophes (’/‘/`&#8217;`-style residue) → straight `'`
  before URL-encoding, so keyboard-derived curly input hits the healthy path.
- `Parse.queryMismatch(cards, query)`: true iff cards are non-empty and no card title
  shares any ≥3-char query token (apostrophe-stripped, case-insensitive).
- `search()`: on page 1, if `queryMismatch` → retry once via `?s=`; if the retry is not
  a mismatch either, use it; otherwise keep the first response (never fabricate emptiness).

## Gate

- `PandaMovies:test` — new tests (red → green) on committed fixtures: cited-query capture
  must NOT be a mismatch; the reproduced garbage page shape must be a mismatch; curly
  input normalizes.
- Clean build (`gradle build`) per pipeline.
- `.pi/skills/verify-provider/scripts/verify.sh` — not present in the run workspace
  (devloop-side skill asset), same flag as FINDINGS-439; parse-level substitute is the
  fixture suite + this run's probes.
- Version bumped 2 → 3.

Not merged here; left committed for human review.
