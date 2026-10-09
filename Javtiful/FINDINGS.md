# FINDINGS — javtiful.com (2026-10-09 redesign probe, issue #534; prior state in FINDINGS-534.md and issue #128/#240 history)

## Verdict: OK after the #534 selector refresh (site redesign 2026-10; `front-*` vocabulary dropped site-wide)

Live saves referenced here (same run): `src/test/resources/javtiful-{home,home-page2,search,watch}.html`.

## Search
- `https://javtiful.com/search?q=kana` → 200; `article.video-card:not(.video-card--partner)` matches.
- Card title link: `a.video-card__title href="/video/..."`. Partner ad cards
  (`article.video-card.video-card--partner`, one per listing page) carry an external
  tracking href (`t.fluxtrck.site/c1/…`) — excluded in code via `:not(.video-card--partner)`,
  excluded in verify.sh raw check via `a.video-card__title[href^="/video/"]`.

## Quick search
- No distinct quick-search endpoint (hasQuickSearch = false).

## Homepage
- Rows = `/videos` (Newest), `?sort=most_viewed`, `?sort=top_rated`, `/uncensored`, category rows.
- Card selector: same as search; page 2 via `pagedUrl()` (`&page=N` on query rows).
  `/videos?sort=most_viewed&page=2` → 200, disjoint from page 1 (0 href overlap).
- Pagination control: `a.pagination__link` (Next label; `is-disabled` = no more pages).
- Site quirk (kept): listings contain censored and reducing-mosaic releases of one JAV code
  under one identical title; provider dedupes by title (`distinctBy { it.name }`).

## Video pages
- Title: `div.watch-title h1` (no suffix; og:title carries a `| javtiful` suffix — verify.sh
  agreement check uses the h1 selector, not og:title).
- Poster: `meta[property=og:image]`. Plot: `meta[property=og:description]`.
- Actors: `a.watch-actor-card` (name in `span`, poster img; no placeholder case live now,
  the placeholder filter in code is inert/harmless).
- Categories: `div.watch-detail:contains(Categories) a.watch-link-chip.is-category`
  (e.g. Married Woman). Tags: `div.watch-detail:contains(Tags) a.watch-link-chip` plain chips.
- Year: `div.watch-detail:contains(Added on) time[datetime]` → ISO `2025-04-18T…`; split year.
- Duration: JSON-LD VideoObject `duration` PT regex — still the only per-video source;
  `#watch-config` has no duration. (verify.sh cannot extract attr/regex fields — year/duration
  exposure asserted here.)

## Related videos
- `div.video-grid.related-grid article.video-card` (12–21 cards, same card shape as listings,
  parsed by the same card helper). Provider dedupes by title (variants quirk above).

## Stream sources (per video page)
- **`id="frontWatchConfig"` is gone** (renamed). Now `<script id="watch-config"
  type="application/json">` with the same JSON schema: `playerSources[].src =
  https://fast-stream.jav.si/p/<hex>`, `"type":"video/mp4"`, `"size":720` → MP4.
  Provider extracts via jsoup `script#watch-config` + `.data()` (attribute-order-proof).
- Stream URLs are **extensionless** (/p/<hex>, no `.mp4` suffix); type comes from the config
  MIME — `isHls()` (issue #240 fix) unchanged and still correct.

## Headers / referer
- Stream verified 206 video/mp4 from the runner without special headers (issue #240 record).
  Provider sets `referer = "$mainUrl/"`, harmless.

## Pagination
- Search: `/search?page=N&q=…` (provider form). Homepage: `?page=N` / `&page=N`.
  Related: fixed list per video, no pagination.

## Fix record
- Issues #534 (this run): selectors + `watch-config` id refresh in `Javtiful.kt`; pure Parse
  helpers (`videoCards`, `videoCard`, `hasNextPage`, `addedOnYear`, `watchTags`) unit-tested
  red→green in `JavtifulParseTest` against the live-save fixtures. Version bumped to 13.
