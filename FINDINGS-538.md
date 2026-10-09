# FINDINGS-538 — Eroticmv: JSON-LD `articleSection` gone from watch pages (tags parsed empty)

Probed 2026-10-09 (this run), plain `curl`, IPv4 runner. Full site ground truth for eroticmv.com
lives in `Eroticmv/FINDINGS.md` (pass 1) + FINDINGS-497/517; this file records only what changed
since. Adjacent to but distinct from #497: #497 is homepage facet breadth — this is a watch-page
field-parse regression.

## Reproduce — confirmed

Issue claims: `load()` derives `tags` from the JSON-LD `articleSection` array; live VideoObject no
longer carries the key → `raw` empty → `tags = null`.

Live VideoObject keys, all sampled watch pages (single `application/ld+json` block per page):

```
$ curl -sL 'https://eroticmv.com/tokyo-nights-2025/' | python3  # json.loads of the ld+json block
VideoObject keys: ['@context','@type','aggregateRating','contentUrl','description','name',
                   'publisher','thumbnailUrl','uploadDate']          # no articleSection
```

| watch page (listing it came from)              | ld+json keys                                  |
|------------------------------------------------|-----------------------------------------------|
| /tokyo-nights-2025/            (issue sample)  | @context, @type, contentUrl, description, name, publisher, thumbnailUrl, uploadDate |
| /any-and-every-which-way-2010/ (issue sample)  | + aggregateRating (rating 3.9) — still no articleSection |
| /a-road-to-viabra-s1-ep-1-2020/ (issue sample) | + aggregateRating — still no articleSection |
| /wafa-s1-ep-5-7-2023/          (homepage "Latest") | no articleSection |
| /high-school-bunnies-1977/     (genre facet ghost) | no articleSection |
| /story-of-a-cloistered-nun-1973/ (homepage "Latest") | no articleSection |

6/6 sampled: **`articleSection` key absent** — regression reproduced on every sampled page, not a
transient. (Some pages gained `aggregateRating`, which the old parser never read.)

## What survived

All other `load()` inputs are healthy on every sampled page:

- `og:title` — `"Watch Tokyo Nights (2025) - Erotic Movies"` (year `(YYYY)` regex works)
- `og:image`, `og:description` — populated
- actors — `.actor-element.single-element a[href*='/actor/']`: 8/8/8 per-page anchors on issue
  samples (6–28 on the others); `parseActors` alive
- `og:video:url` — Shape A base64 token on all six,

```
http://aHR0cHM6Ly92aWRjZG4yLmVyb3RpY212LmNvbS9kYXQxL3Rva3lvbmlnaHRzMjAyNS90b2t5b25pZ2h0czIwMjUubTN1OA==.m3u8'
→ decode → https://vidcdn2.eroticmv.com/dat1/tokyonights2025/tokyonights2025.m3u8
→ curl Range 0-64 with Referer https://eroticmv.com/ → 206 application/octet-stream, body "#EXTM3U"
# streams differ per video (every sampled page has its own vidcdn2/dat1/<slug>/ path)
```

## Replacement tag source (live)

The watch page carries its categories block as static anchors, scoped to the post header — not
the nav (nav category links are `menu-item` list items, no `category-item` class):

```html
<article id="post-45553" class="single-post-content global-single-content ... category-2020s ...">
  <header class="entry-header">
    <div class="categories-elm meta-font"><div class="categories-wrap">
      <a data-cat-id="cat_157" href="https://eroticmv.com/category/decades/2020s/"
         title="2020s" class="category-item m-font-size-10">2020s</a>
      <a ... href=".../category/genre/asian-erotica/"   title="Asian Erotica">Asian Erotica</a>
      <a ... href=".../category/country/philippines/"   title="Philippines">Philippines</a>
      <a ... href=".../category/genre/swinging/"        title="Swinging">Swinging</a>
      <a ... href=".../category/genre/group/"           title="Threesome">Threesome</a>
    </div></div>
```

Per-page counts and values (anchor text = title attr in all probed cases):

| watch page                             | `.categories-wrap a.category-item` text   | anchors |
|----------------------------------------|-------------------------------------------|---------|
| tokyo-nights-2025                      | 2020s, Asian Erotica, Philippines, Swinging, Threesome | 5 |
| any-and-every-which-way-2010           | 2010s, Asian Erotica, Japan, Prostitution | 4 |
| a-road-to-viabra-s1-ep-1-2020          | 2020s, Asian Erotica, India, Swinging     | 4 |
| wafa-s1-ep-5-7-2023                    | 2020s, Asian Erotica, Cheating, India     | 4 |
| high-school-bunnies-1977               | 1970s, Classic Porn, Gangbang, USA        | 4 |
| story-of-a-cloistered-nun-1973         | 1970s, Classic Erotica, Italy, Nuns, Religious | 5 |

- On every sampled watch page **all** `category-item` anchors on the page live inside the single
  `.categories-wrap` (verified: total vs in-wrap counts equal), so scoping to `.categories-wrap`
  future-proofs against nav restyling without changing results today.
- Richer than the old `articleSection` parse (which returned a genre list): now includes decade +
  country + display label of the slug (`genre/group` shows "Threesome").
- Same block also appears inside listing cards (`article.post-item` carries an identical
  `categories-elm`), so the selector scoping must stay anchored to the watch-page header block —
  `.categories-wrap a.category-item` in `load()` (watch page fetch) reads this page's own tags;
  on watch pages the first match in document order IS the video's own header block.

## Fix applied (this run)

- `Eroticmv.parseTags(document)` — pure Parse fn (ADR-0005) over
  `.categories-wrap a.category-item`, `distinct()`, fixture
  `Eroticmv/src/test/resources/watch-tokyo-nights-2025.html` (fresh full-page capture incl. nav,
  so the scoping contract is tested, not assumed). Red → green before touching `load()`.
- `load()` switched to `parseTags`; dead JSON-LD `articleSection` string-slicing removed.
- `Eroticmv/build.gradle.kts` version 9→10 in-tandem (plugin `version` bump, repo rule).

## Risks / blockers

None observed — no CF challenge, no age wall, streams serve with referer+UA only. Runner region
US (probe IP); eroticmv serves identical catalogs here.

## Field-exposure inventory (post-fix, per sampled watch page)

- title — `og:title` ✅ (all 6)
- poster — `og:image` ✅ (all 6)
- plot — `og:description` ✅ (all 6)
- tags — `.categories-wrap a.category-item` ✅ (all 6, post-fix; was JSON-LD articleSection ❌ gone)
- actors — `.actor-element.single-element a.main-color-udr` text ✅ (all 6; the name is in both
  the `title` attr and the anchor text — provider reads the attr)
- year — `og:title` `(YYYY)` ✅ (all 6)
- duration — **site does not expose it**: no `og:video:duration` or similar meta on any sampled
  page (provider correctly never populates duration)
- recommendations — `div.single-related-posts article.post-item` present (raw extraction shows the
  section; related posts section is the provider's recs selector)

## verify.sh run 2026-10-09 (post-fix) — all checks PASS except one documented artifact

Config: search `/?s=tokyo+nights` (`article.post-item`, title `h3.post-title a`), home `/` +
`/page/2/` (24 cards each), 6 video URLs from three listings (issue samples + homepage "Latest" +
facet ghost), stream selector `meta[property="og:video:url"]`, related `div.single-related-posts
article.post-item`, tags/actors/year selectors above, `Referer: https://eroticmv.com/`,
load-response `recommendations,tags,plot,year,actors,posters`.

- check 1 search 200 / 1 card; check 1a home 24+24, no dupes; check 2 all 6 videos 200, title ✓
- streams: 6/6 distinct resolved m3u8s serve `206 application/octet-stream` with `#EXTM3U` body
- field exposure: tags, actors, year — present on all 6 sampled pages; duration NOTE (not exposed);
  quick-search NOTE (no distinct endpoint — `hasQuickSearch = false`, #290)
- check 6: recommendations/tags/plot/year/actors/posters each ≥1 assignment in `Eroticmv`

### Documented script artifacts (raw HTML adjudication, precedent: Eroticmv/FINDINGS.md)

- **check 1b**: no `--quick-search-url` — eroticmv has no distinct suggest endpoint (FINDINGS #290);
  explicit here, not a gap.
- **check 5 poster FAIL, adjudicated same-image**: search card poster path
  `…/uploads/2026/10/tokyo.nights-165x248.jpg` (WP `data-src` card thumbnail) vs watch og:image
  `…/uploads/2026/10/tokyo.nights.jpg` — identical image at WP size variants; the script compares
  raw paths after query-strip only, so the resize suffix fails it. Provider behavior is correct
  (card = thumbnail, load = full poster); title agreement passed. No action.
- **related count reflects global last-part count** (23–25 = page-wide `article.post-item` count,
  not the section's ~6) — pydom artifact, recorded without action; rec titles/distinct/self checks
  scoped correctly and PASSed.
