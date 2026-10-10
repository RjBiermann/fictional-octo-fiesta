# FINDINGS-559 — eroticmv.com facet pagination — 2026-10-14 (issue #559)

## Issue
`https://eroticmv.com/category/genre/classic-porn/page/2/` — facet rows were
hardcoded 1-page (FINDINGS-497, 2026-09-29: `/page/2/` and `?paged=2` both
404 → `homeUrlFor` returned null for every facet at page > 1). Pagination
missing for every facet row in-app.

## Live probe (this run, curl, UA Mozilla/5.0)

### Site changed: facets paginate now
- `GET /category/genre/classic-porn/` → 200, `rel="next" href=".../page/2/"`
- `GET .../page/2/` → 200, `rel="prev"` + `rel="next" .../page/3/`
- `GET .../page/3/`, `/4/` … `/14/` → 200; `/page/14/` has **no** `rel="next"`
  → last page; `/page/15/`, `/16/` → 404
- Both cross-checked against `article.post-item` blocks (jsoup-scoped, not the
  raw-page grep that picks up sidebar/related dupes):
  - page 1 → 24 cards, page 2 → 24 cards, **overlap of slugs = 0**
  - page-2-specific slugs confirmed real (ball-in-the-family-1988,
    barbies-fantasy-1974, crazy-with-the-heat-1986 …)
- Not classic-porn-specific: `/category/country/usa/page/2/` → 200 with
  rel=next; `/category/decades/1970s/page/2/` → 200. All 75 facets paginate.
- Boundary: past the last page → HTTP 404, empty body for cards. Provider's
  `getMainPage` maps an empty/failed fetch to empty list + `hasNext = false`,
  which terminates pagination correctly.

### Homepage unchanged
- `/page/4/` → 200 (existing #517 pagination still works).

## Diagnosis (code vs. site)
- `Eroticmv.homeUrlFor(data, page)`: `page > 1 -> null` for facet data ⇒
  in-app pages past 1 return an empty list — exactly the "pagination missing"
  report. Selector/markup unchanged (`.post-item`, `a.blog-img`, data-post-id).
- Bug list (one item): facet page-URL construction only.

## Fix
`homeUrlFor` in `Eroticmv.kt` companion object: `page > 1` on a facet
`data` → `data.removeSuffix("/") + "/page/$page/"` instead of `null`.
`hasNext` already keys off card presence, so facet pagination terminates.
Test updated red → green (`EroticmvPaginationTest`): facet page 2 →
`/page/2/` URL, page 1 → plain URL.

Open question for reviewers: the 1-page behavior was FINDINGS-497 evidence —
the theme (or WP) changed since 2026-09-29. Live re-probe above is the ground
truth now.

## Delivery
Committed on `devloop/issue-559`, pushed, PR #560 open — not merged, human review only.

Run A (facet rows = fixed surface + full data surface): search `/?s=classic`
(22 cards, `article.post-item` + `h3.entry-title`), home = classic-porn page 1
+ page 2 (24 cards each, zero dupes — the fix surface), 5 video URLs across
facets/homepage (death-shock-1981, star-babe-1977,
resurrection-of-eve-1973, creampie-2026, gorgeous-curvy-milf-cuckold), streams
206 with m3u8 bodies via provider-resolved URLs, related
(`div.single-related-posts article.post-item`, ~23 recs/video, no self-hits,
no dup titles), tags present on all 5 pages, LoadResponse fields all populated
in code. **Rows are calibrated separately**: homepage "Latest" row and facet
rows are different lists in-app; merging their URLs into one run false-FAILs
on real cross-row overlap (newest posts appear in Latest and their genre
row).

Run B (homepage "Latest" row regression, unchanged surface): home `/` +
`/page/2/` PASS (exit 0), plus foreplay-1982 Shape-A stream (206 m3u8,
decode matches provider output).

### Documented script artifacts (same family as FINDINGS-321, adjudicated)
- **pydom selector grammar**: any chained selector starting with a tag-less
  part (`.single-related-posts …`) counts -1; and search/related card titles
  extracted from `a.blog-img` roots are empty (title `h3.entry-title` is a
  sibling of the link, not inside it). Calibrated with `article.post-item`
  / `div.single-related-posts article.post-item` card roots instead.
- **Search↔load poster agreement is mechanistically impossible on this
  site**: card `data-src` posters are `-165x248` size crops, `og:image` on the
  watch page has no crop — for all 5 sampled videos (FINDINGS-321 noted the
  same for creampie-2026). So the agreement check was left unexercised (NOTE)
  rather than fed a query that fakes a FAIL; title agreement shape is already
  covered by the exact selectors the cards use.
- **actors selector omitted from this run on purpose**: the site exposes the
  `.actor-element.single-element` Stars block inconsistently (present on 4/5
  sampled pages; absent on gorgeous-curvy-milf-cuckold — FINDINGS-321
  recorded the same pair), and the anchor's actor name lives in `title=""`
  (empty inner text on some pages, e.g. creampie-2026's "Christy Imperial"),
  which the script's text proxy can't see. The provider reads `attr("title")`
  and handles the missing block gracefully (empty list) — site shape
  variance, not parser drift. Site **does** expose actors on most pages.
- year: exposure asserted in code (og:title "(YYYY)" regex); no separate
  page element exists — not script-asserted. duration: site does not expose.
- quick search: no distinct suggest endpoint (provider `hasQuickSearch = false`,
  issue #290 FINDINGS) — intentionally omitted.
