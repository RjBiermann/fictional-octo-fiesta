# FINDINGS — Issue #534 (Javtiful site redesign, live probe 2026-10-09)

Live saved by the probe from this run (CI runner, direct curl, no challenge/451):

- `/videos?sort=most_viewed` → 200 (`Javtiful/src/test/resources/javtiful-home.html`)
- `/videos?sort=most_viewed&page=2` → 200 (`javtiful-home-page2.html`)
- `/search?q=kana` → 200 (`javtiful-search.html`)
- `/video/83257/royd-238` → 200 (`javtiful-watch.html`)

## Verdict: site redesigned, all `front-*` listing/watch selectors dead; stream config id ALSO moved (issue body under-counted this)

## Listings (home + search + category rows — identical card markup everywhere)

- Cards: `article.video-card` (23 per page); partner ad: `article.video-card.video-card--partner`
  (formerly `front-partner-card`; href is `t.fluxtrck.site/c1/…` tracking link, still exclude).
  `a.front-video-title` / `article.front-video-card` match zero elements.
- Title link: `a.video-card__title` (href = `/video/61787/mdsr-0006-2`, text carries full title).
- Thumbnail anchor: `a.video-card__thumbnail` (preview mp4 in `data-front-video-preview-src`, ignore).
- Poster: `img[data-front-lazy-src]` — placeholder SVG in `src` (`/assets/front/media/video-thumb-placeholder.svg`),
  lazy attr carries the real thumb (`/uploads/uploads/videos/thumbs/…jpg`). Poster rule unchanged in spirit.
- Pagination: `a.pagination__link` (labels Previous/1/2/…/Next; `is-active` current; disabled
  state is `is-disabled` — page 1 has `is-disabled` on *Previous*, `Next` enabled with href).
  Page 2 disjoint from page 1 (0 overlap across 23 cards). `pagedUrl()` URL shape unchanged.

## Watch page `/video/83257/royd-238`

- Title: `div.watch-title h1` (old `div.front-watch-title h1` dead).
- Year: `div.watch-detail:contains(Added on) time[datetime]` = `2025-04-18T08:38:24+07:00`
  (old `div.front-watch-detail` prefix dead; the `:contains(Added on) time` shape survives).
- **Stream config (NOT in the issue body): `id="frontWatchConfig"` is gone.** Renamed
  `id="watch-config"`: `<script id="watch-config" type="application/json">` carrying
  `playerSources` with `src` (`fast-stream.jav.si/p/<hex>`), `type`, `size` — same JSON schema,
  same host. The old `substringAfter("id=\"frontWatchConfig\" type=\"application/json\">")`
  extraction returns garbage (whole page) → jackson `readValue` fails → `loadLinks` returns
  false. Fixed in code by switching to jsoup `script#watch-config` + `.data()` (attribute-order-proof).
- JSON-LD VideoObject still present (`duration` PT format workable, `og:image`, `og:description`).
  `JsonLdParse.minutes()` path unchanged.
- Actors: `a.watch-actor-card` (link to `/actress/<slug>`, name in `span`, real poster img upload —
  the old `profile-placeholder.png` filter has no live case now but remains harmless).
- Tags/Categories: `div.watch-detail:contains(Categories) a.watch-link-chip.is-category`,
  `div.watch-detail:contains(Tags) a.watch-link-chip` (plain) — e.g. `Married Woman`,
  `creampie`, `solowork`.
- Related: `div.video-grid.related-grid article.video-card` (20 cards, same card parser as listings).

## Net effect before fix

Home rows, search, pagination, load(), recommendations AND loadLinks all dead — the
`frontWatchConfig` id move broke streams in addition to the issue's listing/watch findings.

## Fix scope

`Javtiful.kt` only: selector refresh + `watch-config` extraction; pure Parse helpers
(`videoCards`, `videoCard`, `hasNextPage`, `addedOnYear`, `watchTags`) per ADR-0005 with
fixtures = live saves. Version bumped. No shared/ change (Root cause is site markup).

## Verify run (verify.sh, live, this run)

- Search ×2 pages, home rows ×5 (each row page1+2 in its own run), video pages ×5 (newest /
  most-viewed / search-sampled / related-sampled / mosaic variant), related selector on all 5,
  field exposure tags+actors on all 5, streams 206 video/mp4 on all sampled pages, check 5
  search↔load title agreement PASS, check 6 data-complete PASS (all 6 LoadResponse fields).
- Remaining raw-check FAILs are the FINDINGS-recorded site quirks, unchanged since the
  2026-09 audit: listings and related grids carry the censored and reducing-mosaic releases
  of one JAV code under one identical title — the provider dedupes (`distinctBy { it.name }`),
  the raw duplicate check cannot. The flagged pairs (iesp-763, mfyd-182, mxgs-1443 on search;
  jur-801 inside roe's related grid) are exactly such pairs — different hrefs, one title.
- Transient fetch corruption observed once (video-4 URL): a 200 whose body had a foreign
  `<head>` (freepornvideos.xxx BLACKED page) glued in front of javtiful's markup — a
  network-layer artifact, not a site redirect; four re-fetches of the same URL were all clean.
  Recorded as transient, not a broken selector.

## verify.sh capability additions for this run (general, reusable)

- `:not(.cls)/:not(#id)` exclusion support in the script's mini selector grammar (partner ad
  cards share the real cards' tag+class vocabulary — `article.video-card:not(.video-card--partner)`).
- Lazy-poster fallback reads `data-front-lazy-src` in addition to `data-src`
  (placeholder SVG sits in `src`).
- `_true_block` now derives the element's tag name from the opening tag itself. Before, the
  tail-split gluing `>` onto attr-less tag names (`h1`) broke the closing-tag pair regex, so
  inner extraction ran past `</h1>` and the agreement check produced false-FAIL titles.
