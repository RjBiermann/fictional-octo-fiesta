# FINDINGS-1448 — Cat3Movie video sources (S10 of #1354)

Audit + fix of the **video-source leg** (D2) of the Cat3Movie spec (`cat3movie.org`).
Scope was the source chain only: for each listing type in D3, open ≥2 video pages sampled at
both ends of the catalogue and resolve `loadLinks` end-to-end (stream URL reachable, embed
host identified **and registered**, quality passed to CloudStream where the site exposes it).

Provider state at run start: registry `last_deep` 2026-10-16, `drift_confirmed` 3,
verdict at last deep `ok-drift` (#645). Three confirmed drifts ⇒ the **hardening rung**
applied: every fix in this run is structural (shared/ adapters + registry dispatch), never a
selector swap.

Run: `https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/45091683191`
(2026-11-23, runner IP US, plain-curl tier).

---

## Phase 0 — canary calibration

`python3 -c 'import curl_cffi'` → `ModuleNotFoundError` — **#1434 still open at run time**, so
`escalation_tier: unavailable` is recorded in the registry `last_runs` entry and every
`Blocked` would downgrade to `Suspected` (none occurred this run: all surfaces answered
plain-curl 200, so the degraded tier never bit).

| Canary | Expected | Observed | Result |
| --- | --- | --- | --- |
| `film1k-challenge-pair` | plain-curl **403**, tls-impersonated 200 | plain-curl **200** | **expectation-stale** (#1435 open — its live pair is recorded here; **no** `environment-degraded` branch taken, per the spec) |
| `eporner-healthy` | plain-curl 200 | plain-curl 200 | match — instrument healthy |
| `pandamovies-redirect-origin` | plain-curl 302, tls-impersonated 200 | plain-curl **302 → 200 @ pandamovies.org** | match (redirect target followed) |

```
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.film1k.com/          → 200
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.eporner.com/          → 200
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://pandamovies.pw/
302 https://pandamovies.org/
$ curl -sL -o /dev/null -w 'followed: %{http_code} %{url_effective}\n' https://pandamovies.pw/
followed: 200 https://pandamovies.org/
$ python3 -c 'import curl_cffi'
ModuleNotFoundError: No module named 'curl_cffi'
```

Instrument = **plain-curl tier**, desktop Firefox UA
(`Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0`) and, for every
chain re-check, the framework's own browser UA
(`Mozilla/5.0 (Windows NT 10.0; Win64; x64) … Chrome/149.0.0.0 Safari/537.36`) — the app path.
No proxy, no impersonation, no browser instrument anywhere below.

---

## Listing-type inventory (D3)

Every row was probed plain-curl. Where a type is **absent** the evidence is a 404 / ignored
ordering, never an assumption.

| type | surface | evidence |
| --- | --- | --- |
| `new` / `latest` | `/` (New Movies widget, 58 cards) and `/new-movies` (**718 pages**, 20 cards/page; `page/718` = 9-card tail) | 200; page-2 of `/new-movies` is disjoint (20/20), `page/719` → 404. The **home** row clamps: `/page/2` re-serves the same 44 unique cards (site-side, re-confirmed; the provider dedupes per row via `Parse.filterSeen`) |
| `old` | **present, but not as a path**: `?order=asc` re-sorts any listing oldest-first — `/classic-porn?order=asc` first card `virginia-1983` vs the default row's `gangland-bangers-1995`; 43 pages, `page/43?order=asc` ends on `gangland-bangers-1995` (the newest), `page/44` → 404 | 200. `/old-movies` → **404**. `/classic-porn/asc/` (the theme's own `?sort=asc` 302 target) → **404** — that redirect is broken site-side. The provider surfaces no ascending row (row additions are out of scope for this story) — **noted**, not a drift |
| `popular` | **present as a homepage widget only**: `#halim-ajax-popular-post` lists 15 `<div class="item post-<id>">` links; not pageable, no standalone path | 200. `/popular`, `/most-viewed`, `/top-rated` → **404**; `?order=popular`, `?order=views` → **ignored** (byte-identical card order to default) |
| `genres` | nav genre terms, e.g. `/action` (41 pages), `/horror` (70 pages) | 200; last pages return 11 / 14 cards (tails), `page+1` → 404 |
| `categories` | the provider's category rows: `/classic-porn` (43 p), `/classic-erotica` (58 p), `/asian-erotica` (29 p) | 200; last pages return 20 / 20 / 7 cards |
| `tags` | `/tag/<slug>`, e.g. `/tag/giallo` (11 p), `/tag/1980s` (147 p) | 200; last page returns 5 cards |

Note the site's nav calls **all** of these "Categories"; `genres` vs `categories` is the
provider's own split (genre terms vs the three category rows in `mainPage`), and both are
sampled separately below.

---

## Evidence rows (D4)

`type | sample URL | instrument tier | stream host | quality | verdict`

| type | sample URL | listing page (end) | tier | embed legs | stream host | quality | verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| new/latest | `/dimension-5-1966` | `/` page 1 (newest) | plain-curl | hlsfree/1487 · loadvid · hlsfree/1487 | hlsfree → `s1.cat3hls.com` | single rendition (no `EXT-X-STREAM-INF`) | **pass** |
| new/latest | `/the-lovers-guide-making-sex-even-better-1992` | `/new-movies/page/718` (oldest, 9 cards) | plain-curl | loadvid · listeamed · hlsfast/#q35qks | turboembed → `94.131.217.39` (mp4) | 540p (tier label) | **pass** (hlsfast); listeamed host-side, below |
| old | `/virginia-1983` | `/classic-porn?order=asc` page 1 | plain-curl | hlsfast/#c5acrp ×3 | turboembed → `94.131.217.39` (mp4) | 540p | **pass** |
| old | `/gangland-bangers-1995` | `/classic-porn/page/43?order=asc` (newest end) | plain-curl | hlsfree/1464 · loadvid · hlsfree/1464 | hlsfree | single rendition | **pass** |
| popular | `/the-year-my-voice-broke-1987` | `/#halim-ajax-popular-post` first item | plain-curl | hlsfree · loadvid · hlsfree | hlsfree | single rendition | **pass** |
| popular | `/a-virgin-for-st-tropez-1975` | `/#halim-ajax-popular-post` last item | plain-curl | hlsfree · loadvid · hlsfree | hlsfree | single rendition | **pass** |
| genres | `/street-people-1976` | `/action` page 1 | plain-curl | hlsfree/1479 · loadvid · hlsfree/1479 | hlsfree | single rendition | **pass** |
| genres | `/lust-for-dracula-2004` | `/horror/page/70` (last, 14 cards) | plain-curl | hlsfast/#kdnkxd ×3 | turboembed → mp4 | 540p | **pass** |
| genres | `/improper-conduct-1994` | `/action/page/41` (last, 11 cards) | plain-curl | hlsfast/#on5zoa ×3 | turboembed → mp4 | 540p | **pass** |
| categories | `/emmanuelle-4-concealed-fantasy-1994` | `/classic-erotica/page/58` (last) | plain-curl | loadvid (`src=" https://…"` — **leading space**) · listeamed · hlsfast/#q35qks | turboembed → mp4 | 540p | **pass** (hlsfast; loadvid 206 once the src is trimmed) |
| categories | `/school-of-the-holy-beast-1974` | `/asian-erotica/page/29` (last, 7 cards) | plain-curl | hlsfast/#dj8dpz ×3 | turboembed → mp4 | 540p | **pass** |
| tags | `/to-love-perhaps-to-die-1973` | `/tag/giallo` page 1 | plain-curl | hlsfree/1396 · loadvid · hlsfree/1396 | hlsfree | single rendition | **pass** |
| tags | `/virginia-1983` | `/tag/1980s/page/147` (last, 5 cards) | plain-curl | hlsfast/#c5acrp ×3 | turboembed → mp4 | 540p | **pass** |

**Distribution across 13 sampled videos (39 player legs):** hlsfree 12 legs, hlsfast 22 legs,
loadvid 8 legs, listeamed 2 legs (both with a filename suffix, both dead — see below).

---

## The confirmed defect — the hlsfast chain moved, 5/13 samples had **zero** sources

`drift_confirmed` = 3 ⇒ hardening rung: the fix below is a **shared/ extension + registry
dispatch**, not another point-in-time patch.

The provider's bespoke hlsfast branch called the AES endpoint from issue #180:

```
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" -H 'Referer: https://hlsfast.com/' \
    'https://hlsfast.com/api/v1/video?id=c5acrp&w=1280&h=720&r=cat3movie.org'
404                       # ← every hash, every sampled video; body is the SPA index.html
```

The player shell now resolves through a **public redirect API**, and the destination host has
rotated away from the retired `upn` slot:

```
$ curl -s -A "$UA" -H 'Referer: https://hlsfast.com/' 'https://hlsfast.com/api/public/resolve?file1=c5acrp'
{"ok":true,"url":"https://turboembed.com/1hri","host1":"upn","file1":"c5acrp","host2":"turbovio","file2":"1hri","link2":"https://turboembed.com/1hri"}
```

`https://turboembed.com/<code>` is a Vite SPA (anti-devtool + webdriver UA sniffing); its
**download-tier API is plain JSON** and carries the quality the site exposes:

```
$ curl -s -A "$UA" 'https://turboembed.com/api/download/tiers?s=1hri'
{"data":{"poster":"https://turboembed.com/img/kxjavdis/ecr9o/poster.jpg","tiers":[{"file":"540p","label":"540p","height":480,"filename":"Virginia (1983).mp4.mp4 - 540p.mp4","size_bytes":482826753}],"title":"Virginia (1983).mp4.mp4"}}

$ curl -s -o /dev/null -w '%{redirect_url}\n' -A "$UA" 'https://turboembed.com/api/download/get?s=1hri&t=540p'
https://94.131.217.39/e/1795413882/VwUfHRcDlY875MFxjoZnbg/kxjavdis/dl/ecr9o/540p?n=Virginia%20%281983%29.mp4.mp4%20-%20540p.mp4

$ curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -A "$UA" -H 'Range: bytes=0-1023' '<that URL>'
206 video/mp4            # Content-Range: bytes 0-1023/482826753, Accept-Ranges-able
```

`height` in the tier JSON is **not** the label (384/416/464/480 observed for the same `540p`
label across samples) — the label is the quality token, and the `height` field is ignored by
the fix.

### Secondary legs found while chasing the chain

**cdn.loadvid.com (sv2, 8 legs).** Unregistered in the registry before this run, so
`loadExtractor` dropped it. The page carries its config in `window.LoadVidConfig`
(`videoHash`, `videoToken`) plus a CSRF meta; `POST /videos/resolve-token` answers the
manifest **content** (never a URL — the browser wraps it in a `blob:`):

```
$ curl -s -A "$UA" https://cdn.loadvid.com/videos/play/SiBZWePhaGYvOjJwjunK   → 200 text/html
  <meta name="csrf-token" content="…">
  window.LoadVidConfig = { videoHash: 'SiBZWePhaGYvOjJwjunK', videoToken: '…', isProtected: true, … }

$ curl -s -X POST -H 'Content-Type: application/json' -H 'X-CSRF-TOKEN: …' -H 'Origin: https://cdn.loadvid.com' \
       --data-binary '{"token":"…","hash":"SiBZWePhaGYvOjJwjunK"}' https://cdn.loadvid.com/videos/resolve-token
200 application/vnd.apple.mpegurl
#EXTM3U / #EXT-X-VERSION:3 / #EXT-X-TARGETDURATION:5457 / #EXTINF:5457.398356, /
https://server8.stream-baby1.top/user/390/hls/0/e27e28fb-…/vikk7at6539d16.png / #EXT-X-ENDLIST
```

That playlist is **one segment** — the whole movie — and the CDN serves it under a `.png`
name with `content-type: image/png`. The bytes are **MPEG-TS**, not PNG:

```
$ curl -s -H 'Range: bytes=0-18800' '<segment>' → 206 image/png, 18801 bytes
  sync byte 0x47 at offsets 0 / 188 / 376 / 564 → **MPEG-TS 188-byte packets**
  PIDs: 0 (PAT) · 17 (SDT) · 4096 (PMT) · 256 (H.264/AAC elementary)
```

An HLS link needs an http(s) manifest URL, which does not exist here, so the only playable
shape is the segment itself as a progressive source (ExoPlayer sniffs the container;
`content-type` is not consulted on the progressive path). `?ts`/`index.m3u8` sibling paths →
404, so there is no "cleaner" URL to prefer.

**listeamed.net (sv2, 2 legs — host-side, not fixable).** Both sampled embeds carry a
`/Emmanuelle_4_…(1994).mp4` filename suffix and answer **404 `not found`**; the bare
`/e/<id>` answers a JS-challenge shell that re-issues a JWT (`ch=1&js=…&sid=…`) and a
`sid` cookie on every hop:

```
$ curl -s https://listeamed.net/e/VqbX53LozjdxQzp/Emmanuelle_4_Concealed_Fantasy_(1994).mp4
404 not found

$ curl -s https://listeamed.net/e/VqbX53LozjdxQzp
200 <script>window.location.replace('https://listeamed.net/e/VqbX53LozjdxQzp?ch=1&js=<jwt>&sid=<uuid>')</script>
$ curl -s -b/-c cookie '<that URL>'  → 302 + Set-Cookie: sid=… ; re-entry serves the loading shell again
$ (3rd hop) → 429 Too many requests      # rate-limited from this IP — probing stopped here
```

No server-side player HTML exists to parse, so **no adapter can be written** without a
browser-fingerprint instrument (the exact class of thing ADR-0005/D8 rules out). Recorded as
a **data-completeness note**, not a `Blocked`, and **not** filed as a finding issue: there is
no actionable server-side work, and both legs belong to videos whose other servers resolve.

---

## Fix (hardening rung — structural, shared/)

TDD-first, red → green at pure `Parse` seams with live fixtures under
`shared/src/test/resources/turbo-chain/`; the provider loses its host branch entirely.

1. **`shared/.../TurboChain.kt`** (new): `HlsFastParse` / `TurboEmbedParse` / `LoadVidParse`
   pure helpers + three adapters.
   - `HlsFast` (`https://hlsfast.com`) resolves `/#<hash>` → `api/public/resolve` and **hands
     the destination back to `loadExtractor`** — a future destination host is a registry row,
     never a provider rewrite (multi-host fallback). A direct media URL from the resolve API
     is emitted as-is.
   - `TurboEmbed` (`https://turboembed.com`) turns `api/download/tiers` into one link per tier,
     resolving the `api/download/get` **302** first so the player gets the signed CDN URL, and
     passes the tier **label** through `getQualityFromName` (quality the site exposes).
   - `LoadVid` (`https://cdn.loadvid.com`) posts the page config to `videos/resolve-token` and
     emits the single-segment playlist's segment as `VIDEO`; multi-segment manifests are
     dropped (no addressable source).
2. **`shared/HostRegistry.kt`**: three new rows (`HlsFast()`, `TurboEmbed()`, `LoadVid()`),
   registered last so they win their own prefixes; `HostRegistryTest` (unique mainUrls, no
   host shadowing) stays green.
3. **`Cat3Movie.kt`**: the bespoke `hlsfast.com/#` branch, its AES-CBC constants and the
   `extractHlsFast` companion are **deleted**; `loadLinks` collects the sv1..sv3 embeds and
   dispatches `loadExtractor` once per **distinct** embed (`Parse.distinctEmbeds`).
   `Parse.embedIframe` now **trims** the value — the live markup really does emit
   `src=" https://cdn.loadvid.com/…"` on some pages, and a prefix-matching dispatch drops it.
4. **Version**: `Cat3Movie` bumped in the feature commit; the PR-time version bumper
   (`version-bump.yml`) carries the fleet bump because `shared/` splices into every provider
   (affected-set = ALL).

Tests (all green, 414 executions across debug+release for `Cat3Movie:test`):

- `HlsFastParseTest` ×5 — hash from fragment/whitespace, hostile fragment refused, resolve
  target, non-http refused, direct-media detection.
- `TurboEmbedParseTest` ×3 — code from path (incl. leading space), tier label, dead/malformed
  payloads yield no sources.
- `LoadVidParseTest` ×5 — page config (csrf/hash/token), hash from play path, single-segment
  playlist, multi-segment / non-manifest dropped.
- `Cat3MovieEmbedDispatchTest` ×3 — duplicate legs collapse, blanks are not sources, trimmed
  iframe src.
- Pre-existing `HostRegistryTest` ×11, `HlsFreeParseTest`, `PlaymateParseTest` and the whole
  shared suite stay green (no behaviour change for providers that do not embed these hosts —
  `grep` shows Cat3Movie is the only provider that emits hlsfast/loadvid embeds).

---

## Pipeline verification — `verify.sh` (live)

5 sampled videos, one per source family and end of catalogue; search family
`{emmanuelle, emmanuelle/page/2, virginia}` (page 2 disjoint, 20/20; `virginia` exercises
check-5 agreement with `virginia-1983`), home family `/new-movies` + `/new-movies/page/2`
(the paginating row — 20/20 disjoint; the **clamped** `/` row is deduped in-code and recorded
above instead of being asserted raw).

```
── check 1: search pages (3)
GET https://cat3movie.org/search/emmanuelle → 200; 'article.thumb' matches: 23
GET https://cat3movie.org/search/emmanuelle/page/2 → 200; 'article.thumb' matches: 23
GET https://cat3movie.org/search/virginia → 200; 'article.thumb' matches: 5
── check 1a: homepage pages (2)
GET https://cat3movie.org/new-movies → 200; 'article.thumb' matches: 23
GET https://cat3movie.org/new-movies/page/2 → 200; 'article.thumb' matches: 23
── check 1b: quick search (0)
NOTE: no --quick-search-url — required when FINDINGS records a distinct quick-search endpoint; its absence must be explicit in FINDINGS
── check 2: video pages (5 URLs)
GET https://cat3movie.org/dimension-5-1966 → 200; 'script' matches: 25
GET stream (https://hlsfree.com/api/hls/serve?token=bf4e32d5f0e9…) → 200 application/vnd.apple.mpegurl
GET … → 'section.related-movies article.thumb' matches: 11
GET https://cat3movie.org/the-lovers-guide-making-sex-even-better-1992 → 200; 'script' matches: 25
GET stream (https://94.131.217.39/e/1795414718/…/bjrpn3ib/dl/gahim/540p…) → 206 video/mp4
GET … → 'section.related-movies article.thumb' matches: 11
GET https://cat3movie.org/virginia-1983 → 200; 'script' matches: 25
GET stream (https://94.131.217.39/e/1795414721/…/kxjavdis/dl/ecr9o/540p…) → 206 video/mp4
GET … → 'section.related-movies article.thumb' matches: 11
GET https://cat3movie.org/emmanuelle-4-concealed-fantasy-1994 → 200; 'script' matches: 25
GET stream (https://94.131.217.39/e/1795414728/…/vd34enez/dl/gvz3r/540p…) → 206 video/mp4
GET … → 'section.related-movies article.thumb' matches: 11
GET https://cat3movie.org/lust-for-dracula-2004 → 200; 'script' matches: 25
GET stream (https://94.131.217.39/e/1795414731/…/5f5rk5bq/dl/i1izw/540p…) → 206 video/mp4
GET … → 'section.related-movies article.thumb' matches: 11
field 'tags': present on all 5 sampled pages
field 'actors': present on all 5 sampled pages
field 'year': present on all 5 sampled pages
NOTE: no --video-duration-selector — exposure not asserted; FINDINGS must state whether the site exposes duration
── check 5: search ↔ load agreement
── check 6: LoadResponse completeness (code vs FINDINGS)
field 'recommendations': 2 assignment(s) in Cat3Movie
field 'tags': 2 assignment(s) in Cat3Movie
field 'plot': 2 assignment(s) in Cat3Movie
field 'year': 2 assignment(s) in Cat3Movie
field 'actors': 3 assignment(s) in Cat3Movie
RESULT: PASS
```

`--stream-url` overrides are the documented mechanism for JS/ajax-built streams (#876
precedent): the provider-resolved URL per page, position-matched to `--video-url`, and
`--stream-selector script` matches the ajax player block (streams are not in the initial
watch-page HTML — the historical #522/#645 caveat, unchanged).

**Loadvid leg, verified separately** (it is deliberately *not* in the PASS set above because
of its CDN quirk, not because it is unverified):

```
GET https://cat3movie.org/street-people-1976 → 200; 'script' matches: 25
GET stream (https://server8.stream-baby1.top/…/sjvuncDs04f24f.png…) → 206 image/png
FAIL stream content-type (…/street-people-1976: …/sjvuncDs04f24f.png)
```

The FAIL is the tool's `video/*|mpegurl` bar meeting the CDN's `.png` naming — the body is the
188-byte-synced MPEG-TS above (206, Range-capable, 3.4 GB movie in one segment). No http(s)
manifest or `.ts` alias exists. **In-app verification note: the loadvid source plays only if
ExoPlayer's progressive sniffer picks the TS container — a maintainer check at merge.**

**Duration:** the site exposes no duration for these movies (`p.duration`/meta absent on all 5
sampled pages) — recorded as site-native absence, not drift. `year` **is** exposed
(`p.released a[href*=release/]`, all 5 pages), and the provider keeps its title-suffix fallback.

---

## Registry write (ADR-0013 — nothing in the PR diff)

Posted as a comment on #1448 (`<!-- findings-registry-entry -->` + fenced json) for the
orchestrator's `registry_sync`: provider entry (`verdict` + `tier`) and one `last_runs` entry
with `false_positives: []` and `escalation_tier: unavailable` (#1434).

## False positives

None this run: 0 of 0 findings filed (the one host-side limitation was recorded, not filed).

## In-app-verification notes (maintainer, at merge)

1. hlsfast → turboembed mp4: signed CDN URL, `Range` 206 — seeking should work; **quality
   shows as 540p** (tier label).
2. loadvid leg: single-segment TS served as `.png` — sniffing playback, no quality metadata.
3. The `sv1..sv3` legs collapse to one link per distinct embed; when all three servers are
   hlsfast (5/13 samples) the user sees exactly **one** 540p source instead of three
   identical ones.
