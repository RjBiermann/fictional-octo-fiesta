# FINDINGS — ixiporn (fix probe, issue #120)

Date: 2026-09-07 · UA: Firefox 130 desktop

- Video pages live on `ixiporn.live` (search/home on `ixiporn.org`, absolute hrefs to .live, `fixUrl`-safe).
- Stream source: `<meta itemprop="contentUrl" content="...mp4">` inside `div.video-player` — casing is `contentUrl` (lowercase `rl`), confirmed on two pages:
  - /mona-darling-bts-2026-moodx-hindi-porn-web-series-episode-4 → `https://cdn2.ixifile.xyz/5/Mona.Darling.BTS.S01E04.mp4`
  - /big-boobs-desi-maid-2026-niksindian-uncut-porn-video → `https://cdn2.ixifile.xyz/5/Perfect%20Big%20Boobs%20Desi%20Maid%20gets%20Rough%20Pounding%20Niks.mp4`
- Stream healthy: HTTP 206, `video/mp4`.
- Search: `https://ixiporn.org/?s=indian` → 200, 30 × `div.video-block`.
- Fix: `meta[itemprop=contentURL]` → `meta[itemprop=contentUrl]` (jsoup attr match is case-sensitive); version 16 → 17.

## Data-completeness probe (issue #127)

Date: 2026-09-13 · UA: Firefox 130 desktop

- Duration: `<meta itemprop="duration" content="P0DT0H41M45S">` on /mona-darling-bts-2026-...; `P0DT0H16M58S` on /indian-desi-mother-fucked-your-priya-desi-hindi-porn-video (200).
- UploadDate (year): `<meta itemprop="uploadDate" content="2026-09-07T09:41:28+05:30">` (and `2022-03-02T16:28:42+05:30` on second page).
- Tags: `div.video-content-row#video-tags` containing `a[href*="/tag/"]` (both pages).
- Related: `div.related-videos` containing `div.video-block` blocks with the same `a.infos` / `a.thumb` structure as listings (both pages) — reuse `toSearchResult()`.
- No actor markers on page → actors not required.

## verify.sh (issue #127) — PASS

- Search: ixiporn.org/?s=indian → 200, 30 × div.video-block.
- 5 video pages: 200, `meta[itemprop=contentUrl]` ×1 each; stream 206 video/mp4 each (with Referer https://ixiporn.live/).
- Related selector `div[class*=related] div[class*=video-block]` → 12 matches on every page.
- Note: /indian-desi-mother-fucked-your-priya-desi-hindi-porn-video stream URL returns 404 from the CDN (dead file on the site itself) — replaced with a fresh video in the verify run.
- verify.sh patched: first_stream_url now also recognizes `itemprop="contentUrl" content=` meta (selector came from FINDINGS, not invented).

## Pagination probe (issue #217)

Date: 2026-09-09 · UA: Firefox 130 desktop

- DEFECT: "Latest Release" home row base `?filter=latest/page/` — `?filter=latest/page/2` returns the same 30 cards as page 1 (curl: p1/p2 card sets identical). WordPress ignores `filter=...` as a filter value.
- FIX: home row base changed to `${mainUrl}/page/` (plain /page/N/ is the same latest listing) — version bump 19 → 20.
- Verified: ixiporn.org/page/1/ vs /page/2/ → 200 ×30 `div.video-block` cards, overlap 0 (disjoint), p2 first card `/bade-boobs-wali-bhabhi-ji-2026-hunter-asia-hindi-uncut-porn-video`.
- Other rows untouched; tag/search rows paginate correctly (per audit).
- Search p2 at `?s=`'s correct WP form is `page/2/?s=indian` (200, 30 cards, disjoint from p1).
- Checker note: raw-regex stream extraction doesn't HTML-decode entities (`&#039;` in `contentUrl` filename) — one card's stream hand-verified OK with decoded URL (+Referer): 200 video/mp4; jsoup `.attr()` decodes, provider code correct.

## verify.sh (issue #217) — PASS

Search p1/p2, home p1/p2 (new `/page/N/` base), 3 video pages × (contentUrl ×1, 12 recs, 206 video/mp4 streams with Referer), tags on all pages, LoadResponse fields populated → RESULT: PASS.

## Deep pass (issue #655, 2026-10-17 — attempt 2; attempt 1's probe identical, its PR push died on a registry conflict)

Deciding fact: **redirect state UNCHANGED** — `ixiporn.org → 301 ixiporn.info → 301 ixiporn.live → 200`, byte-for-byte the #468 chain. Standing update; no re-filed condition.

### Probe evidence (plain-curl tier, UA Firefox 130, canaries 3/3)

- Home: all 10 mainPage rows × p1/p2 → 200, 30 `video-block` cards each, `data-post-id` disjoint **within every row** (row1 latest p1∩p2=0 — the #217 `/page/` fix holds).
- Search: `/page/1?s=amateur` … `/page/3?s=amateur` → 200, 30 cards, fully disjoint; legacy `/search/<q>/page/N/` grammar also healthy. Trailing-slash form `/page/2/?s=` returns the identical card set.
- Quick search: **absent** — only the theme `ftt_ajax_var` lazyload admin-ajax var (`action=ftt_search` → 400, no suggest endpoint). Recorded absent, not drift.
- 6 shape-A video pages (latest ×2 / tag ullu / search / related ×2, 2021–2026) → 200, `meta[itemprop=contentUrl]` ×1 each, streams **206 video/mp4** on `.live` referer (hosts ixifile.xyz / cdn2.ixifile.xyz); `og:title/image/description`, `meta[itemprop=duration|uploadDate]` (#video-tags 5 of 7 in sample — page variance), related 12 cards/page.
- verify.sh RESULT: PASS (2 search + 20 home pages, 6 videos, streams 206, search↔load agreement, LoadResponse complete). NOTEs: quickSearch absent (per spec = not drift); actors not asserted — site does not expose actors (#127); `img` poster is `data-src` lazyload (invisible to the instrument, provider unaffected).

### New tier (documented; in-scope fix applied — extractor deferred to #709)

- **shape-B pages** (2021–2023 hub-embed era: 2/3 searched `amateur` pages here; 3/7 in attempt 1's wider sample): **no** `contentUrl`. The player is `<iframe src="https://ixiporn.live/wp-content/plugins/clean-tube-player/public/player-x.php?q=<b64>">` whose decoded payload wraps an external hub embed — `embed.redtube.com/?id=21152821` in this run's sample; attempt 1 sampled `xvideos.com/embedframe` — **hub varies per video**. Duration meta on those pages is a site-side placeholder (`P0DT18H0M0S`).
- **Confirmed code hazard** (fixed in-run, TDD, version 26 → 27): old `loadLinks` did `selectFirst("meta[itemprop=contentUrl]")?.attr("content")?.trim().toString()` → on shape-B pages this emitted `fixUrl("null")` — a garbage dead stream entry instead of no stream. New `IxiPornParse.contentUrl` (JUnit4, fixtures verbatim from live blocks, red → green) returns null → nothing emitted. The embed-extractor work (b64 payload → hub resolver chain) stays scoped to #709.
