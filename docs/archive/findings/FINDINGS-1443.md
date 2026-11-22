# FINDINGS-1443 — FullPorner video sources (S5 of #1354)

Run: 2026-11-22 (`05-implement`, story **S5** of parent spec #1354). Scope per the spec:
the **video-source leg** (D2) across the D3 listing types — `new`/`latest`, `old`,
`popular`, `genres`, `categories`, `tags` — ≥2 video pages per type, sampled at both
catalogue ends where the type is pageable, with `loadLinks` resolved end-to-end.
Listing correctness (pagination/card-field correctness, #632–#659) and *rows* work are
out of scope.

Site: `https://fullporner.com` — **plain-curl on every surface**, desktop UA
(`Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0`),
`Referer: https://fullporner.com/` on watch/player/stream fetches (what the provider emits).
Runner IP country: **US** (`x-client-ip: 20.169.61.193` in the xiaoshenke edge response).

Registry at start: `last_deep` 2026-10-10, `drift_confirmed` 0, verdict `ok`, no standing
issue. Dedup searched open **and** closed issues for this provider: #1443 (this spec),
#644 (closed — prior deep pass, embed grammar fix), #736 (closed — plugin shape),
#526/#530 (closed — load-field fixes), #461 (closed `suspected_excluded`, never reopened).
No standing issue covers the condition found in this run.

## Phase 0 — calibration (ran before any provider probing)

| canary | expected | observed (plain-curl) | reading |
|---|---|---|---|
| `film1k-challenge-pair` | plain 403 / TLS 200 | plain **200** / TLS unavailable | **expectation-stale** — open #1435; this run records the live pair and does **not** take the `environment-degraded` branch for that canary |
| `eporner-healthy` | plain 200 | plain **200** | match — instrument healthy |
| `pandamovies-redirect-origin` | plain 302 / TLS 200 | plain **302 → https://pandamovies.org/** | match (redirect-following intact) |
| `fullporner.com` (site itself) | — | plain **200** (41 660 B home) | probe tier available; no challenge |

```
$ UA="Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0"
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.film1k.com/
200
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.eporner.com/
200
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://pandamovies.pw/
302 https://pandamovies.org/
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://fullporner.com/
200
$ .pi/skills/site-probe/scripts/impersonate.sh https://www.film1k.com/
impersonate.sh: curl_cffi missing for python3. Install it, then re-run:
```

- **#1434 (open)** — the TLS-impersonated tier is unusable in this container →
  `escalation_tier: unavailable`. Only `Blocked` verdicts downgrade to `Suspected`;
  **this run produced zero `Blocked` verdicts**, so nothing was downgraded.
- **#1435 (open)** — film1k's stale expectation is recorded, not treated as degradation.
- No genuine `environment-degraded` mismatch → the instrument is healthy.

## Listing enumeration (the D3 types as the site actually serves them)

Every listing surface renders `div.video-block div.video-card` cards, 24 per page, with
`div.video-card-body div.video-title a` titles and `div.video-card-image a img[data-src]`
posters (`src` is the `//static.xiaoshenke.net/img/blank.gif` LazyLoad placeholder).
Page 1 vs page 2 intersections were re-checked: **0** on the home row and the category row.

| type | first page | far end | evidence |
|---|---|---|---|
| `new` / `latest` | `/home/1` — 24 cards, newest-first (create epochs 1795278917…) | `/home/8` — 24 cards, older (1795188174…); `/home/999` 200 (older still) | `/home/2` 24 cards, p1∩p2 = 0; paginator links `/home/1`…`/home/7` |
| `old` | — | — | **absent as an exposed listing type**: `/old` 404; `/home/old` and `/home/popular` return the **same newest-first content as `/home/1`**; no sort control or `?sort=` / `?order=` parameter changes any listing. `/home/0` (and `/home/latest`, which casts to 0) returns the earliest page (2023-12-24) but is a page-number quirk, not linked from any listing — recorded `absent`, never a finding (D3) |
| `popular` | — | — | **absent as an exposed listing type**: `/popular` 404; `/home/popular` aliases `/home/1`; no views/rating sort control on home or category pages |
| `genres` | — | — | **absent as a taxonomy**: `/genre` and `/genres` 404; no genre links in the nav, category index, or watch pages. Category/tag semantics carry it |
| `categories` | `/category` index — 85 category links; `/category/vintage/1` — 24 cards | `/category/vintage/5` — 10 cards (last; 2015–2017 uploads) | `/category/vintage/2` 24 cards, p1∩p2 = 0; `/category/anal/7` 24 cards also sampled |
| `tags` | video pages expose `p.tag-link span a` → `/category/<slug>` (e.g. `#pov`, `#threesome`); `/category/pov/1` 24 cards | `/category/threesome/1` 24 cards | **no distinct tag route** (`/tags`, `/tag/` 404): tags are the category taxonomy, so the tag anchors were followed to their category listings and sampled as the `tags` type |

```
/home/1 24 · /home/2 24 (p1∩p2 = 0) · /home/8 24 · /home/999 200
/category/vintage/1 24 · /category/vintage/2 24 (p1∩p2 = 0) · /category/vintage/5 10
/category/pov/1 24 · /category/threesome/1 24 · /category/anal/7 24
/old 404 · /popular 404 · /genre 404 · /genres 404 · /tags 404 · /tag/ 404
```

## Evidence rows

`type | sample URL | instrument tier | stream host | quality | verdict`

Every row was resolved the way `loadLinks` does it: watch page →
`div.single-video iframe` (now `//fullporner.com/player/<id>/<mask>`) → player page →
`var id = "<id>".split('').reverse().join('')` + `var quality = parseInt("<mask>")` →
`https://xiaoshenke.net/vid/<reversedId>/<q>` → **302** → `vN.xiaoshenke.net/s0/…`.
`quality` is the ladder the mask exposes (1=360 / 2=480 / 4=720 / 8=1080).

| type | sample URL | tier | stream host | quality | verdict |
|---|---|---|---|---|---|
| `new` / `latest` (p1) | `/watch/6b018b4a7e3c9641e8e9ad1a` | plain-curl | `xiaoshenke.net` → `v7.xiaoshenke.net` (embed: first-party `fullporner.com/player`) | 720p | **pass** |
| `new` / `latest` (p8 far end) | `/watch/6b0039d07e3c9641e8e9ac6e` | plain-curl | `xiaoshenke.net` → `v5.xiaoshenke.net` | 720p | **pass** |
| `categories` (`vintage` p1) | `/watch/6af4590e2f7835591446c758` | plain-curl | `xiaoshenke.net` → `v4.xiaoshenke.net` | 480/720/1080p | **pass** |
| `categories` (`vintage` p5 last) | `/watch/5e60799836ebf415681eb74f` | plain-curl | `xiaoshenke.net` → `v5.xiaoshenke.net` | 360/720/1080p | **pass** |
| `tags` (`#pov` listing p1) | `/watch/6b018b4b7e3c9641e8e9ad22` | plain-curl | `xiaoshenke.net` → `v5.xiaoshenke.net` | 720p | **pass** |
| `tags` (`#threesome` listing p1) | `/watch/6b018b4a7e3c9641e8e9ad1e` | plain-curl | `xiaoshenke.net` → `v5.xiaoshenke.net` | 720p | **pass** |
| `old` | — | — | — | — | **absent** (no exposed sort; `/old` 404) |
| `popular` | — | — | — | — | **absent** (no exposed sort; `/popular` 404) |
| `genres` | — | — | — | — | **absent** (no genre taxonomy; `/genre` 404) |

All six sampled streams answered **206 `video/mp4`** with a Range request; every final URL
was on a distinct `vN.xiaoshenke.net` path. The embed host is first-party
(`fullporner.com/player/<id>/<mask>`), so it needs **no `HostRegistry` row** — the provider
emits the xiaoshenke stream URL directly.

Representative transcript (newest sample; `/watch/6b018b4a7e3c9641e8e9ad1a`):

```
$ curl -s -A "$UA" https://fullporner.com/watch/6b018b4a7e3c9641e8e9ad1a \
    | grep -o '<iframe[^>]*src="[^"]*"' | head -1
<iframe width="100%" height="315" src="//fullporner.com/player/d826d15d710b6/4" …>
$ curl -s -A "$UA" -e https://fullporner.com/ 'https://fullporner.com/player/d826d15d710b6/4' \
    | grep -E 'var id|var quality'
    var id = "d826d15d710b6".split('').reverse().join('');
    var quality = parseInt("4");
$ curl -s -o /dev/null -D - -H 'Range: bytes=0-64' -A "$UA" \
    -e https://fullporner.com/ 'https://xiaoshenke.net/vid/6b017d51d628d/720' | head -8
HTTP/2 302
location: //v7.xiaoshenke.net/s0/6b017d51d628d/720/…/1795326382
$ curl -sL -o /dev/null -w '%{http_code} %{content_type}\n' -H 'Range: bytes=0-64' \
    -A "$UA" -e https://fullporner.com/ 'https://xiaoshenke.net/vid/6b017d51d628d/720'
206 video/mp4
```

Multi-quality sample (`/watch/6af4590e2f7835591446c758`, mask 14):

```
$ curl -s -A "$UA" -e https://fullporner.com/ 'https://fullporner.com/player/3685933/14' \
    | grep -E 'var id|var quality'
    var id = "3685933".split('').reverse().join('');
    var quality = parseInt("14");
$ for q in 480 720 1080; do curl -sL -o /dev/null \
    -w "$q %{http_code} %{content_type}\n" -H 'Range: bytes=0-64' -A "$UA" \
    -e https://fullporner.com/ "https://xiaoshenke.net/vid/3395863/$q"; done
480 206 video/mp4
720 206 video/mp4
1080 206 video/mp4
```

## Confirmed condition (fixed in this run)

The watch page's **embed host moved** from `//xiaoshenke.net/video/<id>/<mask>` to
`//fullporner.com/player/<id>/<mask>`. `loadLinks`'s source path was unaffected —
`parseEmbedSources` reads the player page's `var id` / `var quality` locals and still
derives the whole ladder (all six samples above resolve). But `load()` still extracted the
poster id with `iframeUrl.substringAfter("video/")`; on the new URL that returns the whole
string, `substringBefore("/")` yields `https:`, and the reversed value is `:sptth`:

```
$ curl -sL -o /dev/null -w '%{http_code} %{content_type}\n' -A "$UA" \
    -e https://xiaoshenke.net/ 'https://xiaoshenke.net/vid/:sptth/720/i'
404 text/html; charset=utf-8        # what the shipped provider requested for every video
$ curl -sL -o /dev/null -w '%{http_code} %{content_type}\n' -A "$UA" \
    -e https://xiaoshenke.net/ 'https://xiaoshenke.net/vid/6b017d51d628d/720/i'
200 image/jpeg                       # correct id (reversed player id), all six samples 200
```

Fix, TDD-first red → green at a pure Parse seam (no listing/search change):

- `FullPornerParse.iframeVideoId(iframeUrl)` accepts **both** grammars
  (`/(?:video|player)/([^/?#]+)`) and returns the raw id; `FullPornerParse.posterUrl`
  builds the `/vid/<reversed>/720/i` thumbnail from it.
- `load()` now calls those functions instead of the `substringAfter("video/")` chain, and
  skips the poster request when no id parses (WebView/source fallback untouched).
- Tests: `FullPornerLoadParseTest` gains the live-trimmed fixture
  `watch_page_player_iframe.html` and two tests (current player grammar + legacy video
  grammar; no-id case). **Red** before the functions existed (`UNRESOLVED_REFERENCE` at
  `iframeVideoId`), **green** after.
- `FullPorner/build.gradle.kts` `version` **27 → 28**.

`loadLinks` itself was **not** changed: the video-source leg already passed, and the
condition was the same player-grammar drift surfacing in the poster leg.

## Verification (delivery gate)

- `./gradlew FullPorner:test` — **green** (shared suite + `FullPornerLoadParseTest` incl.
  the two new `#1443` tests, `FullPornerPluginShapeTest`).
- `./gradlew FullPorner:make` — clean `FullPorner.cs3` at **version 28**.
- `.pi/skills/verify-provider/scripts/verify.sh` — **RESULT: PASS**:
  search `/search?q=threesome&p=1|2` 200/24 cards; home `/home/1` + `/home/2` 24+24;
  6 varied video pages 200 with the `div.single-video iframe` embed; streams **6/6**
  `206 video/mp4` (720p ×4, 1080p ×2; multi-quality ladders visible in the table);
  related `div.video-recommendation div.video-card` 24/page; tags/year/duration present on
  6/6; `LoadResponse` fields all populated in Kotlin.
  `verify.sh`'s poster NOTE is the LazyLoad card placeholder (`img[src]` is
  `blank.gif`; the real thumb is `data-src`) and its video-poster NOTE is because the watch
  page has **no `og:image`** — the provider computes the poster from the player id, proven
  above (200 `image/jpeg`).
- `.pi/skills/audit-providers/scripts/check-findings.sh` — **PASS**.

## Scope notes (not conditions)

- **`old`, `popular`, `genres` are absent site-side** — no route, no sort control, no
  separate taxonomy; `/home/old`, `/home/popular` and `/home/latest` are page-number
  aliases of `/home/1`/`/home/0`, not listings. Recorded `absent` per D3, never a finding.
- **`tags` = categories** — watch-page `#tag` anchors all resolve to `/category/<slug>`;
  there is no `/tag/` route. The tag type was sampled by following those anchors.
- **Quick search absent** (registry record, unchanged): `/search/suggest?q=` renders the
  regular search page for the literal word; `/api/search` 404. Out of this story's scope,
  no provider change.
- **Cards** expose posters via `data-src` (`src` is `blank.gif`); listing correctness was
  not audited here (#632–#659), and the home row's own cards were only used to enumerate
  videos.
- **Actors** are exposed only where the site's `Pornstar:` cell is populated (present-but-
  empty otherwise); `parseActors` already filters blanks. Not asserted across the sample.
- **No DRM, geo-wall or challenge** was observed on any sampled video/stream from this
  runner; the TLS tier is unavailable (#1434) but was not needed.

## Registry write

The provider entry and one `last_runs` entry (with `false_positives: []`) are posted as a
comment on #1443 beginning `<!-- findings-registry-entry -->`, not in this diff (ADR-0013).
