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

**Addendum — S1 of the #1468 blank-thumbnail spec (2026-11-21).** §Homepage now carries the
listing stills' CDN shard / hotlink matrix and a fixture-vintage note; §Risks replaces the old
"signed URLs rotate → re-parse" story with the real failure mode (hotlink-protected shard + a
missing `Referer`). Read that matrix before touching poster emission.

**Addendum — S1 of the #1490 search-pagination spec (2026-11-22).** §Pagination → §Search now
carries the **reporter's own query** (`george+uhl`) transcript and the M1/M2/M3 mechanism
table that resolves #1490: **no provider-side pagination defect exists** — the reported
failure is app-side batching (M2) or a stale installed plugin (M3). §Search records that the
search fixtures are now the reporter's query, with the last-page / no-results fixtures kept
on the broad `teen` probe.

**Addendum — S2 of the #1490 pagination spec (2026-11-22).** §Pagination → §Home now derives
the row stop from the site's own `rel=next` (`Parse.homeHasNext`, replacing the hardcoded
`hasNext = true`) and carries today's four-row page-1/page-2 transcript plus the 404
past-the-end shape. The home fixtures gained the responses' head lines and a new
`home-past-end.html`; read §Pagination (home) before touching the row stop rule.

**Repair round on #1503 (2026-11-22).** Two review findings from the PR's first round: (i) the
codacy reviewer's "an empty body keeps pagination active indefinitely" — answered by making the
empty-body fallback **page-scoped** (`Parse.homeHasNext(html, page)` keeps the row open on page 1
only, §Pagination (home)); (ii) the SonarCloud new-code gate (maintainability B) — `kotlin:S1125`
(the `if (…) true else …` fallback lost its redundant Boolean literal) and `kotlin:S6518`, whose
analyzer reads NiceHttp's single-argument `app.get(...)` as a collection accessor (86 identical
false positives on `main`, no classpath in this analysis). The second is
a **false positive on the HTTP verb**, suppressed at `getMainPage` with the count recorded; the
root cause is a Sonar project-level exclusion, filed as a triage issue from this round. The
reviewer's "`getMainPage` wiring has no direct test" suggestion stays declined per ADR-0005:
`MainAPI`/HTTP flows are covered by pipeline Verification (the four-row transcript below), never
by HTTP mocking in unit tests.

**Addendum — S3 of the #1490 pagination spec (2026-11-22).** Durable verification + release
record: the merged tree carries **`version = 10 // #1508: version bump (v10)`** — the
version-bump lane's own comment, not a `#1490` one. This story adds **no marker**: by the time
the branch merged, two fleet bumps had already moved the number past #1490's S3 target (v7)
and past the branch's own v9 push. §Verification note (S3 of #1490) carries the full live run
(reporter query + broad query, four rows × two pages, five videos, streams, build gates). The
mechanism table's verdict is unchanged — **no provider-side pagination defect (M2/M3)** — and
the version chain, renumbered from #1490's S3 story, is: #1491 5 → 6, S1 (#1498) 6 → 7, S2
(#1503) 7 → 8, #1444's fleet bump (#1505) 8 → 9, #1508's bump 9 → 10. The pagination fix
itself shipped in S1/S2 (v7/v8), so every published plugin from v7 on already carries it; the
`#1490` traceability lives in this section and in §Selector traceability, not in a
`build.gradle.kts` comment the lane rewrites on each push.

**Addendum — S1 of the #1524 pagination re-probe spec (2026-11-23).** §Pagination → §Search now
carries a re-probe at **v12** and the resolved M1/M1′/M2/M3/M4 table: the pipeline egress was
**SFW/geo-gated** for this run (`isSfw:"1"`, `/video` → `302 → /` with `ph-redirect: 1041`), and
under that gate the search page 1 carries cards but **no pagination mark-up of any kind** — the
**M1′** shape, live — while page 2 answers `404 div.noResultsWrapper`. Verdict: **M1′ (degraded
page 1 on a gated egress), no provider defect**; the missing mark-up and the last-page
discriminator are handed to S2 as the guard-2 fixture. Read §Pagination (search) before changing
the search stop rule.

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
  `pix*.phncdn.com/…/rs:fit:320:180` + a signature — `?hdnea=st=…~exp=…~hmac=…` on `pix-fl`,
  `?hash=…&validto=…` on `pix-cdn77`; legacy `ei` stills are unsigned; see the shard matrix
  below); `data-mediumthumb` holds the
  identical URL. `data-image` / `data-src` / `data-original` appear on the *dropdown* card
  shape and are absent here, so the parser probes that chain first and falls through to `src`.
  `data-pix=""` and `data-mediabook` (a 180P **webm preview**, not a still) are never posters.
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

### Listing — CDN shard & hotlink matrix (S1 of #1468, 2026-11-21)

Probe date **2026-11-21 20:44–21:06 UTC**, CI runner egress `20.171.39.91` (`ipinfo.io` →
`"city": "Phoenix", "region": "Arizona", "country": "US"`, AS8075 Microsoft) — record the
egress with every listing claim, as §Risks asks. This is the evidence the #1468
blank-thumbnail report stands on; S1 changed **no** provider logic (S2, #1472, ships the
header fix).

**The still shard is assigned per fetch.** Desktop UA, stills inside `ul#videoCategory` that
carry `rs:fit:320:180`:

```
fetch 1  GET /video -> HTTP 200 1206819B  cards 31  stills 62  pix-fl 62
fetch 2  GET /video -> HTTP 200 1206504B  cards 31  stills 62  pix-fl 62
fetch 3  GET /video -> HTTP 200 1204367B  cards 31  stills 62  pix-cdn77 62
```

So in this `/video` sample one fetch is entirely `pix-fl` and the next entirely `pix-cdn77`
(the pinned search pair is *mixed* — 24 `pix-cdn77` + 7 legacy `ei` on page 1, 36 `pix-fl` +
7 `ei` on page 2; see §Fixture vintage), so the signed CDN family can change between fetches,
and the **signature shape changes with it** — `?hdnea=st=…~exp=…~hmac=…` on
`pix-fl`, `?hash=…&validto=…` on `pix-cdn77`. Both must be emitted verbatim.

**The reporter's repro** (`GET /video/search?search=george+uhl` → HTTP 200, 1,121,476 B):

```
result block ul#videoSearchResult: cards 38, listing stills 38 — pix-cdn77 38 (100%)
signature shape: ?hash=…&validto=… (47 still URLs on the page, all that shape)
```

**Hotlink matrix.** One `pix-cdn77`, one `pix-fl`, and one legacy `ei` still (the third family
mixed into the pinned search fixtures), `curl` with no cookie jar (the `ei` URL is unsigned and
was fetched with the desktop UA):

| still | Referer | result |
|---|---|---|
| `pix-cdn77` + `?hash=…&validto=…` | none | **403** `text/html` 868 B |
| `pix-cdn77` + signature | `https://www.pornhub.com/` | **200** `image/jpeg` 18,369 B |
| `pix-cdn77` + signature | `https://www.pornhub.com/video` | **200** `image/jpeg` 18,369 B |
| `pix-cdn77` + signature | `https://evil.example/` | **200** `image/jpeg` 18,369 B |
| `pix-cdn77` + signature | none, `okhttp/4.12.0` UA | **403** `text/html` 868 B |
| `pix-cdn77` + signature | `https://www.pornhub.com/`, `okhttp/4.12.0` | **200** `image/jpeg` 18,369 B |
| `pix-cdn77`, query stripped | none | **403** `text/html` 868 B |
| `pix-cdn77`, query stripped | `https://www.pornhub.com/` | **403** `text/html` 868 B |
| `pix-fl` + `?hdnea=…` | none | **200** `image/jpeg` 11,462 B |
| `pix-fl` + signature | `https://www.pornhub.com/` | **200** `image/jpeg` 11,462 B |
| `pix-fl` + signature | `https://evil.example/` | **200** `image/jpeg` 11,462 B |
| `pix-fl`, query stripped | none | **470** `text/html` 422 B |
| `pix-fl`, query stripped | `https://www.pornhub.com/` | **470** `text/html` 421 B |
| `ei` (legacy, unsigned) | none | **200** `image/jpeg` 19,153 B |
| `ei` | `https://www.pornhub.com/` | **200** `image/jpeg` 19,153 B |
| `ei` | `https://evil.example/` | **200** `image/jpeg` 19,153 B |

Two rules, both load-bearing:

1. **`Referer` is a presence check, not a value check.** `pix-cdn77` answers 200 to any Referer
   (a non-PornHub origin included) and 403 with none; `pix-fl` and unsigned `ei` answer 200
   either way.
2. **The signature is required.** Stripping `?hash=…` / `?hdnea=…` turns a 200 into 403
   (`pix-cdn77`) or 470 (`pix-fl`) *even with* a valid Referer — the "emit the unsigned still"
   idea is refuted; emit the parsed URL verbatim.

The app's image loader sends no `Referer`, which is why a `pix-cdn77` fetch is blank in the app
while a `pix-fl` fetch is not. The shipped fix (S2, #1472) is one header at the poster emission
sites — not a selector change, not a URL rewrite, not a proxy.

**Watch-page `og:image`** (`GET /view_video.php?viewkey=6a333a768c26c` ×3):

```
attempt 1: https://pix-egi.phncdn.com  signature ?validfrom=…&validto=…&hash=…
attempt 2: https://pix-egi.phncdn.com  signature ?validfrom=…&validto=…&hash=…
attempt 3: https://pix-egi.phncdn.com  signature ?validfrom=…&validto=…&hash=…
```

`pix-egi` answers 200 without a Referer, so the load poster is not hotlink-protected; its shard
did **not** rotate to `pix-cdn77` in this window. 14 consecutive re-fetches of the fixture video
(25 s apart, 21:03–21:10 UTC) plus a 140-video sample across `/video`, `/video?o=mv|tr|cm`, `/video/search` and
`/transgender` (2025+ assets) returned **119 `pix-egi` / 21 legacy `ei` / 0 `pix-cdn77`**. So the
load-poster shard split recorded previously (§Video pages) is corrected: `og:image` is
`pix-egi` for 2025+ assets and unsigned `ei` for older ones on this egress. `video-page-1.html`
pins the real `pix-egi` `og:image`; the hotlink-protected `pix-cdn77` shape is pinned by the
**listing** fixtures (`home-page-1`, `home-page-2`, `search-page-1`), which is where the
reported blanks come from. The plan's "re-fetch until `og:image` is `pix-cdn77`" clause could
not be satisfied by the live site and is recorded here rather than faked.

### Fixture vintage (why S1–S4 and the committed fixtures never saw this)

The old fixtures happened to pin only the referer-free families: `home-page-1`,
`search-page-1` and `search-page-2` were `pix-fl`, `video-page-1`/`video-page-2` were
`pix-egi`, and only `home-page-2` carried `pix-cdn77` — with no test asserting a shard or an
HTTP header. Parse-only tests read HTML; a missing `Referer` is invisible to them, so the suite
stayed green on exactly the shape that fails in the app.

Refreshed for S1 (2026-11-21, same cut, query signatures verbatim):

- `home-page-1.html`, `home-page-2.html` — the verbatim `ul#videoCategory` block of `/video`
  page 1 and page 2 from a **`pix-cdn77`** fetch pair (0 shared viewkeys); S2 prepended the
  same responses' `<link rel="canonical"/"next">` (page 2 `"prev"` too) head lines
  (§Pagination (home) fixture refresh).
- `search-page-1.html` — the verbatim canonical/next head lines + `ul#videoSearchResult` block,
  **24 `pix-cdn77` + 7 legacy `ei` stills** (mixed family, as the live page served it);
  `search-page-2.html` refreshed as the same-session page-2 pair, **36 `pix-fl` + 7 legacy
  `ei`**, so the referer-free shapes stay covered and "page 2 does not repeat page 1" still
  holds. Only the `/video` pair is 100 % one signed family;
- `video-page-1.html` — the same `viewkey=6a333a768c26c` watch page as before, re-fetched
  (`pix-egi` `og:image`, 3 HLS legs + the remote `/video/get_media` leg).

The one brittle assertion (`PornHubLoadParseTest.kt:42`, `startsWith("https://pix-egi.phncdn.com/")`)
is now a host-shape regex (`^https://pix-[a-z0-9]+\.phncdn\.com/`), so a refreshed shard cannot
fail the build again.

### Verification note (S1)

verify.sh hard-requires `--search-url` and `--video-url` (surfaces S2/S3 ship), so S1's home-only
story was verified with the same mechanics it applies to a listing page: fetch each row's page 1
and page 2, count `ul#videoCategory li.pcVideoListItem` cards, require ≥1, and require page 2 to
share no viewkey with page 1 (transcript above). The fixtures under
`PornHub/src/test/resources/` are the **verbatim `<ul id="videoCategory">` block** of
`/video` page 1 and page 2 (S2 later prepended the same responses' head lines, §Pagination
(home)), the only markup the card Parse seam reads.

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
  with page-1 duplicates.) The 404 body also carries no `<link rel="next">` and no
  `page_next` pagination control at all — only header-dropdown cards, which the scoped
  listing selector drops — and that shape is now pinned by `home-past-end.html`.

- **Stop signal: derived, never a constant (S2 of #1490).** Every live row page publishes the
  same head signal as search — `<link rel="next" href="…page=N+1">` (page 2 also carries
  `<link rel="prev">`) — with `div.pagination3 li.page_next a[href]` as the backstop, and the
  past-the-end 404 body has neither. `getMainPage` therefore passes
  `hasNext = Parse.homeHasNext(html, page)`, i.e. the same `Parse.hasNextPage` rule search uses,
  for every real body. The old `hasNext = true` constant survives **only** as the fallback for an
  empty/whitespace body **on page 1**, where the derived rule has no mark-up to read and `false`
  would end the row on a transport hiccup. Past page 1 the same blank body means "nothing left to
  show" and the row stops — the review round on #1503's codacy finding (a site answering blank
  bodies would otherwise leave the row with an endless "next"; no live body is blank, so this is
  the hypothetical the fallback is bounded against, not a measured shape). A real body carrying
  neither signal — the past-the-end 404 or a gated/degraded listing — stops the row
  (`hasNext = false`) on any page; no code path re-introduces an unconditional `true`.

S2 re-probe (2026-11-22, egress `20.169.65.236` Phoenix/Arizona, AS8075 Microsoft — ungated,
`/video` answers 200 with the listing, so §Risks' gate caveat does not apply to these lines):

```
== home rows: page 1 + page 2 (viewkey-keyed, scoped to ul#videoCategory) ==
https://www.pornhub.com/video                  p1 HTTP 200 cards= 63 distinct= 32 rel=next=True
                                               p2 HTTP 200 cards= 87 distinct= 44 rel=next=True  shared=0
https://www.pornhub.com/video?o=mv             p1 HTTP 200 cards= 63 distinct= 32 rel=next=True
                                               p2 HTTP 200 cards= 87 distinct= 44 rel=next=True  shared=0
https://www.pornhub.com/video?o=tr             p1 HTTP 200 cards= 63 distinct= 32 rel=next=True
                                               p2 HTTP 200 cards= 87 distinct= 44 rel=next=True  shared=0
https://www.pornhub.com/video?o=cm             p1 HTTP 200 cards= 63 distinct= 32 rel=next=True
                                               p2 HTTP 200 cards= 87 distinct= 44 rel=next=True  shared=0

== past the end: /video?page=9999 ==
GET /video?page=9999 -> HTTP 404; ul#videoCategory: no; cards in block=0; rel=next=no; enabled page_next=no
RESULT: PASS
```

`cards` counts anchor occurrences, `distinct` counts viewkeys: each card links the same
viewkey twice (thumbnail anchor + title anchor), 63/87 anchors → 32/44 viewkeys — the
identity key S4's transcript uses. `verify.sh` check 1a fetches the same eight URLs (all →
200; `ul#videoCategory li.pcVideoListItem` 45 / 57 global matches, the documented
not-container-scoped number) but its duplicate check false-FAILs site-wide because `url_path`
strips `?viewkey=` (§Verification (S4)), so this viewkey-keyed transcript is the authoritative
"0 shared viewkeys on each pair" evidence. The four rows are distinct listings, not one page
served four times (`?o=mv` p1 shares 5 viewkeys with `/video` p1, `?o=tr`/`?o=cm` share 0).

S3 of #1490 re-ran the same eight URLs on 2026-11-22 (egress `20.171.39.149`, Phoenix/Arizona,
AS8075 Microsoft, ungated): all eight answered 200 with `rel=next` present, 32 / 44 distinct
viewkeys per row and **0 shared** on every page-1/page-2 pair — the derived stop rule reads a
live signal on every row, not a page-specific artifact (transcript §Verification note (S3 of
#1490)).

### Fixture refresh — home rows (S2 of #1490, 2026-11-22)

`home-page-1.html` / `home-page-2.html` used to be the listing block alone (no head), which is
why the derived stop had no live shape to assert against. They now carry the **verbatim**
`<link rel="canonical">` / `<link rel="next">` (page 2 also `<link rel="prev">`) head lines
of the same responses, prepended to the same verbatim `ul#videoCategory` block — the shape
`search-page-*.html` already had. `home-past-end.html` is new: the verbatim
`<title>Page Not Found</title>` + error block of the real `/video?page=9999` 404 (no listing
block, no `rel=next`, no `page_next`) — the body that makes the derived stop safe.
`PornHubParseTest` asserts `Parse.homeHasNext` true on both live pages, false on the
past-the-end body and on a degraded body (listing block present, head link and enabled Next
anchor both gone), and true only for an empty/whitespace **page-1** body (the documented
constant fallback, page-scoped: an empty body past page 1 stops the row — FINDINGS §Pagination
(home)).

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

### Search — reporter pagination (#1490 / #1494, 2026-11-22)

Reported by the owner on #1490: *"Search pagination not working in the app. I think it is
showing results from page 1 only."* Probed with the reporter's own query on **2026-11-22
04:54–04:59 UTC from the CI runner egress — region not recorded for this run** (the site
answered `200` with the full result block, not the geo/SFW `302 → /` of §Risks, so none of
that section's gates fired here; a run from the reporter's own egress is still the only way
to rule M1 out for *their* context — see the table below).

Card identity here is the **`?viewkey=` query**, exactly as in §Verification (S4): verify.sh's
`url_path` strips the query, so every card normalizes to `/view_video.php` and its duplicate
check false-FAILs on this site. Same mechanics as verify.sh's check 1, keyed by viewkey:

```
GET https://www.pornhub.com/video/search?search=george+uhl
  cards=31 distinct=31 rel=next=yes rel=prev=no
GET https://www.pornhub.com/video/search?search=george+uhl&page=2
  cards=43 distinct=43 rel=next=yes rel=prev=yes
  shared viewkeys with page 1: 0
GET https://www.pornhub.com/video/search?search=milf
  cards=31 distinct=31 rel=next=yes rel=prev=no
GET https://www.pornhub.com/video/search?search=milf&page=2
  cards=42 distinct=42 rel=next=yes rel=prev=yes
  shared viewkeys with page 1: 0
```

Both pages of both queries carry the site's own `<link rel="next">` (page 2 also carries
`<link rel="prev">`), which is the only signal `Parse.hasNextPage` reads first — so the seam
returns `hasNext = true` on page 1 **and** page 2 of the reporter's query. Encoded with
`Parse.searchUrl`, the page-2 request is byte-identical to the URL that answers `200` with
the 43-card body below — no redirect:

```
$ curl -s -A "$UA" -o /dev/null -w '%{http_code} %{redirect_url}\n' 'https://www.pornhub.com/video/search?search=george%20uhl'
301 https://www.pornhub.com/video/search?search=george+uhl
$ curl -s -A "$UA" -o /dev/null -w '%{http_code}\n' 'https://www.pornhub.com/video/search?search=george+uhl&page=2'
200
```

`./gradlew PornHub:test` asserts exactly this offline (`PornHubSearchParseTest`):
`Parse.searchUrl("george uhl", 2) == https://www.pornhub.com/video/search?search=george+uhl&page=2`,
`hasNext` true on both fixtures, page 2 ≥20 cards with **0** shared urls, and per-card
duration/views on both pages.

The repo's own script, run on the same tree (4 search pages = reporter query + broad query,
each 2 deep; homepage pages 1–2; 2 sampled watch pages with their resolved HLS legs):

```
── check 1: search pages (4)
GET https://www.pornhub.com/video/search?search=george+uhl         → 200; 'ul#videoSearchResult li.pcVideoListItem' matches: 38
GET https://www.pornhub.com/video/search?search=george+uhl&page=2  → 200; matches: 50
GET https://www.pornhub.com/video/search?search=milf               → 200; matches: 38
GET https://www.pornhub.com/video/search?search=milf&page=2        → 200; matches: 50
FAIL duplicate search cards … href /view_video.php …   ← the documented viewkey plateau, not drift
── check 1a: homepage pages (2)
GET https://www.pornhub.com/video          → 200; 'ul#videoCategory li.pcVideoListItem' matches: 45
GET https://www.pornhub.com/video?page=2   → 200; matches: 57
FAIL duplicate home cards …                            ← same plateau
── check 2: video pages (2 URLs)
GET …viewkey=6a50b7fdc5c26 → 200; 'div#player' matches: 3
GET stream → 206 application/vnd.apple.mpegurl
GET …viewkey=6af346a688993 → 200; 'div#player' matches: 2
GET stream → 206 application/vnd.apple.mpegurl
field 'tags': present on all 2 sampled pages
field 'actors': present on all 2 sampled pages
── check 5: search ↔ load agreement
FAIL poster/title mismatch for /view_video.php …       ← same plateau (all rows collapse to one path)
── check 6: LoadResponse completeness
field 'recommendations'/'tags'/'plot'/'duration'/'year'/'actors': populated in PornHub
RESULT: FAIL
```

Every `FAIL` above is the stripped-query identity plateau recorded in §Verification (S4) — one
root cause, not five defects — and the `matches:` counts are global (38 = 31 result cards + 7
header-dropdown cards; 50 = 43 + 7), as that section already documents. The stream, exposure
and `LoadResponse` checks pass unchanged.

### Mechanism table (resolving #1490)

| # | Mechanism | Discriminating test | Provider-fixable? | Verdict from this run |
|---|---|---|---|---|
| M1 | Provider returns `hasNext=false` in the reporter's context, or page 2 parses empty there (geo/SFW gate `302 → /`, `ph-redirect: 1041`; ad-slot ratio) | one page-1 + page-2 fetch from the reporter's egress, or the app's request log; replay a gated body through the seam | yes — *if* a gated/unscoped body can silence the signal | **Not reproduced here.** On this egress the site paginates the reporter's query (31 → 43 cards, 0 shared, `rel=next` on both) and the seam derives `hasNext` from that same link, so there is nothing to fix at the Parse seam. A gated body would drop `rel=next` **and** the result block together, which makes `hasNext=false` the honest answer (the app can then show nothing rather than stale page 1) — evidence-based, not a constant. |
| M2 | App bundles providers: `SearchViewModel.bundleSearch` sets the combined list's `hasNext=false` when >1 provider yields results; paging happens per provider via the expand control (`expandAndReturn` → `repo.search(query, nextPage)`) | does the same query paginate with only PornHub enabled? | no — app-side | **The live cause consistent with every observation**: the provider emits `hasNext=true` and fresh page-2 cards, yet the visible list stops at page 1 while other providers are enabled. User action: enable only PornHub, then expand the PornHub section before scrolling (the app only fetches page N per provider through that expand control). |
| M3 | Installed plugin predates v5 (search `hasNext` was a constant) or came from another repo | plugin version in the app vs this repo's `plugins.json` | no — update the plugin | **Possible, and user-checkable**: this repo publishes the current PornHub plugin — the `builds` branch `plugins.json` tracks `PornHub/build.gradle.kts` (**v10**: #1491 → v6, S1 → v7, S2 → v8, #1444's fleet bump → v9, #1508's bump → v10). An install still on the pre-v5 constant-`hasNext` plugin shows page 1 only. User action: update the plugin (or reinstall from this repo's `repo.json`). |

**Conclusion (no provider defect).** The search seam paginates the reporter's query exactly as
the site does; the reported "page 1 only" behaviour is **M2 (app-side batching — the combined
search list's `hasNext` is the app's, not the provider's)** or **M3 (a stale installed plugin)**.
Neither is fixable inside `PornHub/`, and no selector or stop-rule change was made — swapping a
selector here would have been a point-in-time guess at a seam that already carries the site's
own evidence. The in-app action is: enable only PornHub and expand the provider's section
before scrolling (M2), and update the plugin to the current release, v10 (M3) — pre-v5
builds searched a constant `hasNext`. S3 re-ran the same probe on 2026-11-22 and reproduced
this verdict unchanged (§Verification note (S3 of #1490)).

> **Partly superseded by the v12 re-probe below (S1 of #1524, 2026-11-23).** On an **SFW-gated**
> egress the same seam sees a page-1 body with cards and **no pagination mark-up at all**, and
> page 2 answers `404 div.noResultsWrapper` — the **M1′** shape, now measured rather than
> hypothetical. The M2/M3 reading above stands for an **ungated** egress (the reporter's
> "homepage works" says theirs is), and M3 is further narrowed there: the reporter's v11 already
> carries the derived stop (shipped in v6/v7), and this repo publishes v12.

### Search pagination re-probe at v12 — M1′ is live on a gated egress (S1 of #1524, 2026-11-23)

Release under test: **v12** — `PornHub/build.gradle.kts` `version = 12 // #1516: version bump
(v12)`, and the published `builds`-branch `plugins.json` reports `PornHub 12`. Egress for every
line below: **`134.33.76.213`, Phoenix/Arizona, `US`, AS8075 Microsoft** (`ipinfo.io`), probe
window **2026-11-23 13:54–14:08 UTC**, cookie-less desktop Firefox UA (`verify.sh`'s own) unless
a line says otherwise; the Chrome-124 and `okhttp/4.12.0` UAs were re-probed and answer the same
shape, so nothing here is UA-dependent.

**This egress is SFW/geo-gated, and the gate reaches search too.** The row URLs are gated exactly
as §Risks records, the root is the SFW single feed, and the *search* bodies carry `"isSfw":"1"`:

```
GET /video/search?search=milf          Cookie: age_verified=1; platform=pc
    → 200 968,694B  isSfw=1  rel_next=no  pagination3=no  cards=31
GET /video/search?search=milf&isSfw=0  → 301 → /video/search?search=milf  (ph-redirect: 1041)
```

Neither the age cookie nor a `isSfw=0` parameter unlocks it (same conclusion as §Risks' "the gate
is decided server-side per egress IP", re-confirmed here for the search surface).

**Page-1 shape — cards present, every pagination signal absent** (one `14:04:21 UTC` snapshot;
identical across 6/6 repeat fetches, 2 s apart):

```
/                          HTTP 200  706,528B  block=no  cards= 0 vkeys= 0 dur= 0 views= 0 resultsCount=     - rel_next=no  pagination3=no  page_next=no  page2hrefs= 0 isSfw=1
/video                     HTTP 302        0B  ph-redirect=1041 ->/
/video?page=2              HTTP 302        0B  ph-redirect=1041 ->/
search=george+uhl page 1   HTTP 200  969,374B  block=yes cards=31 vkeys=32 dur=31 views=31 resultsCount=    32 rel_next=no  pagination3=no  page_next=no  page2hrefs= 0 isSfw=1
search=george+uhl page 2   HTTP 404  600,348B  block=no  cards= 0 vkeys= 0 dur= 0 views= 0 resultsCount=     - rel_next=no  pagination3=no  page_next=no  isSfw=1 div.noResultsWrapper
search=george+uhl page 3   HTTP 404  599,454B  block=no  cards= 0 vkeys= 0 dur= 0 views= 0 resultsCount=     - rel_next=no  pagination3=no  page_next=no  isSfw=1 div.noResultsWrapper
search=milf page 1         HTTP 200  966,096B  block=yes cards=31 vkeys=32 dur=31 views=31 resultsCount=    32 rel_next=no  pagination3=no  page_next=no  page2hrefs= 0 isSfw=1
search=milf page 2         HTTP 404  598,488B  block=no  cards= 0 vkeys= 0 dur= 0 views= 0 resultsCount=     - rel_next=no  pagination3=no  page_next=no  isSfw=1 div.noResultsWrapper
search=teen page 1         HTTP 200  968,487B  block=yes cards=31 vkeys=32 dur=31 views=31 resultsCount=    32 rel_next=no  pagination3=no  page_next=no  page2hrefs= 0 isSfw=1
search=teen page 100       HTTP 404  600,266B  block=no  cards= 0 vkeys= 0 dur= 0 views= 0 resultsCount=     - rel_next=no  pagination3=no  page_next=no  isSfw=1 div.noResultsWrapper
```

`block` = `ul#videoSearchResult` present; `cards` = `li.pcVideoListItem` **inside** it; `vkeys` =
distinct `?viewkey=` in it; `dur`/`views` = cards carrying `var.duration` / `span.views`;
`page2hrefs` = whole-body occurrences of `page=2`. So the card seam is intact — 31 parseable
cards with title, poster, duration and views — and only the pagination mark-up is gone:

```
<li class="pcVideoListItem js-pop videoblock videoBox videoBoxesSearch" data-video-vkey="6a0ca93102062" …>
  <a href="/view_video.php?viewkey=6a0ca93102062" title="How Lauren Phillips Turned ExtremeScenes Into a Brand" …>
  <var class="bgShadeEffect duration tooltipTrig" data-title="Video Duration">54:46</var>
  <span class="views"><i class="ph-icon-view-on tooltipTrig" …></i><var>113K</var></span>
```

What is missing on page 1, precisely — **both signals, and the last-page control as well**:

- **`link rel="next"`: 0.** (Also 0 `rel="prev"` — page 1 of an ungated body carries `next`,
  §Pagination (search).)
- **`div.pagination3` / `li.page_next a[href]`: 0.** This is not the reachable-last-page shape —
  that body carries the pagination block with `li.page_next` **`disabled`** (`search-last-page.html`,
  §Pagination (search)). Here the whole block is **absent**: 0 occurrences of `pagination3`,
  0 `page_next`, and **0 `?page=2` hrefs in the entire 969 KB body**.
- **Page 2 is `HTTP 404` + `div.noResultsWrapper` ("No Search Results.")** — not a page-2 result
  body, not a `302 → /`. So on this egress there is no page 2 to request; consecutive-page
  viewkey overlap is therefore **not computable** (page 2 carries 0 result cards), which is a
  Blocked-by-gate fact, not a pass of the distinct bar. Page 3 was fetched only to close that
  loop (the story asks for it when page 2 is non-empty) and is the same 404.
- **`resultsCount` is not usable as a page-count on this body — record the drift and do not lean
  on it.** The same `milf` URL reported `"resultsCount":875019` at 13:56–14:00 UTC and `32` at
  14:01–14:08 UTC in this one run (`teen`: 321297 → 32), same `block=yes cards=31` shape. Read it
  as an unstable estimate; the *pagination decision* must come from the mark-up, which is exactly
  what `Parse.hasNextPage` does.
- The three queries return **different card sets** (viewkey overlap `george+uhl`↔`milf` 3,
  `george+uhl`↔`teen` 8, `milf`↔`teen` 1), so the gate is filtering a real result set down to one
  page rather than serving one canned list.

`Parse.hasNextPage` therefore returns **`false`** on each page-1 body (no `link[rel=next]`, no
enabled `li.page_next a[href]`), and `hasNext = false` is exactly the reported "search shows page
1 only".

**A genuine last page still carries its pagination control; this body carries none.** That is the
only discriminator the mark-up offers, and it is testable purely offline — the two repo fixtures
already pin both ends of it: `search-last-page.html` has `pagination3` ×2 with `<li class="page_next
 disabled">`, `search-page-1.html` has `link rel="next"`. The gated body has **neither**.

**The gate also thins the watch page** — supporting evidence that these are SFW-variant bodies
and not selector drift (`viewkey=6b006f06066c7`, same egress):

```
GET /view_video.php?viewkey=6b006f06066c7  → 200 714,556B  isSfw=1
  <meta property="og:*"> 0   application/ld+json 0   div.categoriesWrapper 0   div.tagsWrapper 0
  div.pornstarsWrapper 1     h1 …span.inlineFree 1    var flashvars_ 1 (5 mediaDefinitions)
```

So under the gate the `og:*` metas and the JSON-LD `VideoObject` are gone (title falls back to the
DOM `inlineFree` span, duration/year/plot have no source), while streams and actors still resolve
— which is why verify.sh's poster/plot lines read `NOTE: … site does not expose it` on this run.
Playback is unaffected: `6afbe046a6991` came back style B (all four HLS legs `410`), the second
`6b006f06066c7` style A (all four legs `206 application/vnd.apple.mpegurl`) — §Stream sources'
CDN host lottery, unchanged by the gate.

**`verify.sh`** (the S4 command shape: four search URLs, both row URLs, one watch page with its
live HLS leg as `--stream-url`, `--provider-src PornHub
--load-response recommendations,tags,plot,duration,year,actors`):

```
── check 1: search pages (4)
GET …search=george+uhl        → 200; 'ul#videoSearchResult li.pcVideoListItem' matches: 31
GET …search=george+uhl&page=2 → 404; matches: 0        FAIL search (the gate's page-2 404)
GET …search=milf              → 200; matches: 31
GET …search=milf&page=2       → 404; matches: 0        FAIL search (same)
FAIL duplicate search cards … href /view_video.php …   ← the documented url_path plateau, not drift
NOTE: repeated poster on search across different videos …   ← series posters, same note class
── check 1a: homepage pages (2)
GET /video        → 200 (curl -L followed the gate's 302 → /); matches: 0   FAIL home (§Risks gate)
GET /video?page=2 → 200 (same 302); matches: 0                            FAIL home (same)
── check 1b: quick search (0)  → NOTE, hasQuickSearch=false (§Quick search)
── check 2: video pages (1)
GET …viewkey=6b006f06066c7 → 200; 'div#player' matches: 1
GET stream (…/1080P_4000K_69935395.mp4/master.m3u8…) → 206 application/vnd.apple.mpegurl
NOTE: no poster / no plot on the sampled page — the gate's body (above), not a selector
NOTE: --video-tags-selector matches nothing — same gated body (div.tagsWrapper absent)
field 'actors': present on all 1 sampled pages
── check 5: search ↔ load agreement
FAIL title mismatch for /view_video.php …              ← same plateau (every card collapses to one path)
── check 6: LoadResponse completeness
field 'recommendations'/'tags'/'plot'/'duration'/'year'/'actors': populated in PornHub
RESULT: FAIL
```

Every `FAIL` is one of the two documented causes, neither a provider defect: the
`url_path`-strips-`?viewkey=` instrument plateau (§Verification (S4)) or the §Risks gate
suppressing page 2 / the row URLs on this egress. The substituted viewkey-keyed facts are the
table above — 31 cards / 32 distinct viewkeys per page 1 (card identity intact), zero pagination
mark-up, page 2 and 3 a `404 noResultsWrapper`.

**Mechanism table, resolved (work item 3)**

| # | Mechanism | Verdict from this run (v12, egress above) | Evidence pointer |
|---|---|---|---|
| M1 | Gated/degraded body delivered as a gate redirect on the **search URL** (`302 → /`, `ph-redirect: 1041`), or page 2 parsing empty | **Not the search shape.** The search URL answers **200**, not a gate redirect — the gate is visible only on the **row** URLs (`/video`, `/video?page=2` → `302 → /`, `ph-redirect: 1041`). Page 2 is not an "empty parse" either: it is a **404 `div.noResultsWrapper`** body. M1's *condition* (an `isSfw:"1"` egress) is what produces M1′ below. | transcript above; `/video` 302 rows |
| M1′ | **Degraded page 1: cards present, pagination mark-up absent** | **PRIMARY — reproduced live (6/6 fetches).** Page 1 carries the scoped result block with 31 cards / 32 viewkeys and full card metadata, while `link rel=next`, `div.pagination3`, `li.page_next` and any `?page=2` href are **all zero** — neither signal, and not even the disabled last-page control. `Parse.hasNextPage` returns `false`; page 2 is a `404 noResultsWrapper`. | page-1 table + "missing on page 1" + last-page discriminator |
| M2 | App bundles providers; the combined list's `hasNext` is the app's and paging is per provider via the expand control | **Not observable server-side, and not needed to explain this run** — page 2 does not exist on this egress, so no client can page it. Still the live secondary for a user on an **ungated** egress (the reporter's "homepage works" says theirs is one). | app-side; discriminator below |
| M3 | Installed plugin predates the derived stop (< v5), or came from a foreign repo | **Excluded for this repo's builds.** The reporter is on v11 and the derived `hasNextPage` stop shipped in v6/v7 (S1/S2 of #1490); this repo now publishes **v12** (`builds` `plugins.json` → `PornHub 12`, matching `build.gradle.kts`). Only a foreign-repo build could still carry a constant. | §Verification note (S3 of #1490) version chain; `plugins.json` |
| M4 | Search section not expanded in a bundled result list | **Not observable server-side; secondary.** Same standing as M2: it can only bite on an ungated egress where the provider does emit `hasNext = true`. | app-side |

**Verdict: M1′ — a degraded page 1 on an SFW/geo-gated egress; no provider defect.**

- Which mark-up was missing on page 1 (the fact work item 2 asks for): **neither** pagination
  signal — no `link rel="next"` **and** no `div.pagination3 li.page_next a[href]` (the block is
  absent, not `disabled`), with the scoped `ul#videoSearchResult` still holding 31 parseable
  cards. The M1′ shape therefore **has live support**: S2's guard 2 is guarding a measured body.
- Handed to S2 as the fixture to build: a page-1 body whose result block is present (cards,
  titles, posters, durations, views) with **zero** pagination mark-up — the discriminator against
  a genuine last page being the pagination block's presence there (`pagination3` + `li.page_next
  disabled`) versus its total absence here. The guard must be **bounded**: on this live M1′ body
  page 2 is a `404 noResultsWrapper`, so "cards but no mark-up ⇒ keep paging open" has to
  terminate on that empty/404 page-2 body instead of opening an endless next — the same
  page-scoped shape `Parse.homeHasNext` already uses for the blank-body fallback.
- User action, stated plainly because the symptom is real: on a gated egress the site serves **no
  page 2 at all** (`404` "No Search Results."), so **no provider change can restore pagination** —
  use an ungated egress (VPN/region) with the current release (v12). If the list stops at one page
  on an **ungated** egress, the cause is app-side: enable only PornHub and expand the PornHub
  section before scrolling (M2/M4).

**Reporter discriminator (#1524 work item 4) — unanswered, not blocking.** This run cannot answer
it: there is no in-app observation here, and on this egress page 2 would `404` even with only
PornHub enabled, so the discriminator is untestable from the pipeline egress. The maintainer's
answer on #1524 decides the branch: *page 2 loads with only PornHub enabled* → M2/M4 (app-side,
no provider fix); *it still stops* → M1′-class mark-up degradation on the reporter's own egress,
and S2's guard 2 is the real target. The report's own clue — homepage pages in the same session —
puts the **gate** out of the reporter's context, which is why M1′ is recorded as primary **for this
egress** and M2/M4 as the live secondary pair for theirs, rather than a both/and on one cause.

**Build gate (this run, unchanged tree):** `./gradlew PornHub:test` → **BUILD SUCCESSFUL**, 231
tests / 0 failures across 30 suites; `./gradlew PornHub:make` → `PornHub.cs3` (171,675 B). The
story adds no tests and changes no provider code.

### Related videos

- **Does not paginate.** The `ul#relatedVideosListing` block is a fixed selection (35 cards on
  the S4 sample, 36 on S3 — the size drifts with the site's own content): no `link rel=next`, no
  `div.pagination3`/`li.page_next` inside or after it, no AJAX "load more" hook (a scan of the
  200 KB following the container finds no pagination control). So `LoadResponse.recommendations`
  carries the whole block and there is no related page-2 URL to record — an explicit absence,
  not an omission (markup and transcripts: §Related videos).

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

**Fixture refresh — the reporter's query (#1494, 2026-11-22).** `search-page-1.html` and
`search-page-2.html` now carry the **reporter's own query**, `?search=george+uhl`, instead of
the old `teen` probe: the verbatim `<link rel="canonical">` / `<link rel="next">` head lines
(page 2 also carries `<link rel="prev">`) plus the verbatim `<ul id="videoSearchResult">`
block — 31 cards on page 1, 43 on page 2, 0 shared viewkeys (transcript §Pagination (search)).
The fixtures were captured from the same 2026-11-22 run and every search Parse test is now
re-pointed at them (including the card-metadata and poster-`Referer` assertions); the
last-page and 404 bodies stay on the broad `teen` probe, which is where those shapes were
measured — dropping them would have silently lost the `hasNext = false` coverage.

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
| poster | `meta[property=og:image]` | signed `pix-egi` on 2025+ assets / unsigned `ei` on older ones; `pix-cdn77` was not observed for `og:image` (0/140, §Homepage shard matrix); compare the asset path, never the URL |
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
| `recommendations` | yes, 5/5 | `ul#relatedVideosListing li.pcVideoListItem` | 35 cards on the S4 sample (36 on S3), all distinct, never the page's own viewkey (§Related videos) |
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
fixed selection whose size drifts with the site's own content — 35 cards on the S4 sample, 36
on the S3 sample (§Video pages); `LoadResponse.recommendations` carries exactly that.

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

**Refinement (S3 of #1490, 2026-11-22): the shape can split inside one page, and a leg that
answered once can answer 410 on the next GET.** Two observations from the S3 run, same egress,
same cookie-less desktop GETs:

```
6afbd8a1f2e9e  one page fetch  legs 1080 -> 410 text/plain;  240 / 480 / 720 -> 200 application/vnd.apple.mpegurl
               (the per-leg isPlayable probe emits exactly the three live legs; a page-level
                all-or-none rule would have dropped three good links)
6aec722848c4d  leg URL 200 application/vnd.apple.mpegurl at resolve time,
               same URL 410 text/plain on the next GET minutes later (style-B `hv-h` token)
```

So "one signing shape per page fetch" (§CDN host lottery) is the common case, not a guarantee:
the shape is bound to the request/edge, and `height` ladders can mix live and dead legs in one
blob. That is exactly why the emission is per-leg and per-call — and it is also why a
pre-resolved `--stream-url` can flip dead between the agent's resolve and `verify.sh`'s fetch
(the first S3 verify run lost 3 of 5 legs that way; the second resolved and fetched the same
minute, 5/5 → 206). Nothing in the provider changes: it never emits an unprobed leg.

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

- **Hotlink-protected still shard + missing `Referer` (the real blank-thumbnail cause, S1 of
  #1468).** The signed listing stills come from one CDN family per fetch (a page can also mix
  in legacy `ei` stills): `pix-cdn77` (signed
  `?hash=…&validto=…`) answers **403** with no `Referer` and **200** once any `Referer` is
  present; `pix-fl` (`?hdnea=st=…~exp=…~hmac=…`) and unsigned `ei` answer 200 either way
  (§Homepage matrix).
  CloudStream's image path sent no `Referer`, so a `pix-cdn77` search/home fetch rendered
  blank. The failure mode is **not** "signature expired → re-parse": stripping the signed
  query makes both shards fail (`pix-cdn77` 403, `pix-fl` 470) *even with* a Referer. Parse the
  still verbatim and send the site `Referer` on image loads (S2 shipped it as
  `Parse.posterHeaders` on every poster emission site, #1472). Never
  persist a poster URL as stable identity (the Distinct bar compares the freshly parsed cards
  of one fixture/page only).
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
  runner C  2026-11-22  ipinfo.io: country "US", region "Arizona", AS8075 (S3 of #1490)
    GET /video, /video?page=2 -> 200 1203080B / 1282678B, 32 / 44 distinct viewkeys, 0 shared
    GET /video?o=mv|tr|cm (+page=2) -> 200, 32 / 44 each, 0 shared, rel=next on all eight
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

  **The gate also reaches the search surface (S1 of #1524, 2026-11-23).** On a gated egress
  (`isSfw:"1"`) the search URL itself answers **200** — so there is no `302` to spot — but the
  page-1 body keeps its cards and drops **all** pagination mark-up (`rel=next`, `pagination3`,
  `page_next` and any `?page=2` href: 0), page 2 answers `404 div.noResultsWrapper`, and the watch
  page loses its `og:*` metas and JSON-LD while keeping flashvars + actors. Neither
  `age_verified=1; platform=pc` nor `isSfw=0` unlocks it. Record the egress with every pagination
  claim too, not just with listing claims — transcript: §Pagination (search), S1 of #1524.
- **Geo wall on watch pages not observed.** ~50 pages on 2026-11-18 (region not recorded) and
  all 5 S4 pages on 2026-11-21 carried `video_unavailable_country: "false"` (and `video_unavailable: false`);
  the parser still honours both flags, but there is no recorded geo-walled sample. Removed
  (404) and premium (200, no flashvars) walls are recorded in §Video pages and both drop the
  card (`load()` → `null`). This is a **different** flag from the listing gate above.
- **Quality ints inherit the page's `height` field, which is not consistent per page.** Most
  probed pages report ladder heights (`1080/240/480/720` on three of the five S4 style-A reads
  and `240/480/720` on a fourth; `240/480/720/1080` on four of the S3 reads), but
  `6acf5129bd5a5` reports pixel widths (`1920/426/852/1280`) for the same `1080P_4000K_…` path.
  The signing shape does not explain the split — every one of those reads is style A — and no
  A/B transcript exists; the cause is unestablished. `Parse.qualityOf` passes the value through
  as `ExtractorLink.quality`, so the quality-ordering surface can see 1920/1280/852/426 instead
  of 1080/720/480/240. Every leg still served video; recorded so a "quality sort looks odd"
  report is diagnosed from §Stream sources.
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
| `Parse.hasNextPage` | `link[rel=next]`, `li.page_next:not(.disabled) a[href]` | §Pagination (search) — absent/disabled on page 100 and the 404 body, and **entirely absent** (with the whole `pagination3` block) on the SFW-gated page-1 body (§Pagination (search), S1 of #1524); consumed by `search` **and** (since S2) by the home rows through `Parse.homeHasNext` |
| `Parse.homeHasNext` | `hasNextPage` on every real body; a blank body keeps the row open only on page 1 | §Pagination (home) — four rows × two pages all carry `rel=next`; the past-the-end 404 (`home-past-end.html`) and a degraded body stop the row (§Verification note (S3 of #1490)) |
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

### Verification note (S2 — poster Referer fix, #1472)

Live checks run **2026-11-21 21:44–21:59 UTC** from the CI runner egress (**region not recorded
for this run**). The shipped change is one constant on every poster emission site —
`Parse.posterHeaders` wired into `parseVideoCards` (home rows *and* search), the `load()`
poster and the `load()` recommendation cards — with no selector change and no URL rewrite.

**Hotlink matrix re-probed live** (desktop UA, cookie-less; one `pix-cdn77` still from `/video`,
one referer-free `pix-fl` still from `/video/search?search=teen`):

```
cdn77 no Referer                        -> HTTP 403 text/html 868B
cdn77 Referer: https://www.pornhub.com/ -> HTTP 200 image/png 32020B
pix-fl no Referer                       -> HTTP 200 image/jpeg 11807B
pix-fl Referer: https://www.pornhub.com/ -> HTTP 200 image/jpeg 11807B
cdn77 signature stripped + Referer      -> HTTP 403 text/html 868B
```

The S1 matrix reproduces on a fresh fetch: the header flips the hotlink-protected shard from
403 to 200, a referer-free shard answers 200 either way, and the signature stays load-bearing
(stripping it still fails with a Referer). The app now sends `Referer:
https://www.pornhub.com/` (a presence check — any value passes).

**TDD red → green**: with the parse seam and the constant in place but no wiring,
`./gradlew PornHub:test` failed exactly the 5 new assertions
(`every home poster carries the site Referer`, `the pix-cdn77 home still keeps its signature
verbatim`, the two search equivalents, and `load poster and related cards carry the site
Referer`); after wiring, the same command is green (168 tests) and `./gradlew PornHub:make`
is `BUILD SUCCESSFUL`.

**verify.sh** (the S4 command shape, five fresh videos `6ad1c2b19ac29` / `6aea17288c77c` /
`642c96c447bd3` / `6ac144ca27c0d` / `6ae97d82eadd3`, highest HLS per page as `--stream-url`):

```
RESULT: FAIL
check 1/1a  duplicate cards  — every href read as /view_video.php (identity plateau)
check 2a    actors 4/5       — one uploader credits no models (site content, video-page-2 shape)
check 3     stream path collision — same plateau (url_path strips ?viewkey=)
check 5     search ↔ load   — whole search page compared to each load page (same plateau)
```

Every FAIL is the documented instrument plateau from S4 (§Verification (S4)) or page content,
not selector drift, and the surfaces the fix touches all pass:

- search pages fetched 200 with 38 / 50 matches, home pages 200 with 45 / 57 matches (global
  counts, as recorded in S4);
- streams **5/5 → 206 `application/vnd.apple.mpegurl`**;
- `tags` present on all 5 pages, and check 6 reports every requested `LoadResponse` field
  populated (`recommendations`, `tags`, `plot`, `duration`, `year`, `actors`).

The listing-side proof is the hotlink matrix above plus the fixtures: `home-page-1.html`
(a `pix-cdn77` fetch) and `search-page-1.html` (mixed `pix-cdn77`/`ei`) now assert every emitted
card carries the header and that the `pix-cdn77` poster is the markup URL verbatim (signature
intact), and `video-page-1.html` asserts the same for the load poster and every related card.

### Verification note (S3 of #1490 — pagination close-out, 2026-11-22)

Release marker: the merged tree carries **`version = 10 // #1508: version bump (v10)`** — the
version-bump lane's comment, not a `#1490` one; this story re-issues nothing. The story text in
#1496 asked for v7, and that number was already spent when the branch first pushed (S1's PR
#1498 took 6 → 7, S2's #1503 took 7 → 8, and #1444's fleet bump #1505 took `main` 8 → 9). The
branch's own push then landed at v9, and a later fleet bump (#1508) took the merged tree to
v10. The chain is therefore #1491 5 → 6, S1 (#1498) 6 → 7, S2 (#1503) 7 → 8, #1505 8 → 9,
#1508 9 → 10; the pagination fix shipped in S1/S2, so every published plugin from v7 on
already carries it.

Live checks ran **2026-11-22 14:24–14:47 UTC** from the CI runner egress **`20.171.39.149`,
Phoenix/Arizona, AS8075 Microsoft** (`ipinfo.io` → `"country": "US"`, `"region": "Arizona"`).
`/video` answered 200 with the listing on this egress, so §Risks' geo/SFW gate **did not fire**
here: no `302 -> /`, no `ph-redirect: 1041`, no `isSfw: 1` — nothing in this section is Blocked.
Toolchain: Gradle 9.9.1 on Temurin JDK 25, cookie-less desktop Chrome UA for every fetch,
`PornHub` at v9 on the run's branch tree (the merged tree is v10; see the release note above).

**Search — the reporter's query and a broad query, two pages each** (identity is the
`?viewkey=` query, the §Verification (S4) substitution: `verify.sh`'s `url_path` strips the
query and false-FAILs every card on `/view_video.php` — §Risks):

```
/video/search?search=george+uhl          p1 200 1123597B cards=32 anchors=63 rel_next=yes rel_prev=no
/video/search?search=george+uhl&page=2   p2 200 1199170B cards=44 anchors=87 rel_next=yes rel_prev=yes  shared=0
/video/search?search=milf                p1 200 1121086B cards=32 anchors=63 rel_next=yes rel_prev=no
/video/search?search=milf&page=2         p2 200 1205379B cards=44 anchors=87 rel_next=yes rel_prev=yes  shared=0
RESULT: PASS
```

`cards` counts distinct viewkeys in the scoped block, `anchors` every `view_video.php?viewkey=`
link in it (each card links the key twice: thumbnail + title). Page 2 is fresh on both queries
(**0 shared viewkeys**), and the site's own `<link rel="next">` is present on page 1 **and**
page 2 — so `Parse.hasNextPage` returns `true` on both pages of the reporter's query, which is
the seam the app reads.

**Home rows — all four `getMainPage` rows, page 1 + page 2:**

```
/video            p1 200 1203080B cards=32 anchors=63 rel_next=yes    /video?page=2            p2 200 1282678B cards=44 anchors=87 rel_next=yes  shared=0
/video?o=mv       p1 200 1210294B cards=32 anchors=63 rel_next=yes    /video?o=mv&page=2       p2 200 1287967B cards=44 anchors=87 rel_next=yes  shared=0
/video?o=tr       p1 200 1205445B cards=32 anchors=63 rel_next=yes    /video?o=tr&page=2       p2 200 1286415B cards=44 anchors=87 rel_next=yes  shared=0
/video?o=cm       p1 200 1204602B cards=32 anchors=63 rel_next=yes    /video?o=cm&page=2       p2 200 1280713B cards=44 anchors=87 rel_next=yes  shared=0
RESULT: PASS
```

**Past the end (the shape the derived stop must survive):**

```
/video?page=9999  HTTP 404 747628B ul#videoCategory=no cards=0 rel_next=no page_next=no
/video?page=0     HTTP 404 744816B ul#videoCategory=no cards=0 rel_next=no page_next=no
RESULT: PASS
```

`Parse.homeHasNext` therefore returns `true` on all eight live bodies (`rel=next` is the first
signal it reads) and `false` on both 404 bodies — the row can neither walk past the end nor
re-serve page 1.

**Video pages — five videos, one per listing (load), with every HLS leg probed:**

```
6afbd8a1f2e9e  /video          200 1334826B  Cute Teen Gets Fucked On Snap After Break Up - Julia James
                               dur=PT00H15M00S up=2026-11-17 cats=10 tags=13 actors=2 related=36 self=no legs=[240,480,720,1080]
6ac144ca27c0d  /video?o=mv     200 1395801B  First Weiner for Lesbian Step Daughter - Myra Moans - Family Therapy - Alex Adams
                               dur=PT00H09M53S up=2026-10-03 cats=12 tags=16 actors=2 related=36 self=no legs=[240,480,720,1080]
691ffed8dfcaa  /video?o=tr     200 1323551B  Asian teen student gets spanked and railed for her 18th birthday
                               dur=PT00H04M11S up=2025-11-21 cats=10 tags=14 actors=1 related=36 self=no legs=[426,854,1280,1920]
6ad1030d797c6  /video?o=cm     200 1269392B  Toni Chavez Welcomes Andrew Andretti
                               dur=PT00H11M28S up=2026-10-15 cats=5  tags=4  actors=2 related=36 self=no legs=[240,480,720,1080]
6af346a688993  search=george+uhl 200 1291069B She helps him forget his wife - MOM XXX
                               dur=PT00H15M27S up=2026-11-10 cats=10 tags=16 actors=2 related=36 self=no legs=[240,480,720,1080]
distinct titles 5/5; distinct poster asset paths 5/5; distinct viewkeys 5; related cards distinct within page
RESULT: PASS
```

All five load pages carry `flashvars` (streams resolvable), `og:*` metadata, the JSON-LD
`VideoObject` duration/uploadDate, categories **and** tags, at least one credited actor, and a
36-card related block that never repeats the page's own viewkey. `691ffed8dfcaa` is the second
height convention again (`[426,854,1280,1920]` — widths, §Risks): the platform's own quality
ladder is unaffected, the ints are just not heights there.

**Search ↔ load agreement (viewkey-keyed):**

```
search card 6af346a688993  title 'She helps him forget his wife - MOM XXX'
load   h1                  title 'She helps him forget his wife - MOM XXX'          equal
poster asset               /videos/202611/10/68645355/original  (card pix-fl rs:fit:320:180 / load pix-egi rs:fit:640:360)
home   card 6afbd8a1f2e9e  title 'Cute Teen Gets Fucked On Snap After Break Up - Julia James'   == load h1
poster asset               /videos/202611/17/69506775/original  == load og:image asset
RESULT: PASS
```

The shard (`pix-fl` vs `pix-egi`) and rendition (`320:180` vs `640:360`) differ by design, so
agreement is compared on the asset path — the §Search rule.

**`verify.sh` run** (the S4 command shape; 4 search pages, 8 home URLs, the 5 videos above,
`--video-tags-selector`/`--video-actors-selector`, `--provider-src PornHub
--load-response recommendations,tags,plot,duration,year,actors`, highest HLS leg per video as
`--stream-url`):

```
── check 1: search pages (4)          → all 200; global matches 38 / 50 / 38 / 50
FAIL duplicate search cards            ← url_path strips ?viewkey= (documented plateau, §Risks)
── check 1a: homepage pages (8)       → all 200; global matches 45 / 57 on every pair
FAIL duplicate home cards              ← same plateau
── check 1b: quick search (0)         → NOTE: no distinct endpoint (§Quick search), hasQuickSearch=false
── check 2: video pages (5)           → all 200, div#player 2–3 matches each
   streams 5/5 → 206 application/vnd.apple.mpegurl
   field 'tags': present on all 5 sampled pages
   field 'actors': present on all 5 sampled pages
── check 3: cross-video identity      FAIL — all five collapse to /view_video.php (same plateau)
── check 5: search ↔ load agreement   FAIL — whole search page compared to each load page (same plateau)
── check 6: LoadResponse completeness
   recommendations 1, tags 2, plot 3, duration 1, year 2, actors 3 assignment(s) in PornHub
RESULT: FAIL
```

Every `FAIL` is the one documented instrument plateau plus the two all-or-none notes the script
cannot take selectors for (`duration`/`year` live in JSON-LD, not a DOM attribute — §Risks), and
the substituted viewkey-keyed transcripts above are the same mechanics keyed correctly — the
precedent is §Verification (S4). The surfaces the story touches all pass: four home rows × two
pages with fresh page 2s, the reporter's query paginating, five videos with live HLS and full
metadata, and a complete `LoadResponse` mapping.

`--related-selector` was **not** passed, per §Verification (S4): its two checks sit on the same
stripped-query path (self-link and duplicate detection both read `/view_video.php`), so the
related block is proven by the viewkey-keyed line above (36 distinct cards, self absent).

**Mechanism verdict for the reporter's symptom — unchanged: M2 or M3, no provider defect.**
M1 is not reproducible on this egress (the seam derives `hasNext` from the site's own
`rel=next` on both pages of the reporter's query and page 2 carries 44 fresh cards), so the
"page 1 only" report is:

- **M2 — app-side:** with more than one provider enabled, the combined search list's
  `hasNext` is the app's, and a provider pages only when its section is expanded. User action:
  enable only PornHub, then expand the PornHub section before scrolling.
- **M3 — stale plugin:** an install older than v5 keeps the constant `hasNext = true`/`false`
  behaviour; this repo now publishes **v10**. User action: update the plugin (or reinstall from
  `repo.json`).

Both are outside `PornHub/`; no selector or stop-rule change was made in this story.

**Build gates (head `45c19e5`, re-run 2026-11-22 after the second `main` merge; every later
commit is docs-only):** `./gradlew bootstrapCloudstream` (sha-pinned classes.jar) →
`./gradlew PornHub:test PornHub:make` **BUILD SUCCESSFUL** — **216 tests, 0 failures** across
27 suites (17 `PornHubParseTest` + 16 `PornHubSearchParseTest` + 16 `PornHubLoadParseTest` = 49
PornHub, plus **167** spliced `shared/` tests, incl. #1444's `JavtifulEmbed*` and #1508's
`KvsVideofile*`/`Youporn*` cases), and `PornHub/build/PornHub.cs3` produced (162,114 B). The
191/196 figures in earlier drafts were the pre-merge trees.
