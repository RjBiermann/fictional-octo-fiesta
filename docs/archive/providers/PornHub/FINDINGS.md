# FINDINGS — PornHub (pornhub.com)

Probe date: 2026-11-18, from the CI runner (GitHub Actions, `Ubuntu` runner IP — EU/US,
see Risks). Stories S1–S4 of the PornHub spec (#1406) fill this document; S1 (§Engine
fingerprint, §Homepage, §Pagination (home)) is complete and verified, S2 (§Search, quick
search), S3 (§Video pages, related, streams) and S4 (closeout) append their sections.

## Engine fingerprint

**Custom proprietary platform** (PornHub's own stack — not KVS/Kernel, not a WP video theme).

Evidence on `https://www.pornhub.com/video` (HTTP 200, 1.2 MB body):

- Listing cards are `li.pcVideoListItem` inside `ul#videoCategory`; video links are
  `view_video.php?viewkey=<hex>` (`grep -o 'view_video\.php?viewkey=' | wc -l` → 93 in the
  whole page, 82 of them inside the listing block).
- Assets are served from `ei.phncdn.com/www-static/js/…?cache=<yyyymmddHH>` and posters from
  `pix*.phncdn.com` — PornHub's own CDN namespaces, no third-party theme paths.
- The page's own sort UI is query-param based off `/video` (`?o=ht|mv|tr|cm|lg`), and the
  site's nav labels those values (§Homepage) — a KVS or WP theme would use path segments.

```
$ curl -s -A "$UA" -o v.html -w 'HTTP %{http_code} %{size_download}B\n' "https://www.pornhub.com/video"
HTTP 200 1201412B
$ grep -o 'id="videoCategory"' v.html | wc -l
1
$ sed -n '/<ul id="videoCategory"/,/^                <\/ul>/p' v.html | grep -o 'class="pcVideoListItem[^"]*"' | wc -l
31
```

No age/consent wall was hit from the runner: plain `curl` with a desktop UA returns the full
listing (no `age_verified` cookie needed) — see §Headers for what S3 still has to probe.

## Homepage

All four rows are the **same `/video` listing** with a different `o=` sort; each row paginates
with the `page` query parameter. Selector for every row (no per-row difference):

- card container: `ul#videoCategory` — scoping matters: the header's "Hottest" dropdown
  (`ul#hottestMenuSection`) renders the **same** `li.pcVideoListItem` markup on every page, so
  a bare `li.pcVideoListItem` returns those duplicates on every row. The listing block is the
  only place a card's `a[href^="/view_video.php?viewkey="]` belongs to the row.
- card: `ul#videoCategory li.pcVideoListItem`
- card link (also the ad-slot filter — `li.sniperModeEngaged` ad slots have no such anchor):
  `a[href^="/view_video.php?viewkey="]`
- title: `span.title a` text (31/31 cards); the thumbnail anchor's `title` attribute carries
  the same string (31/31) and is the fallback.
- poster: the card's first `<img>`. Listing cards carry the still in **`src`** (absolute,
  `pix*.phncdn.com/…/rs:fit:320:180?hdnea=…`); `data-mediumthumb` holds the identical URL.
  `data-image` / `data-src` / `data-original` appear on the *dropdown* card shape and are
  absent here, so the parser probes that chain first and falls through to `src`. `data-pix=""`
  and `data-mediabook` (a 180P **webm preview**, not a still) are never posters.
- duration: `var.duration` → `H:MM:SS` / `M:SS` text, 31/31 page-1 cards, 43/43 page-2 cards.
- views: `span.views var` → `46.4K`, 31/31 page-1 cards, 43/43 page-2 cards.
  (Carried by `Parse.VideoCard`; `SearchResponse` has no duration/views field, so the emitted
  card does not duplicate them — the load page is the app's metadata source, S3.)

Row URLs (page 1 → page 2):

| Row label | page 1 | page 2 |
|---|---|---|
| Home | `/video` | `/video?page=2` |
| Most Viewed | `/video?o=mv` | `/video?o=mv&page=2` |
| Top Rated | `/video?o=tr` | `/video?o=tr&page=2` |
| Newest | `/video?o=cm` | `/video?o=cm&page=2` |

Sort labels are the site's own, not guesses: the header's "Discover Videos" menu maps
`/video?o=mv` → `Most Viewed`, `/video?o=tr` → `Top Rated`, `/video?o=cm` → `Newest`:

```
$ for o in mv tr cm; do
    curl -s -A "$UA" "https://www.pornhub.com/video" | grep -A2 "href=\"/video?o=$o\"" | sed -n 3p
  done
				Most Viewed			</a>
				Top Rated			</a>
				Newest			</a>
```

The listing's own sort menu confirms `o=cm` = Newest (`data-origin-id="newest"`), so S1 ships
`/video?o=cm` for the Newest row — the round-2 probe's guess is the site's real Newest sort:

```
$ curl -s -A "$UA" "https://www.pornhub.com/video?o=cm" \
  | grep -o '<a class="gtm-event-sortmenu[^>]*data-origin-id="newest"[^>]*>'
<a class="gtm-event-sortmenu" data-event="sorting_menu" data-label="sort_newest" data-origin-id="newest" href="/video?o=cm">
```

Page-1 card counts: 31 cards on each row's page 1, 43 on each page 2 (page 1 carries ad slots).

Transcript (runner, 2026-11-18 — container-scoped counts, page-2 disjointness, row overlap):

```
GET https://www.pornhub.com/video -> HTTP 200; cards: 31
  titles distinct: True; posters distinct: True; durations present: 31/31; views present: 31/31
GET https://www.pornhub.com/video?page=2 -> HTTP 200; cards: 43
  page 2 vs page 1 overlap: 0
GET https://www.pornhub.com/video?o=mv -> HTTP 200; cards: 31
GET https://www.pornhub.com/video?o=mv&page=2 -> HTTP 200; cards: 43
  page 2 vs page 1 overlap: 0
GET https://www.pornhub.com/video?o=tr -> HTTP 200; cards: 31
GET https://www.pornhub.com/video?o=tr&page=2 -> HTTP 200; cards: 43
  page 2 vs page 1 overlap: 0
GET https://www.pornhub.com/video?o=cm -> HTTP 200; cards: 31
GET https://www.pornhub.com/video?o=cm&page=2 -> HTTP 200; cards: 43
  page 2 vs page 1 overlap: 0
NOTE row overlap Home vs Most Viewed: 4 (site content: one video may sit on two sort surfaces)
NOTE every other row pair: 0 shared viewkeys
RESULT: PASS
```

Cross-row overlap is **site content**, not a card-parse defect: the default `/video` feed mixes
in most-viewed items (4 shared viewkeys between Home and Most Viewed on 2026-11-18; zero
in-page duplicates on any row). verify.sh's check-1a spans *all* `--home-url` arguments, so
passing four rows × two pages in one invocation reports those cross-row repeats as a FAIL
artifact — verify one surface's pagination per invocation and treat the other rows' GET line +
card count as their evidence (same treatment recorded in
`docs/archive/providers/AllClassicPorn/FINDINGS.md`, audit-#323).

### Verification note (S1)

verify.sh hard-requires `--search-url` and `--video-url` (surfaces S2/S3 ship), so S1's home-only
story was verified with the same mechanics it applies to a listing page: fetch each row's page 1
and page 2, count `ul#videoCategory li.pcVideoListItem` cards, require ≥1, and require page 2 to
share no viewkey with page 1 (transcript above). The fixtures under
`PornHub/src/test/resources/` are the **verbatim `<ul id="videoCategory">` block** of
`/video` page 1 and `/video?page=2`, the only markup the Parse seam reads.

## Pagination

### Home (all four `/video` rows)

- Pattern: **query param**. Page 1 is the row's own URL (no `page` argument); page N appends
  `page=N` to the existing query (`/video?o=mv&page=2`, `/video?page=2`).
- Page 2 returns fresh items on every row: 43 cards, 0 shared viewkeys with page 1
  (transcript §Homepage).
- Past the last page the site does **not** clamp — it 404s:

```
$ for u in "https://www.pornhub.com/video?page=9999" "https://www.pornhub.com/video?page=0"; do
    curl -s -o last.html -w "$u -> HTTP %{http_code}\n" -A "$UA" "$u"; done
https://www.pornhub.com/video?page=9999 -> HTTP 404
https://www.pornhub.com/video?page=0 -> HTTP 404
```

  (Neither body contains `ul#videoCategory`, so a past-last-page request cannot flood the row
  with page-1 duplicates — `hasNext = true` in `getMainPage` is safe and the app stops on the
  empty/404 page rather than re-serving page 1.)

### Search / related

Deferred to S2 (#1408) and S3 (#1409) — not probed in this story.

## Search

Deferred to S2 (#1408). The row/listing card Parse function (`Parse.cards` →
`parseVideoCards`) is the seam S2 reuses; no search endpoint is claimed here.

## Quick search

Deferred to S2 (#1408), which records the explicit presence/absence probe. S1 ships
`hasQuickSearch` at its `false` default with no claimed endpoint.

## Video pages / Related videos / Stream sources / Headers

Deferred to S3 (#1409) and S4 (#1410). Card-level evidence carried into S3: `viewkey` is the
video identity, listing cards expose duration + views (above), and poster URLs are **signed**
(`?hdnea=st=…~exp=…`) — regenerated on every fetch, never cacheable across runs.

## Risks / blockers

- **Signed/rotating media URLs**: posters and thumbnails carry `hdnea=st=…~exp=…` (+
  `~hmac`) and rotate; `pix-fl` / `pix-cdn77` hostnames alternate per request. Parse fresh per
  row load; never persist a poster URL as stable identity (the Distinct bar compares the
  freshly parsed cards of one fixture/page only).
- **Header dropdown duplicates**: `ul#hottestMenuSection` (header "Hottest" menu) repeats
  `li.pcVideoListItem` cards on *every* page, including page 2 — the card selector must stay
  scoped to `ul#videoCategory` or every row/page cross-check sees duplicates.
- **Ad slots**: `li.sniperModeEngaged` (and other promo `li`s) carry no video link; they are
  dropped by the link selector, not by position.
- **Geo**: probed from the CI runner (EU/US GitHub Actions IP); no geo-wall or age gate
  observed on `/video`, listings, or the sort rows. Video/stream surfaces are S3's to probe.
- Chrome desktop UA used for every request (`Mozilla/5.0 … Chrome/124.0 Safari/537.36`); no
  Referer or cookie was required for any S1 surface.
