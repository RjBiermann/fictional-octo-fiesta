# FINDINGS-1455 — Neporn video sources (S17 of #1354)

Audit of the **video-source leg** (D2) of the Neporn spec (`neporn.com`). Scope: for every
listing type in D3 (`new`/`latest`, `old`, `popular`, `genres`, `categories`, `tags`) open ≥2
video pages — sampled at both ends of the catalogue where the type is pageable — and resolve
`loadLinks` end-to-end: video page → KVS `flashvars.video_url` → `get_file/.../{id}_720p.mp4/?v-acctoken=…`
→ **302** → `data00X.neporn.com/remote_control.php` → **206 `video/mp4`**, embed host identified,
quality passed to CloudStream where the site exposes it.

Provider state at run start: registry `last_deep` = 2026-10-16, `drift_confirmed` = **0**,
verdict at last deep `ok` (#648). `drift_confirmed` = 0 ⇒ a targeted fix would be allowed if a
condition were confirmed; nothing was.

**Result: no condition confirmed on the video-source leg — the leg is clean, no code change, no
version bump (stays 23).** No finding filed. Every listing type the site exposes answered
plain-curl, and all 15 sampled rows resolved end-to-end (page → single 720p source → 302 → 206
`video/mp4`, ISO-BMFF `ftyp isom` magic).

Run: `05-implement` for #1455, 2026-11-24, plain-curl tier, runner IP US (52.240.176.168).
Workflow run: `https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/45305227572`.

Out of scope per spec: a full listing audit (pagination/card-field correctness was #632–#659) and
*rows* work (uncensored/ura row additions). Listing correctness was checked only as far as
enumeration needed, and not re-probed beyond that (`drift_confirmed` = 0, enumeration intact).

---

## Phase 0 — canary calibration (before touching the site)

| Canary | Expected | Observed | Result |
| --- | --- | --- | --- |
| `eporner-healthy` | plain-curl 200 | plain-curl **200** (312 140 B — real homepage) | match — instrument healthy |
| `film1k-challenge-pair` | plain-curl 403, tls-impersonated 200 | plain-curl **200** (214 908 B) | **expectation-stale** (#1435 open) — live pair recorded here; **no** `environment-degraded` branch taken |
| `pandamovies-redirect-origin` | plain-curl 302, tls-impersonated 200 | plain-curl **302 → https://pandamovies.org/** | plain-tier match; the TLS leg cannot run (#1434) |

```
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://www.eporner.com/  → 200 312140
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://www.film1k.com/   → 200 214908
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://pandamovies.pw/     → 302 https://pandamovies.org/
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://neporn.com/       → 200 111967
$ python3 -c 'import curl_cffi'
ModuleNotFoundError: No module named 'curl_cffi'      # #1434 still open at run time
```

**#1434 is still open**, so the registry `last_runs` entry carries `escalation_tier: unavailable`
and any `Blocked` this run would downgrade to `Suspected`. None occurred — neporn.com answered
plain-curl on every surface below, including all 15 streams.

Instrument = **plain-curl tier**, desktop Chrome UA
(`Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36`).
No proxy, no impersonation, no browser instrument anywhere below. Stream fetches were re-run with
an app-like UA (`Mozilla/5.0 (Linux; Android 10; K) … Chrome/125 Mobile Safari/537.36`) and with
the provider's own `Referer: https://neporn.com/` — same answers (see *Headers / referer*).

---

## Listing-type inventory (D3)

The site is a **KVS (Kernel Video Sharing)** deployment: server-rendered `div.list-videos div.item`
grids, pagination as `/<listing>/<page>/`, player config in a `flashvars` JS object. Taxonomies
live at `/categories/<slug>/` (27-slug index) and `/tags/<slug>/` (7 720-tag index); models live at
`/models/`. Every row probed plain-curl. A type the site does not expose is recorded **`absent`**
with the evidence that proves it, never assumed.

| type | surface | evidence |
| --- | --- | --- |
| `new` / `latest` | **`/latest-updates/`** (the provider's first row) — p1 **200, 24** cards, p2 **200, 24**, p1↔p2 overlap **0**; far end `/latest-updates/1690/` **200, 4** cards (ids 12/9/8/7 — the oldest catalogue content), `/latest-updates/1691/` **404** (catalogue ≈ 40 540 videos). Content is newest-first by id (p1 head `45235` → p1 tail `45208`) | 200 |
| `old` | **absent as an ordering.** `/oldest/` → **404**; the KVS sort parameters are ignored — `/latest-updates/?sort_by=post_date&sort_by_direction=asc` returns a page **byte-for-byte identical in card order** to `/latest-updates/` (same first id `45235`, `sort asc identical to p1? True`), so ascending/oldest listings are not exposed. The oldest content is nevertheless reachable as the deep tail of `latest` and is sampled as rows 3–4 (`/latest-updates/1690/`, ids 12 and 7) | 404 (route) / 200 (deep tail) |
| `popular` | **`/most-popular/`** (the provider's second row) — p1 **200, 24**, p2 **200, 24**, overlap **0**; far end `/most-popular/1690/` **200, 4**, p1691 **404**. p1 shares **0** cards with `/latest-updates/` p1 (separate ranking) | 200 |
| `top-rated` | **`/top-rated/`** (the provider's third row, and a distinct D3 ranking surface) — p1 **200, 24**, p2 **200, 24**, overlap **0**; far end `/top-rated/1690/` **200, 4**, p1691 **404**. p1∩popular p1 = **1** card (rankings overlap by content, not by ordering) | 200 |
| `genres` | **absent.** `/genres/` → **404**; `/genre/milf/` → **404**; `/categories/?group=genres` → **404** (only `?group=categories` exists). No genre taxonomy appears in the nav, the category index, or the video-page markup — the subject vocabulary is `categories` + `tags` only | 404 |
| `categories` | **`/categories/<slug>/`** — index `/categories/` **200** with **27** slugs (`amateur, anal, asian, big-ass, big-boobs, blonde, blowjob, brunette, creampie, cumshots, deep-throat, double-penetration, ebony, gang-bang, interracial, latina, lesbian, lingerie, milf, orgy, pov, red-head, shemale, solo, teen, threesome, toys`). `/categories/milf/` p1 **200, 24**, p2 **200, 24**, overlap **0**, far end `/categories/milf/137/` **200, 7**, p138 **404** (≈ 3 271 videos). `/categories/amateur/` last page **38** | 200 |
| `tags` | **`/tags/<slug>/`** — index `/tags/` **200, 1 012 207 B**, **7 720** tag links, single page (no pagination). `/tags/milf/` p1 **200, 24**, p2 **200, 24**, overlap **0**, far end `/tags/milf/174/` **200, 7**, p175 **404** (≈ 4 159 videos). `/tags/anal/` has ≥ 300 pages | 200 |

Note on pagination markup: `/categories/<slug>/` and `/tags/<slug>/` pages carry **no visible
pagination widget** (the `div.pagination` block is absent), yet `/<listing>/<page>/` serves real,
disjoint pages — the widget is simply not rendered for those listing kinds. Enumeration is
unaffected (the provider's `getMainPage` builds `"${request.data}$page/"`), and page *N+1* returning
new videos is what the in-app paging needs.

### Search and quick search

```
$ curl -s -A "$UA" 'https://neporn.com/search/milf/'                                  → 200, 24 cards
$ curl -s -A "$UA" 'https://neporn.com/search/?q=milf&mode=async&function=get_block&block_id=list_videos_videos_list_search_result&from_videos=2'
                                                                                       → 200, 24 cards, 0 overlap with p1
$ curl -s -A "$UA" 'https://neporn.com/search/milf/2/'                                → 404 (slug pagination does not exist)
```

The provider's search wiring is unchanged and correct: p1 via the KVS slug route, p2+ via the
`async` block endpoint with `from_videos=<page>`; the pagination block carries
`li.page.last` whose link is not `.page-current` on any page before the last, which is exactly what
`hasNext` reads (`doc.selectFirst("li.page.last:not(.page-current)")`).

**Quick search: absent** — there is no suggest/live-typing endpoint (no `/suggest/`, no
`?mode=async&function=get_block&block_id=search_suggest`), so `hasQuickSearch` stays `false` and
`verify.sh` check 1b is a NOTE (standing record since #648, not drift).

---

## Evidence rows (D4)

`type | sample URL | instrument tier | stream host | quality | verdict` — 15 videos, ≥2 per present
type, both catalogue ends where the type is pageable. Every row resolved **end-to-end**:
video page **200** → `flashvars.video_url` (single source, `_720p.mp4`) → **302** →
`data00X.neporn.com/remote_control.php?...acctoken=…` → **206 `video/mp4`**.

| # | type | sample URL | listing position | tier | stream host | quality | verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | new/latest (`/latest-updates/`) | `/video/45235/jynx-maze-x-mike-adriano-3/` | p1 head | plain-curl | `data004.neporn.com` | 720p | **pass** |
| 2 | new/latest (`/latest-updates/`) | `/video/45208/trinity-blond-hardcore-anal-fuck/` | p1 tail | plain-curl | `data004.neporn.com` | 720p | **pass** |
| 3 | old (deep catalogue tail) | `/video/12/avn-gangbang-2023-cam-jacuzzi/` | p1690 head (last page) | plain-curl | `data001.neporn.com` | 720p | **pass** |
| 4 | old (deep catalogue tail) | `/video/7/the-pros-episode-10-ufc-champ-house-party-goes-wrong/` | p1690 tail (last page) | plain-curl | `data001.neporn.com` | 720p | **pass** |
| 5 | popular (`/most-popular/`) | `/video/4106/evil-angel-films-i-am-angela-ep-4-ciao-bella/` | p1 head | plain-curl | `data002.neporn.com` | 720p | **pass** |
| 6 | popular (`/most-popular/`) | `/video/32026/double-digit-squirting-with-valerica-steele-damion-dayski/` | p1 tail | plain-curl | `data002.neporn.com` | 720p | **pass** |
| 7 | popular (`/most-popular/`) | `/video/45178/latina-lesbian-cams-compilation/` | p1690 head (last page) | plain-curl | `data004.neporn.com` | 720p | **pass** |
| 8 | top-rated (`/top-rated/`) | `/video/17234/from-romance-to-squirting/` | p1 head | plain-curl | `data002.neporn.com` | 720p | **pass** |
| 9 | top-rated (`/top-rated/`) | `/video/32149/hardx-big-peruvian-anal-ass/` | p1 tail | plain-curl | `data002.neporn.com` | 720p | **pass** |
| 10 | genres | — | **absent** (`/genres/`, `/genre/milf/`, `?group=genres` all 404) | plain-curl | — | — | **absent** |
| 11 | categories (`/categories/milf/`) | `/video/45198/marie-fox-bbc-anal/` | p1 head | plain-curl | `data004.neporn.com` | 720p | **pass** |
| 12 | categories (`/categories/milf/`) | `/video/44857/german-gothic-milf-with-big-hard-silicone-tits-and-pierced-nipples/` | p1 tail | plain-curl | `data004.neporn.com` | 720p | **pass** |
| 13 | categories (`/categories/milf/`) | `/video/87/le-wood-francesca-le-hotwife-does-brickzilla/` | p137 head (last page) | plain-curl | `data001.neporn.com` | 720p | **pass** |
| 14 | tags (`/tags/milf/`) | `/video/45202/mercedes-carrera-make-him-wait/` | p1 head | plain-curl | `data004.neporn.com` | 720p | **pass** |
| 15 | tags (`/tags/milf/`) | `/video/44712/bridgette-b-rub-down/` | p1 tail | plain-curl | `data004.neporn.com` | 720p | **pass** |
| 16 | tags (`/tags/milf/`) | `/video/53/le-wood-gizelle-blanco-next-gen-submission/` | p174 head (last page) | plain-curl | `data001.neporn.com` | 720p | **pass** |

All 15 URLs above are `https://neporn.com` + path. `old` has no ordering route (see D3), so its
two rows are the oldest catalogue content, reached through the `latest` surface's last page — the
same videos an "old" listing would show, and the only place the site serves them.

---

## Stream chain (raw transcript, 15/15)

`flashvars.video_url` is a **session-bound bearer**: the `v-acctoken` encodes the requesting IP and
an expiry. `get_file` answers **302** to `data00X.neporn.com/remote_control.php?file=…&acctoken=…`,
which serves the mp4. Range request transcript (position-matched to the rows above):

```
$ curl -sS -L -r 0-1023 -H 'Referer: https://neporn.com/' -A "$UA" '<flashvars video_url>'
HTTP/2 302                                          # neporn.com/get_file/.../{id}_720p.mp4/?v-acctoken=…
location: https://data00X.neporn.com/remote_control.php?file=<opaque>.mp4&acctoken=<signed>
HTTP/2 206                                          # data00X.neporn.com
content-type: video/mp4
content-range: bytes 0-1023/<size>
$ xxd -p -l 16 seg.bin → 000000206674797069736f6d00000200   # ISO-BMFF: size=0x20, "ftypisom"
```

| # | row | chain | total size |
| --- | --- | --- | --- |
| 1 | new/latest p1 head | 302 → 206 video/mp4 | 780 821 542 |
| 2 | new/latest p1 tail | 302 → 206 video/mp4 | 427 765 432 |
| 3 | old p1690 head | 302 → 206 video/mp4 | 413 162 010 |
| 4 | old p1690 tail | 302 → 206 video/mp4 | 899 576 830 |
| 5 | popular p1 head | 302 → 206 video/mp4 | 1 210 742 372 |
| 6 | popular p1 tail | 302 → 206 video/mp4 | 1 163 320 672 |
| 7 | popular p1690 head | 302 → 206 video/mp4 | 202 976 750 |
| 8 | top-rated p1 head | 302 → 206 video/mp4 | 622 705 218 |
| 9 | top-rated p1 tail | 302 → 206 video/mp4 | 597 953 314 |
| 11 | categories p1 head | 302 → 206 video/mp4 | 394 002 602 |
| 12 | categories p1 tail | 302 → 206 video/mp4 | 127 813 442 |
| 13 | categories p137 head | 302 → 206 video/mp4 | 593 248 505 |
| 14 | tags p1 head | 302 → 206 video/mp4 | 328 608 150 |
| 15 | tags p1 tail | 302 → 206 video/mp4 | 343 492 005 |
| 16 | tags p174 head | 302 → 206 video/mp4 | 773 232 498 |

**Embed host: none to register.** Neporn does not embed a third-party player — the video page's
only iframe is the site's own `/embed/<id>` (`getEmbed()` in its player JS), and the stream is the
site's own `get_file` route. `shared/`'s `HostRegistry` is therefore not on this provider's path:
`loadLinks` emits the flashvars URL directly as an `ExtractorLinkType.VIDEO` link with
`referer = mainUrl`. Nothing DRM-wrapped, no adapter gap.

### Headers / referer

```
$ curl -sS -L -r 0-511 -A "$UA" '<video_url>'                          → 302 → 206 video/mp4   (no referer)
$ curl -sS -o /dev/null -w '%{http_code}' -A "$UA" -r 0-511 '<video_url without ?v-acctoken>'
403
$ curl -sS -o /dev/null -w '%{http_code}' -A "$UA" -r 0-511 -H 'Referer: https://neporn.com/' '<same bare URL>'
403
```

The token is what authorises the fetch; a referer changes nothing, and a tokenless `get_file` is
**403** on any tier — the known session-bound class recorded since #648, **not** drift. The
provider emits exactly the token-bearing URL the page carries, so the app's own fetch (device IP,
live token) is the same authorised path proven here.

### Quality

The site exposes **one rendition per video**, named in the flashvars (`postfix: '_720p.mp4'`,
`video_url_hd: '1'`) and in the filename token (`…/{id}_720p.mp4`); `video_alt_url` /
`video_alt_url2` are never present. Swept **41 additional video pages** across `/latest-updates/`
(p1, 50, 300, 700, 1200, 1689), `/most-popular/` (1, 40, 500, 1000, 1689), `/top-rated/`
(1, 25, 800, 1689), `/categories/amateur/` (1, 20, 38) and `/tags/anal/` (1, 30, 120), two random
cards per page:

```
pages 41  statuses {200: 41}
quality tokens/postfix/hd:  ('720', '_720p.mp4', '1') × 37   ('720', '_720p.mp4', None) × 4
alt keys seen: {}
```

So the provider's quality mapping (`_720p` → `Qualities.P720`, else `Unknown`) is **exact for the
entire catalogue shape the site serves** — no 480p/1080p/4K rendition exists to be dropped. Nothing
to fix; no data-completeness condition.

---

## Site-side notes (recorded, **not** provider findings)

1. **`plot` mirrors the site's own meta, including its generic tagline.** On 4 of the 15 rows
   (`45235`, `7`, `45198`, `45202`) the page's `og:description` is the site-wide
   boilerplate *"If you're looking for the best adult website on the internet for free, full-length
   porn videos, Neporn.com is the place to be."*; the rest carry a real per-video synopsis (e.g.
   `4106` 1 400 B, `17234` 579 B, `87` 372 B). The provider sets `plot = og:description`, i.e. it
   reproduces what the site marks up. Filtering the tagline would be a plot-content heuristic, not
   a parse fix, and plot is outside the video-source leg (D2) — recorded for a future plot/rows
   pass, **no issue filed from this story** (scope discipline).
2. **`/categories/` exposes 27 slugs; the provider renders 14 category rows.** A rows question
   (which categories deserve a main-page row), explicitly out of scope here — not a D3 failure:
   every category listing resolves, whichever slug is opened.
3. **Newest uploads carry no models** (`video_models: ''` in flashvars; no `/models/` anchor on the
   page), older scenes do — unchanged from #648, actors stay optional. Not asserted by
   `verify.sh` (see the check 2a NOTES below).
4. **JSON-LD carries a tokenless `contentUrl`** (`"contentUrl": "…/{id}_720p.mp4/"`), which 403s
   without `?v-acctoken`. The provider does **not** read it — it emits the flashvars URL — so this
   is a page-shape fact, and the reason check 3's stream URLs are position-matched overrides below
   (the tool's generic extractor finds both forms; only one is fetchable).
5. **Duration/year exist only as JSON-LD / meta, not as a stable CSS element**: `duration` as
   `PT0H52M34S` in the `VideoObject` block (plus `Video Duration: 52:34` inside `meta[name=description]`),
   `year` as `uploadDate`. The provider reads both with `JsonLdParse` / `Parse.durationMinutes`
   (the #826 fix), so no `--video-duration-selector` / `--video-year-selector` can be passed.

---

## Pipeline verification — `verify.sh` (live): **PASS**

Two-page search, two-page home, 5 sampled videos across the listing types, position-matched
`--stream-url` overrides (the streams live in `flashvars`, and the page also carries a tokenless
JSON-LD `contentUrl` — note 4), `--provider-src Neporn`:

```
── check 1: search pages (2)
GET https://neporn.com/search/milf/ → 200; 'div.list-videos div.item' matches: 24
GET …/search/?q=milf&mode=async&…&from_videos=2 → 200; 'div.list-videos div.item' matches: 24
── check 1a: homepage pages (2)
GET https://neporn.com/latest-updates/   → 200; 'div.list-videos div.item' matches: 24
GET https://neporn.com/latest-updates/2/ → 200; 'div.list-videos div.item' matches: 24
── check 1b: quick search (0)
NOTE: no --quick-search-url — … (no suggest endpoint; recorded above)
── check 2: video pages (5 URLs)
GET /video/4106/evil-angel-films-i-am-angela-ep-4-ciao-bella/ → 200; 'div.info-content' matches: 1
GET stream (…/4106_720p.mp4… ) → 206 video/mp4
GET … → 'div.related-videos div.item' matches: 24
… (5/5 pages 200, 5/5 streams 206 video/mp4, related rails 22–24 cards)
field 'tags': present on all 5 sampled pages
NOTE: no --video-actors-selector — exposure not asserted; FINDINGS must state whether the site exposes actors
NOTE: no --video-year-selector — exposure not asserted; FINDINGS must state whether the site exposes year
NOTE: no --video-duration-selector — exposure not asserted; FINDINGS must state whether the site exposes duration
── check 5: search ↔ load agreement
NOTE: no sampled video appears in the search page(s) — pick a --search-url whose query matches a sample video to exercise agreement
── check 6: LoadResponse completeness (code vs FINDINGS)
field 'recommendations': 2 · 'tags': 2 · 'plot': 2 · 'duration': 1 · 'year': 2 · 'actors': 2
RESULT: PASS
```

Invocation notes (the FINDINGS lines the flags require):

- **Quick search** — no distinct endpoint exists (above); `hasQuickSearch` stays `false`.
- **Home page 2** — `/latest-updates/` p1+p2, 24+24, **0** overlap.
- **Search page 2** — the provider's `from_videos=<page>` async URL yields a real page 2 (24 cards,
  **0** overlap with p1); both pages are passed as the two search URLs.
- **Check 5 — NOTE, not exercised.** The `milf` search draw (chosen for a clean disjoint
  two-page pagination assertion) contains none of the 5 verify samples, so the tool's search↔load
  comparison had no pair to compare. Proven directly instead — card from `/search/milf/` p1 vs the
  same video's load page:

  ```
  card  /video/31211/ginger-milf/
        strong.title  "Ginger MILF"
        img.thumb     https://cdn.neporn.com/contents/videos_screenshots/31000/31211/500x282/2.jpg
  load  h1 ownText     "Video: Ginger MILF"           → provider strips the "Video: " prefix
        og:title       "Ginger MILF"
        og:image       https://cdn.neporn.com/contents/videos_screenshots/31000/31211/preview.jpg
  ⇒ title equal (card == og:title == h1 minus prefix); poster differs by surface, by design
  ```

  The poster difference is the site's own card-vs-detail artwork split (card = a `500x282` video
  screenshot, detail = `preview.jpg`) — per-surface renders of one video, the same class as the
  recorded `-t`/`-n` cover-art variants, and a card-field question owned by the #632–#659 listing
  pass, not by this leg. The provider is faithful to both surfaces (`img.thumb` on cards,
  `og:image` on load).
- **Tags** — `div.info-content a[href*=/tags/]`, present on 5/5 sampled pages.
- **Actors / year / duration** — no assertable CSS selector (notes 3 and 5); exposure stated there.
- **Streams** — `flashvars`-only, so `--stream-url` overrides are position-matched to the 5 pages
  with the token-bearing URLs fetched fresh at invocation time (the `v-acctoken` is IP- and
  time-bound). `div.info-content` is the page element passed to `--stream-selector` for the
  page-shape count; it carries no stream itself.

---

## Gates

- `.pi/skills/audit-providers/scripts/check-findings.sh` → **PASS** (registry untouched in the diff, ADR-0013)
- `./gradlew Neporn:test` → **BUILD SUCCESSFUL** (all suites green, `shared/` tests included)
- `./gradlew Neporn:make` → **BUILD SUCCESSFUL**, clean `Neporn/build/Neporn.cs3` (161 445 B)
- `.pi/skills/verify-provider/scripts/verify.sh` (live) → **RESULT: PASS**
- No confirmed condition → **no code change, no version bump (Neporn stays 23)**

---

## Registry write (ADR-0013 — nothing in the PR diff)

Posted as a comment on #1455 that begins with `<!-- findings-registry-entry -->` followed by a
fenced json document in the delta shape `{providers: {Neporn: …}, last_runs: […]}` for the
orchestrator's `registry_sync`: the Neporn provider entry (`verdict` **and** `tier`, `last_deep`
2026-11-24, `drift_confirmed` 0, `chronic` false) and one `last_runs` entry with
`false_positives: []` and `escalation_tier: unavailable` (#1434).
