# FINDINGS — issue #555 (PandaMovies parser drift: PsyPlay → new theme)

Probed 2026-10-13, plain curl (no impersonation needed — markup identical to the
challenge-checked audit tiers; challenge canaries healthy, no block). Fixtures:
`src/test/resources/panda-search.html` (live `?s=sex`) and `panda-video.html`
(live `/watch-sex-romance-4-movie-online-free`).

## Condition (reproduced live, against current master)

- Origin healed `pandamovies.pw` → `pandamovies.org` (curl -L follows; final URL 200).
- `div.ml-item` count **0** on every listing surface (home, search, genre, /page/2). The
  PsyPlay grammar behind `Parse.cards`, `Parse.videoPage`, and `Parse.embeds` is gone
  sitewide. Confirms the issue's listing finding — and extends it: the video page
  migrated too (issue only verified video-page 200s + embed hosts, not markup).

## New markup grammar (all surfaces)

Listing cards (search `?s=q`, `/search/q`, `/genre/x`, home `/`, `/page/2`):
`<article class="card">` containing `a.card__th[href]` (href), `img.card__img[src]`
(poster, `?resize=360,540`), `h2.card__t a` (title), `span.card__q` (year),
`span.card__dur` (duration). Pagination: **35 cards/page** on listing surfaces
(home page 1 shows 49 total: "Featured Porn Movies" (grid--blk, 14) + "Latest Porn
Movies" (grid--mc, 35); page 2 = 35 fresh cards).

- Home `/` and `/page/2` 200, 35 fresh cards/page. Genre rows unchanged URLs (`/genre/…`)
  200 with card grammar. Home page 1 shows each Featured card that also sits in Latest
  twice in raw markup (Featured + Latest grids); `Parse.cards` dedupes by href — the
  provider emits 48 distinct items there. The mechanical verify note consequently pins
  the homepage check to the Latest grid (`div.grid--mc article.card`).
- Search: `/search/q` paginates truly via `/search/q/page/2` (35 fresh cards). `?s=q&page=2`
  ignores the offset — it returns the same 35 cards as page 1 (WP relevance quirk, confirmed
  live); the provider's primary `/search/<slug>` path is the correct one, the `?s=` form
  stays only as the #444 mismatch-retry fallback.
- Video page 200: similar-titles related strip = `section.sec h2.sec__h` "Similar titles"
  → `div.grid--blk article.card` (same card grammar inside; 14 cards, title element is
  `h3.card__t` there vs `h2.card__t` in listings — `.card__t` covers both).

## Video page grammar (replaces .mvic-thumb/.mvic-info/pettabs)

- Title: `span.bc__c` (breadcrumb current = short title, e.g. "Sex & Romance 4",
  matches the search-card title exactly). `h1.vid__t` is SEO-mangled
  ("Watch … 2026 by … Porn Movie Online Free") — not usable as-is; og:title needs
  prefix/suffix stripping (fallback).
- Poster: `img.vid__poster[src]` (`?resize=360,540` — same path shape as card posters).
- Plot: `div.entry.vid__txt` (full synopsis incl. scene list). og:description is the
  truncated summary.
- Year: `span.st--year` ("2019"); release-year chip `/release-year/2019` also present.
- Duration: `span.st--duration` ("5:05:00" / "H:MM:SS" or shorter); `meta itemprop
  duration content="18300"` also present. Old "Duration: 3 hrs. 42 mins." prose is gone.
- Tags: `a.chip[href*=/genre/]` inside "Genres" chip row.
- Actors: `a.chip--star[href*=/actor/]` in "Pornstars" chip row.

## Stream sources (replaces #pettabs / a[id=#iframe])

Embeds are no longer anchors — they are a JSON array in the player's
`section.hlm[data-servers]` attribute (html-escaped; jsoup unescapes on attr read):
`[{"u":"https://doply.net/e/xu6fxle6be5p","t":"iframe","l":"DoodStream 2","h":"…"}, …]`.
Mirrors present on the sample: doply.net, doodstream (doply), voe.sx, mixdrop.ag —
all covered by the shared host registry. Old luluvid/voe-slash-e host normalization
kept (harmless).

## URL fixes

- `/movies` → 404 ("Page not found") even on .org. The "Latest" home row is repointed
  at the homepage itself (`mainUrl`), which paginates via `/page/2`.
- No `/latest`, `/new` taxonomy page exists.

## Quick search

`/?s=q` and `/search/q` both work; `/search/q/page/N` paginates. No distinct
quick-search endpoint was recorded in FINDINGS-425 and none found now — remains absent.

## Fix shape

`Parse.cards` → `article.card` / `a.card__th` / `h2.card__t a` / `img.card__img`
(related = `div.grid--blk article.card`); `Parse.videoPage` → vid__ grammar
(bc__c title, vid__poster, vid__txt, st--year, st--duration); `Parse.embeds` →
`section.hlm[data-servers]` JSON; `hasNextPage` threshold 40 → 35; Latest row →
`mainUrl`. Version bump + fixture/test refresh (TDD red → green).


## Verification (fix applied, 2026-10-13)

`verify.sh` **PASS** (exit 0), plain-curl tier:

- Search: `/search/dance` + `/search/dance/page/2` → 200, 35 cards each, no dups.
- Home: `/` (49) + `/page/2` (35) on the Latest grid, `div.grid--mc article.card`, no dups.
- 5 varied video pages (home/search/genre mixes): `section.hlm[data-servers]` match ×5;
  title `span.bc__c`, poster `og:image`, plot `div.entry.vid__txt`, tags/actors/year/duration
  all present on every sampled page.
- Streams: LuluStream mirror chain (embed page → Dean-Edwards-unpacked player config)
  resolved per video; 5/5 m3u8 master manifests serve `application/vnd.apple.mpegurl`.
  doply.net 403s this runner behind a Cloudflare challenge — the LuluStream server is
  the resolved chain here (doply/dood adapters stay registered for in-app picks).
- Search↔load agreement: Cocos Sensual Lap Dance agrees on title + poster path.
- Search URL semantics recorded above: `?s=…&page=2` is a duplicate of page 1 — verify
  uses the provider's `/search/<slug>/page/N` form.

## Drift history (agg: #439 → #444 → #457 → #463/#471 → #555)

Sixth confirmed occurrence for this provider — Chronic per repo rules. Left unlabeled:
`ai-remove-site` vs hardening is a maintainer decision (this fix is the hardening step
if the site survives).
