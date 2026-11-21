# FINDINGS — PornHub (pornhub.com)

Probe date: 2026-11-18, from the CI runner (GitHub Actions, `Ubuntu` runner IP) — **region not
recorded for that probe**, and do not infer it from this date: the 2026-11-18 runner that saw
the listing gate is a different egress (§Risks runner A), so the S1 transcript below is neither
that gated run nor evidence of a particular region. S3's live
probes ran 2026-11-21; S4's closeout probes ran **2026-11-21 04:14–04:35 UTC from a US
(Virginia) egress** (`ipinfo.io` → `"country": "US"`, `"region": "Virginia"`, AS8075) — record
the egress with every listing claim, because `/video` is **geo/egress-gated** (§Risks/blockers).
Stories S1–S4 of the PornHub spec (#1406) fill this document, and it is now complete: S1
(§Engine fingerprint, §Homepage, §Pagination (home)), S2 (§Search, §Pagination (search),
§Quick search), S3 (§Video pages, §Stream sources, §Headers), S4 (§Related videos, per-field
exposure inventory, §Selector traceability, §Verification (S4), and the region qualification
of §Risks/blockers). Read §Risks first when a probe from your own region disagrees with a
transcript here.

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

Re-confirmed by S4 from the US (Virginia) egress (2026-11-21): `GET /video` → HTTP 200,
1,203,178 B, `id="videoCategory"` ×1, zero `singleFeedSection`, 31 `li.pcVideoListItem` cards
inside the block (45 `pcVideoListItem` in the whole page once the header dropdown is counted).
The site **root** `/` is a different story on the same egress — it answers the SFW variant
(HTTP 200, 1,001,461 B, `ul#singleFeedSection`, zero `ul#videoCategory`), which is why no
shipped row uses `/` (§Risks/blockers, partial wall).

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

Transcript (runner, 2026-11-18 — region not recorded for this probe; container-scoped counts,
page-2 disjointness, row overlap):

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

S4 re-ran all four rows × two pages from the US (Virginia) egress on 2026-11-21 — same shape,
card identity keyed on `?viewkey=` (the instrument plateau in §Risks flattens `url_path`, so
the counts below are container-scoped and the disjointness test is viewkey-keyed):

```
GET /video                 -> HTTP 200 1203178B; cards: 31; distinct viewkeys: 31; titles 31/31; posters 31/31; duration 31/31; views 31/31
GET /video?page=2          -> HTTP 200 1283803B; cards: 43; distinct viewkeys: 43; duration 43/43; views 43/43
  page 2 vs page 1 shared viewkeys: 0
GET /video?o=mv            -> HTTP 200 1207199B; cards: 31; page 2: 1291421B cards: 43; shared: 0
GET /video?o=tr            -> HTTP 200 1204193B; cards: 31; page 2: 1288207B cards: 43; shared: 0
GET /video?o=cm            -> HTTP 200 1197182B; cards: 31; page 2: 1280653B cards: 43; shared: 0
cross-row page-1 shared viewkeys: Home vs Most Viewed 4 / every other pair 0
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

### Search

- Pattern: **query param**, the same helper as the rows — page 1 is
  `/video/search?search=<q>` (no `page` argument), page N appends `page=N`
  (`Parse.searchUrl` reuses `Parse.pageUrl`).
- Page 2 is fresh: 43 cards, **0 shared viewkeys** with page 1 (transcript below).
- Page 1 carries ad slots (`li.sniperModeEngaged`, 31 real cards); every page 2+ has 43.
- Signal: the site's own `<link rel="next" href="…&page=N+1">` in `<head>`, with
  `div.pagination3 li.page_next a[href]` (same href) as the backstop. Both are **absent /
  disabled on the last reachable page** and on the 404 no-results response, so `hasNext` is
  derived, never a constant (`Parse.hasNextPage`).
- Which page is last depends on the query's result count: `search=teen` reports
  `resultsCount: 320365` in `page_params.VIDEOS_FILTER` and still stops at **page 100**
  (43 cards, no `link rel=next`, `li.page_next disabled` with `href=""`). Page 101+ answers
  **HTTP 404** with no result block, so the app's pagination ends on the `hasNext = false`
  page instead of walking into 404s (or into re-served page-1 cards).

```
== search page 1 + page 2 (query: teen) ==
search page 1                GET /video/search?search=teen -> HTTP 200; cards: 31; distinct viewkeys: 31; rel=next: True
search page 2                GET /video/search?search=teen&page=2 -> HTTP 200; cards: 43; distinct viewkeys: 43; rel=next: True
page 2 vs page 1 shared viewkeys: 0
== multi-word query encoding ==
big tits (+ form)            GET /video/search?search=big+tits -> HTTP 200; cards: 31; distinct viewkeys: 31; rel=next: True
big tits (%20 form)          GET /video/search?search=big tits -> HTTP 301 to the `+` form -> HTTP 200; cards: 31; distinct viewkeys: 31; rel=next: True
== past the last page ==
search page 100 (last)       GET /video/search?search=teen&page=100 -> HTTP 200; cards: 43; distinct viewkeys: 43; rel=next: False
search page 101              GET /video/search?search=teen&page=101 -> HTTP 404; cards: 0; distinct viewkeys: 0; rel=next: False
search page 0                GET /video/search?search=teen&page=0 -> HTTP 404; cards: 0; distinct viewkeys: 0; rel=next: False
no-result query              GET /video/search?search=zzzzqqqqnonexistent -> HTTP 404; cards: 0; distinct viewkeys: 0; rel=next: False
RESULT: PASS
```

The card counts are scoped to `ul#videoSearchResult` — the 404 bodies still hold 20–29
`li.pcVideoListItem` header-dropdown cards, so an unscoped count would fake-pass the
past-last-page check (see §Search, scoping).

S4 re-confirmed the past-last-page behaviour on 2026-11-21 (US egress), with the unscoped
`li.pcVideoListItem` count beside the result-block count to show why the scoping matters:

```
/video/search?search=teen&page=100 -> HTTP 200 1197151B; result block 43 cards (43 distinct); link rel=next: False; li.page_next disabled with href=""
/video/search?search=teen&page=101 -> HTTP 404  685186B; result block 0 cards; unscoped li.pcVideoListItem: 20
/video/search?search=teen&page=0   -> HTTP 404  746475B; result block 0 cards; unscoped li.pcVideoListItem: 20
/video/search?search=zzzzqqqqnonexistent -> HTTP 404; div.noResultsWrapper present; result block 0
/video/search?search=big+tits   -> HTTP 200, 31 cards
/video/search?search=big%20tits -> HTTP 301 -> the canonical `+` form (same 31 viewkeys once followed)
RESULT: PASS
```

### Related videos

- **Does not paginate.** The `ul#relatedVideosListing` block is a fixed 35-card selection: no
  `link rel=next`, no `div.pagination3`/`li.page_next` inside or after it, no AJAX "load more"
  hook (a scan of the 200 KB following the container finds no pagination control). So
  `LoadResponse.recommendations` carries the whole block and there is no related page-2 URL to
  record — an explicit absence, not an omission (§Related videos).

## Search

The row/listing card Parse function (`Parse.cards` → `parseVideoCards`) is the seam S2
reuses; only the container differs:

- endpoint: `https://www.pornhub.com/video/search?search=<q>` (HTTP 200, ~1.1 MB body;
  S4 re-confirmed 2026-11-21 from the US egress: 1,125,406 B, 31 cards in the result block);
- results block: `ul#videoSearchResult` (class `full-row-thumbs display-grid col-3-sm
  col-4 videos search-video-thumbs`), cards `ul#videoSearchResult li.pcVideoListItem`;
- card shape is **identical** to the listing (§Homepage): `a[href^="/view_video.php?viewkey="]`
  for the link (ad slots `li.sniperModeEngaged` have none and are dropped), `span.title a`
  for the title, `var.duration` (here classed `bgShadeEffect duration tooltipTrig`) for the
  duration, `span.views var` for the views, `src` / `data-mediumthumb` for the poster.
  Page 1: duration 31/31, views 31/31.
- **Scoping is load-bearing again, for a third container**: the search page also renders the
  header dropdown (`ul#hottestMenuSection`) and a bottom promo row (`ul#bottomVideos`) with
  the same `li.pcVideoListItem` markup. A bare `li.pcVideoListItem` count therefore reports
  33 cards on page 1 where the result block has 31, and 20–29 on a 404 body where the
  result block has 0. `Parse.searchCards` scopes to `ul#videoSearchResult`.
- **Space encoding**: the canonical form is `+` (`<link rel="canonical"
  href="…?search=big+tits">`). `search=big%20tits` answers **301** into the `+` form; both
  then serve the same 31 cards. `Parse.searchUrl` encodes with
  `URLEncoder.encode(q, "UTF-8")` → `+`, so the app never pays the redirect.
- **No results**: a query with no matches answers **HTTP 404** with `div.noResultsWrapper`
  ("No Search Results."), no `ul#videoSearchResult` and no `link rel=next`. NiceHttp's
  `app.get` does not throw on 4xx, so the body is parsed: empty list, `hasNext = false`.
- **search ↔ load agreement**: three sampled page-1 cards fetched as
  `view_video.php?viewkey=<vkey>` → HTTP 200 with `og:title` equal to the card title
  (`Her Tits Drives Me Crazy`; `Cumming inside my Best Friend's Mom`; `5 Minutes Before
  Class and I'm Still Not Ready: Hard Pillow Fuck and Squirt`). Their `og:image` is the
  same asset path on a **different CDN shard and rendition**
  (`pix-fl…/plain/rs:fit:320:180` on the card vs `pix-egi…/plain/rs:fit:640:360` on the
  load page) — so poster agreement has to compare the asset path, never the signed URL
  (carried into S3).

### Verification note (S2)

verify.sh hard-requires `--video-url` (plus its stream checks), which S3 ships, so S2 was
verified with the same mechanics verify.sh applies to its search check — fetch both search
pages, require ≥1 card, require page 2 to share no viewkey with page 1, and require the
multi-word query to round-trip — via the transcript in §Pagination (search) plus the
title-agreement probe above. The fixtures under `PornHub/src/test/resources/` are the
verbatim `<link rel="canonical">` / `<link rel="next|prev">` head lines followed by the
verbatim `<ul id="videoSearchResult">` result block (`search-page-1.html`,
`search-page-2.html`), the verbatim head lines + `<div class="pagination3">` block of the
last reachable page (`search-last-page.html`), and the verbatim `<title>` +
`div.noResultsWrapper` of the 404 response (`search-no-results.html`) — the only markup the
search Parse seams read.

## Quick search

**No distinct quick-search endpoint.** All three round-2 probes answer HTTP 404, so there is
nothing for `quickSearch` to call and `hasQuickSearch` stays `false` (results come from the
full search page only):

```
$ for u in "/video/search_autocomplete?q=teen" "/api/v1/video/search?search=teen" "/suggest?q=teen"; do
    curl -s -o /dev/null -w "https://www.pornhub.com$u -> HTTP %{http_code}\n" -A "$UA" "https://www.pornhub.com$u"; done
https://www.pornhub.com/video/search_autocomplete?q=teen -> HTTP 404
https://www.pornhub.com/api/v1/video/search?search=teen -> HTTP 404
https://www.pornhub.com/suggest?q=teen -> HTTP 404
```

Re-probed by S4 on 2026-11-21 from the US (Virginia) egress — all three still `HTTP 404`
(`text/html` 404 page, no JSON body, no card markup):

```
$ for u in "/video/search_autocomplete?q=teen" "/api/v1/video/search?search=teen" "/suggest?q=teen"; do
    curl -sS -o /dev/null -A "$UA" -w "https://www.pornhub.com$u -> HTTP %{http_code} %{content_type}\n" "https://www.pornhub.com$u"; done
https://www.pornhub.com/video/search_autocomplete?q=teen -> HTTP 404 text/html; charset=UTF-8
https://www.pornhub.com/api/v1/video/search?search=teen -> HTTP 404 text/html; charset=UTF-8
https://www.pornhub.com/suggest?q=teen -> HTTP 404 text/html; charset=UTF-8
```

So `hasQuickSearch = false` stays: there is no site endpoint whose response shape differs from
the full search page, and the app's fallback is the full `/video/search` surface (§Search).

## Video pages

Watch page = `https://www.pornhub.com/view_video.php?viewkey=<vkey>` (HTTP 200, ~1.3 MB).
**Desktop markup only:** a mobile Chrome UA gets a *different, thinner* page — corrected by S4,
see "Mobile UA" below; curl's, OkHttp's and the app's default UA all get the desktop page
(`okhttp/4.12.0` re-verified 2026-11-21: HTTP 200, 1,333,227 B, `inlineFree` ×1,
`pornstarsWrapper` ×1, `var flashvars` ×1). Every selector below is from that desktop page.

| Field | Selector / source | Notes |
|---|---|---|
| title | `h1.title span.inlineFree` | fallback `meta[property=og:title]` (same string) |
| poster | `meta[property=og:image]` | signed, `pix-egi`/`pix-cdn77` alternate; compare the asset path, never the URL |
| plot | `meta[property=og:description]` | the site exposes **no owner-written synopsis**; the generated `"Watch <title> on Pornhub.com, …"` string is the only description and is carried verbatim (never invented) |
| tags | `div.categoriesWrapper a[data-label=category]` + `div.tagsWrapper a[data-label=tag]` | both feed `LoadResponse.tags`; categories first (13/14 cat + 14/16 tag links on the sampled pages) |
| actors | `div.pornstarsWrapper a.pstar-list-btn` | anchor text is the model name (`Indica Marie`); uploads that credit nobody render **no wrapper** → empty actor list (fixture `video-page-2.html`) |
| duration | JSON-LD `VideoObject` `"duration":"PT00H16M50S"` | no DOM attribute carries it; `JsonLdParse.minutes` floors to minutes (repo convention); flashvars `video_duration` (seconds) is the fallback |
| year | JSON-LD `"uploadDate":"2026-06-18T00:26:47+00:00"` | `JsonLdParse.year`; no DOM attribute carries it either |
| related videos | `ul#relatedVideosListing li.pcVideoListItem` | same card shape as the listing; the block also carries ad `<li class='js-nativeTjVideoGrid js_promoItem'>` slots (no `view_video` link) which the card parser drops — full transcript in §Related videos |

All-or-none exposure on 5 sampled pages (2026-11-21): title/poster/plot 5/5, `tags` 5/5,
`actors` 5/5 (all five sampled uploads credit at least one model; a no-credit upload is the
`video-page-2.html` fixture shape), related block 5/5 (36 cards on the S3 sample, 35 on the S4
pages — the block is a fixed selection whose size drifts with the site's own content).

```
$ for vk in 6a80b72923d3f 6aefb497a0734 6aa3d2b52e477 ph5db33702d8ca6 6a333a768c26c; do
    curl -s -A "$UA" "https://www.pornhub.com/view_video.php?viewkey=$vk" …
  done
6a80b72923d3f  title='Perfect Little Model Makes Love To Giant Cock'                  dur=PT00H23M06S year=2026 tags=16 actors=1 related=36 self_in_related=False
6aefb497a0734  title='Big Tits Stepsister Wants Some Help From Stepbro - Charli O - …' dur=PT00H23M11S year=2026 tags=8  actors=1 related=36 self_in_related=False
6aa3d2b52e477  title='A Hardcore Pee Gangbang for Brazilian Slut Cintia Lara'       dur=PT00H16M47S year=2026 tags=15 actors=2 related=36 self_in_related=False
ph5db33702d8ca6 title='HOW DOES IT FIT! TEEN PIPER PERRI SQUIRTS &amp; GETS …'        dur=PT00H42M22S year=2019 tags=17 actors=2 related=36 self_in_related=False
6a333a768c26c  title='Picking Up My Step Sister From Starbucks | Perfect Tits | …'   dur=PT00H16M50S year=2026 tags=14 actors=2 related=36 self_in_related=False
distinct titles 5/5; distinct poster asset paths 5/5; distinct viewkeys 5; related cards distinct within page
RESULT: PASS
```

### Per-field exposure inventory (S4, 2026-11-21)

Five pages sampled from **five different listings** — Home, Most Viewed, Top Rated, Newest and
`search=teen` — one per surface, US (Virginia) egress:

```
page            from listing    http  title                                             dur          upload        cats tags actors related
6afbd8a1f2e9e   /video          200   Cute Teen Gets Fucked On Snap After Break Up - Julia James  PT00H15M00S  2026-11-17    10   13   2      35
6aee3ff58a230   /video?o=mv     200   Milf with Big ASS Touched & Fucked by a Stranger …  PT00H17M03S  2026-11-06    11   16   1      35
6acf5129bd5a5   /video?o=tr     200   1500 Win Tonight                                   PT00H10M40S  2026-10-14    9    14   1      35
6b00722b9a541   /video?o=cm     200   Beautiful blonde slow PASSIONATE fuck with big dick …  PT00H15M25S  2026-11-20    10   16   2      35
6ac3a3b42e432   search=teen     200   First creampie video for 19-year-old Latina         PT00H11M13S  2026-10-05    12   13   1      35
distinct titles 5/5; distinct og:image paths 5/5; distinct viewkeys 5; og:title == h1 5/5; JSON-LD name == h1 5/5
RESULT: PASS
```

Exposure inventory, explicit per field (`LoadResponse` field → what the page actually exposes):

| `LoadResponse` field | Exposed? | Source | Note |
|---|---|---|---|
| `name` | yes, 5/5 | `h1.title span.inlineFree`, fallback `meta[property=og:title]` | identical strings on all 5; the JSON-LD `VideoObject.name` is the same string |
| `posterUrl` | yes, 5/5 | `meta[property=og:image]` (+ JSON-LD `thumbnailUrl`) | signed, shard/rendition vary per fetch; compare the asset path |
| `plot` | yes, 5/5 — **generated, not authored** | `meta[property=og:description]` (fallback `meta[name=description]`, same string) | the site exposes **no owner-written synopsis**; carry the generated string verbatim, never invent one |
| `tags` | yes, 5/5 | `div.categoriesWrapper a[data-label=category]` + `div.tagsWrapper a[data-label=tag]` | 9–13 categories + 13–16 tags per page; distinct set is 22–27 |
| `actors` | yes — **absent on no-credit uploads** | `div.pornstarsWrapper a.pstar-list-btn` | 1–2 on the five §Video pages sampled; uploads that credit nobody render **no wrapper at all**, so `actors` is legitimately empty there — the shape pinned by the `video-page-2.html` fixture |
| `duration` | yes, 5/5 | JSON-LD `VideoObject.duration` (ISO-8601, `PT00H15M00S`) | no DOM attribute; flashvars `video_duration` (900) is the fallback and agrees |
| `year` | yes, 5/5 | JSON-LD `VideoObject.uploadDate` (`2026-11-17T04:35:53+00:00`) | no DOM attribute either |
| `recommendations` | yes, 5/5 | `ul#relatedVideosListing li.pcVideoListItem` | 35 cards per page, all distinct, never the page's own viewkey (§Related videos) |
| `score` | **exposed but not populated** | `div.rating-container` / `span.votesUp[data-rating]` (`data-rating="122"` on `6afbd8a1f2e9e`) and `div.ratingInfo` | the provider leaves `LoadResponse.score` unset — a documented non-population, not a site gap. Recorded so a future story can wire it without re-probing. |
| channel / uploader | **exposed but not populated** | JSON-LD `VideoObject.creator.name` (e.g. `Unrated Stories`) + `creator.url` | `LoadResponse` carries no studio/channel field for a movie; actors are the model list, not the channel |
| `backgroundPosterUrl` / `logoUrl` | **not exposed** | — | no per-video backdrop or logo in the desktop markup; the theme's own art is site chrome |
| `trailers` | **not exposed** | — | no trailer entry in `mediaDefinitions` or `og:video`; the only video sources are the watch page's own `mediaDefinitions` (§Stream sources) |

### Mobile UA (corrected by S4)

The earlier note ("a mobile Chrome UA gets a page with no `h1.title`/`pornstarsWrapper`") was
wrong as written. A mobile Chrome UA **does** get `var flashvars` (5 `mediaDefinitions`),
`og:*`, a `pornstarsWrapper` and an `inlineFree` title, but it is a **thinner layout**:

```
$ UA='Mozilla/5.0 (Linux; Android 13; Pixel 7) … Chrome/124.0.0.0 Mobile Safari/537.36'
$ curl -s -A "$UA" "https://www.pornhub.com/view_video.php?viewkey=6afbd8a1f2e9e"
HTTP 200 825168B
  <h1 class="floatLeft fullWidth scrollableTitle">     <- no `title` class, so `h1.title span.inlineFree` does NOT match
      <span class="inlineFree">Cute Teen Gets Fucked on Snap after Break up - Julia James</span>
  categoriesWrapper: 0   tagsWrapper: 0   relatedVideosListing: 0   pornstarsWrapper: 1   var flashvars: 1
$ curl -s -A "$UA" "https://www.pornhub.com/video"
HTTP 200 860316B; id="videoCategory" matches: 0     <- mobile listing has no listing block at all
```

So under mobile markup the provider degrades **silently but safely**: title falls back to
`og:title` (present), streams still resolve (flashvars present), `tags`/`recommendations`/
home cards come back empty. The app's own UA never sees this page (§Headers), which is why the
provider targets desktop markup — but the degradation is recorded here so a UA change is
diagnosed from this transcript instead of a silent empty `load()`.

### Walled pages (recorded samples)

A premium video answers **HTTP 200** with `og:*` metas, `i.premiumIconTitleOnVideo` and
`div.premiumLockedPartners`, but **no `var flashvars_` blob** — the paywall wall. A removed /
invalid viewkey answers **HTTP 404 `Page Not Found`**, also with no flashvars blob. Both →
`Parse.videoDetails` returns `null`, so the app drops the card instead of throwing:

```
$ curl -s -o w.html -w 'HTTP %{http_code}\n' -A "$UA" "https://www.pornhub.com/view_video.php?viewkey=64894d8f6af1f"
HTTP 200  # premium: flashvars=False premiumLock=True premiumIconTitle=True  (Brazzers title retained in og:title)
$ curl -s -o w.html -w 'HTTP %{http_code}\n' -A "$UA" "https://www.pornhub.com/view_video.php?viewkey=0000000000000"
HTTP 404  # flashvars=False, <title>Page Not Found</title>
```

A geo wall (`video_unavailable_country: "true"`) was **not observed** from the runner's IPs
across ~50 sampled pages on 2026-11-18 (region not recorded) and again on all 5 S4 pages on
2026-11-21 (US/Virginia egress;
`"video_unavailable": false`, `"video_unavailable_country": false` on every one). The
flashvars flags are still honoured by the parser (site documentation shape), but there is no
recorded geo-walled *watch* page — stated, not fabricated. The **listing** gate is a different
wall and it *was* observed: §Risks/blockers.

## Related videos

Every sampled watch page (5/5, `ul#relatedVideosListing` present) carries the related block:

- container: `<ul class="videos underplayer-thumbs fixedSizeThumbsVideosListing"
  data-gaBlockName="Related Video Container" id="relatedVideosListing">`;
- cards: `ul#relatedVideosListing li.pcVideoListItem` — the **same card markup** as the listing
  (`a[href^="/view_video.php?viewkey="]`, `span.title a`, `var.duration`, `span.views var`,
  `img src`), so `Parse.relatedCards` reuses the listing card parser and only the container
  differs;
- ad slots: the block interleaves `<li class='js-nativeTjVideoGrid js_promoItem'>` rows with no
  `view_video` link — dropped by the link selector, never by position.

```
6afbd8a1f2e9e  35 cards  distinct viewkeys 35  titles distinct 35  self_in_related=False
6aee3ff58a230  35 cards  distinct viewkeys 35  titles distinct 35  self_in_related=False
6acf5129bd5a5  35 cards  distinct viewkeys 35  titles distinct 35  self_in_related=False
6b00722b9a541  35 cards  distinct viewkeys 35  titles distinct 35  self_in_related=False
6ac3a3b42e432  35 cards  distinct viewkeys 35  titles distinct 35  self_in_related=False
RESULT: PASS
```

Pagination: **the related block does not paginate** — there is no `link rel=next`, no
`div.pagination3`/`li.page_next` inside or after the block (a scan of the 200 KB following the
container finds no pagination control), and no AJAX "load more" hook on it. The block is a
fixed 35-card selection; `LoadResponse.recommendations` carries exactly that.

**verify.sh plateau:** `--related-selector` cannot be used on this site — card identity is the
`?viewkey=` query and verify.sh's `url_path` strips the query, so all 35 cards normalize to
`/view_video.php`, tripping both the "duplicate recommendations" and "recommendations contain
the video itself" checks on a correct provider. Substituted with the viewkey-keyed transcript
above (same pattern as `PerverZija`/`Javmost`; see §Verification (S4)).

## Stream sources

The player config is a single-line `var flashvars_<videoId> = { … };` blob on the watch page
(desktop markup). Its `mediaDefinitions` array is the source list:

| entry | format | height | videoUrl |
|---|---|---|---|
| 1 | `hls` | 240 | `…/240P_1000K_<id>.mp4/master.m3u8?…` |
| 2 | `hls` | 480 | `…/480P_2000K_<id>.mp4/master.m3u8?…` |
| 3 | `hls` | 720 | `…/720P_4000K_<id>.mp4/master.m3u8?…` |
| 4 *(often)* | `hls` | 1080 | `…/1080P_4000K_<id>.mp4/master.m3u8?…` |
| last | `mp4` | source height | `https://www.pornhub.com/video/get_media?s=…&v=<vkey>&e=0&t=p`, `remote: true` |

- **HLS, not progressive mp4.** Every `hls` entry's `videoUrl` is a master playlist
  (`master.m3u8`, `Content-Type: application/vnd.apple.mpegurl`) — the provider emits one
  `ExtractorLink` per HLS entry with `type = ExtractorLinkType.M3U8` and the entry's own
  `height` as quality (`Parse.qualityOf`; `Qualities` values are pixel heights). The entry
  `quality` field is a string (`"720"`); `height` is the int actually used.
- **Fresh every call.** URLs are signed and short-lived (`validfrom`/`validto`, ~2 h);
  `loadLinks` re-fetches the watch page and re-parses `flashvars` on every invocation — no
  cached URL is ever reused. `mediaPriority: "hls"` on every sampled page.
- **`/video/get_media` fallback.** The `remote: true` mp4 leg carries the documented fallback
  endpoint. It answers a JSON array of progressive mp4 legs (240/480/720/1080,
  `Content-Type: video/mp4`). It is only used when the flashvars carries **no HLS leg**, and
  it needs the watch page's own session cookies — a cookie-less GET answers `[]`
  (`ss`/`sessid` are the load-bearing cookies; the provider forwards `response.cookies`).

Resolve loop (2026-11-21, page re-fetched fresh per video, then every emitted quality probed):

```
6a80b72923d3f  highest 1080 → 200 application/vnd.apple.mpegurl; all [240,480,720,1080] → 200 mpegurl
6aefb497a0734  highest 1080 → 200 application/vnd.apple.mpegurl; all [240,480,720,1080] → 200 mpegurl
6aa3d2b52e477  highest 1080 → 200 application/vnd.apple.mpegurl; all [240,480,720,1080] → 200 mpegurl
ph5db33702d8ca6 highest 1080 → 200 application/vnd.apple.mpegurl; all [240,480,720,1080] → 200 mpegurl
6a333a768c26c  highest  720 → 200 application/vnd.apple.mpegurl; all [240,480,720]       → 200 mpegurl
no two videos share a stream path; every path is <videoId>-specific
RESULT: PASS
```

S4 re-ran the resolve loop on the 5 §Video pages sampled from five **different listings**
(2026-11-21, US Virginia egress), this time re-fetching until the page was assigned a live
(style A) signing shape, then probing **every** emitted HLS leg:

```
6afbd8a1f2e9e (Home)        page fetches until style A: 1; legs [1080,240,480,720] -> all 200 application/vnd.apple.mpegurl
6aee3ff58a230 (MostViewed)  page fetches until style A: 1; legs [1080,240,480,720] -> all 200 application/vnd.apple.mpegurl
6acf5129bd5a5 (TopRated)    page fetches until style A: 1; legs [1920,426,852,1280] -> all 200 application/vnd.apple.mpegurl
6b00722b9a541 (Newest)      page fetches until style A: 5; legs [1080,240,480,720] -> all 200 application/vnd.apple.mpegurl
6ac3a3b42e432 (search=teen) page fetches until style A: 1; legs [240,480,720]       -> all 200 application/vnd.apple.mpegurl
highest URLs distinct 5/5 by path; every path is <videoId>-specific
RESULT: PASS
```

**`height` is not the same convention on every page.** All five S4 reads above are style A
(each page was re-fetched until a 200-answering signing shape), yet four report ladder heights
(`[1080,240,480,720]`; `[240,480,720]` on `6ac3a3b42e432`) and only `6acf5129bd5a5` reports
`[1920,426,852,1280]`. The S3 resolve block above is likewise all style A (every leg 200) and
reports `[240,480,720,1080]` on four of five. So the signing shape does **not** select the
convention — style-A pages split 4:1 here. The numbers also differ in dimension:
`1920/1280/852/426` are the standard pixel **widths** of 1080p/720p/480p/240p, while
`1080/720/480/240` are the **heights**. What was measured is "some pages put the pixel width in
`height`, others the ladder height, per video/asset"; the cause is not established by this
data, and no A/B transcript exists (the S4 loop's re-fetch counts record attempts to land
style A, not a style-B measurement). `Parse.qualityOf` passes the page's own `height` through
as the `ExtractorLink.quality` int, so the player's quality ordering can see 1920/1280/852/426
instead of 1080/720/480/240 on such pages. Every probed leg served real video either way —
recorded as an observation for the quality-priority surface, not as a playback defect.
`/video/get_media` on the same pages shows the same split (`240/480/720/1080` on four,
`426/852/1280/1920` on `6acf5129bd5a5`), so the fallback path inherits it.

`/video/get_media` fallback re-probed with S4's cookie jar (watch page's own `Set-Cookie`
replayed):

```
6afbd8a1f2e9e  WITH cookies: [(240, …/240P_1000K_69506775.mp4), (480, …), (720, …), (1080, …)]   COOKIE-LESS: []
6aee3ff58a230  WITH cookies: [(240, …/68202355…), (480,…), (720,…), (1080,…)]                  COOKIE-LESS: []
6acf5129bd5a5  WITH cookies: [(426, …/64760725…), (852,…), (1280,…), (1920,…)]                 COOKIE-LESS: []
6b00722b9a541  WITH cookies: [(240, …/69936225…), (480,…), (720,…), (1080,…)]                  COOKIE-LESS: []
6ac3a3b42e432  WITH cookies: [(240, …/433637271…), (480,…), (720,…), (1080,…)]                 COOKIE-LESS: []
RESULT: PASS
```

So the fallback the provider takes when a page fetch lands on a dead style-B assignment
(§CDN host lottery) does answer real mp4 legs — and only with the watch page's session cookies,
exactly as `loadLinks` sends them.

### CDN host lottery (risk, see Risks)

The same video's `mediaDefinitions` URLs are assigned one of two signing/CDN shapes per page
fetch: **style A** on `ev-h`/`em-h-phncdn.com` (`?validfrom=…&validto=…&hash=…`) answers 200,
**style B** on `hv-h`/`hm-h`/`km-h` (`?h=…&e=…&f=1`) answers **410 text/plain** (Cloudflare
edge, `__cf_bm` set). The assignment flips per request (measured ~50/50; the 5-video PASS
above needed 5/0/2/4/1 page re-fetches to land style A). Style-B tokens are host-bound
(replaying the same query on `ev-h` → 470), so the provider cannot rewrite the host.

**What the provider does with a dead shape (repaired on review of #1419):** `loadLinks` probes
the HLS legs of the page fetch it just made and emits only legs whose master playlist answers
2xx (`Parse.playableHls` + `PornHub.isPlayable`). A shape that answers nothing falls through to
the `/video/get_media` mp4 legs instead of handing the player unresolvable URLs. The probe runs
per call, so it follows the device's own assignment rather than the runner's.

## Headers / referer

- Every S1–S3 surface (listings, search, watch pages, flashvars) answered to a plain GET with a
desktop UA and **no cookies**; the age/consent cookie (`age_verified=1`, `platform=pc`) was
**not needed** and is not sent by the provider for page loads.
- The one cookie-dependent surface is the `/video/get_media` fallback (above): the provider
  forwards the watch page's `Set-Cookie` map (`ss`, `sessid`, …) on that call only.
- Playback referer: the app's default is used; the HLS masters above answer 200 with
  `Referer: https://www.pornhub.com/` (the provider sets `referer = mainUrl/` on every link).
  S4 probed the same master **with and without** a Referer from the US egress on 2026-11-21:

  ```
  no referer:   206 application/vnd.apple.mpegurl
  with referer: 206 application/vnd.apple.mpegurl
  ```

  so the referer is sent as the site's documented expectation, not as a hard requirement of
  the CDN — a missing Referer never turns a working link into a dead one here.
- No `Cookie` header is needed for any page surface, and **no `age_verified` cookie is required
  at all** (the site root sets `platform=pc`, `ss`, `sessid`, … on its own; a plain cookie-less
  GET gets the same body). Verified by fetching the 4 listing rows, both search pages, 5 watch
  pages and both walls cookie-less.
- Mobile UA is the one UA that changes the **page shape**, not just cosmetics — see
  "Mobile UA" in §Video pages.

### Verification note (S3)

verify.sh ran with 5 watch pages drawn from 5 different surfaces (home, Most Viewed, Top Rated,
Newest, `search=starbucks`), the search page-1/page-2 pair, the home page-1/page-2 pair, and
`--related-selector`/`--video-tags-selector`/`--video-actors-selector`:

- `div#player` matched 3/3 per watch page; title (5/5), poster (5/5), plot (5/5) exposed;
  `tags` and `actors` present on **all 5** pages; check 6 reports every requested LoadResponse
  field populated (recommendations/tags/plot/duration/year/actors).
- Streams: flashvars JSON escapes slashes (`https:\/\/…`), so verify.sh's page-level m3u8
  regex cannot see the emitted links — the run used the documented position-matched
  `--stream-url` override, each URL resolved live through the **provider's own** flashvars
  parse (the highest HLS of that page). All five → **206 `application/vnd.apple.mpegurl`**;
  the resolve loop separately probed *every* emitted quality (240/480/720/1080) → 200 (see
  §Stream sources). No stream path is shared by two videos.
- **Instrument plateau:** card/video identity is the `?viewkey=` query, and verify.sh's
  `url_path` drops the query → every href normalises to `/view_video.php`. That one root
  cause makes check-1/1a duplicate detection, check-3 href identity, the related
  self/duplicate checks and check-5 agreement false-FAIL. Substituted with the same mechanics
  keyed on `viewkey` (transcript below): search p1 32 / p2 44 cards, all distinct within a
  page, **0 shared** between pages; home p1 32 / p2 44, **0 shared**; the five sampled videos
  have 5 distinct titles, 5 distinct poster asset paths and 5 distinct viewkeys; each related
  block's 36 cards are distinct and never repeat the page's own viewkey. Precedent for the
  substitution: `docs/archive/providers/PerverZija/FINDINGS.md` (§Verification) and
  `docs/archive/providers/Javmost/FINDINGS.md`.
- Walled: the premium sample (`64894d8f6af1f`, HTTP 200, no flashvars) and the removed sample
  (`0000000000000`, HTTP 404) both return `null` from `Parse.videoDetails` — pinned by
  `PornHubLoadParseTest` against `video-page-walled.html`.
- Gates: `./gradlew PornHub:test` green (161 tests incl. the S3 suite), `./gradlew PornHub:make`
  BUILD SUCCESSFUL.

## Risks / blockers

- **Signed/rotating media URLs**: posters and thumbnails carry `hdnea=st=…~exp=…` (+
  `~hmac`) and rotate; `pix-fl` / `pix-cdn77` hostnames alternate per request. Parse fresh per
  row load; never persist a poster URL as stable identity (the Distinct bar compares the
  freshly parsed cards of one fixture/page only).
- **Header dropdown duplicates**: `ul#hottestMenuSection` (header "Hottest" menu) repeats
  `li.pcVideoListItem` cards on *every* page, including page 2 — the card selector must stay
  scoped to `ul#videoCategory` (rows) or `ul#videoSearchResult` (search) or every row/page
  cross-check sees duplicates. On the search page `ul#bottomVideos` (promo row) is a third
  container with the same card shape.
- **Ad slots**: `li.sniperModeEngaged` (and other promo `li`s) carry no video link; they are
  dropped by the link selector, not by position.
- **Geo / SFW gate on listings (region-dependent) — `Blocked` for the affected egress.** The
  `/video` listing surface is geo/egress-gated, and the gate is the same one that serves the
  SFW homepage. Two CI runners, two opposite outcomes for the **same** URLs:

  ```
  runner A  2026-11-18  countryCode: "US" (ph-redirect: 1041, isSfw: 1)
    GET /video, /video?page=2, /video?o=mv, /video?o=tr, /video?o=cm  -> 302 -> /
    served root: ul#singleFeedSection (SFW), 0 x ul#videoCategory
  runner B  2026-11-21  ipinfo.io: country "US", region "Virginia", AS8075 (this S4 run)
    GET /video        -> 200 1203178B, id="videoCategory" x1, 31 cards
    GET /video?page=2 -> 200 1283803B, 43 cards, 0 shared viewkeys with page 1
    GET /video?o=mv|tr|cm -> 200, 31 cards each; page 2 43 each, 0 shared
    GET /                -> 200 1001461B, ul#singleFeedSection x1, 0 x ul#videoCategory, "isSfw":""
  ```

  So the gate is **not** a selector drift and **not** a dead endpoint: the four row URLs in
  `PornHub.kt` and `Parse.cards` are correct where the gate is absent (runner B: 4 rows × 2
  pages, 31/43 cards, 0 cross-page overlap). A `302 -> /` with `ph-redirect: 1041` **is the
  gate**, not a broken link — record the egress with every listing transcript and never "fix"
  it with a new selector. The site root `/` is SFW on runner B while `/video` is open: a
  **partial** wall, which is why no shipped row uses `/`. `vpnStatus = VPNStatus.MightBeNeeded`
  is the provider's only signal that a region may see an empty feed; no cookie/param unlock
  was recorded from a gated egress (the gate is decided server-side per egress IP).
  `Blocked: geo/SFW gate (302 -> / + ph-redirect: 1041, no unlock)` — a gated egress cannot
  verify the four home rows, and no re-probe is worth running from it.
- **Geo wall on watch pages not observed.** ~50 pages on 2026-11-18 (region not recorded) and
  all 5 S4 pages on 2026-11-21 carried `video_unavailable_country: "false"` (and `video_unavailable: false`);
  the parser still honours both flags, but there is no recorded geo-walled sample. Removed
  (404) and premium (200, no flashvars) walls are recorded in §Video pages and both drop the
  card (`load()` → `null`). This is a **different** flag from the listing gate above.
- **Quality ints inherit the page's `height` field, which is not consistent per page.** Most
  probed pages report ladder heights (`1080/240/480/720` on four of the five S4 style-A reads,
  `240/480/720/1080` on four of the S3 reads), but `6acf5129bd5a5` reports pixel widths
  (`1920/426/852/1280`) for the same `1080P_4000K_…` path. The signing shape does not explain
  the split — every one of those reads is style A — and no A/B transcript exists; the cause is
  unestablished. `Parse.qualityOf` passes the value through as `ExtractorLink.quality`, so the
  quality-ordering surface can see 1920/1280/852/426 instead of 1080/720/480/240. Every leg
  still served video; recorded so a "quality sort looks odd" report is diagnosed from
  §Stream sources.
- Chrome desktop UA used for every request (`Mozilla/5.0 … Chrome/124.0 Safari/537.36`); no
  Referer or cookie was required for any S1/S2 surface.
- **Mobile UA gets a different, thinner page.** A mobile Chrome UA still gets `var flashvars`
  (streams work) and `og:*`, but has **no** `div.categoriesWrapper` / `div.tagsWrapper` /
  `ul#relatedVideosListing` and no `ul#videoCategory` on `/video` (transcript in §Video pages,
  "Mobile UA"). curl, OkHttp and the app's default UA all get the desktop markup the selectors
  target (`okhttp/4.12.0` re-verified 2026-11-21). Recorded so a future UA change is diagnosed
  from here, not from a silent empty `load()` / empty tags / empty recommendations.
- **CDN host lottery on watch pages (S3).** `mediaDefinitions` HLS URLs come in two signing
  shapes per fetch: style A (`ev-h`/`em-h`, `validfrom…hash`) → **200
  `application/vnd.apple.mpegurl`**; style B (`hv-h`/`hm-h`/`km-h`, `h…e…f=1`) → **410
  text/plain** (Cloudflare edge, `__cf_bm`). Measured ~50/50; style-B tokens are host-bound
  (same query on `ev-h` → 470), so the provider emits the page's URLs verbatim and cannot
  normalise them. Verification re-fetches the page until style A (5/0/2/4/1 attempts on the
  five sampled videos) and proves every quality resolves. **Repaired on review of #1419:**
  `loadLinks` probes the legs of the page fetch it just made and emits only the ones that answer
  2xx; a dead shape now falls through to `/video/get_media` instead of emitting URLs the player
  cannot open (§Stream sources). Whether a *style-A* assignment is what a device gets in-app is
  a maintainer emulator note, not a code path.
- **Signed/rotating media URLs (watch pages).** HLS masters and posters carry signed query
  tokens (`validfrom`/`validto` ~2 h, or `h`/`e`) and rotate on every page fetch; `loadLinks`
  resolves fresh on every call and never caches a stream URL.
- **`/video/get_media` needs session cookies.** A cookie-less GET answers `[]`; the watch
  page's `ss`/`sessid` cookies make it return the mp4 array. Only the fallback path forwards
  them, so a cookie-less environment degrades to "no fallback links", never to wrong URLs.
- **Plot is boilerplate.** The site exposes no owner-written synopsis; `LoadResponse.plot` is
  the generated `og:description` (`Watch <title> on Pornhub.com, …`) carried verbatim. A real
  per-video description is not exposed (stated here instead of fabricated).
- **`duration`/`year` are script-embedded.** They live in the JSON-LD `VideoObject` (and
  flashvars `video_duration`), not in a DOM attribute — verify.sh's `--video-duration-selector`
  / `--video-year-selector` cannot assert them; they are covered by the Parse fixtures
  (`PT00H16M50S` → 16 min, `uploadDate` → 2026) plus the §Video pages transcript.
- **verify.sh's identity model flattens PornHub's video ids (instrument plateau).** Card and
  video identity lives in the `?viewkey=` **query string**; verify.sh's `url_path` strips the
  query, so every card/video normalises to `/view_video.php`. Check-1/1a duplicate detection,
  check-3 href identity, the related self/duplicate checks and check-5 agreement all
  false-FAIL on that one root cause — they were substituted with the viewkey-keyed equivalents
  (see Verification note (S3) and §Verification (S4)). Same plateau class as
  PerverZija/Javmost FINDINGS.

## Selector traceability (acceptance: every selector/endpoint in `PornHub.kt` → evidence)

Every selector and endpoint the shipped provider uses, and the section of this document that
carries its transcript:

| `PornHub.kt` / `Parse` symbol | Literal | Evidence here |
|---|---|---|
| `Parse.BASE`, `mainUrl` | `https://www.pornhub.com` | every transcript |
| `mainPage` rows | `/video`, `/video?o=mv`, `/video?o=tr`, `/video?o=cm` | §Homepage (nav labels + sort menu + S4 transcript); gate caveat §Risks |
| `Parse.LISTING` | `ul#videoCategory li.pcVideoListItem` | §Homepage (scoping + counts) |
| `Parse.SEARCH_LISTING` | `ul#videoSearchResult li.pcVideoListItem` | §Search (scoping; unscoped 38 vs 31) |
| `Parse.RELATED_LISTING` | `ul#relatedVideosListing li.pcVideoListItem` | §Related videos |
| `Parse.LINK` | `a[href^="/view_video.php?viewkey="]` | §Homepage (ad-slot filter), §Related videos |
| `SEARCH_PATH` | `/video/search?search=<q>` (`+` encoding) | §Search, §Pagination (search) |
| `Parse.pageUrl` | `page=N` query param | §Pagination (home, search) |
| `Parse.hasNextPage` | `link[rel=next]`, `li.page_next:not(.disabled) a[href]` | §Pagination (search) — absent/disabled on page 100 and the 404 body |
| card `span.title a` | title | §Homepage (31/31 + `title` attr fallback) |
| card `var.duration` | duration text | §Homepage (31/31, 43/43) |
| card `span.views var` | views | §Homepage (31/31, 43/43) |
| card poster chain | `data-image`→`data-src`→`data-original`→`src`, `data:` skipped | §Homepage (poster note) |
| `Parse.FLASHVARS` | `var flashvars[_id] = {…};` | §Stream sources |
| `mediaDefinitions` / `format` / `height` / `remote` / `videoUrl` | player config array | §Stream sources (per-leg probe + height convention) |
| `video_duration` | seconds fallback | §Video pages (agrees with JSON-LD on all 5) |
| `video_unavailable`, `video_unavailable_country` | wall flags | §Video pages (walled samples), §Risks |
| `h1.title span.inlineFree` | title, fallback `og:title` | §Video pages (inventory; mobile-UA caveat) |
| `meta[property=og:image]` | poster | §Video pages (inventory) |
| `meta[property=og:description]`, `meta[name=description]` | plot | §Video pages (inventory + boilerplate note) |
| `div.categoriesWrapper a[data-label=category]` | tags (categories) | §Video pages (9–13 per page) |
| `div.tagsWrapper a[data-label=tag]` | tags | §Video pages (13–16 per page) |
| `div.pornstarsWrapper a.pstar-list-btn` | actors | §Video pages (absent on no-credit uploads) |
| `script[type=application/ld+json]` `VideoObject` | duration/year (+ name/thumbnail/uploader) | §Video pages (inventory) |
| `JsonLdParse.minutes` / `.year`, `DurationParse.fromSeconds`, `Parse.clockMinutes` | duration parsing | §Video pages + fixtures (`PornHubLoadParseTest`) |
| `/video/get_media` (`remote` leg) | mp4 fallback | §Stream sources (cookie vs cookie-less transcript) |
| `Parse.qualityOf` | `height` → quality | §Stream sources (height convention) + §Risks |
| `Parse.playableHls` + `PornHub.isPlayable` | per-leg 2xx probe | §Stream sources (CDN host lottery) |
| `hasQuickSearch = false` | — | §Quick search (3 endpoints → 404, probed twice) |
| `vpnStatus = VPNStatus.MightBeNeeded` | — | §Risks (listing gate) |

## Verification (S4)

Live checks run 2026-11-21 from the US (Virginia) egress, with the repo's own script and its
documented substitution for the identity plateau:

```
.pi/skills/verify-provider/scripts/verify.sh \
  --search-url 'https://www.pornhub.com/video/search?search=teen' \
  --search-url 'https://www.pornhub.com/video/search?search=teen&page=2' \
  --search-selector 'ul#videoSearchResult li.pcVideoListItem' --search-title-selector 'span.title a' \
  --home-url 'https://www.pornhub.com/video' --home-url 'https://www.pornhub.com/video?page=2' \
  --home-selector 'ul#videoCategory li.pcVideoListItem' \
  --video-url … (the 5 §Video pages, one per listing) \
  --stream-selector 'div#player' --stream-url … (highest HLS per page) \
  --video-title-selector 'h1.title span.inlineFree' \
  --video-poster-selector 'meta[property=og:image]' \
  --video-plot-selector 'meta[property=og:description]' \
  --video-tags-selector 'div.tagsWrapper a[data-label=tag]' \
  --video-actors-selector 'div.pornstarsWrapper a.pstar-list-btn' \
  --provider-src PornHub --load-response 'recommendations,tags,plot,duration,year,actors'
```

What the run proved and what it could not:

- **Streams 5/5 → 206 `application/vnd.apple.mpegurl`** via the position-matched `--stream-url`
  override (flashvars escapes slashes, so the page-level m3u8 regex cannot see the emitted
  links); `div#player` matched 2–3× per page; `tags` and `actors` present on **all 5** pages;
  check 6 reports every requested `LoadResponse` field populated (recommendations/tags/plot/
  duration/year/actors).
- **The `matches:` numbers in the output are global, not container-scoped** (verify.sh's
  `count` checks only that the ancestor exists, then counts the last selector part everywhere):
  `ul#videoCategory li.pcVideoListItem` → 45 on `/video` and 57 on page 2, against 31 / 43
  cards inside the block; `ul#videoSearchResult li.pcVideoListItem` → 38 / 50 against 31 / 43.
  The card extraction itself *is* scoped (`blocks()` walks the ancestor), which is why the
  identity checks below still operate on 31 / 43 cards — recorded so the two numbers are not
  read as selector drift.
- **The identity checks false-FAIL, as the plateau predicts**: check 1/1a report every card as
  an `href=/view_video.php` duplicate, check 3 collides all 5 videos on the same path, and
  check 5 compares the whole search page against each load page — one root cause
  (`url_path` strips `?viewkey=`), not five defects. `--related-selector` was therefore not
  passed (its two checks sit on the same stripped path).
- **Substituted, viewkey-keyed transcript (all PASS)**: search p1 31 / p2 43 cards, all
  distinct within a page, **0 shared** between pages; home rows p1 31 / p2 43 with **0 shared**
  on all four rows; the 5 sampled videos have 5 distinct titles, 5 distinct `og:image` paths
  and 5 distinct viewkeys; each related block's 35 cards are distinct with the page's own
  viewkey absent; search→load agreement holds (card `6ac3a3b42e432`, title `First creampie
  video for 19-year-old Latina`, equals the load `h1`, and its card poster shares the load
  page's asset core `/c6371/videos/202609/10/61819255/original_61819255.mov` — shard `pix-fl`
  vs `pix-egi` and rendition `rs:fit:320:180` vs `640:360` differ by design).
- **Walls** re-probed: premium `64894d8f6af1f` → HTTP 200, no flashvars, `premiumLockedPartners`
  + `premiumIconTitleOnVideo` present; removed `0000000000000` → HTTP 404 `<title>Page Not
  Found</title>`; both still return `null` from `Parse.videoDetails`.
- **Build gates**: `./gradlew test` (all providers) green, then `./gradlew clean` followed by
  `./gradlew test make makePluginsJson` BUILD SUCCESSFUL (PornHub `.cs3` produced), on this
  run's tree.
