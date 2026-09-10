---
name: site-probe
description: Probe an adult video site and produce FINDINGS ground truth (engine fingerprint, search and quick-search pattern, homepage rows, video page structure, stream source, headers, per-surface pagination, field-exposure inventory) before writing any CloudStream provider code. Use when building or fixing a provider for a site.
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
<the card selector, and how title/poster extract from a card: card text + first img is the
verify-provider default; note sub-selectors when the card needs them>
<a query that matches ≥1 of the sampled video pages — needed for the search↔load
agreement check>

## Quick search
<CloudStream's quickSearch surface: the site's live-typing/suggest endpoint if one exists
distinct from the search page — URL, card shape, transcript. Or an explicit "no distinct
quick-search endpoint" note (the provider keeps hasQuickSearch = false). Never left unstated.>

## Homepage
<the rows the provider's getMainPage will render: page-1 URL, card selector (usually the
search card selector — note sub-selectors when a row differs), page-2 URL, and transcript
evidence that page 2 returns different items. Or an explicit "homepage does not paginate"
note. Never left unstated.>

## Video pages
<for each of the ≥5 probed pages: URL, which listing it came from (recent/genre/related),
structure + selector evidence, curl transcript, and the extracted title/poster/description
values — verify-provider cross-checks these differ across videos>
<exposure inventory, explicit per field: title, poster, description/plot, tags/categories,
duration, upload date/year, actors — "site does not expose X" notes are mandatory for
anything the provider will not populate>

## Related videos
<the related/recommended-videos selector with transcript — or an explicit
"no related-videos section" note. Never left unstated.>

## Stream sources (per video page)
<for EACH probed video page: every source found, each with its own transcript and
content-type check. Different videos can carry different sources. Record every stream URL —
verify-provider asserts no stream path repeats across videos.>

## Headers / referer
<what requests require, evidence>

## Pagination
<per surface — search, homepage, related-videos: pattern (path-based vs query param vs AJAX),
page-2 URL, and transcript evidence that page 2 returns different items. verify-provider
requires page-2 URLs for search and homepage unless a surface explicitly records "does not
paginate".>

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
of that engine. While on the homepage, record its rows for the Homepage section: page-1 URL,
card selector, and whether it paginates (page-2 URL + evidence of different items).

### 2. Search

Common patterns to try (fill in from the engine fingerprint):
- `/{search}/{query}/`, `/search/{query}/`, `/?s={query}`, `/search/{query}/{page}/`
- KVS JSON: `/api/json/...` or `/search/{query}/?mode=async`
Judge by results: the working pattern returns item HTML/JSON matching a repeatable structure.
Capture the exact URL and a transcript showing ≥1 result. Record how title and poster extract
from one card (verify-provider defaults: card text + first `<img>`; sub-selectors when the card
needs them).

Also probe quick search: many sites have none distinct from the search page (the app falls back
to full search) — record the distinct suggest/live-typing endpoint with evidence, or an explicit
"no distinct quick-search endpoint" note. One or the other — never unstated.

### 3. Video pages — ≥5, varied

Probe **at least 5 video pages** from *different listings*: most-recent, a genre/category page,
and one pulled from a related-videos section — variety across recency and genre. More the
better beyond that floor. For each page identify: title, poster, tags/categories, description,
duration, upload date — each with a selector proven against the fetched page. Check JSON-LD
first: a `<script type="application/ld+json">` `VideoObject` block usually carries name,
thumbnailUrl, description, duration, and uploadDate in one parse — then `og:` meta tags, then
page markup (these are also verify-provider's default selectors). Record the
extracted values per page: verify-provider asserts they differ across videos (Distinct bar).
Record the exposure inventory explicitly, per field — including "site does not expose X" for
anything the provider won't populate; verify-provider NOTEs omitted field selectors only when
FINDINGS carries that note. Capture the URL shape (slug vs numeric id) and record *where* each
URL came from.

### 4. Related videos

On every probed video page, find the related/recommended-videos selector (`section#related`,
`#list_videos_related_videos_items`, etc.). Record it with a transcript, or explicitly note
"no related-videos section on this site". One or the other — never unstated.

### 5. Stream sources — enumerate ALL

For each probed video page, enumerate **every** source on it — do not stop at the first one
that works. A single video can carry several sources, and different videos on the same site
can carry different sets. For each page, in this order:
1. Direct: `<video><source>` / `og:video` / JSON-LD `contentUrl` / player config in inline JS
   (`video_url`, `contentUrl`, `flashvars`, `setVideoUrl*`) — all qualities and mirrors
2. m3u8/HLS: search the page and its JS for `.m3u8`
3. Embed iframes: record **every** embed domain and URL pattern. Then
   **check the repo's existing extractors first** (grep the provider directories for that
   domain) — reuse before writing a new extractor.
Each source gets its own verification request (headers + referer if needed) and content-type
check. Player configs are often URL-encoded or base64 — decode before recording. Record every
stream URL per page: verify-provider HEAD-checks up to 5 per page and asserts no stream path
is shared by different videos.

### 6. Headers / referer

Replay the stream request without the referer/UA, then with. Record which combination is
required — providers die silently on missing referer.

### 7. Pagination — per surface

Page 2 of each listing surface — search, homepage, related-videos: `/{page}/` suffix,
`?page=N`, AJAX endpoint? Record the pattern per surface and confirm page 2 returns *different*
items (transcript). Search and homepage page-2 URLs are mandatory in FINDINGS; a surface that
doesn't paginate gets an explicit "does not paginate" note, never silence.
