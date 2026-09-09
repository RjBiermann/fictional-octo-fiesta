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
