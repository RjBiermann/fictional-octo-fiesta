---
name: site-probe
description: Probe an adult video site and produce FINDINGS ground truth (engine fingerprint, search pattern, video page structure, stream source, headers, pagination) before writing any CloudStream provider code. Use when building or fixing a provider for a site.
---

# Site Probe

Turn a URL into ground truth. No Kotlin is written until FINDINGS is complete: every selector
and endpoint in provider code must exist in FINDINGS evidence. Never guess.

## Output

A `FINDINGS.md` in the provider directory with this structure:

```markdown
# FINDINGS — <site name>

## Engine fingerprint
<engine + the evidence that identified it>

## Search
<pattern(s) tested, the one that works, curl transcript>

## Video page
<URL structure + selector evidence, curl transcript>

## Stream source
<where the stream URL lives, curl transcript, content-type check>

## Headers / referer
<what requests require, evidence>

## Pagination
<pattern, evidence>

## Risks / blockers
<Cloudflare, age walls, IP blocks, anything that would break a runner>
```

Rules:
- Every selector and endpoint gets a curl transcript in the doc (`curl -s ... | grep ...` is fine).
- Prove the stream actually serves video: HTTP 200 + `Content-Type: video/*` or m3u8 playlist body.
- Test at least two search URL patterns; keep the one that returns results.
- If the site blocks the probing machine (Cloudflare challenge, IP ban), record it under
  Risks/blockers with the response evidence — Blocked is a valid FINDINGS outcome, not a failure.

## Probe order

### 1. Fingerprint the engine

Fetch the homepage and check [references/patterns.md](references/patterns.md) fingerprints in
this order: KVS/Kernel → WP video theme → custom. Record which fingerprint matched, with the
HTML snippet. Reuse engine knowledge: same engine ⇒ same search/stream layout as other sites
of that engine.

### 2. Search

Common patterns to try (fill in from the engine fingerprint):
- `/{search}/{query}/`, `/search/{query}/`, `/?s={query}`, `/search/{query}/{page}/`
- KVS JSON: `/api/json/...` or `/search/{query}/?mode=async`
Judge by results: the working pattern returns item HTML/JSON matching a repeatable structure.
Capture the exact URL and a transcript showing ≥1 result.

### 3. Video page

Pick one result. Identify: title, poster, tags/categories, description, duration, upload date —
each with a selector proven against the fetched page (`og:` meta tags are often the cheapest
source). Capture the URL shape (slug vs numeric id).

### 4. Stream source

Find the stream URL, in this order:
1. Direct: `<video><source>` / `og:video` / player config in inline JS (`video_url`, `contentUrl`,
   `flashvars`, `setVideoUrl*`)
2. m3u8/HLS: search the page and its JS for `.m3u8`
3. Embed iframe: if the stream lives on an embed domain, record the embed URL pattern. Then
   **check the repo's existing extractors first** (grep the provider directories for that
   domain) — reuse before writing a new extractor.
Verify the stream URL with a real request (headers + referer if needed) and record the
content-type. Player configs are often URL-encoded or base64 — decode before recording.

### 5. Headers / referer

Replay the stream request without the referer/UA, then with. Record which combination is
required — providers die silently on missing referer.

### 6. Pagination

Page 2 of search and home listing: `/{page}/` suffix, `?page=N`, AJAX endpoint? Record the
pattern and confirm page 2 returns *different* items.
