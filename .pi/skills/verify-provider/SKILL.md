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
  --video-url 'https://site.com/cat/slug/123/' \
  --stream-selector 'source[src]' \
  [--stream-quality-attr res] \
  [--header 'User-Agent: …'] [--header 'Referer: …']
```

## What it asserts

1. Search page fetches and contains ≥1 element matching `--search-selector`.
2. Video page fetches and contains ≥1 element matching `--stream-selector`.
3. The first stream URL (plus per-element quality attr if given) responds with HTTP 200/206 and
   `Content-Type: video/*` (or an m3u8 playlist body).

## Rules

- Selectors/URLs must come from FINDINGS or the provider's Kotlin — never invented.
- PASS/FAIL per check with the request evidence printed; copy the output into the PR as proof.
- Exit code: 0 only if all checks pass. On FAIL, fix the provider (or FINDINGS) and re-run —
  do not open a PR with failing checks; a site that blocks the probing machine is recorded as
  Blocked instead (valid outcome, explicit note in the PR).
