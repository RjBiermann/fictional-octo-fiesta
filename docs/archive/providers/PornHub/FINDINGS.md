# FINDINGS — PornHub (pornhub.com)

Probe date: 2026-11-18, from the CI runner (GitHub Actions, `Ubuntu` runner IP — EU/US,
see Risks). Stories S1–S4 of the PornHub spec (#1406) fill this document; S1 (§Engine
fingerprint, §Homepage, §Pagination (home)), S2 (§Search, §Pagination (search), §Quick
search) and S3 (§Video pages, §Stream sources, §Headers) are complete; S3's live probes ran
2026-11-21; S4 (closeout) completes the document.

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
big tits (%20 form)          GET /video/search?search=big tits -> HTTP 200; cards: 31; distinct viewkeys: 31; rel=next: True
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

## Search

The row/listing card Parse function (`Parse.cards` → `parseVideoCards`) is the seam S2
reuses; only the container differs:

- endpoint: `https://www.pornhub.com/video/search?search=<q>` (HTTP 200, ~1.1 MB body);
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

## Video pages

Watch page = `https://www.pornhub.com/view_video.php?viewkey=<vkey>` (HTTP 200, ~1.3 MB).
**Desktop markup only:** a mobile Chrome UA gets a different mobile page with no
`h1.title`/`pornstarsWrapper`; curl's, OkHttp's and the app's default UA all get the desktop
page (`okhttp/4.12.0` re-confirmed). Every selector below is from that desktop page.

| Field | Selector / source | Notes |
|---|---|---|
| title | `h1.title span.inlineFree` | fallback `meta[property=og:title]` (same string) |
| poster | `meta[property=og:image]` | signed, `pix-egi`/`pix-cdn77` alternate; compare the asset path, never the URL |
| plot | `meta[property=og:description]` | the site exposes **no owner-written synopsis**; the generated `"Watch <title> on Pornhub.com, …"` string is the only description and is carried verbatim (never invented) |
| tags | `div.categoriesWrapper a[data-label=category]` + `div.tagsWrapper a[data-label=tag]` | both feed `LoadResponse.tags`; categories first (13/14 cat + 14/16 tag links on the sampled pages) |
| actors | `div.pornstarsWrapper a.pstar-list-btn` | anchor text is the model name (`Indica Marie`); uploads that credit nobody render **no wrapper** → empty actor list (fixture `video-page-2.html`) |
| duration | JSON-LD `VideoObject` `"duration":"PT00H16M50S"` | no DOM attribute carries it; `JsonLdParse.minutes` floors to minutes (repo convention); flashvars `video_duration` (seconds) is the fallback |
| year | JSON-LD `"uploadDate":"2026-06-18T00:26:47+00:00"` | `JsonLdParse.year`; no DOM attribute carries it either |
| related videos | `ul#relatedVideosListing li.pcVideoListItem` | same card shape as the listing; the block also carries ad `<li class='js-nativeTjVideoGrid js_promoItem'>` slots (no `view_video` link) which the card parser drops |

All-or-none exposure on 5 sampled pages (2026-11-21): title/poster/plot 5/5, `tags` 5/5,
`actors` 5/5 (all five sampled uploads credit at least one model; a no-credit upload is the
`video-page-2.html` fixture shape), related block 5/5 (36 cards each).

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

A geo wall (`video_unavailable_country: "true"`) was **not observed** from the runner's EU/US
IP across ~50 sampled pages; the flashvars flags are still honoured by the parser (site
documentation shape), but there is no recorded geo-walled sample — stated, not fabricated.

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
- **Geo**: probed from the CI runner (EU/US GitHub Actions IP); no geo-wall or age gate
  observed on `/video`, listings, sort rows, or the S3 watch pages (see the geo note in Risks).
- Chrome desktop UA used for every request (`Mozilla/5.0 … Chrome/124.0 Safari/537.36`); no
  Referer or cookie was required for any S1/S2 surface.
- **Mobile UA gets a different watch page.** A mobile Chrome UA serves a page without
  `h1.title`/`pornstarsWrapper`; curl, OkHttp and the app's default UA all get the desktop
  markup the S3 selectors target (re-confirmed `okhttp/4.12.0`). Recorded so a future UA change
  is diagnosed from here, not from a silent empty `load()`.
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
- **Geo wall not observed.** ~50 sampled pages from the runner's EU/US IP carried
  `video_unavailable_country: "false"`; the parser still honours the flag, but there is no
  recorded geo-walled sample. Removed (404) and premium (200, no flashvars) walls are
  recorded in §Video pages and both drop the card (`load()` → `null`).
- **`duration`/`year` are script-embedded.** They live in the JSON-LD `VideoObject` (and
  flashvars `video_duration`), not in a DOM attribute — verify.sh's `--video-duration-selector`
  / `--video-year-selector` cannot assert them; they are covered by the Parse fixtures
  (`PT00H16M50S` → 16 min, `uploadDate` → 2026) plus the §Video pages transcript.
- **verify.sh's identity model flattens PornHub's video ids (instrument plateau).** Card and
  video identity lives in the `?viewkey=` **query string**; verify.sh's `url_path` strips the
  query, so every card/video normalises to `/view_video.php`. Check-1/1a duplicate detection,
  check-3 href identity, the related self/duplicate checks and check-5 agreement all
  false-FAIL on that one root cause — they were substituted with the viewkey-keyed equivalents
  (see Verification note (S3)). Same plateau class as PerverZija/Javmost FINDINGS.
