---
name: verify-provider
description: Prove a CloudStream provider's selectors, response data, and stream URLs against the live site — search, quick search, homepage rows, every pagination, and all exposed fields return real site data, and streams serve video. Run after building a provider or fixing one. Use when validating a provider.
---

# Verify Provider

Gradle passing ≠ works. This skill runs the mechanical checks against the live site; the agent
supplies what to check (judgment, from FINDINGS + the provider's Kotlin), the script asserts
(mechanics).

## Usage

```bash
.pi/skills/verify-provider/scripts/verify.sh \
  --search-url 'https://site.com/?s=query' \
  --search-url 'https://site.com/?s=query&page=2' \
  --search-selector 'a.videocard' \
  [--search-title-selector 'h2.title'] [--search-poster-selector 'img.poster'] \
  --home-url 'https://site.com/' \
  --home-url 'https://site.com/page/2/' \
  [--home-selector 'a.videocard'] \
  [--quick-search-url 'https://site.com/search/suggest/?q=query'] \
  [--quick-search-selector 'a.videocard'] \
  --video-url 'https://site.com/recent/slug-a/123/' \
  --video-url 'https://site.com/genre/slug-b/456/' \
  --video-url 'https://site.com/genre/slug-c/789/' \
  --video-url 'https://site.com/cat/slug-d/012/' \
  --video-url 'https://site.com/cat/slug-e/345/' \
  --stream-selector 'source[src]' \
  [--stream-url 'https://cdn…/video.mp4' …] \
  [--stream-quality-attr res] \
  [--related-selector 'section#related a.card'] \
  [--video-title-selector 'h1'] [--video-poster-selector 'meta[property=og:image]'] \
  [--video-plot-selector 'meta[property=og:description]'] \
  [--video-tags-selector 'span.tag'] [--video-actors-selector 'span.actor'] \
  [--video-year-selector 'span.year'] [--video-duration-selector 'span.duration'] \
  [--provider-src '<provider dir>'] \
  [--load-response 'recommendations,tags,plot,duration,year,actors'] \
  [--header 'User-Agent: …'] [--header 'Referer: …']
```

- `--search-url` is **repeatable** (page 1 + page 2 from FINDINGS). At least one query must
  match a sampled video — that is what exercises the search↔load agreement check.
- `--home-url` is **repeatable**: page 1 + page 2 of the homepage rows the provider's
  `getMainPage` renders (FINDINGS Homepage section). Omit page 2 only when FINDINGS explicitly
  records "homepage does not paginate". `--home-selector` defaults to the search selector —
  override when the homepage rows use a different card shape.
- `--quick-search-url`: **required when FINDINGS records a distinct quick-search endpoint**
  (the site's live-typing/suggest endpoint). Quick search is a single page — no pagination.
  When FINDINGS records "no distinct quick-search endpoint", omit it; the script NOTEs, and the
  provider's `hasQuickSearch` stays `false`. Never left unstated either way.
- `--video-url` is **repeatable**: supply ≥5 varied URLs (different listings — most-recent,
  genres/categories, related) so page-shape and source differences are covered. Fewer only when
  FINDINGS records the site has fewer.
- Video title/poster/plot selectors default to `og:title` / `og:image` / `og:description`.
  Override from FINDINGS when the site uses other markup — e.g. if the page title carries a
  site-name suffix that the search card title lacks, pick a selector that excludes it.
- `--video-tags/actors/year/duration-selector` come from FINDINGS whenever the site exposes the
  field. An omitted selector requires FINDINGS to explicitly record "site does not expose X" —
  an omitted selector with unstated FINDINGS is a probe bug, not a pass.
- `--related-selector` comes from FINDINGS when the site exposes a related-videos section.

## What it asserts

1. **Search**: every search page fetches and has ≥1 card matching `--search-selector`; no
   duplicate cards within a page or across pages — so page 2 must return *different* items than
   page 1 (pagination returning the same cards is a FAIL).
1a. **Homepage rows** (`getMainPage`): same bar as search — every `--home-url` fetches, ≥1 card,
    no duplicates within/across pages (page 2 must return new items). Defaults to the search
    card shape unless `--home-selector` is given.
1b. **Quick search**: when `--quick-search-url` is passed, the endpoint fetches and returns ≥1
    card, no duplicate cards (single page, no pagination).
2. **Video pages**: every page fetches, matches `--stream-selector`, and yields a title.
   Poster/plot are **all-or-none** across sampled pages — present on some but missing on others
   is an inconsistent page shape (FAIL); missing everywhere means the site doesn't expose it
   (NOTE, excluded from distinctness).
2a. **Field exposure**: any of tags/actors/year/duration with a selector passed is
   all-or-none across sampled pages, same rule as poster/plot. Omitted selectors are NOTEs.
3. **Distinct bar (glossary: Distinct)**: across the sampled videos, no two videos share a
   title, plot, poster path, or stream path; no stream path repeats within one page. Title,
   plot, poster, and stream URLs are the *identity fields* — tags/actors/year/duration/score
   are never distinctness-checked (they legitimately repeat).
4. **Streams**: every extracted stream URL (≤5 per page) responds HTTP 200/206 with
   `video/*` or an m3u8 playlist body.
5. **Agreement**: a sampled video that appears in the search page(s) must agree with its load
   page — same normalized title, same poster path. (Duplicate `--video-url` inputs are
   rejected: self-comparison would fake-FAIL distinctness.)
6. **Data-complete (code half)**: with `--load-response` + `--provider-src`, every listed field
   has ≥1 population assignment in the provider Kotlin. Whether the site actually exposes each
   field — and the selector proving it — comes from FINDINGS; the agent judges that, the script
   asserts exposure consistency (check 2a).

Recommendations (`--related-selector`): titles non-empty, pairwise distinct, and never the
video itself.

## Rules

- Selectors/URLs must come from FINDINGS or the provider's Kotlin — never invented.
- Use the varied video URLs site-probe recorded (recent / genre / related), not five URLs
  scraped from one listing — the point is catching page-shape and per-video source variety.
- Every surface FINDINGS records gets a flag: homepage rows, distinct quick-search endpoint,
  related videos, each exposed LoadResponse field. FINDINGS must explicitly record every
  surface and field that is *absent* — "does not paginate", "no distinct quick-search
  endpoint", "site does not expose X" — never unstated.
- PASS/FAIL per check with the request evidence printed; copy the output into the PR as proof.
- Exit code: 0 only if all checks pass. On FAIL, fix the provider (or FINDINGS) and re-run —
  do not open a PR with failing checks. A stream that 403/451s from the runner but is evidence
  of an access wall (challenge page body, 451) may be recorded as **Blocked** for that video
  with the response evidence — only after re-testing via FINDINGS' alternate embed; a broken
  selector or wrong content-type is a FAIL, never Blocked.
