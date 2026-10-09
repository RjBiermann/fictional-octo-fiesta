# FINDINGS-538 — Eroticmv tags field parse regression

Probe 2026-10-09, plain curl (`curl -s -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0"`)
from the execution runner. Field-exposure regression probe following audit run 8 (issue #524
finding #538). Adjacent to but distinct from open #497 (homepage facet breadth).

## Problem (issue #538)

`Eroticmv.kt` `load()` parsed `tags` from the standalone VideoObject JSON-LD
`articleSection` array. Live watch pages no longer carry that key in the VideoObject —
`tags` parse to `null` on every sampled video.

## Probes

Three watch pages fetched, HTTP 200 each:

- `https://eroticmv.com/tokyo-nights-2025/`
- `https://eroticmv.com/any-and-every-which-way-2010/`
- `https://eroticmv.com/a-road-to-viabra-s1-ep-1-2020/`

### 1. VideoObject lost `articleSection` (confirmed)

First `<script type="application/ld+json">` on every page now carries only
`name / description / thumbnailUrl / uploadDate / contentUrl / publisher` — transcript
(tokyo-nights-2025):

```
{"@context":"https://schema.org","@type":"VideoObject","name":"Tokyo Nights (2025)",
"description":"…","thumbnailUrl":"https://eroticmv.com/wp-content/uploads/2026/10/tokyo-nights-2025-750x422.jpg",
"uploadDate":"2026-10-06T06:55:55+00:00",
"contentUrl":"http://aHR0cHM6Ly92aWRjZG4yLmVyb3RpY212LmNvbS9kYXQxL3Rva3lvbmlnaHRzMjAyNS90b2t5b25pZ2h0czIwMjUubTN1OA==.m3u8",
"publisher":{"@type":"Organization","name":"Erotic Movies",…}}
```

No `articleSection` key. The provider's old `jsonLd.substringAfter("articleSection")`
hit only this script (`selectFirst`), so `raw` was empty → `tags = null`.

### 2. Correction to the audit: `articleSection` survives in the Yoast `@graph` Article node

The page's *second* ld+json script (yoast-schema-graph) still carries the values, under
`@graph[…]"@type":"Article"` (transcript, tokyo-nights-2025):

```
"…title":"Tokyo Nights (2025)","datePublished":"2026-10-06T06:55:55+00:00",
"articleSection":["2020s","Asian Erotica","Philippines","Swinging","Threesome"],…
```

### 3. Static markup mirror — the source the fix uses (scoped anchor block)

Every watch page renders the video's own categories as anchors in the post header:

```html
<div class="categories-elm meta-font"><div class="categories-wrap">
 <a data-cat-id="cat_157" href="https://eroticmv.com/category/decades/2020s/" title="2020s" class="category-item …">2020s</a>
 <a data-cat-id="cat_100" href="https://eroticmv.com/category/genre/asian-erotica/" title="Asian Erotica" class="category-item …">Asian Erotica</a>
 <a data-cat-id="cat_171" href="https://eroticmv.com/category/country/philippines/" title="Philippines" class="category-item …">Philippines</a>
 <a data-cat-id="cat_103" href="https://eroticmv.com/category/genre/swinging/" title="Swinging" class="category-item …">Swinging</a>
 <a data-cat-id="cat_168" href="https://eroticmv.com/category/genre/group/" title="Threesome" class="category-item …">Threesome</a>
</div></div>
```

Present exactly once per watch page (`grep -c categories-elm` → 1 on all three). Anchor set per page:

- tokyo-nights-2025 → `2020s, Asian Erotica, Philippines, Swinging, Threesome`
- any-and-every-which-way-2010 → `2010s, Asian Erotica, Japan, Prostitution`
- a-road-to-viabra-s1-ep-1-2020 → `2020s, Asian Erotica, India, Swinging`

These are decade + country + genre display names — exactly the old `articleSection` semantics
(old fixture `embed-page.html` articleSection: `["2010s","Cuckold","Interracial","MILF","Newage Porn","USA"]`).
The `.categories-elm` scope excludes the nav palette: the page's ~150 other `/category/`
anchors carry `menu-item-object-category` classes inside the twice-rendered navigation menu,
no `category-item` class, and sit outside `.categories-elm`.

### 4. Still healthy (unchanged, live-confirmed)

- `og:title` with year regex `\((\d{4})\)` — `Tokyo Nights (2025)` OK
- `og:image`, `og:description` OK
- `.actor-element.single-element a[href*='/actor/']` — 8 actor anchors on tokyo page OK
- JSON-LD `contentUrl` m3u8 unchanged; stream extraction untouched

## Fix applied

`parseTags(document)` in `Eroticmv.kt` companion: `document.select(".categories-elm a.category-item")`
→ trimmed text, distinct; `null` when absent. `load()` uses it instead of the
VideoObject `articleSection` substring hack. Fixture `watch-tokyo-nights.html`
(fresh probe 2026-10-09); test `EroticmvTagsTest` red → green (ADR-0005).

Not done: re-sourcing tags from the Yoast `@graph` (option 2) — the anchored block is
plain static markup independent of the SEO plugin's graph format and mirrors the same
values; add a fallback if `.categories-elm` itself disappears (would be a new probe).

## verify.sh run 2026-10-09 (post-fix)

Config: search `/?s=Tokyo` `article.post-item` (+h3.entry-title), home `/` + `/page/2/`,
5 video URLs (3 audit-sampled + 1 homepage + 1 genre/milf facet listing),
stream `meta[property=og:video:url]`, related `div.single-related-posts article.post-item`,
tags `div.categories-elm a.category-item`, Referer eroticmv.com,
load-response recommendations,tags,plot,year,actors.

- check 1 search: 200, 16 cards, tokyo-nights-2025 present — PASS
- check 1a homepage: 200, 24 + 24 cards, page 2 new items — PASS
- check 2 videos: 5/5 fetch + `og:video:url` match — PASS
- streams: 5/5 → 206, m3u8 playlist body (all Shape-A direct, `vidcdn2.eroticmv.com`) — PASS
- **tags field: `present on all 5 sampled pages` — PASS (the fixed field)**
- check 6: recommendations/tags/plot/year/actors all populated in Kotlin — PASS
- check 5 title agreement — PASS (search card ↔ load og:title, `h3.entry-title` inner text)

### Documented script artifacts (raw HTML adjudicated)

- check 5 poster mismatch (`tokyo.nights-165x248.jpg` vs `tokyo.nights.jpg`): same-family WP
  size crop — search card serves the lazy `data-src` 165x248 variant, og:image the full image;
  video page has no 165x248 path so equality is impossible. Same adjudication as the
  2026-09-11 run (FINDINGS.md). Search markup matches provider code.
- actors run 1: `--video-actors-selector` reported 2/5 — pydom `field` takes the FIRST
  matched block's inner text and that block is the img-only `a.blog-img` anchor (empty text);
  raw HTML: `/actor/` links present on **all 5** pages (6/8/3/28/7 occurrences),
  `parseActors` selector unchanged and green in EroticmvActorsTest. Selector dropped from 2a,
  FINDINGS carries the note (same adjudication path as 2026-09-11).
- check 4 related: chained pydom counts inflated (23–25 vs raw 12) — raw extraction of the
  `.single-related-posts` section per page: 12 distinct rec titles each, **0 duplicates,
  no self-hit on all 5 pages**.
- quick search: no distinct endpoint (hasQuickSearch=false, unchanged — FINDINGS-497 era
  note stands).

## Risks / blockers

None — plain curl 200, no age wall, no challenge. Geo: runner IP (US); no region wall observed.
