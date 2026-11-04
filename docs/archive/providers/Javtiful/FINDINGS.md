# FINDINGS — javtiful.com

## 2026-10-17 deep pass (issue #658, sub-issue of #631) — verdict **ok, no drift** (fix #548/v13 still holding)

Full-surface re-audit; tier **plain-curl** on every surface (browser step never reached — no blocked surface). Canaries 3/3: film1k plain 403 (TLS 200 expected), eporner 200, pandamovies 302→200@.org.

### Home pagination (5 main-page rows, p1 + p2 each)

- `/videos`, `/videos?sort=most_viewed`, `/videos?sort=top_rated`, `/uncensored`, `/category/milf`
- All 200 × **23 non-partner `article.video-card` cards/page** (1 partner ad card excluded by `:not(.video-card--partner)`), **0 href overlap p1↔p2 on every row** (verified mechanically by verify.sh cross-page dedupe).
- `a.pagination__link` still live: p1 carries `Previous` with `is-disabled` + enabled `Next` with real hrefs (`/videos?page=2`, sort rows & canonicalized `&amp;page=2`). `hasNextPage()` (`text == "Next" && !is-disabled`) unchanged and correct.

### Site-shape observations (not drift)

1. **Sort key renamed**: `top_rated` → **`most_liked`** site-wide. The old key is still a live alias — `/videos?sort=top_rated` returns 200 serving the identical `most_liked` listing (23/23 href overlap), and the page's own pagination anchors canonicalize to `sort=most_liked`. The provider row (`Top Rated` → `?sort=top_rated`) is fully functional via the alias (p1/p2 disjoint ×23). Recorded as a **resilience watch**: if the alias is ever dropped, the row breaks — next fix opportunity is a one-line data switch to `most_liked`.
2. **Commented-out sort keys**: `top_favorites` and `being_watched` each now serve the default newest listing (23/23 overlap with `/videos`) — alias behavior, they stay commented out.
3. **Same-page same-title variant rows** (unchanged quirk): censored and reducing-mosaic releases of one code list side by side under one identical title with different hrefs (e.g. `/video/114223/iesp-763-reducing-mosaic` + `/video/113965/iesp-763`; 3 such pairs per search page observed). Provider dedupes by title (`distinctBy { it.name }`, test-covered) — verify.sh's title-dedup FAIL line on this is the instrument unable to express the shape (see verify record).
4. **Root `/` now 301s → `/main`** (the SPA). The SPA-root-not-a-provider-row distinction still stands — infinite-feed duplication on `/main` is never filed against this provider.

### Search

- `/search?q=kana` p1/p2 200, 23+23 cards, **0 overlap**.
- Title link unchanged: `a.video-card__title` (inner text AND `title` attr, both live on every face).
- Search↔load agreement exact: card title of `/video/114223/iesp-763-reducing-mosaic` == load `div.watch-title h1`.

### quickSearch — absent (recorded, not drift)

No distinct quick-search endpoint: `search/suggest`, `/suggest`, `/quick-search`, `/api/search` all 404; the search input carries an empty `autocomplete` attr; no live-typing JS suggest call found in the search page. `hasQuickSearch` stays false.

### Video pages (×9 across faces: newest, most_viewed, related-hop, search, category)

- All 200; `div.watch-title h1`, `meta[property=og:image]`, `meta[og:description]`, chips (`a.watch-link-chip`), actor cards (`a.watch-actor-card`), `Added on` `time[datetime]` year, JSON-LD `VideoObject` duration — all live and parseable.
- Poster agreement: card `img[data-front-lazy-src]` path is **identical** to the video's `og:image` path (`/uploads/uploads/videos/thumbs/…jpg`) — the `sub_field` lazy-poster fallback runs on the placeholder SVG `src` (`/assets/front/media/video-thumb-placeholder.svg`).
- **Actor variance (site reality)**: some releases are actor-less (e.g. NCYF-055, TIKB-229 pages carry no `a.watch-actor-card`); optional-actor handling in code (empty list → `addActors(empty)`) is correct. Hence no `--video-actors-selector` in verify (all-or-none instrument cannot express optional fields) — exposure asserted here instead.
- **Duration exposure**: JSON-LD `VideoObject.duration` only (PT… regex via shared `JsonLdParse.minutes`) — `#watch-config` does not carry duration. Out of pydom reach → designated: the field is exposed and covered.

### Related

- `div.video-grid.related-grid article.video-card` live on every sampled page: 12–21 cards (regex-DOM counts 13–21 due to a container double-match; jsoup DOM is exact).

### Streams (end-to-end, the #658 deep-pass focus)

- `script#watch-config` JSON `playerSources` → `https://fast-stream.jav.si/p/<token>` — **3/3 fresh tokens 206 `video/mp4`**, no referer/cookies required, and a bare `okhttp/4.9.2` UA also gets 206 (issue #240 record still holds; the provider's `referer = "$mainUrl/"` remains harmless).
- `isHls()` unchanged and still correct: MIME `video/mp4` → `ExtractorLinkType.VIDEO`.

### verify.sh record (6 invocations)

- Main run: search p1/p2 + 4 video pages (IESP-763-reducing-mosaic / SQTE-727 / MUCD-362 / SDMU-979) + position-matched `--stream-url` overrides + related + tags + year + LoadResponse(recommendations,tags,plot,duration,year,actors,posters) — every check PASS (tag/year present on all 4 pages; LoadResponse 7/7 fields in code).
- 5 home-row runs (p1+p2 per row): all rows 200 ×23, cross-page dedupe clean.
- **1 instrument-limited FAIL line** (same in every invocation): the mandatory search listing's 3 same-page variant-title pairs trip the title-dedup — different hrefs, identical title, site-native (point 3); the provider dedupes them by design. NOT a provider or site defect.
- NOTEs: no quick-search endpoint (absent, above); actors optional (above); duration JSON-LD-only (above).

### Gates

- `gradlew Javtiful:test` PASS (fixtures unchanged — live shape identical to the 2026-10-09 saves) · clean `gradlew Javtiful:make` PASS · `check-findings.sh` PASS.
- **No code change, no version bump — Javtiful stays 13.**

---

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
