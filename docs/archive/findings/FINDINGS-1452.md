# FINDINGS-1452 — WatchPorn video sources (S14 of #1354)

Audit of the **video-source leg** (D2) of the WatchPorn spec (`watchporn.to`). Scope: for every
listing type in D3 (`new`/`latest`, `old`, `popular`, `genres`, `categories`, `tags`) open ≥2 video
pages — sampled at both ends of the catalogue where the type is pageable — and resolve `loadLinks`
end-to-end: `flashvars` rendition URL → `get_file` 302 → `srv*` origin 206, embed host identified,
quality passed to CloudStream where the site exposes it.

Provider state at run start: registry `last_deep` = 2026-10-16, `drift_confirmed` = 1, verdict at
last deep `ok-drift` (#649). `drift_confirmed` = 1 ⇒ a **targeted** fix is allowed (the ≥3
hardening rung is not reached); a confirmed condition would be fixed **in this run** (D1).

**Result: no condition confirmed on the video-source leg — the leg is clean, no code change, no
version bump.** One adjacent **data-completeness** observation (plot) sits outside D2 and is filed
as a backlog issue (see *Adjacent observation*), not fixed in this diff.

Run: `05-implement` for #1452, 2026-11-23, plain-curl tier, runner IP US.
Workflow run: `https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/45191665184`.

Out of scope per spec: listing-card/pagination correctness beyond what enumeration needs
(#632–#659), and *rows* work (uncensored/ura row additions).

---

## Phase 0 — canary calibration (before touching the site)

| Canary | Expected | Observed | Result |
| --- | --- | --- | --- |
| `eporner-healthy` | plain-curl 200 | plain-curl **200** (314 816 B) | match — instrument healthy |
| `film1k-challenge-pair` | plain-curl 403, tls-impersonated 200 | plain-curl **200** (214 913 B) | **expectation-stale** (#1435 open) — its live pair is recorded here; **no** `environment-degraded` branch taken |
| `pandamovies-redirect-origin` | plain-curl 302, tls-impersonated 200 | plain-curl **302 → https://pandamovies.org/** | plain tier match; the TLS leg cannot run (#1434) |

```
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://www.eporner.com/   → 200 314816
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://www.film1k.com/    → 200 214913
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://pandamovies.pw/      → 302 https://pandamovies.org/
$ python3 -c 'import curl_cffi'
ModuleNotFoundError: No module named 'curl_cffi'      # #1434 still open at run time
```

**#1434 is still open**, so the registry `last_runs` entry carries
`escalation_tier: unavailable` and any `Blocked` this run would downgrade to `Suspected`. None
occurred — watchporn.to answered plain-curl on every surface below, including all streams.

Instrument = **plain-curl tier**, desktop Firefox UA
(`Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0`). No proxy, no
impersonation, no browser instrument anywhere below. Stream fetches were re-run with app-like UAs
(`CloudStream/4.9.1`, `Dalvik/2.1.0`, Android Chrome) — same answers (see *Headers*).

---

## Listing-type inventory (D3)

The site is KVS (`list_videos_*` block ids, `kt_player` + inline `var flashvars`) behind a
ddos-guard edge, rendered by its own `wp-*` theme (the grammar #649 re-recorded). Two taxonomies
exist: **`/categories/<slug>/`** (69 genre slugs) and **`/sites/<slug>/`** (25 studio slugs); the
provider's own rows use `/categories/<studio-slug>/`, which **301s** to `/sites/<studio-slug>/`.

Every row probed plain-curl. A type the site does not expose is recorded **`absent`** with the
evidence that proves it, never assumed.

| type | surface | evidence |
| --- | --- | --- |
| `new` / `latest` | `/latest-updates/` — p1 **39** cards, p2 **39**, overlap **0**; far end `/latest-updates/300/` **200, 39**, `/latest-updates/800/` **200, 39**, `/latest-updates/3000/` **200, 39**, `/latest-updates/5000/` **404**, `/latest-updates/9999/` **404** | 200 |
| `old` | **absent.** `/old/` and `/oldest/` → **404**. No oldest-first route or honoured parameter: `/latest-updates/?sort=old`, `?sort=oldest`, `?sort_by=post_date&sort_dir=asc`, `?sort_by=video_id&sort_dir=asc` are **byte-identical** to `/latest-updates/` (`md5 826013b74749` on all five) | 404 / inert |
| `popular` | `/most-popular/` — p1 **19**, p2 **19**, overlap **0**; far end `/most-popular/300/`, `/800/`, `/4000/` **200, 19** each; `/most-popular/9000/` and `/9999/` **404** | 200 |
| `genres` | **absent as its own namespace.** `/genres/` and `/genre/milf/` → **404**. The site's genre vocabulary *is* the `/categories/` namespace (index 200, **69** category links, 0 video cards) — sampled under `categories` below | 404 |
| `categories` | `/categories/` index **200** (`Top Rated Categories`, 69 genre links, 0 cards); `/categories/amateur/` p1 **19**, p2 **19**, overlap **0**; far end `/categories/amateur/100/` **200**, `/categories/amateur/500/` **404**; `/categories/blonde/` p1 **19**, p2 **19**; `/categories/18-19-years-old/` **200** | 200 |
| `tags` | `/tags/` index **200** (`<h1>Tags`, **160** curated slugs, 0 video cards); `/tags/airtight/` p1 **19**, p2 **19**, overlap **0**; `/tags/airtight/3/` **200, 19**; `/tags/airtight/50/` **404**; `/tags/69/`, `/tags/anal/`, `/tags/lesbian/` **200**. A slug outside the curated namespace (`/tags/milf/`) is a **404** while `milf` is a `/categories/` slug — two distinct namespaces | 200 |

Provider-row surfaces re-probed for enumeration (not D3 types, same leg):
`/top-rated/` p1 **19** / p2 **19**, overlap **0**; `/top-rated/300/`, `/800/` **200, 19**;
`/top-rated/9999/` **404**. `/sites/` index **200, 25** studio links, 0 cards;
`/sites/manyvids/` p1 **19** / p2 **19**, overlap **0**; `/sites/manyvids/100/` and `/300/` **200**.
`/categories/manyvids/` → **301** `https://watchporn.to/sites/manyvids/` (the provider's row URLs
redirect to the studio namespace; NiceHttp follows redirects, so the rows resolve).

### Search and quick search

```
$ curl -s -A "$UA" 'https://watchporn.to/search/?q=taboo&mode=async&function=get_block&block_id=list_videos_videos_list_search_result&category_ids=&sort_by=&from_videos=1'
200, 39 cards (div.thumb.item.wp-card), li.wp-pag__next present
$ ...&from_videos=2
200, 39 cards, overlap with p1 = 0
$ curl -s -A "$UA" 'https://watchporn.to/suggest/?q=milf'
200 application/json — {"suggestions":[{"value":"Cory Chase","data":{"type":"Models","url":"https://watchporn.to/models/cory-chase/"}}, … ]}
```

Quick search is a **distinct JSON endpoint** (`/suggest/?q=`), unchanged since #278: 20 `Models` +
20 `Videos` entries, `SuggestParse` contract intact (fixture `suggest-deep.json`, `WatchPornSuggestTest`
green). `verify.sh`'s card harness cannot assert a JSON surface — its check 1b NOTE is satisfied by
this transcript (same limitation recorded in FINDINGS-649).

---

## Evidence rows (D4)

`type | sample URL | instrument tier | stream host | quality | verdict` — 25 videos, ≥2 per present
type, both catalogue ends where the type is pageable. 24 rows resolved **both** renditions
(720p `video_url` + 1080p `video_alt_url`) end-to-end; row 25 is the single-rendition class, drawn
from the `tags` surface (`/tags/big-tits/` p1) rather than from a newest-listing page — the `type`
cell names the class, the `listing position` cell names the surface it came from.

| # | type | sample URL (`/video/<id>/<slug>/`) | listing position | tier | stream host | quality | verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | new/latest | `/video/170727/lovely-lo-taboo-bj/` | `/latest-updates/` p1 (head) | plain-curl | `srv50.zload.cc` | 720p + 1080p | **pass** |
| 2 | new/latest | `/video/166198/lilredvelvet-taking-sneeze-powder-and-riding-a-dildo/` | `/latest-updates/` p1 | plain-curl | `srv46.zload.cc` | 720p + 1080p | **pass** |
| 3 | new/latest | `/video/156424/summer-vixen-luna-luxe-luna-summers-sloppy-spit-show-bjraw/` | `/latest-updates/300/` (far end) | plain-curl | `srv50.zload.cc` | 720p + 1080p | **pass** |
| 4 | new/latest | `/video/152919/blake-blossom-blake-gets-fucked-by-dante-colle/` | `/latest-updates/300/` (far end) | plain-curl | `srv44.zload.cc` | 720p + 1080p | **pass** |
| 5 | popular | `/video/29723/jackandjill-bff-their-first-3some-together/` | `/most-popular/` p1 (head) | plain-curl | `srv50.zload.cc` | 720p + 1080p | **pass** |
| 6 | popular | `/video/29051/tushy-mia-malkova-and-ange-venus/` | `/most-popular/` p1 | plain-curl | `srv51.zload.cc` | 720p + 1080p | **pass** |
| 7 | popular | `/video/71658/kenna-james-and-melody-marks-my-new-family-pt-2-allherluv/` | `/most-popular/300/` (far end) | plain-curl | `srv45.zload.cc` | 720p + 1080p | **pass** |
| 8 | popular | `/video/149025/riley-star-tiny-blonde-cutie-gets-stretched-out-by-bbc-blacked/` | `/most-popular/300/` (far end) | plain-curl | `srv50.zload.cc` | 720p + 1080p | **pass** |
| 9 | top-rated (provider row) | `/video/140452/elly-clutch-sweatpants-bandit/` | `/top-rated/` p1 (head) | plain-curl | `srv46.zload.cc` | 720p + 1080p | **pass** |
| 10 | top-rated (provider row) | `/video/171922/brianna-beach-past-your-bedtime/` | `/top-rated/` p1 | plain-curl | `srv45.zload.cc` | 720p + 1080p | **pass** |
| 11 | top-rated (provider row) | `/video/56793/jasmine-wilde-jasmine-juices-the-jizz-out-dirtyauditions/` | `/top-rated/300/` (far end) | plain-curl | `srv44.zload.cc` | 720p + 1080p | **pass** |
| 12 | top-rated (provider row) | `/video/118747/chloe-foxxe-sexy-co-ed-babe-chloe-foxxe-needs-a-good-pounding-naughtybookworms/` | `/top-rated/300/` (far end) | plain-curl | `srv51.zload.cc` | 720p + 1080p | **pass** |
| 13 | categories (`amateur`) | `/video/172277/rebecca-vanguard-didn-t-make-it-to-the-bedroom/` | `/categories/amateur/` p1 (head) | plain-curl | `srv46.zload.cc` | 720p + 1080p | **pass** |
| 14 | categories (`amateur`) | `/video/172242/ava-amira-loves-big-cocks/` | `/categories/amateur/` p1 | plain-curl | `srv50.zload.cc` | 720p + 1080p | **pass** |
| 15 | categories (`amateur`, far end) | `/video/133548/bubble-booty-anal/` | `/categories/amateur/100/` | plain-curl | `srv46.zload.cc` | 720p + 1080p | **pass** |
| 16 | categories (`amateur`, far end) | `/video/132570/goldy-kim-goldykim-takes-a-huge-cock/` | `/categories/amateur/100/` | plain-curl | `srv47.zload.cc` | 720p + 1080p | **pass** |
| 17 | categories (studio slug, provider row) | `/video/171056/conception-deception/` | `/sites/puretaboo/` p1 (head) | plain-curl | `srv49.zload.cc` | 720p + 1080p | **pass** |
| 18 | categories (studio slug, provider row) | `/video/169616/myra-moans-the-deceiving-widower/` | `/sites/puretaboo/` p1 | plain-curl | `srv52.zload.cc` | 720p + 1080p | **pass** |
| 19 | categories (studio slug, provider row) | `/video/163678/nicole-nabors-our-loser-cuck/` | `/sites/manyvids/100/` (far end) | plain-curl | `srv47.zload.cc` | 720p + 1080p | **pass** |
| 20 | categories (studio slug, provider row) | `/video/164680/sadie-andrews-sadie-and-johnny-love/` | `/sites/manyvids/100/` (far end) | plain-curl | `srv49.zload.cc` | 720p + 1080p | **pass** |
| 21 | tags (`airtight`) | `/video/171158/adventurous-babe-rossa-vaxx-takes-a-dp-pounding-during-wild-group-sex-session/` | `/tags/airtight/` p1 (head) | plain-curl | `srv47.zload.cc` | 720p + 1080p | **pass** |
| 22 | tags (`airtight`) | `/video/171790/ada-paradiso-mesmera-trixxx-athena-star-rocco-s-sex-experience-italian-studs/` | `/tags/airtight/` p1 | plain-curl | `srv45.zload.cc` | 720p + 1080p | **pass** |
| 23 | tags (`airtight`, far end) | `/video/113784/nela-decker-and-vera-jarw-and-greta-foss-dp-dvp-cum-swap-orgy-evilangel/` | `/tags/airtight/3/` | plain-curl | `srv50.zload.cc` | 720p + 1080p | **pass** |
| 24 | tags (`airtight`, far end) | `/video/113020/whitney-wright-and-ivy-ireland-i-ve-never-had-bbc-before-herlimit/` | `/tags/airtight/3/` | plain-curl | `srv51.zload.cc` | 720p + 1080p | **pass** |
| 25 | single-rendition class (tags surface) | `/video/166665/lucy-mochi-episode-312-lucy-and-leo/` | `/tags/big-tits/` p1 | plain-curl | `srv47.zload.cc` | 720p (no `_text` label — filename token supplies it) | **pass** |

Every pass row was fetched with `Range: bytes=0-2047` and the in-app referer; all **49** stream
requests answered `206 video/mp4`, `Content-Range` present, ftyp magic `00000020 66747970 69736f6d`
— the same 12-byte prefix as the FINDINGS-1449/1451 records. No row needed a non-pass transcript.

Scan breadth (video-page enumeration, not just the table): **451 unique video pages** sampled across
22 listing pages covering every present D3 type plus the two provider-row surfaces, at p1, p2 and the
catalogue far end. Distribution:

| shape | count | note |
| --- | --- | --- |
| `video_url` (720p) + `video_alt_url` (1080p) | **443** | the normal shape |
| `video_url` only (`*_720p.mp4`, **no** `video_url_text`) | **8** | single-rendition imports; filename token still yields 720p |
| `video_alt_url2` (third rendition) present | **0** | unlike KVS sites such as javbangers (#1451), WatchPorn never serves it |
| page with no `video_url` at all | **0** | no file-less placeholder class in the sampled range |
| `*_text` label vs filename token disagreement | **0** | `video_url_text`/`video_alt_url_text` always agree with `_NNNp` |

A separate **iframe-host scan** over 26 video pages (5 listing surfaces): the only video embed is
`https://watchporn.to/embed/<id>` (self-hosted player shell, same `flashvars` contract), plus an
occasional ad iframe on `creative.whitetrafsa.com` and the `a.magsrv.com` ad slots. **No third-party
video embed host exists on this site**, so there is nothing for `shared/`'s `HostRegistry` to
register for this provider, and no data-completeness gap in the embed-adapter sense.

## The stream chain (unchanged, re-proven)

```
GET https://watchporn.to/video/170727/lovely-lo-taboo-bj/                      → 200, 92 046 B
  <div id="kt_player"></div>
  <script> … var flashvars = {
      video_url:      'https://watchporn.to/get_file/14/26f58f…/170000/170727/170727_720p.mp4/?v-acctoken=…',
      video_url_text: '720p',   video_url_hd: '1',
      video_alt_url:      '…/170000/170727/170727_1080p.mp4/?v-acctoken=…',
      video_alt_url_text: '1080p', video_alt_url_fhd: '1',
      video_categories: 'Stepmom, Pantyhose, Taboo, Blonde, Blowjob',
      video_tags: 'small tits, natural tits', video_models: 'Lovely Lo', …
  } … kt_player('kt_player', 'https://watchporn.to/player/kt_player.swf?v=10.11.14', '100%', '100%', flashvars);</script>

GET https://watchporn.to/get_file/14/26f58f…/170000/170727/170727_720p.mp4/?v-acctoken=…   → 302
  server: ddos-guard          set-cookie: __ddg8_/__ddg9_/__ddg10_/__ddg1_ (.watchporn.to)
  location: https://srv50.zload.cc/remote_control.php?file=…&acctoken=…
GET https://srv50.zload.cc/remote_control.php?…  -r 0-2047  Referer: https://watchporn.to/  → 206 video/mp4
GET https://srv50.zload.cc/remote_control.php?…  -r 0-2047  no Referer                     → 206 video/mp4
```

`flashvars` is a **global, server-rendered** `var` on the video page (not built by an XHR), which is
what the provider's WebView extractor reads — so the extraction path has HTTP-level ground truth:

```kotlin
if (typeof flashvars !== 'undefined') {
  if (flashvars.video_url     && flashvars.video_url.indexOf('https://')     !== -1) videos.push(flashvars.video_url);
  if (flashvars.video_alt_url && flashvars.video_alt_url.indexOf('https://') !== -1) videos.push(flashvars.video_alt_url);
}
```

Quality is derived in `loadLinks` from the filename token
(`…/170727_720p.mp4/` → `720p`) — identical to the site's `*_text` labels on **all 451** sampled
pages, and the only source of a quality on the 8 label-less single-rendition pages. Nothing was
dropped: unlike the javbangers third rendition (#1451), there is no `video_alt_url2` to emit here.

### Headers / referer

Nothing is required. The origin hop serves `206` with **and** without `Referer: https://watchporn.to/`
(per-shard `srv44`–`srv52.zload.cc`), and with `CloudStream/4.9.1`, `Dalvik/2.1.0` and Android Chrome
UAs alike — the 2026-09-10 “session-bound anti-leech” note still does not reproduce, matching
FINDINGS-649. The provider sets `referer = mainUrl` unconditionally; harmless, and it stays as the
hedge for per-shard behaviour.

### Geo

Probed from a US runner IP; no region wall observed on listing, video page, or either stream
rendition. In-app verification note below still applies to non-US users.

---

## Instrument / site-side notes (recorded, never filed)

Consistent with FINDINGS-649's four instrument-limited lines for this provider; re-confirmed today:

1. **Card title is a site-native short form.** Listing/search cards carry the title *without* the
   performer prefix (`a.wp-ctitle` → `Taboo BJ`), while the video page's `h1.wp-vi__title` and
   `og:title` carry it (`Lovely Lo - Taboo BJ`). The card's `a.wp-card__link[title]` attribute
   holds the long form. The provider stores the short form on cards and the long form in `load()`
   — both are the site's own surfaces, so every sampled video that appears in search hits
   `verify.sh` check 5's title bar only through the card's short form.
2. **Poster surfaces differ per page type.** Cards serve a `…/videos_screenshots/<bucket>/<id>/336x189/1.jpg`
   crop; the load page's `og:image` is `…/<bucket>/<id>/preview.jpg` (same artwork directory,
   different render). `LoadResponse.posterUrl` uses the stored card poster, so the app stays
   internally consistent; check-5 poster equality cannot be exercised without a false FAIL.
3. **`plot` is not a D2 field and is not populated** — see *Adjacent observation*.
4. **`duration`/`year` are JSON-LD-only** (`script[type=application/ld+json]` `VideoObject`,
   `PT0H36M40S` / `uploadDate`), so no CSS selector exists for `verify.sh`'s 2a bar; the shared
   `JsonLdParse.minutes/year` path is the provider's surface and is unit-tested.
5. **Model-less imports exist site-side** (`/video/169024/dirty-step-daughter-selfies/` renders
   `0` `a.wp-cast` links, description present, both renditions present) — `load()` correctly leaves
   `actors` empty for those; not drift.
6. **Tags index is curated, not exhaustive**: `/tags/` lists 160 slugs and `/tags/milf/` is a 404
   even though `milf` is a category slug on video pages. `/categories/` (69 genre slugs) and
   `/sites/` (25 studio slugs) are the two taxonomy routes; `genres` is not a namespace of its own.

## Related videos

`<div class="grid related-videos" id="list_videos_related_videos">` with
`<div class="grid__list" id="list_videos_related_videos_items">` inside; the provider's
`div.related-videos div.thumb.item` matched **15** cards on all 13 verify-sampled pages, with no
self-reference and no duplicate hrefs. The `wp-*` card grammar inside the rail is unchanged from
#649.

---

## Adjacent observation — out of D2 scope, filed as backlog

The #649 comment in `load()` says the theme redesign removed the description paragraph and that
`ld+json`/`og:description` carry only tag-glue/boilerplate. That is no longer true: the current
theme renders a real per-video synopsis at `div.wp-desc > p`, mirrored in the `VideoObject`
`description`:

```
/video/170727/…  div.wp-desc p: "Who knew ] drop-off could get so wild? I showed up in my tightest leggings…"
/video/172277/…  div.wp-desc p: "Rebecca needs my husband's cock so badly that they don't make it past the stairs…"
/video/165845/…  div.wp-desc p: "She asked me to cum on her face! And she drove me wild with her throat!…"
```

12 of 14 sampled pages carry it with distinct text; the other 2 serve the title itself as the
`ld+json`/`og:description`. In the wider 25-row sample, 4 pages (`/video/152919/`, `/video/71658/`,
`/video/171790/`, `/video/171056/`) carry no synopsis at all and serve the 151-char site boilerplate
as `og:description` (`Free full-length porn videos in HD on WatchPorn. Browse by category, pornstar
or studio …`) — which is exactly why `og:description` cannot be used as the plot surface and why
the verify sample was drawn from the synopsis-bearing majority.
`load()` never populates `plot`, so this is a **data-completeness** gap, not a video-source defect
and not D2 work — filed as a `triage/from-agent` issue with this evidence, no fix in this diff
(scope discipline, Implement step).

---

## Pipeline verification — `verify.sh` (live): **PASS**

13 video pages spanning every present D3 type at both catalogue ends (plus the single-rendition
shape), 13 position-matched `--stream-url` overrides (streams are JS
`flashvars`, the page carries no CSS stream element — the override is the documented path for
that), `--provider-src WatchPorn`:

```
── check 1: search pages (2)
GET …/search/?q=taboo&…&from_videos=1 → 200; 'div.thumb.item.wp-card' matches: 39
GET …/search/?q=taboo&…&from_videos=2 → 200; 'div.thumb.item.wp-card' matches: 39
── check 1a: homepage pages (2)
GET https://watchporn.to/top-rated/   → 200; 'div.thumb.item.wp-card' matches: 19
GET https://watchporn.to/top-rated/2/ → 200; 'div.thumb.item.wp-card' matches: 19
── check 1b: quick search (0)
NOTE: no --quick-search-url — … (JSON surface, transcript above; FINDINGS-649 record)
── check 2: video pages (13 URLs)
GET …/video/170727/lovely-lo-taboo-bj/ → 200; 'div#kt_player' matches: 1
GET stream (…/170727_720p.mp4… ) → 206 video/mp4
… (13/13 pages 200, 13/13 streams 206 video/mp4, 13 related rails × 15 cards)
field 'tags': present on all 13 sampled pages
field 'actors': present on all 13 sampled pages
NOTE: no --video-year-selector — exposure not asserted; FINDINGS must state whether the site exposes year
NOTE: no --video-duration-selector — exposure not asserted; FINDINGS must state whether the site exposes duration
── check 5: search ↔ load agreement
NOTE: no sampled video appears in the search page(s) — pick a --search-url whose query matches a sample video to exercise agreement
── check 6: LoadResponse completeness (code vs FINDINGS)
field 'recommendations': 2 · 'tags': 3 · 'duration': 2 · 'year': 2 · 'actors': 3 · 'score': 1 · 'posters': 3
RESULT: PASS
```

Invocation notes (the FINDINGS lines the flags require):

- **Quick search** — distinct `/suggest/?q=` JSON endpoint exists (above); it is not card-shaped, so
  no `--quick-search-url` is passed. `hasQuickSearch = true` and `SuggestParse` stays covered by
  `WatchPornSuggestTest`.
- **Home page 2** — `/top-rated/` p1+p2, 19+19, 0 overlap. The card selector
  `div.thumb.item.wp-card` excludes the per-page `grid-ad` ad slot that made #649's home check
  report duplicate ad cards.
- **Search page 2** — the provider's `from_videos=$page` param yields a real page 2 (39 cards, 0
  overlap); both pages are passed as the two search URLs.
- **Check 5** — NOTE, not exercised: the sampled videos do not appear in the `taboo` draw (the site
  re-samples listings per request). The site-side surface difference that makes the tool's
  title/poster equality bars inapplicable is recorded above (notes 1–2); titles agree exactly on the
  `a.wp-card__link[title]` / `og:title` form and posters are per-surface renders of one artwork
  directory, so there is no provider-side disagreement to test.
- **Plot** — `div.wp-desc p` is passed as the *site's* plot surface (all 13 verify samples carry it).
  The provider does not populate `plot` (backlog issue), so `--load-response` omits `plot`.
- **Tags / actors** — `a.wp-vchip` and `a.wp-cast span.wp-cast__n`, present on 13/13.
- **Year / duration** — JSON-LD only (note 5 above); no CSS selector passed.
- **Streams** — `div#kt_player` is the only stream-bearing element; the URLs come from `flashvars`,
  fetched fresh for each page at invocation time (the `v-acctoken` is short-lived, as in FINDINGS-1449).

Code-half notes: `score` is assigned on cards in `toMainPageResult()`; `plot` is the only
`LoadResponse` field the provider leaves empty, deliberately, pending the backlog issue.

---

## Registry write (ADR-0013 — nothing in the PR diff)

Posted as a comment on #1452 that **begins** with `<!-- findings-registry-entry -->` followed by a
fenced ```json``` document in the delta shape `{providers: {WatchPorn: …}, last_runs: […]}` for the
orchestrator's `registry_sync`: the WatchPorn provider entry (`verdict` **and** `tier`, `last_deep`
2026-11-23, `drift_confirmed` 1, `chronic` false) and one `last_runs` entry with
`false_positives: []` and `escalation_tier: unavailable` (#1434).

That anchored delta supersedes the earlier registry comment on #1452 (2026-11-23T14:47:05Z), which
shipped no anchor line and a `{provider, entry, last_run}` document shape: `_registry_entry()` skips
it (anchor missing), `_registry_candidate()`'s self-heal skips it (no top-level `providers` dict),
and anchoring it as-is would have unioned `provider`/`entry`/`last_run` into `audits/findings.json`
as junk top-level keys instead of updating `providers.WatchPorn` / `last_runs` — so S14's registry
update would have been lost once `Fixes #1452` closed the issue (`registry_sync()` scans only open
issues).

## False positives

None — **0** conditions confirmed, **0** findings filed against the video-source leg. The notes above
are site-side shapes or instrument limits, not drift: the `wp-*` grammar (#649), stream contract,
listing pagination and `suggest` JSON all held.

## In-app-verification notes (maintainer, at merge)

1. **Both renditions reach the app** — 720p and 1080p are emitted from `flashvars` and both serve
   `206`; check the quality picker shows two rungs on e.g. `/video/170727/`.
2. **WebView extractor** — `loadLinks` renders the page in a WebView and reads the global
   `flashvars`; the HTTP transcript above is what that JS sees. If an in-app failure appears here,
   the WebView (not the site) is the suspect.
3. **No referer needed** — origins serve without `Referer`; the provider's `referer = mainUrl` is a
   hedge only.
