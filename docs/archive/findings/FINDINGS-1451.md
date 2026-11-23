# FINDINGS-1451 — Javbangers video sources (S13 of #1354)

Audit + fix of the **video-source leg** (D2) of the Javbangers spec (`www.javbangers.com`).
Scope: for each listing type in D3 (`new`/`latest`, `old`, `popular`, `genres`,
`categories`, `tags`) open ≥2 video pages — sampled at both ends of the catalogue where the
type is pageable — and resolve `loadLinks` **end-to-end**: `flashvars` stream URL → `get_file`
302 → CDN origin 206, embed host identified (self-hosted `get_file`, no third-party embed),
quality passed to CloudStream where the site exposes it.

Provider state at run start: registry `last_deep` 2026-10-16, `drift_confirmed` = 1, verdict at
last deep `ok`. `drift_confirmed` = 1 ⇒ a **targeted** fix is allowed (the ≥3 hardening rung is
not reached); a confirmed condition is fixed **in this run** (D1). One confirmed defect was
found and fixed TDD-first at a pure `Parse` function.

Run: `05-implement` for #1451, 2026-11-23, plain-curl tier, runner IP US.
Workflow run: `https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/45182708392`.

Out of scope per spec: #1429 (rows work — the uncensored set), and listing/card correctness
beyond what enumeration needs.

---

## Phase 0 — canary calibration (before touching the site)

| Canary | Expected | Observed | Result |
| --- | --- | --- | --- |
| `eporner-healthy` | plain-curl 200 | plain-curl **200** (314 972 B) | match — instrument healthy |
| `film1k-challenge-pair` | plain-curl 403, tls-impersonated 200 | plain-curl **200** (214 913 B) | **expectation-stale** (#1435 open) — its live pair is recorded here; **no** `environment-degraded` branch taken |
| `pandamovies-redirect-origin` | plain-curl 302, tls-impersonated 200 | plain-curl **302 → https://pandamovies.org/** | plain-tier match; TLS leg cannot run (#1434) |

```
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://www.eporner.com/    → 200 314972
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://www.film1k.com/    → 200 214913
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://pandamovies.pw/      → 302 https://pandamovies.org/
$ python3 -c 'import curl_cffi'
ModuleNotFoundError: No module named 'curl_cffi'      # #1434 still open at run time
```

**#1434 is still open**, so the registry `last_runs` entry carries
`escalation_tier: unavailable` and every `Blocked` this run would downgrade to `Suspected`.
No row below is `Blocked` or `Suspected` — the site answered plain-curl everywhere.

Instrument = **plain-curl tier**, desktop Firefox UA
(`Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0`). No proxy, no
impersonation, no browser instrument anywhere below.

---

## Listing-type inventory (D3)

The site is KVS (`porntrex-box` wrapper, `list_videos_*` block ids, `kt_player` + inline
`flashvars`) and exposes **one** taxonomy namespace — `/categories/<slug>/`. Three of the six
D3 types are genuinely absent; each is recorded here as `absent`, never as a finding.

| type | surface | evidence |
| --- | --- | --- |
| `new` / `latest` | `/latest-updates/` — p1 **30** cards, p2 **30** cards; far end `/latest-updates/4444/` **200, 30** cards, `/latest-updates/9999/` **404**. Cross-page overlap is **site-side and draw/state dependent**: 0/30 at 13:21, then a deterministic **5/30** (`412659`, `412665`, `412666`, `412667`, `412670`) on two consecutive probes at 14:05 — the page-2 window pads/rotates back into page 1's tail | 200 |
| `popular` | `/most-popular/` — p1 **24**, p2 **24**, **0** overlap (two probes); far end `/most-popular/4444/` **200, 24** (0 overlap with p1/p2) | 200 |
| `categories` | `/categories/<slug>/` — `milf` p1 24 / p2 24 (**0** overlap), far end `/categories/milf/200/` **200, 24** cards, `/categories/milf/2000/` **404**; `uncensored` p1 24 / p2 24 (**15/24** overlap — the small-catalogue last-page wrap-pad class from #656) | 200 |
| `tags` | **absent.** `/tags/` (nav item) is a 200 **empty container**: `div.content.page-tags .main-container` is empty — 0 cards, 0 tag links (intermittent `502` 150 B from the origin, 3 probes: 200/200/502 — origin flakiness, not a surface). `/tags/<slug>/` → **404** on `milf`, `uncensored`, `creampie`, `japanese`, `gangbang`, `big-tits`; `/tag/<slug>/` → **404**. Video pages carry a `Tags:` row **with href-less anchors** — the site has the vocabulary but no tag listing route | 200 (empty) / 404 |
| `genres` | **absent.** `/genres/` and `/genres/milf/` → **404**. The site's genre-equivalent taxonomy *is* the single `/categories/` namespace (`/categories/` index 200 lists every slug); there is no second dimension (`/channels/` and `/models/` are studio/performer directories with 0 video cards in the server-rendered grid) | 404 |
| `old` | **absent.** `/old/`, `/oldest/` → **404**; ordering params are silently ignored — `/latest-updates/?sort_by=video_id&sort_dir=asc` is **byte-identical** to `/latest-updates/` (`md5 913882fd…`), as are `?sort=old`, `?order=old`, `?sort_by=post_date&sort=asc`. No oldest-first route or parameter exists | 404 / inert |

Pagination remains path-based (`{base}/{N}/`) exactly as the provider codes it (`pageUrl`), and
search pagination is unchanged: `/search/isd-147/2/` and `/search/mdyd/2/` → **404** (standing
#475 contract; provider `hasNext = false`).

### Cross-page overlap is a site-side page-window property (not a provider defect)

Measured on five rows in one pass (2026-11-23 ~14:05, p1 vs p2, unique card ids):
`most-popular` 24+24 overlap **0** · `categories/milf` 24+24 overlap **0** ·
`top-rated` 24+24 overlap **2** · `latest-updates` 30+30 overlap **5** ·
`categories/uncensored` 24+24 overlap **15**. The last two are the KVS grid-padding class #656
already recorded for small categories (uncensored) — and `latest-updates` moved from 0 to a
*stable* 5 between 13:21 and 14:05 with no provider change, i.e. the page-2 window is served
from a slightly stale offset and pads back into page 1. The provider mirrors the site (path
pagination, `hasNext = items.isNotEmpty()`); enumeration is unaffected, so this is recorded,
never filed. `verify.sh` check 1a (cross-page duplicate bar) was therefore run against
`/most-popular/` p1+p2 (the other provider row, 0 overlap); the `latest-updates` pair would
falsely FAIL on the site's own repeat.

---

## Evidence rows (D4)

`type | sample URL | instrument tier | stream host | quality | verdict`

At least two videos per **present** type; the far-end page is named for pageable types.

| # | type | sample URL | listing position | tier | stream host | quality | verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | new/latest | `/video/412669/beauty-brazilian-in-silver` | `/latest-updates/` p1 (head) | plain-curl | `get_file` → `origin13-direct.cdntrex.com` | **480p + 720p + 1080p** (3 renditions) | **pass** — all three 206 video/mp4 |
| 2 | new/latest | `/video/412685/beauty-prostitute-milf-on-gangbang` | `/latest-updates/` p1 | plain-curl | `get_file` → `origin25-direct.cdntrex.com` | 480p + 720p + 1080p | **pass** |
| 3 | new/latest | `/video/412690/i-doll-31` | `/latest-updates/` p1 | plain-curl | `get_file` → `origin13-direct.cdntrex.com` | **single rendition, no caption** → `Unknown` (site exposes no label) | **pass** |
| 4 | new/latest (catalogue far end) | `/video/135274/fc2-ppv-1723598-jav-uncensored` | `/latest-updates/4444/` | plain-curl | `get_file` → `origin25-direct.cdntrex.com` | 480p + 720p + 1080p | **pass** |
| 5 | new/latest (catalogue far end) | `/video/135241/fc2-ppv-1742419-jav-uncensored` | `/latest-updates/4444/` | plain-curl | `get_file` → `origin13-direct.cdntrex.com` | 480p + 720p | **pass** |
| 6 | popular | `/video/19376/bakky-bks-017-16` | `/most-popular/` p1 | plain-curl | `get_file` → `origin25-direct.cdntrex.com` | single rendition, no caption → `Unknown` | **pass** |
| 7 | popular | `/video/110229/fc2-ppv-15089782` | `/most-popular/` p1 | plain-curl | `get_file` → `origin24-direct.cdntrex.com` | 480p + 720p (+ 1080p per flashvars scan) | **pass** |
| 8 | popular | `/video/2413/rtp-071-…` | `/most-popular/` p2 | plain-curl | `get_file` → `origin13-direct.cdntrex.com` | 480p + 720p | **pass** |
| 9 | popular | `/video/117920/fc2-ppv-1591233` | `/most-popular/` p2 | plain-curl | `get_file` → `origin25-direct.cdntrex.com` | 480p + 720p (+1080p per scan) | **pass** |
| 10 | categories (`milf`) | `/video/411516/elle-price-message-source` | `/categories/milf/` p1 | plain-curl | `get_file` → `origin11-direct.cdntrex.com` | 480p + 720p | **pass** |
| 11 | categories (`milf`, far end) | `/video/251928/gvh-558-english-subtitle-the-single-mother-clip-1` | `/categories/milf/200/` | plain-curl | `get_file` (`/get_file/8/`) → cdntrex origin | 480p + 720p + 1080p | **pass** |
| 12 | categories (`milf`, far end) | `/video/250680/isd-147-a-rice-farmer-s-busty-wife-shiori-mizuki-who-pl` | `/categories/milf/200/` | plain-curl | `get_file` (`/get_file/9/`) → cdntrex origin | 480p + 720p | **pass** |
| 13 | categories (`uncensored`) | `/video/412653/japanese-tight-pussies-attractive-japanese-redhead-takes-in-her-lover-s-veiny-dick` | `/categories/uncensored/` p1 | plain-curl | `get_file` → `origin13-direct.cdntrex.com` | 480p + 720p + 1080p | **pass** |
| 14 | categories (`uncensored`, far end) | `/video/383546/girls-of-hell-i-tie-her-up-and-make-her-squirt-peti` | `/categories/uncensored/200/` | plain-curl | `get_file` → `origin11-direct.cdntrex.com` | 480p + 720p + 1080p | **pass** |
| 15 | categories (`milf` p1 head) | `/video/412659/gvg-130-unc` | `/categories/milf/` p1 | plain-curl | **no player** — 0 sources (site-side placeholder import, standing #656 record) | — | **pass (provider chain: `loadLinks` → false, correctly)** |

Every pass row was fetched with `Range: bytes=0-2047` and the app's referer:

```
206 video/mp4, Content-Range present, ftyp magic 00000020 66747970 69736f6d
```

Scan breadth: **37** video pages enumerated across the six surfaces; **8** were file-less
placeholder imports (no `flashvars`, no `kt_player`, no `get_file` — real `og:image`/`og:title`,
real `block-details`, related list present; the standing #656 site-hygiene class), **26** of the
**29** file-bearing pages carried two or more renditions, **18** carried the third
(`video_alt_url2`, 1080p). Single-rendition pages (`412690`, `412680`, `19376`) expose no
`*_text` caption at all → `Unknown` is the correct quality, not a defect.

### Raw transcript — the referer requirement (unchanged, re-verified)

The origin hop still **403s without the site referer**; the provider has always shipped
`referer = mainUrl`, so the chain works. Re-verified on `412669` (3/3 renditions) and `412690`:

```
GET https://www.javbangers.com/get_file/4/f5497c…/412000/412669/412669_1080p.mp4/?v-acctoken=…   → 302
  Location: https://origin13-direct.cdntrex.com/remote_control.php?file=…&acctoken=…
GET origin13-direct.cdntrex.com/remote_control.php?…  -r 0-2047  Referer: https://www.javbangers.com/  → 206 video/mp4
GET origin13-direct.cdntrex.com/remote_control.php?…  -r 0-2047  no Referer                              → 403 text/html
GET origin13-direct.cdntrex.com/remote_control.php?…  -r 0-2047  Referer: https://jav.cdntrex.com/       → 403 text/html
```

`get_file` URLs are short-lived and IP/UA-bound: a URL replayed from a page fetched a couple of
minutes earlier already 404s at `get_file` itself (observed once this run — the provider always
fetches the page and plays immediately, so this never bites the app). Origin shards rotate
(`origin11`, `origin13`, `origin24`, `origin25` — all `-direct.cdntrex.com`), and `get_file`
path shards vary (`/get_file/4|6|8|9/`); nothing provider-side depends on either.
`origin24-direct` answered **206 even without** the referer on `110229` — per-shard behaviour,
so the referer stays unconditional.

### The placeholder class (re-confirmed, not a finding)

`/video/412659/gvg-130-unc` (the head of `/categories/milf/` p1) is one of the file-less
imports:

```
GET https://www.javbangers.com/video/412659/gvg-130-unc  → 200, 78 699 B
counts: flashvars 0 · kt_player 0 · get_file 0 · <iframe 6 · og:image 1 · block-details 1 · related_videos_items 1 · Tags: 1 · Submitted: 1
```

`load()` parses (title/poster/tags/year all present), `loadLinks` returns `false` and the app
shows “no streams” — exactly what the site's own player region shows. Site content hygiene
(delisted/dummy imports), never selector drift; recorded, never filed.

---

## The confirmed defect — the third rendition (`video_alt_url2`) was dropped

`loadLinks` hardcoded a two-key list:

```kotlin
val sources = listOf("video_url", "video_alt_url").mapNotNull { … }   // ← before
```

KVS now serves a **third** rendition on most videos, and the shared grammar already knows it
(`KvsFlashvars.URL_KEYS = listOf("video_url", "video_alt_url", "video_alt_url2")`) — the
provider just never asked for it. **18 of the 29** file-bearing pages sampled this run carry
`video_alt_url2` (`video_alt_url2_text: '1080p'`, `video_alt_url2_hd: '1'`), so on those the app
never got the best available quality. Verbatim flashvars (fixture
`Javbangers/src/test/resources/flashvars_412669.js`):

```
video_url: 'https://www.javbangers.com/get_file/4/4897206…/412000/412669/412669.mp4/?v-acctoken=…',
video_url_text: '480p',
video_alt_url: '…/412000/412669/412669_720p.mp4/?v-acctoken=…',
video_alt_url_text: '720p',
video_alt_url_hd: '1',
video_alt_url2: '…/412000/412669/412669_1080p.mp4/?v-acctoken=…',
video_alt_url2_text: '1080p',
video_alt_url2_hd: '1',
```

All three renditions answer `302 → origin → 206 video/mp4` — the dropped one is real media, not
a placeholder URL. This is a data-completeness defect on the video-source leg (D2: “quality
passed to CloudStream where the site exposes it”), not a selector swap.

### Fix (TDD-first, red → green at a pure Parse function)

Red first (`Javbangers:test` → `Unresolved reference 'sources'`), then green after the
implementation.

1. **`JavbangersParse.sources(flashvars)`** — walks the shared `KvsFlashvars.URL_KEYS` list
   (`video_url`, `video_alt_url`, `video_alt_url2`), returning `(label, url)` pairs in key order
   (base → best). Label comes from `KvsFlashvars.quality(...)`: the `<key>_text` caption when
   present, else the filename's `_NNNp` token; **null** (→ `Unknown`) when the site exposes
   neither — never an invented quality. The `startsWith("http")` filter stays
   Javbangers-specific: a non-url value (KVS encrypted/shifted shape) is a page this provider
   must not emit.
2. **`loadLinks`** — emits every returned pair (it previously rebuilt the URL/label lookup
   inline); referer `mainUrl`, quality `getQualityFromName(label)` unchanged.
3. **Version** 22 → 23.

Root cause is provider-local (the shared key list already carried `video_alt_url2`), so **no
`shared/` change and no other provider bump**.

Tests (`Javbangers:test`, **16 tests / 0 failures**; 6 new, all green):

- `all three renditions parse in key order with their captions` (verbatim `412669` fixture →
  `480p`, `720p`, `1080p` in key order) ·
  `two-rendition page yields two sources` ·
  `caption-less single source keeps a null label` (the `412690` shape) ·
  `filename token supplies the label when the caption is absent` ·
  `non-http value is not a source` (encrypted/shifted shape) ·
  `blank or absent flashvars yields no sources`

---

## Pipeline verification — `verify.sh` (live): **PASS**

Two search pages (two queries — search has no page 2, #475), two home pages (`/most-popular/`
p1+p2 — the `latest-updates` pair currently pads 5 cards back into page 1, site-side, see
above), eight live video pages spanning every present listing type and both catalogue ends,
eight position-matched `--stream-url` overrides (flashvars are JS-built; the page carries no
CSS stream element), `--provider-src Javbangers`:

```
── check 1: search pages (2)
GET https://www.javbangers.com/search/isd-147/ → 200; 'div.video-item' matches: 74
GET https://www.javbangers.com/search/mdyd/ → 200; 'div.video-item' matches: 3
── check 1a: homepage pages (2)
GET https://www.javbangers.com/most-popular/ → 200; 'p.inf' matches: 24
GET https://www.javbangers.com/most-popular/2/ → 200; 'p.inf' matches: 24
── check 1b: quick search (0)
NOTE: no --quick-search-url — required when FINDINGS records a distinct quick-search endpoint; its absence must be explicit in FINDINGS
── check 2: video pages (8 URLs)
GET …/video/412669/beauty-brazilian-in-silver → 200; 'div.block-details' matches: 1
GET stream (…/get_file/4/f5497c98…/412000/41… [1080p]) → 206 video/mp4
GET …/video/412685/beauty-prostitute-milf-on-gangbang → 200
GET stream (…/412685_720p.mp4… ) → 206 video/mp4
GET …/video/135274/fc2-ppv-1723598-jav-uncensored → 200
GET stream (…/135274_1080p.mp4… ) → 206 video/mp4
GET …/video/19376/bakky-bks-017-16 → 200
GET stream (…/19376/19376.mp4… ) → 206 video/mp4
GET …/video/2413/rtp-071-… → 200
GET stream (…/2413_720p.mp4… ) → 206 video/mp4
GET …/video/251928/gvh-558-…-clip-1 → 200
GET stream (…/get_file/8/60fdb8ec…/251000/25… [1080p]) → 206 video/mp4
GET …/video/412653/japanese-tight-pussies-… → 200
GET stream (…/412653.mp4… ) → 206 video/mp4
GET …/video/250680/isd-147-… → 200
GET stream (…/250680_720p.mp4… ) → 206 video/mp4
field 'tags': present on all 8 sampled pages
NOTE: no --video-actors-selector — exposure not asserted; FINDINGS must state whether the site exposes actors
NOTE: no --video-year-selector — exposure not asserted; FINDINGS must state whether the site exposes year
NOTE: no --video-duration-selector — exposure not asserted; FINDINGS must state whether the site exposes duration
── check 5: search ↔ load agreement
─ (250680 appears in /search/isd-147/ — title + poster agree, no mismatch printed)
── check 6: LoadResponse completeness (code vs FINDINGS)
field 'recommendations': 2 assignment(s)      field 'tags': 4 assignment(s)
field 'plot': 1 assignment(s)                 field 'year': 3 assignment(s)
RESULT: PASS
```

Invocation notes (per the skill's FINDINGS rule):

- **Quick search** — absent (no distinct suggest endpoint; the header form's `data-url` is an
  on-submit redirect to `/search/%QUERY%/`, #656 record). `hasQuickSearch` stays `false`; the
  script NOTEs, as required.
- **Home page 2** — the provider's rows are all path-paginated. `latest-updates` p1+p2 currently
  share 5 cards (site-side page-window pad, table above), so check 1a ran on `/most-popular/`
  p1+p2 (0 overlap); `p.inf` is the home card selector for the same reason as search (clean title
  text, no badge run-on).
- **Search page 2** — does not exist (`/search/<q>/2/` **404** on `isd-147` and `mdyd`, re-probed
  this run). Two different queries stand in for the two `--search-url` pages;
  `--search-title-selector 'p.inf a'` is needed because a card's default inner text carries the
  view/duration/age badges.
- **Posters** — listing thumbs live in `img.cover[data-original]` (a lazy-load attribute the
  script's fallbacks don't cover), so card posters read empty (uniform NOTE, #656 record).
  Direct parse: `/search/isd-147/` card for `250680` →
  `https://jav.cdntrex.com/contents/videos_screenshots/250000/250680/300x168/1.jpg`; load page
  `og:image` → `…/250000/250680/preview.jpg` — same artwork directory, per-surface crop.
  Titles agree exactly (`ISD-147 A Rice Farmer’s Busty Wife Shiori Mizuki Who Pl`).
- **Related videos** — `--related-selector` omitted: the script's regex DOM resolves
  `div.related-videos p.inf a` to ~100 whole-page anchors (fake breadth). Direct parse on all
  8 sampled pages: `list_videos_related_videos_items` present, **10** cards each, distinct
  hrefs, no self-reference.
- **Tags** — `--video-tags-selector 'div.block-details a[href*="/categories/"]'` (the Tags row's
  own anchors are href-less, so they cannot be a CSS surface); present on 8/8.
- **Plot** — `div.videodesc em`, present 8/8.
- **Year** — the site's only date is the relative `Submitted:` badge; the script's `em.badge`
  default reads the *empty* Duration badge, so no selector is passed. Direct parse on 8/8
  sampled pages: `Submitted:` badge present (`2 hours ago` … `5 years ago`) and
  `JavbangersParse.yearFromAge` unit-tested.
- **Duration** — **absent** on video pages (the `Duration:` badge is JS-filled and empty
  server-side; duration exists only on listing cards). `LoadResponse.duration` stays null.
- **Actors** — **absent** site-side (`/models/` links: 0 on 8/8 sampled pages; `video_models`
  flashvar is empty). Provider no-ops.

---

## Registry write (ADR-0013 — nothing in the PR diff)

Posted as a comment on #1451 (`<!-- findings-registry-entry -->` + fenced json) for the
orchestrator's `registry_sync`: the Javbangers provider entry (`verdict` **and** `tier`) and one
`last_runs` entry with `false_positives: []` and `escalation_tier: unavailable` (#1434).

## False positives

None this run — **1** in-scope defect fixed, **0** findings filed. The `/tags/` 502 was origin
flakiness (200 on re-probe, empty container by design); the file-less placeholder pages and the
`uncensored` p1/p2 wrap-pad overlap are the standing site-side records (#656), not drift.

## In-app-verification notes (maintainer, at merge)

1. **1080p rendition** — now emitted on the 18/29 pages that carry `video_alt_url2`; previously
   absent. Prove `1080p` is selectable in-app on e.g. `/video/412669/`.
2. **Referer** — the CDN origin 403s without `Referer: https://www.javbangers.com/`; the
   provider sets `referer = mainUrl`. Any future in-app 403 on playback points here first.
3. **Placeholder imports** — ~22% of sampled video pages have no player at all; “no streams” for
   those is the site's own state, not a provider failure.
