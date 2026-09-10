# FINDINGS — javtiful.com (2026-09 audit; updated for issue #128; updated for issue #240)

## Verdict: OK

## Search
- `https://javtiful.com/search?q=red` → 200; `article.front-video-card:not(.front-partner-card)` matches; `/video/112318/mngs-075`.
- The card's title link is `a.front-video-title href="/video/…"` (href precedes class). Partner
  ad cards (`article.front-video-card.front-partner-card`, one per listing page) carry an
  external tracking href (`t.fluxtrck.site/c1/…`) — excluded in code via `:not(.front-partner-card)`,
  excluded in verify.sh raw check via `a.front-video-title[href^="/video/"]`.

## Quick search
- No distinct quick-search endpoint (hasQuickSearch = false).

## Homepage
- Rows = `/videos` (Newest), `?sort=most_viewed`, `?sort=top_rated`, `/uncensored`, category rows.
- Card selector: same as search; page 2 via `pagedUrl()` (`&page=N` on query rows) — verified
  2026-09 (issue #207) and re-verified for #240: `/videos?page=2` → 200, 23 new cards.
- Site quirk: listings contain the censored and reducing-mosaic releases of the same JAV code
  under one identical title (e.g. `/video/107163/fit-007-reducing-mosaic` and `/video/107052/fit-007`);
  provider dedupes by title (`distinctBy { it.name }`). verify.sh's raw duplicate-title check
  flags these pairs although hrefs/posters/streams are distinct — known quirk, mitigated in code.
- Partner ad card repeats across page 1 and page 2 in the raw HTML; the provider's
  `:not(.front-partner-card)` filter removes it (verify.sh check passes with the href-prefix selector).

## Video pages
- Watch page carries JSON-LD block with ISO-8601 duration: `"duration":"PT1H58M21S"`.
  Player config `#frontWatchConfig` has no duration field; JSON-LD is the only per-video source.
- Title: `div.front-watch-title h1` (no suffix; og:title carries a `| javtiful` suffix and is
  truncated with `Destro...` — verify.sh agreement check uses `meta[property=og:image:alt]`,
  which holds the full title without suffix; 2026-09-10 run).
- Poster: `meta[property=og:image]`. Plot: `meta[property=og:description]`.
- Actors: `a.front-watch-actor-card` (site renders "Unknown" when there is none, then no card).
- Tags/Categories: `a.front-watch-link-chip` blocks under `<strong>Tags:</strong>` /
  `<strong>Categories:</strong>` in `div.front-watch-detail`.
- Year: from "Added on:" `<time datetime>`; duration from the JSON-LD `PT…H…M…S` regex.
  (verify.sh cannot extract attr/regex fields, so year/duration selectors are NOTEs; exposure
  is asserted here in FINDINGS — the site exposes both.)

## Related videos
- `div.front-video-grid-related article.front-video-card a.front-video-title` (12–21 cards).
- Site quirk: some grids repeat the same card verbatim (same href+title, e.g. `rctd-701` twice
  in avsa-457's grid); provider dedupes via `distinctBy { it.name }` — code covers it, raw
  verify.sh check can express neither `:not` nor dedupe.
- Some grids pair the censored + mosaic releases of one code under one title — provider dedupes.

## Stream sources (per video page)
- `id="frontWatchConfig" type="application/json">` JSON `playerSources[0].src=https://fast-stream.jav.si/p/<hex>`
  with `"type":"video/mp4"`, `"size":720` → **206 video/mp4** (verified 2026-09-10 on 5 fresh videos).
- Stream URLs are **extensionless** (/p/<hex>, no `.mp4` suffix).

## Issue #240 root cause (fixed)
- The stream is a plain MP4 at an extensionless URL. `loadLinks` chose the link type via
  `source.src.contains(".mp4")` — always false → link flagged `ExtractorLinkType.M3U8` → ExoPlayer
  parsed MP4 bytes as an HLS manifest → `parsing_manifest_malformed` (3002) / encoding error.
- Fix: `isHls(src, mimeType)` (new pure helper, unit-tested red → green in `JavtifulLinkTypeTest`)
  prefers the config's own MIME (`"video/mp4"` ⇒ VIDEO) and only reports HLS for an
  `mpegurl` MIME or a `.m3u8` path. The config carries `type:"video/mp4"`; stream observed
  206 video/mp4 with and without Referer/UA variations — headers are not required.
- No site-side change vs the #128/#207 audit: search, listing, video pages, player config,
  and stream all serve normally from the runner.

## Headers / referer
- Stream request verified 200/206 video/mp4 without headers, with default UA, with
  `Referer: https://javtiful.com/`, and with `Referer: https://fast-stream.jav.si/` — none required.
  Provider still sets `referer = "$mainUrl/"`, harmless.

## Pagination
- Search: `?page=N&q=…` (provider form `/search?page=2&q=…` → 200, different cards).
- Homepage: `?page=N` / `&page=N` (issue #207 fix). Related: fixed list per video, no pagination.
- Malformed-URL curl transcript (site quirk, kept for the record): `?page=2` on a sorted URL
  returns 200 with the default Newest listing — silent drift, no 4xx.
