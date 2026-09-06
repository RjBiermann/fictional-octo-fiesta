---
name: verify-provider
description: Prove a CloudStream provider's selectors and stream URLs against the live site — search returns results, stream serves video content-type. Run after building a provider or fixing one. Use when validating a provider.
---

# Verify Provider

Gradle passing ≠ works. This skill runs the mechanical checks against the live site; the agent
supplies what to check (judgment, from FINDINGS + the provider's Kotlin), the script asserts
(mechanics).

## Usage

```bash
.pi/skills/verify-provider/scripts/verify.sh \
  --search-url 'https://site.com/?s=query' \
  --search-selector 'a.videocard' \
  --video-url 'https://site.com/recent/slug-a/123/' \
  --video-url 'https://site.com/genre/slug-b/456/' \
  --video-url 'https://site.com/genre/slug-c/789/' \
  --video-url 'https://site.com/cat/slug-d/012/' \
  --video-url 'https://site.com/cat/slug-e/345/' \
  --stream-selector 'source[src]' \
  [--stream-quality-attr res] \
  [--related-selector 'section#related a.card'] \
  [--header 'User-Agent: …'] [--header 'Referer: …']
```

`--video-url` is repeatable: **supply ≥5 URLs** from FINDINGS' varied video pages — different
listings (most-recent, genres/categories, related) so page-shape and source differences are
covered; more the better. `--related-selector` comes from FINDINGS when the site exposes a
related-videos section.

## What it asserts

1. Search page fetches and contains ≥1 element matching `--search-selector`.
2. **Every** supplied video page fetches and contains ≥1 element matching `--stream-selector`
   (per-video PASS/FAIL, one combined transcript).
3. The first stream URL per video page (plus per-element quality attr if given) responds with
   HTTP 200/206 and `Content-Type: video/*` (or an m3u8 playlist body).
4. If `--related-selector` is given: every video page contains ≥1 element matching it —
   recommendations are verified here, not discovered in-app.

## Rules

- Selectors/URLs must come from FINDINGS or the provider's Kotlin — never invented.
- Use the varied video URLs site-probe recorded (recent / genre / related), not five URLs
  scraped from one listing — the point is catching page-shape and per-video source variety.
- PASS/FAIL per check with the request evidence printed; copy the output into the PR as proof.
- Exit code: 0 only if all checks pass. On FAIL, fix the provider (or FINDINGS) and re-run —
  do not open a PR with failing checks; a site that blocks the probing machine is recorded as
  Blocked instead (valid outcome, explicit note in the PR).
