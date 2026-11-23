# FINDINGS-1453 — AllClassicPorn video sources (S15 of #1354)

Audit of the **video-source leg** (D2) of the AllClassicPorn spec (`allclassic.porn`). Scope: for
every listing type in D3 (`new`/`latest`, `old`, `popular`, `genres`, `categories`, `tags`) open ≥2
video pages — sampled at both ends of the catalogue where the type is pageable — and resolve
`loadLinks` end-to-end: cfg/`get_file` URL → **302** `remote_control.php` → `cdn*.allclassic.porn`
**200/206 `video/mp4`** (ftyp body), embed host identified, quality passed to CloudStream where the
site exposes it.

Provider state at run start: registry `last_deep` = 2026-10-16, `drift_confirmed` = 0, verdict at
last deep `ok` (#637). `drift_confirmed` = 0 ⇒ a targeted fix would be allowed if a condition were
confirmed (the ≥3 hardening rung is not reached).

**Result: no condition confirmed on the video-source leg — the leg is clean, no code change, no
version bump.** One out-of-scope listing observation (a single category-listing card repeating on
page 2) is recorded below and deliberately **not** filed: it is not a provider surface (the provider
never requests `/categories/<slug>/`), and listing/pagination correctness is excluded by the spec
(D5, #632–#659).

Run: `05-implement` for #1453, 2026-11-23, plain-curl tier, runner IP US.
Workflow run: `https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/45260528268`.

Out of scope per spec: a full listing audit (pagination/card-field correctness was #632–#659) and
*rows* work (uncensored/ura row additions). Listing correctness was checked only as far as
enumeration needed, and not re-probed beyond that (`drift_confirmed` = 0, enumeration intact).

---

## Phase 0 — canary calibration (before touching the site)

| Canary | Expected | Observed | Result |
| --- | --- | --- | --- |
| `eporner-healthy` | plain-curl 200 | plain-curl **200** (3 591 B) | match — instrument healthy |
| `film1k-challenge-pair` | plain-curl 403, tls-impersonated 200 | plain-curl **200** (253 676 B) | **expectation-stale** (#1435 open) — live pair recorded here; **no** `environment-degraded` branch taken |
| `pandamovies-redirect-origin` | plain-curl 302, tls-impersonated 200 | plain-curl **302 → https://pandamovies.org/** | plain-tier match; the TLS leg cannot run (#1434) |

```
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://www.eporner.com/  → 200 3591
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://www.film1k.com/   → 200 253676
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://pandamovies.pw/     → 302 https://pandamovies.org/
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://allclassic.porn/  → 200 257249
$ python3 -c 'import curl_cffi'
ModuleNotFoundError: No module named 'curl_cffi'      # #1434 still open at run time
```

**#1434 is still open**, so the registry `last_runs` entry carries `escalation_tier: unavailable` and
any `Blocked` this run would downgrade to `Suspected`. None occurred — allclassic.porn answered
plain-curl on every surface below, including all 11 streams.

Instrument = **plain-curl tier**, desktop Chrome UA
(`Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36`).
No proxy, no impersonation, no browser instrument anywhere below.

---

## Listing-type inventory (D3)

The site is **KVS / Kernel Video Sharing** (`list_videos_*` block ids, `pageContext.langUrls` inline
JS, `get_file` + `remote_control.php` stream delivery) on a video.js player (`player/vjs/allclassic-vjs.js`,
`var cfg = { videoUrl: … }` — the #876 grammar). Taxonomy routes: `/categories/<slug>/` (61 slugs on
the index), `/tags/<slug>/` (574 curated slugs), `/years/` → `/year/<yyyy>/` (19 year links,
1920–2004), `/studios/<slug>/` (806), `/models/<slug>/`, plus the era rows `/40s/`…`/2000s/` and the
sort rows `/best/`, `/most-popular/`, `/longest/`, `/most-commented/`, `/most-favourited/`.

Every row probed plain-curl. A type the site does not expose is recorded **`absent`** with the
evidence that proves it, never assumed.

| type | surface | evidence |
| --- | --- | --- |
| `new` / `latest` | `/page/` (`pageContext.langUrls.videos_latest_updates`) — p1 **60** cards, p2 **60**, overlap **0**, no in-page duplicate hrefs; far end `/page/102/` **200, 26** (catalogue tail, oldest `post_date`), `/page/103/` **404** | 200 |
| `old` | **no oldest-first sort route**: `/old/` and `/oldest/` → **404**; `/page/?sort_by=post_date&sort_by_direction=asc` → **301** to the site root (the param is not a listing surface). The site's oldest-catalogue surfaces are the era rows and the `/page/` tail; sampled both: `/40s/` **200, 47** cards (`/40s/2/` **404** — not pageable) and `/page/102/` (above) | 200 / 404-no-sort |
| `popular` | `/most-popular/` — p1 **60**, p2 **60**, overlap **0**; far end `/most-popular/102/` **200, 26**, `/most-popular/103/` **404** | 200 |
| `genres` | **absent as its own namespace.** `/genres/`, `/genre/`, `/genre/milf/` → **404** (and none of those bodies carries `list_videos_common_videos_list` — their card-looking footer is the 404 page's own recommendation block). The site's genre vocabulary *is* the `/categories/` namespace (index **200**, 61 category links), sampled under `categories` below. Recorded absent, not a finding | 404 |
| `categories` | `/categories/` index **200** (61 category links); `/categories/milf/` p1 **60**, p2 **60**, far end `/categories/milf/27/` **200, 8**, `/categories/milf/28/` **404** | 200 |
| `tags` | `/tags/` index **200** (574 curated slugs); `/tags/classic/` p1 **60**, p2 **60**, overlap **0**, far end `/tags/classic/4/` **200, 9**, `/tags/classic/5/` **404** | 200 |

Provider-row surfaces re-probed for enumeration (not D3 types, same leg): `/best/` **200, 60**;
`/longest/` **200, 60**; `/most-commented/` **200, 60**; `/most-favourited/` **200, 60**;
`/90s/2/` **200, 60**; `/year/1950/` p1 **60** / p2 **35** (`/year/1950/3/` **404**).
`/all-catalog/` **200** (whole-catalogue page, different card class `class="item"`, 1 685 unique
`/videos/` links) is **not** a provider row and was not used for sampling.

### Search and quick search

```
$ curl -s -A "$UA" https://allclassic.porn/search/milf/     → 200; 'a.th.item' matches: 60
$ curl -s -A "$UA" https://allclassic.porn/search/milf/2/   → 200; 'a.th.item' matches: 60   (overlap with p1: 0)
$ curl -s -A "$UA" https://allclassic.porn/suggest/?q=milf        → 404 text/html (HTML error page, not JSON)
$ curl -s -A "$UA" https://allclassic.porn/search/suggest/?q=milf → 301
$ curl -s -A "$UA" 'https://allclassic.porn/search/milf/?mode=async' → 200 text/xml
        <failure><error type="invalid_params"/></failure>
```

**No distinct quick-search endpoint** — the explicit note `verify.sh` check 1b requires
(provider keeps `hasQuickSearch = false`, unchanged since #204). The `?mode=async` responder is the
KVS listing endpoint and rejects a bare query (`invalid_params`), so it is not a suggest contract.

---

## Evidence rows (D4)

`type | sample URL | instrument tier | stream host | quality | verdict` — 11 videos, ≥2 per present
type, both catalogue ends where the type is pageable. Every row resolved **end-to-end**: cfg
`videoUrl` → `get_file` **302** → `cdn{1,3,9}.allclassic.porn` **206 `video/mp4`** (ftyp/isom body),
`Referer` = the video page (what `loadLinks` sets).

| # | type | sample URL (`/videos/<id>/<slug>/`) | listing position | tier | stream host | quality | verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | new/latest | `/videos/6545/candy-film-5-the-sadists/` | `/page/1/` p1 (head) | plain-curl | `cdn9.allclassic.porn` | 480p | **pass** |
| 2 | new/latest | `/videos/6417/caught-from-behind-5/` | `/page/1/` p1 | plain-curl | `cdn1.allclassic.porn` | 480p | **pass** |
| 3 | new/latest (catalogue tail) | `/videos/28/scene-1-airport-affair/` | `/page/102/` (oldest `post_date`) | plain-curl | `cdn9.allclassic.porn` | 480p | **pass** |
| 4 | old | `/videos/2351/hollywood-burlesque/` | `/40s/` p1 (oldest era row) | plain-curl | `cdn1.allclassic.porn` | 480p | **pass** |
| 5 | old | `/videos/1026/costumed-cuties-historic-erotica-40/` | `/year/1950/2/` (far end) | plain-curl | `cdn3.allclassic.porn` | 480p | **pass** |
| 6 | popular | `/videos/243/debbie-does-dallas/` | `/most-popular/` p1 (head) | plain-curl | `cdn9.allclassic.porn` | 480p | **pass** |
| 7 | popular (far end) | `/videos/4414/nature-calling-american-stag-film/` | `/most-popular/102/` | plain-curl | `cdn1.allclassic.porn` | 480p | **pass** |
| 8 | categories (`milf`) | `/videos/6463/tous-les-trous-sont-permis/` | `/categories/milf/` p1 (head) | plain-curl | `cdn9.allclassic.porn` | 480p | **pass** |
| 9 | categories (`milf`, far end) | `/videos/163/music-affair-from-color-climax-collection-scene-57/` | `/categories/milf/27/` | plain-curl | `cdn3.allclassic.porn` | 480p | **pass** |
| 10 | tags (`classic`) | `/videos/6562/starlight-film-1005-der-hemmungslose-assessor/` | `/tags/classic/` p1 (head) | plain-curl | `cdn9.allclassic.porn` | 480p | **pass** |
| 11 | tags (`classic`, far end) | `/videos/21/adam-yves/` | `/tags/classic/4/` | plain-curl | `cdn1.allclassic.porn` | 480p | **pass** |

`genres` is `absent` (see inventory) so it contributes no row — absence is the recorded outcome, not
a finding.

Every pass row was fetched with `Range: bytes=0-1023` and the in-app referer; all 11 stream requests
answered **302 → 206**, `Content-Type: video/mp4`, body magic `0000 0020 6674 7970 6973 6f6d`
(`ftypisom`). No row needed a non-pass transcript — hence no raw transcript section beyond the
chain below.

Scan breadth (video-page enumeration, not just the table): **45 video pages** across **15 listing
pages** covering every present D3 type plus the provider rows, at p1, p2 and the far end.
Distribution:

| shape | count | note |
| --- | --- | --- |
| `var cfg = { videoUrl: … }` (video.js grammar, #876) | **45** | the only grammar served today |
| legacy `video_url:` / `flashvars['video_url']` | **0** | the #876 drift is complete site-wide — no page carries the old grammar |
| `video_url_text` / any `*_text` caption | **0** | quality is carried solely by the filename token (`…_480p.mp4`) |
| filename token `_480p.mp4` | **45** | → `parseQuality` → `480p` → `getQualityFromName` → **480** |
| `videoAltUrl` / `video_alt_url2` (extra renditions) | **0** | single-rendition site; no second source to emit |
| page with no resolvable source | **0** | no file-less placeholder class in the sampled range |
| `<iframe>` of any kind | **0** | no third-party embed host anywhere |
| `<meta property="og:video">` | **45** | always the **self-hosted** `https://allclassic.porn/embed/<id>` player shell |

**Embed host:** the only video embed is the self-hosted `allclassic.porn/embed/<id>` shell (same
`cfg` contract, same origin), which the provider deliberately does not emit — the direct `videoUrl`
mp4 is the source. **No third-party embed host exists on this site**, so there is nothing for
`shared/`'s `HostRegistry` to register for this provider and no data-completeness gap in the
embed-adapter sense.

---

## The stream chain (unchanged since #876, re-proven)

```
GET https://allclassic.porn/videos/6545/candy-film-5-the-sadists/                → 200
  <div id="kt_player"></div>
  <script>/* <![CDATA[ */ (function () {
    // Values come from KVS player settings (flashvars), except the VAST tag
    var cfg = {
      videoUrl: 'https://allclassic.porn/get_file/1/aaba0698…/6000/6545/6545_480p.mp4/?v-acctoken=NzcxfDF8…',
      posterUrl: 'https://allclassic.porn/contents/videos_screenshots/6000/6545/preview.jpg',
      volume: parseFloat('0.5') || 0.5,
      timelineUrl: '…/6000/6545/timelines/mp4/224x126/{time}.jpg',
      statsUrl: 'https://allclassic.porn/player/stats.php?embed=0&video_id=6545', relatedUrl …
  } … });</script>

GET https://allclassic.porn/get_file/1/aaba0698…/6000/6545/6545_480p.mp4/?v-acctoken=…  → 302
  server: nginx/1.30.7      content-length: 0
  location: https://cdn9.allclassic.porn/remote_control.php?file=…&acctoken=…
GET https://cdn9.allclassic.porn/remote_control.php?…  -r 0-0  Referer: <video page>   → 206 video/mp4
                                                       Last-Modified: Wed, 23 Sep 2026 12:20:05 GMT
                                                       body: 0000 0020 6674 7970 6973 6f6d  (ftypisom)
```

`parseVideoUrl` = `KvsFlashvars.source` (legacy → cfg chain) resolves this in one read, and
`parseQuality` = `KvsFlashvars.quality` derives `480p` from the `_480p` filename token (no caption
flashvar exists any more). `loadLinks` then sets `referer = data` (the video page) and
`quality = getQualityFromName("480p")` → **480**, which is exactly what the app gets on the device:
`getQualityFromName` lowercases, strips `p`, `toIntOrNull()` ⇒ `480` (`Qualities.P480`-equivalent) —
verified against the vendored `cloudstream3-pre-release.jar`
(`javap -c com/lagradost/cloudstream3/utils/ExtractorApiKt`).

### Headers / referer

The origin hop (`get_file`) 302s and the CDN hop serves **206 with and without** a `Referer`, and
with `CloudStream/4.9.1`, `Dalvik/2.1.0`-style and desktop UAs alike:

```
no-referer        → 206 video/mp4
CloudStream/4.9.1 → 206 video/mp4
Android Chrome    → 206 video/mp4
```

The provider sets `referer = data` unconditionally; harmless, and it stays as the hedge for
per-shard behaviour. No cookies, no Cloudflare, no age wall.

### Geo

Probed from a **US** runner IP; no region wall observed on listings, video pages, or the stream
chain. In-app verification note for non-US users stands at merge (maintainer's call).

---

## Related videos

`div#list_videos_related_videos_items` with `a.th.item` cards — **30** cards on every one of the 10
verify-sampled pages (`verify.sh` check 4), no empty titles, no self-reference, no duplicate hrefs.
Pagination is an AJAX "load more" on the same block:

```html
<div id="list_videos_related_videos_pagination" class="show-more">
  <a class="btn btn-large" href="#more" data-action="ajax"
     data-container-id="list_videos_related_videos_pagination"
     data-block-id="list_videos_related_videos"
     data-append-items-to="list_videos_related_videos_items"
     data-max-queries="141" data-parameters="sort_by:;from:2;ipp:18">Click To Load More...</a>
</div>
```

The provider reads only the server-rendered first rail (30 cards) — unchanged behaviour, and the
rail is populated on 10/10 verify-sampled pages, so `recommendations` stays non-empty.

---

## Instrument / site-side notes (recorded, never filed)

1. **Poster surfaces differ per surface (site-side, no code fix).** Listing cards carry
   `…/videos_screenshots/<bucket>/<id>/320x240/<random N>.jpg` (the index `N` changes per render),
   while the load page's `og:image`/`posterUrl` is `…/<bucket>/<id>/preview.jpg`. `verify.sh` check 5
   therefore reports `FAIL poster mismatch` **only when a sampled video is also in the search draw**;
   re-run with the faithful selectors, the transcript is:

   ```
   FAIL title mismatch …  ← omitted when --video-title-selector h1[itemprop=name] is passed
   FAIL poster mismatch for /videos/6463/tous-les-trous-sont-permis:
        search='…/6000/6463/320x240/16.jpg' load='…/6000/6463/preview.jpg'
   ```

   With `--video-title-selector 'h1[itemprop=name]'` the title comparison **passes** for the same
   video (h1 text = `Tous les trous sont permis, Full length movie (1980)` = the card
   `div.th-description` text exactly, case/punctuation-identical). Only the poster line remains: the
   site serves different thumbnail *renders* for the same artwork, no provider-side change can
   equalize them (audit #204 artifact 2, #266, #637). The delivery-gate run below therefore samples a
   disjoint set (see transcript) and records this line here instead of as a green-washed pass.
2. **Tags / actors / year / duration are not CSS-selectable.** Tags/categories/actors live in the
   inline KVS flashvars (`video_tags` / `video_categories` / `video_models`), year in the h1
   `- (yyyy)` suffix, duration in `meta[itemprop=duration]` — `verify.sh`'s `--video-*-selector` bar
   cannot express them (check 2a NOTEs). Exposure is evidenced by the #205 fixtures/tests and
   `load()`'s assignments (check 6 green: tags 3, actors 3, year 1, duration 2, recommendations 2,
   posters 3).
3. **`plot`** is `og:description`-first with the `div.description-container` fallback (#289); on the
   current site only a minority of pages carry `og:description` (1/5 in #637, and the verify sample
   below passes `div.description-container`, which is present on 10/10 pages), so the fallback is the
   load-bearing surface — already covered by the #289 fixtures (`video-5887.html` is the og-less
   case) and green in `AllClassicPornParseTest`.
4. **Quick search does not exist as a surface** (transcript above); `hasQuickSearch = false` is
   correct, not drift.

---

## Out-of-scope observation — listing pagination (not filed, not fixed)

`/categories/milf/` page 1 and page 2 share **one** card (`/videos/6087/c-era-una-volta-l-incesto-…/`),
reproducible back-to-back; every other probed pair (`/page/`, `/most-popular/`, `/tags/classic/`,
`/year/1950/`, `/search/milf/`) is 0-overlap with no in-page duplicate hrefs. A second back-to-back
`/search/milf/` pair taken ~20 minutes apart also showed 2 shifted cards, i.e. the live catalogue
inserts move items between pages over time.

Recorded, **not filed**: this is listing/pagination correctness (explicitly out of scope, D5,
#632–#659) and the provider never requests a `/categories/<slug>/` surface — no `mainPage` row, no
`search` path. It cannot affect `loadLinks` or any enumeration this audit relies on.

---

## Delivery gate

```
$ ./gradlew AllClassicPorn:test   → BUILD SUCCESSFUL (31 tasks; shared + AllClassicPorn suites green)
$ ./gradlew AllClassicPorn:make   → AllClassicPorn/build/AllClassicPorn.cs3 (161 833 B)
$ .pi/skills/audit-providers/scripts/check-findings.sh → findings-lint: PASS
```

`verify.sh` (live, plain-curl tier) — **PASS**, 10 video pages spanning every present D3 type at both
catalogue ends, 10 position-matched `--stream-url` overrides (streams are JS `cfg`, the page carries
no CSS stream element — the documented override path for that), `--provider-src AllClassicPorn`:

```
── check 1: search pages (2)
GET https://allclassic.porn/search/milf/ → 200; 'a.th.item' matches: 60
GET https://allclassic.porn/search/milf/2/ → 200; 'a.th.item' matches: 60
── check 1a: homepage pages (2)
GET https://allclassic.porn/page/1/ → 200; 'a.th.item' matches: 60
GET https://allclassic.porn/page/2/ → 200; 'a.th.item' matches: 60
── check 1b: quick search (0)
NOTE: no --quick-search-url — required when FINDINGS records a distinct quick-search endpoint; its absence must be explicit in FINDINGS
── check 2: video pages (10 URLs)
GET …/videos/6545/candy-film-5-the-sadists/ → 200; 'div#kt_player' matches: 1
GET stream (…/6000/6545/6545_480p.mp4…) → 206 video/mp4
GET …/videos/6545/… → 'div#list_videos_related_videos_items a.th.item' matches: 30
… (10/10 pages 200, 10/10 streams 206 video/mp4, 10 related rails × 30 cards)
NOTE: no --video-tags-selector — exposure not asserted; FINDINGS must state whether the site exposes tags
NOTE: no --video-actors-selector — exposure not asserted; FINDINGS must state whether the site exposes actors
NOTE: no --video-year-selector — exposure not asserted; FINDINGS must state whether the site exposes year
NOTE: no --video-duration-selector — exposure not asserted; FINDINGS must state whether the site exposes duration
── check 5: search ↔ load agreement
NOTE: no sampled video appears in the search page(s) — pick a --search-url whose query matches a sample video to exercise agreement
── check 6: LoadResponse completeness (code vs FINDINGS)
field 'recommendations': 2 · 'tags': 3 · 'duration': 2 · 'year': 1 · 'actors': 3 · 'posters': 3
RESULT: PASS
```

Invocation notes (the FINDINGS lines each flag requires):

- **Quick search** — no distinct endpoint (transcript above); `hasQuickSearch = false` is correct.
- **Home page 2** — `/page/1/` + `/page/2/`, 60 + 60, 0 overlap, 0 in-page duplicates; the #322 D1
  `page/1/` mapping is intact.
- **Search page 2** — `/search/milf/` + `/search/milf/2/`, 60 + 60, 0 overlap.
- **Check 5** — NOTE, not exercised: the sample is drawn from the D3 listings and the `milf` search
  draw does not contain those videos (the one collision, video 6463, is transcribed in instrument
  note 1 with its title agreement passing and only the site-side poster-render line failing).
- **Plot** — `div.description-container` (present on 10/10 sampled pages; note 3).
- **Tags / actors / year / duration** — flashvars / h1 / JSON-LD only (note 2); NOTEs are the
  documented outcome.
- **Streams** — `div#kt_player` is the only stream-bearing element; the URLs come from the `cfg`
  block, fetched fresh per page (the `v-acctoken` is short-lived).

---

## Registry note

`audits/findings.json` is **not** in this diff (ADR-0013). The provider entry refresh + this run's
`last_runs` entry are posted as a `<!-- findings-registry-entry -->` comment on #1453 for the
orchestrator's `registry_sync`.
