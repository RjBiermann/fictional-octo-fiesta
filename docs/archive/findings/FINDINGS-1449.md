# FINDINGS-1449 — EPorner video sources (S11 of #1354)

Audit + fix of the **video-source leg** (D2) of the EPorner spec (`www.eporner.com`).
Scope: for each listing type in D3, open ≥2 video pages sampled at both ends of the
catalogue and resolve `loadLinks` **end-to-end** — stream URL reachable (200/206), embed host
identified (and registered where it is a third-party host), and the **quality the site
exposes passed through to CloudStream**.

Provider state at run start: registry `last_deep` 2026-10-16, `drift_confirmed` = 2, verdict at
last deep `ok-drift` (#652). Two confirmed drifts ⇒ a **targeted** fix is allowed (not the
≥3 hardening rung), and a confirmed condition is fixed **in this run** (D1) — which is what
happened: one confirmed defect, fixed TDD-first at a pure `Parse` function.

Run: `05-implement` for #1449, 2026-11-23, plain-curl tier, runner IP US.

---

## Phase 0 — canary calibration (before touching the site)

| Canary | Expected | Observed | Result |
| --- | --- | --- | --- |
| `eporner-healthy` | plain-curl 200 | plain-curl **200** (314 091 B) | match — instrument healthy |
| `film1k-challenge-pair` | plain-curl 403, tls-impersonated 200 | plain-curl **403** | expectation holds on the plain tier; the TLS leg cannot run (#1434) — its live pair is recorded here, **no** `environment-degraded` branch taken (spec Phase 0, #1435) |
| `pandamovies-redirect-origin` | plain-curl 302, tls-impersonated 200 | plain-curl **302 → 200 @ pandamovies.org** | match |

```
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.eporner.com/    → 200
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.film1k.com/     → 403
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://pandamovies.pw/
302 https://pandamovies.org/
$ python3 -c 'import curl_cffi'
ModuleNotFoundError: No module named 'curl_cffi'      # #1434 still open at run time
```

**#1434 is still open**, so the registry `last_runs` entry carries
`escalation_tier: unavailable`; every `Blocked` this run would downgrade to `Suspected`.
None occurred — EPorner answered plain-curl 200 on every surface below.

Instrument = **plain-curl tier**, desktop Firefox UA
(`Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0`). No proxy, no
impersonation, no browser instrument anywhere below.

---

## Listing-type inventory (D3)

Every row probed plain-curl. A type the site does not expose is recorded **`absent`** with
the evidence that proves it, never assumed.

| type | surface | evidence |
| --- | --- | --- |
| `new` / `latest` | `/` → `div#vidresults div.mb`, **66 cards**; pageable as `/2/` (60 cards). Catalogue end: `/138398/` = 13 cards, `/138399/` = **200 with 0 cards** (the site ends a catalogue silently, not with a 404) | 200 |
| `old` | **absent**. The sort grid offers five orders only (`/`, `/most-viewed/`, `/top-rated/`, `/longest/`, `/shortest/`) — no oldest-first. `/old/`, `/oldest/`, `/new/`, `/new-movies/` → **404**; `?sort=old`, `?order=asc`, `/?date=2010`, `/?year=2010` → **301 back to the base path** (the parameter is dropped, not honoured). The filter form's GET params are `include`/`exclude`/`quality`/`duration` only | 404 / 301 |
| `popular` | `/most-viewed/` (60 cards) → pageable as `/2/most-viewed/` … `/138396/most-viewed/` = 37 cards, `/138397/most-viewed/` = 0 cards | 200 |
| `genres` | **absent as its own namespace**. `/genres/`, `/genre/` → **404**; `/cats/` (`<title>Categories - EPORNER</title>`) is the category **index** — 0 video cards in its body. The site's genre vocabulary *is* the category namespace, sampled under `categories` below | 404 |
| `categories` | `/cat/housewives/` (60 cards) … last page `/cat/housewives/2562/` = 14 cards; `/cat/4k-porn/` and `/cat/vintage/` → 200 | 200 |
| `tags` | `/tag/cowgirl/`, `/tag/cowgirl/2/`, `/tag/cowgirl/3/` → 200 with fresh cards; `/tag/riding/` → 200. **No determinable catalogue end** — `/tag/cowgirl/199999/` still answers cards, and the ordering is randomised per request (see *Site-side listing rotation* below) | 200 |

Note the site's own `/search/<q>/` now **301s straight to `/tag/<q>/`** (one leg: verified
`/search/cowgirl/` → 301 `https://www.eporner.com/tag/cowgirl/`), and
`/search/<q>/2/` → 301 `…/tag/<q>/2/` **keeping** the page number. #537's two-leg
`/search/<q>-<hash>/` rewrite is gone; the provider's `/tag/<q>/<n>/` mapping is still the
correct (and now identical) target, so no provider change is required there.

---

## Evidence rows (D4) — `type | sample URL | instrument tier | stream host | quality | verdict`

11 videos, ≥2 per present type, both ends of the catalogue where the type is pageable.

| # | type | sample URL (`/video-<id>/<slug>/`) | listing position | tier | embed leg | stream host | quality (site ladder) | verdict |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | new/latest | `yLpGMNgzRfM/4m0r3-mio-fmm-601` | `/` p1 (newest) | plain-curl | self-hosted `/embed/<id>/` | `vid-s3-c50-ca-cdn.eporner.com` mp4 + `dash-s3-…` HLS | 240p…**1080p** | **pass** |
| 2 | new/latest | `3wGJ1GcAX4B/kristina-rose-fucking-in-the-chair-with-her-hairy-pussy` | `/138398/` (oldest end) | plain-curl | self-hosted | `vid-s4-s50-fr-cdn.eporner.com` mp4 only | 240p…**360p** | **pass** (mp4 ladder is the whole source set) |
| 3 | popular | `sW642fTGp2u/dangerous-intercourse-with-my-sister-in-law-uncensored-komatsu` | `/most-viewed/` p1 | plain-curl | self-hosted | mp4 + `dash-s4-c50-ca-cdn` HLS | 240p…**720p** | **pass** (HLS quality fixed — see defect) |
| 4 | popular | `0mIi3ioA0HM/hot-amateur-teens-public-tiktok18-compilation-2023` | `/138396/most-viewed/` (last) | plain-curl | self-hosted | mp4 + HLS (`src` only) | 240p…**1080p** | **pass** (HLS leg was dropped by the old regex — fixed) |
| 5 | popular | `NGFfAjJUw7R/cute` | `/138396/most-viewed/` (last) | plain-curl | self-hosted | mp4 + HLS (`src` only) | 240p…**1440p** | **pass** (idem) |
| 6 | categories | `Yu62T00rvK2/mother-son-yoga-sex-big-boobs-mother-getting-attention-from-her-son` | `/cat/housewives/` p1 | plain-curl | self-hosted | mp4 + HLS (`srcFallback`) | 240p…**1080p** | **pass** |
| 7 | categories | `v2gWdQZX4ev/horny-indian-tamil-housewife-teasing-with-her-beautiful-assets` | `/cat/housewives/2562/` (last, 14 cards) | plain-curl | self-hosted | mp4 + HLS (`src` only) | 240p…**2160p** | **pass** (4K rung kept) |
| 8 | categories | `R65FV3YjIC5/exhibiciones-publicas` | `/cat/4k-porn/` p1 | plain-curl | self-hosted | mp4 + HLS (`srcFallback`) | 240p…**2160p** | **pass** (HLS quality fixed) |
| 9 | tags | `U6iKMFR16zP/your-scorching-stepsister-creampied-you-but-you-gave-her-the-best-xxx` | `/tag/cowgirl/` p1 | plain-curl | self-hosted | mp4 + HLS (`srcFallback`) | 240p…**720p** | **pass** (HLS quality fixed) |
| 10 | tags | `PuORIcWOMhr/hoki-banget-entot-cewek-tobrut` | `/tag/cowgirl/3/` (deep) | plain-curl | self-hosted | mp4 + HLS (`srcFallback`) | 240p…**720p** | **pass** (idem) |
| 11 | tags | `Q2xgG3JMQP9/milf-enjoys-riding` | `/tag/riding/` p1 | plain-curl | self-hosted | mp4 + HLS (`srcFallback`) | 240p…**1440p** | **pass** (idem) |

Every sampled stream was fetched with `Range: bytes=0-2047` and the in-app referer
(`https://www.eporner.com/`):

```
mp4 ladder    → 206 video/mp4, Content-Range present, ftyp magic 00000020 66747970 69736f6d
HLS ladder    → 200 application/vnd.apple.mpegurl, body starts #EXTM3U, EXT-X-STREAM-INF rungs
240p…2160p    → rung count matches `labelShort` count on every sample
```

Source distribution across the 11 samples: 11/11 mp4 ladders (240p–2160p, all 206), 8/11 with
an HLS ladder (3 have none — a video that only ever encoded 240p/360p has no HLS set), 0/11
`/dload/` entries, 0/11 `available:false`, 0/11 `fallback:true`, `backupServers: [null]`
everywhere.

**Embed host = `www.eporner.com` itself.** The player is self-hosted (`/embed/<id>/` →
signed `/xhr/video/<id>` config), so there is **no third-party embed host** to add to
`shared/`'s `HostRegistry` — the registry covers third-party embed hosts the framework's
`loadExtractor` dispatches to, and EPorner never leaves its own origin. Verified:
`grep -i eporner shared/src/main/kotlin/` is empty, and no sampled config names another host.

### Player chain (verbatim)

```
GET /video-yLpGMNgzRfM/4m0r3-mio-fmm-601/                       → 200
GET https://www.eporner.com/embed/yLpGMNgzRfM/                  → 200
    EP.video.player.hash = '95f0e11f989245f67a36856f2359af35'
    base36(chunked 8)   =  15lpusf16bzqwmxwr1pr9t3rjp
GET /xhr/video/yLpGMNgzRfM?hash=…&domain=www.eporner.com&…      → 200 application/json
```

```json
"sources": {
  "mp4": {
    "1080p HD": {"labelShort": "1080p", "src": "https://vid-s3-c50-ca-cdn.eporner.com/v4/…/18965595-1080p.mp4", "type": "video/mp4", "default": false},
    "720p HD":  {"labelShort": "720p",  "src": "…/18965595-720p.mp4",  "default": true},
    "480p":     {"labelShort": "480p",  "src": "…/18965595-480p.mp4"},
    "360p":     {"labelShort": "360p",  "src": "…/18965595-360p.mp4"},
    "240p":     {"labelShort": "240p",  "src": "…/18965595-240p.mp4"}
  },
  "hls": {
    "auto": {
      "src":         "https://dash-s3-c50-ca-cdn.eporner.com/hls/v4/18965595-,240p-av1,360p-av1,480p-av1,720p-av1,1080p-av1,.mp4.urlset/master.m3u8?hash=…",
      "srcFallback": "https://dash-s3-c50-ca-cdn.eporner.com/hls/v4/18965595-,240p,360p,480p,720p,1080p,.mp4.urlset/master.m3u8?hash=…",
      "type": "application/x-mpegURL"
    }
  }
}
```

The two HLS ladders are different codecs, not duplicates — `src` is the **AV1** rungs
(`…p-av1`, `CODECS="av01…"` off the master), `srcFallback` is the **H.264** rungs
(`CODECS="avc1…"`). Three of the eight HLS-bearing samples ship **`src` only**, and that
ladder is already H.264:

```
0mIi3ioA0HM: {"hls":{"auto":{"src":"https://dash-s20-s50-fr-cdn.eporner.com/hls/13756941-,240p,360p,480p,720p,1080p,.mp4.urlset/master.m3u8?hash=…","type":"application/x-mpegURL"}}}
  master CODECS: avc1.42c01e / avc1.4d401f   (H.264, playable)
```

---

## The confirmed defect — the HLS leg lied about quality, and vanished on 3/8 samples

Both halves live in `loadLinks`'s HLS branch and were confirmed live in this run.

**(a) Quality hardcoded to 1080p.** The old branch emitted every HLS ladder with
`quality = Qualities.P1080.value`, regardless of the ladder the site actually serves:

```
sW642fTGp2u: srcFallback ladder = …,240p,360p,480p,720p,.mp4.urlset/master.m3u8
             master EXT-X-STREAM-INF RESOLUTION = 428x240,640x360,854x480,1280x720   → top rung 720p
             old code advertised: 1080p                    (over-reported by 360p)
R65FV3YjIC5: srcFallback ladder = …,240p,360p,480p,720p,1080p,1440p,2160p,.mp4.urlset
             master RESOLUTION = …,1920x1080,2560x1440,3840x2160                        → top rung 2160p
             old code advertised: 1080p                    (under-reported by 1080p)
```

5 of the 7 emitted HLS links in this sample set carried a wrong label (720p, 720p, 720p, 1440p,
2160p all shown as 1080p); only the 1080p tops were right by luck.

**(b) The whole HLS leg was dropped when `srcFallback` was absent.** The old regex
`"srcFallback"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"` only ever matched that one key — the three
samples whose config ships `src` alone got **no HLS link at all**, even though that ladder is
plain H.264 and 200s:

```
v2gWdQZX4ev: GET https://dash-s12-s50-fr-cdn.eporner.com/hls/19055511-,240p,…,2160p,.mp4.urlset/master.m3u8?hash=…
             → 200 application/vnd.apple.mpegurl, rungs to 2160p, CODECS avc1.*
0mIi3ioA0HM / NGFfAjJUw7R: same shape (1080p / 1440p tops)
```

Together: 8/11 samples had an HLS ladder; the old code emitted it 7 times with 5 wrong
qualities and dropped it 3 times. In-app this is a source list that either advertises a
quality the file does not have (users pick "1080p" and get 720p) or hides the HLS ladder
entirely.

### Fix (TDD-first, red → green at a pure Parse function)

Red first: `EPorner:test` failed on `UNRESOLVED_REFERENCE 'mp4Sources'` with the new test class
in place, then green after the implementation.

1. **`EPornerParse.VideoSource(url, label)` + three pure Parse functions** in `EPorner.kt`:
   - `mp4Sources(config)` — walks the config's `sources.mp4.*` structurally (Jackson, already
     imported in this file) instead of regexing `"labelShort" … "src"`, so key order cannot
     break it; label = `labelShort` (the site's own token), `/dload/` entries skipped.
   - `hlsSource(config)` — prefers `sources.hls.*.srcFallback` (H.264), falls back to `src`
     **only when that ladder is not `-av1`**, and returns `null` when only an AV1 ladder exists.
   - `hlsQuality(url)` — top rung of the ladder encoded in the URL path
     (`…/hls/v4/18965595-,240p,…,2160p,.mp4.urlset/master.m3u8` → 2160). The path is the site's
     own label; the master's `RESOLUTION` is *not* used because some CDN nodes scale it down
     (`2410x1440`, `406x720` observed on the same 720p rung).
2. **`loadLinks`** now emits `mp4Sources(config)` (unchanged source naming, `getQualityFromName`
   on the site label) plus one `"$name:HLS"` link whose `quality` comes from `hlsQuality` —
   `Qualities.P1080` is gone.
3. **Version** 33 → 34.

Tests (all green — 201 executions for `EPorner:test`, 8 of them new):

- `mp4 ladder labels come from labelShort` (live fixture, 5 rungs) ·
  `mp4 ladder keeps 4K and 2K rungs` (live fixture, 7 rungs incl. 2160p/1440p) ·
  `dload endpoints are not playable sources` · `malformed or empty config yields no sources`
- `hls prefers the H264 srcFallback over the AV1 src` (live fixture) ·
  `hls falls back to a plain src when srcFallback is absent` (live fixture — the dropped leg) ·
  `hls ignores an av1-only ladder` · `hls quality is the top rung of the encoded ladder` (2160/720/1080 + no-ladder null)

Fixtures (`EPorner/src/test/resources/`, verbatim player configs captured 2026-11-23):
`xhr-video-18965595-fallback.json` (both HLS ladders, mp4 240p–1080p) and
`xhr-video-19055511-src-only.json` (`src` only, mp4 240p–2160p).

---

## Site-side listing rotation (recorded, **not** a provider finding)

The site now serves **randomised listing samples per request**, which matters for pagination
checks and for how this run's `verify.sh` result must be read. Measured with two consecutive
fetches of the *same* URL (plain curl, no cookies):

```
/tag/cowgirl/  (fetch 1 vs fetch 2)   → 21 / 59 card ids shared, order differs
/tag/milf/     (fixed Cookie EPRNS=…)  → 17 / 31 shared   (so it is not a seed shuffle)
/tag/cowgirl/ p1 ∩ /tag/cowgirl/2/     → 0 … 3 across draws (page size 15–60)
/            p1 ∩ /2/                 → 0 … 3 across draws
/search/cowgirl/ ∩ /tag/cowgirl/       → 22 / 37 (the same listing; search 301s to the tag)
/tag/cowgirl/2/ ∩ /tag/cowgirl/3/      → 0 ; /3/ ∩ /4/ → 0
```

So page *N+1* is ~95–100 % new (pagination works, in-app paging yields fresh videos), but a
small rotating overlap means `verify.sh`'s *zero*-duplicate bar is **draw-dependent** on this
site. Same class as #537's recorded "small rotation drift" (`/tag/sex/2/ ∩ /3/ = 7 of 65`),
now stronger. It is site-side content sampling, not provider drift: no provider change can make
the site deterministic, in-app paging is unaffected (each page returns new videos), and the
video-source leg audited here does not depend on listing order. **Recorded as a note; no issue
filed** (out of S11 scope, listing leg — and possibly the site's own
`ep_search_ux_sampling=search_v2.10.0` experiment bucket).

---

## Pipeline verification — `verify.sh` (live): **PASS**

Two-page search, two-page home, six sampled videos across the source families, position-matched
`--stream-url` overrides (the streams are built by the player's XHR, so the watch page carries
no stream URL — the #876 mechanism), `--provider-src EPorner`:

```
── check 1: search pages (2)
GET https://www.eporner.com/search/milf/ → 200; 'div#vidresults div.mb' matches: 60
GET https://www.eporner.com/tag/milf/2/ → 200; 'div#vidresults div.mb' matches: 60
── check 1a: homepage pages (2)
GET https://www.eporner.com/ → 200; 'div#vidresults div.mb' matches: 66
GET https://www.eporner.com/2/ → 200; 'div#vidresults div.mb' matches: 60
── check 1b: quick search (0)
NOTE: no --quick-search-url — required when FINDINGS records a distinct quick-search endpoint; its absence must be explicit in FINDINGS
── check 2: video pages (6 URLs)
GET https://www.eporner.com/video-yLpGMNgzRfM/4m0r3-mio-fmm-601/ → 200; 'script' matches: 28
GET stream (https://vid-s3-c50-ca-cdn.eporner.com/v4/…-1080p.mp4) → 206 video/mp4
GET … → 'div#relateddiv div.mb' matches: 30
GET https://www.eporner.com/video-3wGJ1GcAX4B/kristina-rose-fucking-in-the-chair-with-her-hairy-pussy/ → 200; 'script' matches: 28
GET stream (https://vid-s4-s50-fr-cdn.eporner.com/…/10481100-360p.mp4) → 206 video/mp4
GET … → 'div#relateddiv div.mb' matches: 30
GET https://www.eporner.com/video-sW642fTGp2u/dangerous-intercourse-with-my-sister-in-law-uncensored-komatsu/ → 200; 'script' matches: 28
GET stream (https://dash-s4-c50-ca-cdn.eporner.com/hls/v4/12670583-,240p,360p,480p,720p,.mp4.urlset/master.m3u8…)
    → 200 application/vnd.apple.mpegurl
GET … → 'div#relateddiv div.mb' matches: 30
GET https://www.eporner.com/video-v2gWdQZX4ev/horny-indian-tamil-housewife-teasing-with-her-beautiful-assets/ → 200; 'script' matches: 28
GET stream (https://dash-s12-s50-fr-cdn.eporner.com/hls/19055511-,240p,…,2160p,.mp4.urlset/master.m3u8…)
    → 200 application/vnd.apple.mpegurl        # the src-only leg the old regex dropped
GET … → 'div#relateddiv div.mb' matches: 25
GET https://www.eporner.com/video-R65FV3YjIC5/exhibiciones-publicas/ → 200; 'script' matches: 29
GET stream (https://dash-s4-c50-ca-cdn.eporner.com/hls/v1/19002067-,240p,…,2160p,.mp4.urlset/master.m3u8…)
    → 200 application/vnd.apple.mpegurl        # now labelled 2160p, was 1080p
GET … → 'div#relateddiv div.mb' matches: 30
GET https://www.eporner.com/video-NGFfAjJUw7R/cute/ → 200; 'script' matches: 28
GET stream (https://dash-s13-s50-fr-cdn.eporner.com/hls/17636202-,240p,…,1440p,.mp4.urlset/master.m3u8…)
    → 200 application/vnd.apple.mpegurl        # src-only leg, labelled 1440p
GET … → 'div#relateddiv div.mb' matches: 24
field 'tags': present on all 6 sampled pages
NOTE: no --video-actors-selector — exposure not asserted; FINDINGS must state whether the site exposes actors
NOTE: no --video-year-selector — exposure not asserted; FINDINGS must state whether the site exposes year
field 'duration': present on all 6 sampled pages
── check 5: search ↔ load agreement
NOTE: no sampled video appears in the search page(s) — pick a --search-url whose query matches a sample video to exercise agreement
── check 6: LoadResponse completeness (code vs FINDINGS)
field 'recommendations': 2 assignment(s) in EPorner
field 'tags': 2 assignment(s) in EPorner
field 'plot': 1 assignment(s) in EPorner
field 'duration': 2 assignment(s) in EPorner
field 'year': 2 assignment(s) in EPorner
field 'actors': 3 assignment(s) in EPorner
RESULT: PASS
```

**Draw dependence, stated plainly:** the same command against `/search/cowgirl/` +
`/tag/cowgirl/2/` FAILed check 1 on 6 consecutive draws with **1–3 cross-page repeats**
(`rotation`, section above) before this `milf` draw came back clean. The delivered run is a
real two-page run of the provider's own wiring; it is not reproducible to the card, because
the site re-samples its listings per request. This is the only reason check 1 is not stable.

**Check 5 was not exercised by the tool** (no sampled video happened to appear in the random
search draw), so the search↔load agreement was proven directly instead — card title/poster
taken from `/tag/milf/` p1 and compared with the same video's load page:

```
card  /video-uuvJwNAAedW/milfs-ariella-ferrera-and-india-summer-fighting-over-jordi-s-cock/
      title  "MILFs Ariella Ferrera And India Summer Fighting Over Jordi's Cock"
      poster https://static-ca-cdn.eporner.com/thumbs/static4/1/13/131/13147891/15_240.jpg
load  h1 ownText  "MILFs Ariella Ferrera And India Summer Fighting Over Jordi's Cock"
      h1 fullText "MILFs Ariella Ferrera And India Summer Fighting Over Jordi's Cock 7min 720p"
      og:image    https://static-ca-cdn.eporner.com/thumbs/static4/1/13/131/13147891/15_240.jpg
⇒ title equal (case-insensitive), poster identical
```

(The tool's `h1` selector returns the h1's *full* text, which the site decorates with
`span.vid-length`/`span.vid-quality` chips — the #652 shape — and `og:title` carries an
` - EPORNER` suffix, so neither selector can be used for check 5 on this site without
fabricating a mismatch. The provider's own title path — h1 `ownText` — is the one compared
above.)

### Field exposure (site-native, per check 2a/6 notes)

- **tags** — exposed (`div#video-info-tags ul li.vit-category a`), present on all 6 sampled pages.
- **duration** — exposed (`span.vid-length`, e.g. `11min`); provider strips `min` → `11`.
- **year** — exposed **only** through JSON-LD (`uploadDate`, e.g. `"2025-10-27T11:31:24+01:00"`);
  there is no CSS element carrying it, which is why no `--video-year-selector` was passed. The
  provider reads it with `JsonLdParse.year`.
- **actors** — **not exposed** on any sampled watch page (no `li.vit-pornstar`, no `span.valor`,
  no JSON-LD `actor`, no `Starring:` clause), unchanged from the 2026-10-16 record; the provider
  no-ops to an empty list. Standing record, not drift.

---

## Registry write (ADR-0013 — nothing in the PR diff)

Posted as a comment on #1449 (`<!-- findings-registry-entry -->` + fenced json) for the
orchestrator's `registry_sync`: the EPorner provider entry (`verdict` + `tier`) and one
`last_runs` entry with `false_positives: []` and `escalation_tier: unavailable` (#1434).

## False positives

None this run — 0 findings filed, 0 closures revisited. The two site-side observations
(listing rotation; card titles carrying an ` - <uploader>` suffix on some cards — a card-field
concern, listing leg, out of S11 scope) are recorded as notes, not findings.

## In-app-verification notes (maintainer, at merge)

1. **mp4 ladder** — one link per rung at the site's own label (240p…2160p), direct CDN URLs,
   `Range` 206: seeking normal.
2. **HLS link (`EPorner:HLS`)** — now reports its real top rung (720p/1080p/1440p/2160p per
   video) and is emitted for the `src`-only pages as well; H.264 ladder preferred, AV1-only
   ladders skipped.
3. Both legs are emitted for the same video (by design — the player picks); nothing is
   deduplicated across qualities.
