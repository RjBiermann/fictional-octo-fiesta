# FINDINGS-1450 — Javmost video sources (S12 of #1354)

Audit + fix of the **video-source leg** (D2) of the Javmost spec (`www.javmost.ws`).
Scope: for each listing type in D3, open ≥2 video pages sampled at both ends of the
catalogue where the type is pageable and resolve `loadLinks` **end-to-end** — AJAX server
row → embed target → stream URL reachable (200/206) → embed host identified/registered →
quality passed through where the site exposes it.

Provider state at run start: registry `last_deep` 2026-10-16, `drift_confirmed` = 2,
verdict at last deep `ok-drift` (#640). Two confirmed drifts ⇒ a **targeted** fix is
allowed (not the ≥3 hardening rung), and a confirmed condition is fixed **in this run**
(D1). One confirmed defect was found and fixed TDD-first at a pure `Parse` function.

Run: `05-implement` for #1450, 2026-11-23, plain-curl tier, runner IP US.
Workflow run: `https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/45166440155`.

---

## Phase 0 — canary calibration (before touching the site)

| Canary | Expected | Observed | Result |
| --- | --- | --- | --- |
| `eporner-healthy` | plain-curl 200 | plain-curl **200** (313 111 B) | match — instrument healthy |
| `film1k-challenge-pair` | plain-curl 403, tls-impersonated 200 | plain-curl **200** (214 913 B) | **expectation-stale** (#1435 open) — its live pair is recorded here; **no** `environment-degraded` branch taken |
| `pandamovies-redirect-origin` | plain-curl 302, tls-impersonated 200 | plain-curl **302 → https://pandamovies.org/** (follow: 403 challenge) | plain-tier match; TLS leg cannot run (#1434) |

```
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://www.film1k.com/     → 200 214913
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://www.eporner.com/    → 200 313111
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://pandamovies.pw/      → 302 https://pandamovies.org/
$ python3 -c 'import curl_cffi'
ModuleNotFoundError: No module named 'curl_cffi'      # #1434 still open at run time
```

**#1434 is still open**, so the registry `last_runs` entry carries
`escalation_tier: unavailable`; every `Blocked` this run is downgraded to `Suspected`.
The only `Suspected` rows below are Cloudflare-challenged media hosts — no finding is
filed from challenge evidence alone.

Instrument = **plain-curl tier**, desktop Firefox UA
(`Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0`).
No proxy, no impersonation, no browser instrument anywhere below. The app path was
mirrored for the player leg (AJAX POST + embed `Referer` / `Sec-Fetch-Dest: iframe`).

---

## Listing-type inventory (D3)

The site serves listings two ways: server-rendered HTML (`/category/...`, `/search/...`,
`/tag/...`, `/top.../`) and the JSON feed `/showlist2/{group}/{page}/{type}/` the provider
uses. Every row below was probed plain-curl.

| type | surface | evidence |
| --- | --- | --- |
| `new` / `latest` | `/` and `/category/all/page/1/` (24 cards); `/release/new/` (24 cards, pageable `page/2`…); catalogue tail `/category/all/page/13821/` = 6 cards | 200 |
| `old` | **absent**. `/release/old/` and `/release/all/` **200 but silently fall back to New Release** (title “Sort by New Release”, first card `DAL-013`, byte-identical to `/release/new/`); `/old/` → **404**; `/category/all/old/` → 200 with 0 cards; `?order=old`, `?order=asc`, `?sort=old` ignored (first cards unchanged); `/showlist2/old/1/release/` answers the same latest feed as `/showlist2/new/...` | 200/404, no oldest-first ordering |
| `popular` | `/topview/` (hourly, 24), `/topdaily/` (23), `/topweek/` (24), `/topmonth/` (23) | 200; **not pageable** — `/topview/page/2/` and `/topdaily/page/2/` re-serve page 1 |
| `genres` | site’s genre namespace is `/category/<Genre>/`, e.g. `/category/Solowork/` (24 cards, pageable `page/2`…`page/1500/`) | 200 |
| `categories` | `/category/censor/`, `/category/uncensor/`, `/category/all/`; censor is pageable to `page/4000/` (24 cards; `page/5000/` = 200 with 0 cards), uncensor ends earlier | 200 |
| `tags` | `/tag/debut/` (24 cards); `/tag/debut/page/2/` → **410 Gone** — tag pages are not pageable | 200 / 410 |

Note: the site’s HTML listing pages also contain an inline JS card **template**
(`<div class="card">…${url}…`), so a naive `div.card` selector counts 25 cards and
introduces a `${url}` pseudo-card. Video enumeration below used the JSON feed / real
`a[alt]` anchors; `verify.sh` uses `div.card-block h1.card-title`, which matches the 24
real cards and excludes the `<h4>` template title.

---

## Evidence rows (D4)

`type | sample URL | instrument tier | stream host | quality | verdict`

At least two videos per present type; the far-end page is named for pageable types.

| # | type | sample URL | listing position | tier | stream host | quality | verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | new/latest | `/ONEZ-351-REDUCING-MOSAIC/` | `/category/all/page/1/` (newest) | plain-curl | doo → `cdn4.mostplayer.com` (m3u8) | single rendition (no `RESOLUTION`; site exposes no quality label) | **pass** |
| 2 | new/latest | `/EKDV-245/` | `/category/all/page/13821/` (oldest tail, 6 cards) | plain-curl | doo → `cdn4.mostplayer.com` (m3u8) | single rendition | **pass** |
| 3 | new/latest | `/SPS-016/` | `/category/all/page/13821/` (oldest tail) | plain-curl | **direct m3u8** → `jl77.boldpureheart.site` | single rendition (not parseable from runner — challenge) | **pass (provider chain)** / stream **Suspected** (CF 403; #1434) |
| 4 | popular | `/DASS-534/` | `/topdaily/` p1 | plain-curl | doo → `cdn.mostplayer.com` (mp4, 206) + `cdn4.mostplayer.com` (m3u8) | single rendition | **pass** |
| 5 | popular | `/TOEN-047-REDUCING-MOSAIC/` | `/topview/` p1 | plain-curl | doo → `cdn4.mostplayer.com` (m3u8) | single rendition | **pass** |
| 6 | genres | `/ZOCM-006-REDUCING-MOSAIC/` | `/category/Solowork/` p1 | plain-curl | doo → `cdn4.mostplayer.com` (m3u8) | single rendition | **pass** |
| 7 | genres | `/KIT-008-UNCENSORED-EDIT/` | `/category/Solowork/page/1500/` (far end) | plain-curl | doo → `cdn4.mostplayer.com` (m3u8) | single rendition | **pass** |
| 8 | categories | `/KBI-087/` | `/category/censor/` p1 | plain-curl | doo → `cdn4.mostplayer.com` (m3u8) | single rendition | **pass** |
| 9 | categories | `/APNS-154/` | `/category/censor/page/2000/` (far end; `page/4000/` is the last page) | plain-curl | doo → `cdn4.mostplayer.com` (m3u8) | single rendition | **pass** |
| 10 | categories | `/CARIBBEANCOM-112226/` | `/category/uncensor/` p1 | plain-curl | doo → `cdn4.mostplayer.com` (m3u8) | single rendition | **pass** |
| 11 | tags | `/HSM-092/` | `/tag/debut/` p1 | plain-curl | doo → `cdn4.mostplayer.com` (m3u8) | single rendition | **pass** |
| 12 | tags | `/BOKD-322/` | `/tag/debut/` p1 (second card) | plain-curl | doo → `cdn4.mostplayer.com` (m3u8) | single rendition | **pass** |
| 13 | categories | `/AV-164/` | `/category/censor/page/4000/` (last page) | plain-curl | doo → `cdn4.mostplayer.com` (m3u8) | single rendition | **Suspected** — provider chain resolves, CDN answers `403 Media unavailable` (site-side dead media) |

`doo` = the site’s `dooplayer.com` / `mostplayer.com` x-embed contract, restored at
#640 and still the dominant current source. Every pass row above was fetched with
`Range: bytes=0-2047` and the app’s referer:

```
m3u8  → 200/206 application/vnd.apple.mpegurl; body starts #EXTM3U (single media playlist)
mp4   → 206 video/mp4, Content-Range present, ftyp magic 00000020 66747970 69736f6d
```

The video pages themselves carry no stream URL — `loadLinks` builds it from the AJAX
response, so the app’s `Referer` is required for the m3u8 hosts:

```
GET cdn4.mostplayer.com/…/v.m3u8   Referer: https://www.mostplayer.com/  → 200
GET cdn4.mostplayer.com/…/v.m3u8   no Referer                            → 403 text/html
```

### Raw transcript — non-pass / Suspected rows

**#3 `/SPS-016/` — AJAX returns a direct media URL, not an embed page** (the confirmed
defect fixed this run):

```
GET https://www.javmost.ws/SPS-016/                                          → 200
$ grep YWRzMQo …                                                             → value present
select_part('1','54',this,'parent',…) / select_part('1','54',this,'child',…)
POST https://www.javmost.ws/ri3123o235r/ (Referer: /SPS-016/)                → 200
{"status":"success","msg":"","data":["https:\/\/jl77.boldpureheart.site\/oo5\/XEn0ezMAjwPK3U_oUqJFbc79gVZ3z8oHFbTItxqPgxcFnNVwwtlTGmo\/qc\/v.m3u8"]}
GET https://jl77.boldpureheart.site/oo5/…/v.m3u8 (Referer: /SPS-016/)        → 403 text/html
    Cloudflare challenge page (datacenter IP); #1434 TLS-impersonation tier unavailable
```

**#13 `/AV-164/` — provider chain resolves; the site’s own media is gone**:

```
GET https://www.javmost.ws/AV-164/                                          → 200
select_part('1','54',this,'parent'|'child',…) → AJAX data:
  https://www.mostplayer.com/embed/e/MTM3MTM5NA.5b60e4dfd97ab6d2
  https://playersb.com/play/ppku2j3gnyff
GET mostplayer embed (Referer + Sec-Fetch-Dest: iframe)                      → 200, x-embed metas
POST https://www.mostplayer.com/api/stream/<token>                           → 200 {"ok":true,"url":"https://cdn4.mostplayer.com/v/…/v.m3u8"}
GET cdn4.mostplayer.com/…/v.m3u8 (Referer: embed)                             → 403 text/plain
    Media unavailable (403)
GET https://playersb.com/play/ppku2j3gnyff (Referer: /AV-164/)                → 200 text/html
    <script>window.location.href="/lander?oref=https%3A%2F%2Fwww.javmost.ws%2FAV-164%2F"</script>
```

`playersb.com` is an ad lander, not a stream host; `javpoll.com` (seen on `/KSBJ-029/`)
does not resolve from the runner (`curl → 000`, DNS empty). Both are treated as
site-side dead sources (`Suspected`), not unsupported-adapter findings — they serve no
media to register.

### Scan breadth (44 video pages across the six surfaces)

Beyond the 13 evidence rows, 44 pages were enumerated and their AJAX server rows
collected. Source-target distribution:

```
mostplayer.com        35
emturbovid.com        19   (TCP connection refused from runner IP — #640 record, Suspected)
dooplayer.com          7
dood.ws / dood.so     12
voe.sx                 4   (DDoS-Guard 403 from runner)
playersb.com           3   (ad lander)
streamtape.com         3   (404 on the sampled old ids)
jl77 / ek9n / …        2   direct m3u8 returned as the “embed” target  ← defect
javpoll.com            1   (DNS/TCP dead)
empty data[] (“null”)  1   (/SDNM-565/ — site returns no source)
```

The two direct-HLS targets are exactly the shape the old `loadLinks` dropped.

---

## The confirmed defect — direct-HLS AJAX targets were dropped

`Parse.embedTargets` correctly returns every `data[]` URL, but `loadLinks` assumed every
target was an **embed page**. When the site returns the HLS stream itself
(`…/v.m3u8`), `loadExtractor` has no prefix to match and `resolveDoo` fetches the m3u8
looking for `x-embed-*` metas that are not there — so the video emitted **zero links**:

```
/SPS-016/     data[] = https://jl77.boldpureheart.site/…/v.m3u8        → old path: 0 links
/NHDTB-715/   data[] = https://ek9n.aubrey-store.site/…/v.m3u8        → old path: 0 links
```

This is a data-completeness defect, not a selector swap: the AJAX contract carries two
target shapes and the provider handled one.

### Fix (TDD-first, red → green at a pure Parse function)

Red first: `Javmost:test` failed on
`Unresolved reference 'isDirectHls'`, then green after the implementation.

1. **`Parse.isDirectHls(url)`** — path-only check (query/fragment stripped, lowercase)
   for `.m3u8`, so a query string containing `v.m3u8` is not a false hit. Deliberately
   **not** a generic `.mp4` check: a dood-style embed page can end in `…/file.mp4` and
   must keep going through the registry (`Parse.isDirectHls` is false for
   `dood.la/e/<id>/…mp4`, covered by a test).
2. **`loadLinks`** — before the emturbovid/doo branches, a direct-HLS target is emitted
   as an `ExtractorLink` (`M3U8`, `Qualities.Unknown`, referer `$mainUrl/`). Embed-page
   targets keep the existing dispatch unchanged.
3. **Version** 27 → 28.

Tests (all green — 219 executions for `Javmost:test`, 7 new):

- `embedTargets keeps a direct m3u8 target from the ajax response` (verbatim SPS-016
  fixture `ajax-direct-m3u8.json`) ·
  `isDirectHls true for an m3u8 target` · `case-insensitive` · `false for a doo embed
  page` · `false for an emturbovid embed` · `false for a dood mp4 embed page` ·
  `ignores an m3u8 token in a query string`

---

## Pipeline verification — `verify.sh` (live): **PASS**

Two-page search, two-page home, six live video pages across the source families, six
position-matched `--stream-url` overrides (the streams are built by the player’s AJAX,
so the watch page carries no stream URL), `--provider-src Javmost`:

```
── check 1: search pages (2)
GET https://www.javmost.ws/search/milf/ → 200; 'div.card-block h1.card-title' matches: 24
GET https://www.javmost.ws/search/milf/page/2/ → 200; matches: 24
── check 1a: homepage pages (2)
GET https://www.javmost.ws/category/all/page/1/ → 200; matches: 24
GET https://www.javmost.ws/category/all/page/2/ → 200; matches: 24
── check 1b: quick search (0)
NOTE: no --quick-search-url — required when FINDINGS records a distinct quick-search endpoint; its absence must be explicit in FINDINGS
── check 2: video pages (6 URLs)
GET …/ONEZ-351-REDUCING-MOSAIC/ → 200; 'div.card-block' matches: 9
GET stream (https://cdn4.mostplayer.com/v/…) → 200 application/vnd.apple.mpegurl; charset=utf-8
GET …/EKDV-245/ → 200; matches: 9
GET stream (https://cdn4.mostplayer.com/v/…) → 200 application/vnd.apple.mpegurl
GET …/DASS-534/ → 200; matches: 9
GET stream (https://cdn4.mostplayer.com/v/…) → 200 application/vnd.apple.mpegurl
GET …/ZOCM-006-REDUCING-MOSAIC/ → 200; matches: 9
GET stream (https://cdn4.mostplayer.com/v/…) → 200 application/vnd.apple.mpegurl
GET …/KBI-087/ → 200; matches: 9
GET stream (https://cdn4.mostplayer.com/v/…) → 200 application/vnd.apple.mpegurl
GET …/HSM-092/ → 200; matches: 9
GET stream (https://cdn4.mostplayer.com/v/…) → 200 application/vnd.apple.mpegurl
NOTE: no --video-tags-selector — exposure not asserted; FINDINGS must state whether the site exposes tags
NOTE: no --video-actors-selector — exposure not asserted; FINDINGS must state whether the site exposes actors
NOTE: no --video-year-selector — exposure not asserted; FINDINGS must state whether the site exposes year
field 'duration': present on all 6 sampled pages
── check 5: search ↔ load agreement
NOTE: no sampled video appears in the search page(s) — pick a --search-url whose query matches a sample video to exercise agreement
── check 6: LoadResponse completeness (code vs FINDINGS)
field 'recommendations': 2 assignment(s) in Javmost
field 'tags': 3 assignment(s) in Javmost
field 'plot': 1 assignment(s) in Javmost
field 'duration': 2 assignment(s) in Javmost
field 'year': 2 assignment(s) in Javmost
field 'actors': 2 assignment(s) in Javmost
RESULT: PASS
```

The listing selector is deliberately `div.card-block h1.card-title` rather than
`div.card`: the page’s inline JS card template is also a `div.card`, adding a `${url}`
pseudo-card that would fake a duplicate on page 2. The real cards are 24/page; the
template’s title is `<h4>`, so the `h1.card-title` selector excludes it. Card hrefs were
enumerated separately from the JSON feed / `a[alt]` anchors for the video samples above.

`--header 'Referer: https://www.mostplayer.com/'` is required for the m3u8 media hosts;
the provider sets `this.referer` to the embed URL it just resolved, which is what the
app path supplies.

### Field exposure (site-native, per check 2a/6 notes)

- **tags / genres** — exposed as `/category/<Genre>/` anchors in the video card-block
  (present on 5/6 sampled pages; `/ONEZ-351-REDUCING-MOSAIC/` carries no genre anchors
  in that block). No all-or-none selector was passed.
- **actors** — exposed as `/star/<name>/` anchors (5/6 sampled pages; `/ONEZ-351/` has
  no star row). No all-or-none selector was passed.
- **year** — exposed as the `Release YYYY-MM-DD` text in `div.card-block`; the provider
  parses it with `Release (\d{4})-`. No CSS element carries it alone, so no selector was
  passed.
- **duration** — exposed as `Time N` minutes in `div.card-block`; provider parses it.
- **plot** — exposed as a second `a[alt]` synopsis anchor in the card-block; provider
  takes the longest matching alt.
- **recommendations** — page-shape dependent, site-side: `a[alt]` related cards are
  present on 3/5 sampled pages (`/EKDV-245/` 11 anchors, `/DASS-534/` 11, `/KBI-087/` 11)
  and the provider’s `Parse.recs` resolves them; `/ONEZ-351-REDUCING-MOSAIC/` and
  `/HSM-092/` render no related cards at all (only the page’s own self anchors), so
  `recommendations` is empty there. Site-side content, not a provider defect. The
  related-videos selector was therefore not passed to `verify.sh` (it would fail on the
  two card-less pages).

---

## Registry write (ADR-0013 — nothing in the PR diff)

Posted as a comment on #1450 (`<!-- findings-registry-entry -->` + fenced json) for the
orchestrator’s `registry_sync`: the Javmost provider entry (`verdict` + `tier`) and one
`last_runs` entry with `false_positives: []` and `escalation_tier: unavailable` (#1434).

## False positives

None this run — 1 in-scope defect fixed, 0 findings filed. `emturbovid.com` TCP-refused
and `voe.sx` DDoS-Guard 403 are runner-IP/network artifacts (Suspected, not findings);
`playersb.com` / `javpoll.com` are dead/ad landers, not unsupported adapters; the
`/SDNM-565/` empty `data[]` is the site returning no source for that title.

## In-app-verification notes (maintainer, at merge)

1. **doo / mostplayer m3u8** — the dominant current source; single media playlist, no
   quality rungs, so the provider correctly reports `Unknown` and the player auto-plays.
2. **Direct-media targets** — `/SPS-016/` and `/NHDTB-715/` now emit the `jl77`/
   `ek9n` `.m3u8` directly (previously 0 links). The runner sees a Cloudflare 403 on
   those hosts (datacenter IP; #1434 escalation unavailable) — confirm playback in-app
   from a residential path.
3. **Old catalogue media** — `/AV-164/` (last censor page) resolves to a CDN that says
   `403 Media unavailable`; the provider did everything it could, the source is dead.
