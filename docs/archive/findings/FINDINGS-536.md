# FINDINGS-536 — FreePornVideos details-block rebuild (Description/Models/Duration dead)

Issue #536 (audit run 8 / #524 detail fix). Re-probe 2026-10-09, runner IP US datacenter,
TLS-impersonated curl (plain curl gets 403 CF — canary-consistent with the sweep 524 tier).
Fix branch: `devloop/issue-536`. Scope: details-field parsing only; listing surfaces untouched.

## Reproduce — confirmed on all sampled pages

10 fresh video pages (3 from search "sex", 5 from latest-updates + most-popular/week listings,
2 more from search) — all HTTP 200 via TLS tool, all 24 `div.item` cards on `/search/sex/1/`.

Dead in the new markup (checked with grep on every page):

```
$ grep -c 'Description:' /tmp/vn1.html → 0        (was a <div>Description: <em>…</em></div> cell)
$ grep -c 'Models:'      /tmp/vn1.html → 0        (label renamed)
$ grep -F '<span>Duration' /tmp/vn1.html → none   (no Duration cell at all)
```

New details block (`div.block-details`, unclosed-div soup — jsoup lands Categories under
`div.hidden_tags` and Pornstars under `div.info`; both descendants of `div.block-details`):

```
<div class="info"><div class="item"><span>Channel:</span>…/sites/…</div>
 <div class="item"><span>Network:</span>…/networks/…</div>
 <div class="hidden_tags"><div class="item"><span>Categories:</span>16 × …/categories/…</div>
 <div class="item"><span>Pornstars:</span>…/models/jovan-jordan/… Mckenzie Mae</a> (hrefs end /models/…)
```

Not reproduced (contradicts the issue text): the **title-tail date branch is NOT stale** —
all 10 of 10 sampled `div.headline h1` end with `/ DD.MM.YYYY` (`… / 09.10.2026` on today's
uploads, `/ 24.01.2022` on older ones), so the legacy last-4-chars year parse still works.
Kept as fallback; JSON-LD `uploadDate` is primary (same value on every page sampled: 2026).

Duration on a fresh page (immediately after fetch):

```
$ …freepornvideos.xxx/videos/93820265/deprived-and-horny/
meta property="video:duration" content="1894"        (seconds)
JSON-LD VideoObject: "duration":"PT0H31M34S", "uploadDate":"2026-…, name, thumbnailUrl, embedUrl
og:description = "Full Length 😎 Starring Jovan Jordan and Mckenzie Mae. Resolution: 4K HD. Release date: October 09, 2026."
```

Also found dead while probing (not in the issue, same rebuild): `div.rating span` →
`<div class="rating positive "> 100% </div>` has no inner span; provider's rating read
returned null on every page. **Not fixed after review:** every `div.rating` block belongs
to a related-video card, and the loaded video itself has no percentage rating — only a
like/dislike vote widget (`rate-like`/`rate-dislike` spans, counts 2/0 on 93820265).
Widening the selector read other videos' ratings on every load, so the rating read was
dropped: `score` is now an honest null (vote-count parsing left as an upgrade path).

Cross-video input values (5 pages, verify Distinct bar):

| page id | meta video:duration | JSON-LD dur | year | actors |
|---|---|---|---|---|
| 93820265 | 1894 | PT0H31M34S | 2026 | Jovan Jordan, Mckenzie Mae |
| 93820261 | 1872 | PT0H31M12S | 2026 | Harley Love, Enzo East |
| 93820260 | 2352 | PT0H39M12S | 2026 | Charlie Dean, Victoria Benz |
| 93820259 | 1387 | PT0H23M07S | 2026 | Frances Bentley |
| 93820249 | 1373 | PT0H22M53S | 2026 | Jade Frost, Mike Jebb |

(Full-length titles: episode/performer chains end with the real title before `/ date`.)

## Unchanged surfaces (still healthy, probed this run)

- search `/search/sex/1/` → 200, 24 `div.item`; page 2 `/search/sex/2/` → different items.
- homepage rows `/latest-updates/1/`, `/most-popular/week/1/` → 200, `div.item` cards; `/2/` pages differ.
- related `div#list_videos_related_videos_items div.item` → 11 cards on 93820265.
- streams `video source` (get_file mp4, labeled 2160p/720p/480p) — **session-bound (KVS
  anti-leech)**: fetched in the same TLS session right after the page → HTTP 206,
  `Content-Type: video/mp4`, body starts `ftypisom`; a cold or minutes-stale session → 403/404.
  Matches the sweep-524 "session/leech class" note (#449/#466). Provider `loadLinks` flow
  unchanged — confirmed live by the pre-fix stream sequence in the run below.
- no distinct quick-search endpoint (provider has no `quickSearch` override — unchanged).

## Fix (this branch)

- TDD red → green at the ADR-0005 seam — this change touches parsing, so FreePornVideos
  **leaves the grandfathered list** (ADR-0005 addendum): new `FreePornVideosParse` object,
  `FreePornVideosParseTest` with a live-shaped fixture
  (`src/test/resources/freepornvideos_video_93820265.html`, fetched 2026-10-09) pinning:
  tags/actors from the span-labelled cells, plot from og:description, duration
  meta-seconds → floored minutes (sentinel-zero/junk → null via shared
  `DurationParse.fromSeconds`) with JSON-LD fallback (shared `JsonLdParse.minutes`), year
  from JSON-LD `uploadDate` with title-tail fallback, plus regression pins that the old
  xpaths match nothing on current markup.
- `load()` swaps the four dead selects for the Parse calls (all four xpaths pinned dead in the
  test, including Categories). The rating read is dropped — no percentage rating exists for the
  video itself, so `score` is an honest null. `this.duration` is now null-when-absent (never
  sentinel 0).
- Shared code untouched. `build.gradle.kts` version 11 → 12.

## verify.sh run (verify-provider skill) — see transcript notes

- verify.sh fetches with plain curl; the site 403s plain-TLS (tier: tls). Verified through a
  `curl_cffi` shim that impersonates chrome **only for `*.freepornvideos.xxx`** and passes
  other hosts through plain TLS — Film1k (#496) precedent. The shim also keeps a
  cross-invocation cookie jar for the impersonated host, which is what let the mechanical
  stream checks replay the page session live.

Run (2026-10-09, 5 video URLs from 3 listings):

- search p1/p2 `/search/sex/{1,2}/` → 200, 25 scoped `div#custom…_items div.item` (24 real
  + ourdream.ai ad tile) with `a.thumb_title` titles; **no duplicate cards** (the ad tile sits
  outside the search scope); homepage p1/p2 `/latest-updates/{1,2}/` → 200, cards scoped by
  `div#list_videos_latest_videos_list_items div.item`.
- One raw-dup FAIL line on homepage: title `create and fuck your ai cum slut ourdream ai`
  home0×home1 — that is the site-wide `<div class="item ourdreamai-native">` **native ad
  iframe tile injected once per listing page** (cross-page dup, not pagination failure).
  Manual card-by-card: 24 real `/videos/` cards per page, **overlap 0**; the provider drops
  the tile (shared `SearchCard.parse` → no `a.thumb_title` anchor → null). FINDINGS-515
  precedent (identical raw-dup false positive pattern).
- video pages 5/5 → 200, `source[src]` (2–3 sources each), `og:title` present; title/poster/
  plot all-or-none consistent; **search ↔ load agreement passed** (sampled search videos
  93404552/…62074: `a.thumb_title` text == `og:title`, poster paths equal).
- exposure: tags (`a[href*=categories]`), actors (`a[href*=models]`), duration
  (`meta[property=video:duration]`) present on all 5 sampled pages. year: NOTE (no simple
  selector — exposure proven by fixture test: JSON-LD `uploadDate` + title-tail date).
- **streams: 13/13 extracted get_file URLs → HTTP 206, `Content-Type: video/mp4`** —
  session-bound (KVS anti-leech): worked because the shim replayed the cookie jar (PHPSESSID
  etc.) from the page fetch into the stream request, seconds apart. A cold session or a
  minutes-stale link → 403/404 (probed live this run). Provider `loadLinks` flow unchanged.
- LoadResponse completeness: recommendations/tags/plot/duration/year/actors all populated.
- no distinct quick-search endpoint (NOTE; provider has no `quickSearch` override —
  hasQuickSearch false, unchanged).

verify.sh exit code is 1 (the ad-tile FAIL line above); every other check passes. Recorded
here as the tool-false-positive precedent, not hidden.

Re-run after the review fixes (2026-10-09, same 5 video URLs + search/home p1/p2, fresh
TLS-impersonated cookie jar): search 25 cards/page no dups, homepage 25 cards/page with
only the ad-tile raw-dup FAIL line, video pages 5/5 with `source[src]`, related 16/page,
all-or-none exposure (tags/actors/duration), **streams 14/14 → HTTP 206 video/mp4** (the
shim's `-sL` cluster-arg and per-cookie-domain jar handling had to be fixed first — the
get_file links redirect 302→signed fpvcdn URL and need the page session's cookies),
search↔load agreement PASS (93404552 from the search page), LoadResponse completeness all
fields populated. Same single false-positive FAIL, exit 1 — ad tile only.

## Status

Fixed and verified on branch `devloop/issue-536` for human review — not merged.
