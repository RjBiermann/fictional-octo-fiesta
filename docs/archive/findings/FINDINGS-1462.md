# FINDINGS-1462 — Film1k (www.film1k.com) video-source pass

Run: 2026-11-25. Issue **#1462** — *S24 — Film1k video sources*, story 24 of 27 of the
video-sources audit (#1354). Scope is the **video-source leg (D2/D3/D4)** only; the card /
listing surface is re-probed only as far as this run's own enumeration needed it (D5) — and
this run's enumeration broke immediately, see *Drift and fix*.

**Verdict: `ok-drift` — one confirmed condition (card-markup rename), fixed in this run.**
`drift_confirmed` 1 → **2**; `last_deep` 2026-10-17 → **2026-11-25**.
Instrument tier: **plain-curl + browser UA** (the provider's own JSON header set; Phase 0
note below). `escalation_tier: unavailable` (#1434 — `curl_cffi`/`playwright` are not
installed in this container; transcript in *Phase 0*). No `Blocked` recorded, so nothing is
downgraded; both stream families were resolved end-to-end from the runner.

---

## Phase 0 — calibration (ran before touching the site)

| canary | expected | observed (2026-11-25) | reading |
|---|---|---|---|
| `film1k-challenge-pair` | plain 403 / TLS 200 | **plain 403** (`cf-mitigated: challenge`) / with browser UA **200** | plain expectation **matches** today; TLS tier not runnable (#1434) |
| `eporner-healthy` | plain 200 | plain 200 | match |
| `pandamovies-redirect-origin` | plain 302 / TLS 200 @ .org | plain **302** (`location: https://pandamovies.org/`) | match (no `-L`, so the 302 is the evidence) |

```
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' https://www.film1k.com/
  403 5244            # cf-mitigated: challenge; body <title>Just a moment...</title>
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$FIREFOX_UA" https://www.film1k.com/
  200 217494          # FIREFOX_UA = Firefox/144.0 (Windows), the shared UA style
$ curl -s -o /dev/null -w '%{http_code}\n' https://www.eporner.com/            → 200
$ curl -sI https://pandamovies.pw/ | head -3
  HTTP/2 302 / location: https://pandamovies.org/
```

**#1435, live pair:** the stale canary expects plain-curl 403 and this run *observed* 403
for a UA-less curl, while the same URL answers 200 with a browser UA. So the pair is not
"the challenge is gone"; the datacenter challenge is **UA-conditional** from this IP
(the provider always sends a browser UA, so the app path is unaffected). This is the live
pair the issue asked for; no `environment-degraded` branch was taken, and #1435 stays open
for the maintainer's expectation decision (the expectation text should also record the UA
dimension, not just the tier).

**Escalation tier: unavailable (#1434).** The TLS rung and the browser rung cannot run here:

```
$ python3 -c 'import curl_cffi'          → ModuleNotFoundError: No module named 'curl_cffi'
$ python3 -c 'import playwright'         → ModuleNotFoundError: No module named 'playwright'
$ bash .pi/skills/site-probe/scripts/impersonate.sh https://www.film1k.com/
  impersonate.sh: curl_cffi missing for python3. Install it, then re-run: …
```

Phase 0 was otherwise healthy (2/2 applicable canaries matched, film1k adjudicated as the
live pair above), so `escalation_tier: unavailable` goes in the registry `last_runs` entry
and nothing in this document is filed from challenge evidence. It did not bind: both stream
families resolved (see D4).

---

## D3 — listing types

| type | exists? | evidence |
|---|---|---|
| new / latest | **yes** | `/` (24 cards/page, 360 pages), `/page/2/` |
| old | **yes, as an explicit ascending sort** | `/?order=asc` = `/page/N?order=asc` (301 normalises `/page/2/?order=asc` → `/page/2?order=asc`); p1 is the catalogue's oldest posts (full overlap with the page-360 tail) |
| popular | **yes** | `/most-viewed` (20 pages), `/most-liked` (20 pages) |
| genres | **absent as a distinct route** | `GET /genre/action` → 404, `GET /genres` → 404, `GET /genre` → 404; the genre-like vocabulary IS the WordPress `category` taxonomy below (recorded, never a finding) |
| categories | **yes** | `/category/<slug>` — 29 slugs listed on `/categories`; `/category/action` announces 38 pages |
| tags | **yes** | `/tag/<slug>` — 23 slugs listed on `/categories`; 48 distinct tag links on the homepage; `/tag/1970s` announces 17 pages |

```
$ GET /genre/action → 404    GET /genres → 404    GET /genre → 404
$ GET /categories → 200 (186,076 bytes) — "All Categories": 29 /category/ slugs + 23 /tag/ slugs
$ GET /?order=asc → 200 24 cards; first: maladolescenza-1977.html
  p1('?order=asc') ∩ p1('/') = 0    p1('?order=asc') ∩ /page/360/ = 13 (= all of the tail page)
```

### Pagination (listing correctness only as far as enumeration needed, D5)

Every surface is path-segment paginated; p1 ∩ p2 = 0; the tail page is short and the next
page is the WordPress 404-wrap (HTTP 404, `<title>Page not found`, **8 seeded cards**).
That wrap is why the gate matters: the structural card parser parses all 8 seeded cards,
so every listing surface stops at `Film1kParse.listingCards` (`isNotFoundTemplate` → empty,
review #1621 — the earlier `getMainPage` had no gate and would have paginated the 404 tail),
and `search()` keeps its own early return on the same check.

| surface | p1 | p2 | overlap | tail | past tail |
|---|---|---|---|---|---|
| `/` (new) | 24 | 24 (`/page/2/`) | 0 | `/page/360/` 13 | `/page/361/` **404** |
| `/?order=asc` (old) | 24 | 24 (`/page/2?order=asc`) | 0 | `/page/360?order=asc` 13 | `/page/361?order=asc` **404** |
| `/most-viewed` | 24 | 24 | 0 | `/most-viewed/page/20` 24 | `/most-viewed/page/21` **404** |
| `/most-liked` | 24 | 24 | 0 | `/most-liked/page/20` 24 | `/most-liked/page/21` **404** |
| `/category/action` | 24 | 24 | 0 | `/category/action/page/38` 10 | `/category/action/page/39` **404** |
| `/tag/1970s` | 24 | 24 | 0 | `/tag/1970s/page/17` 14 | `/tag/1970s/page/18` **404** |

Search: `/?s=housewife` 24 cards, `/page/2/?s=housewife` 24 (disjoint); exhausted
`/page/9/?s=taboo` → 404 + `Page not found` title + 8 seeded cards (the #654 gate holds).
No distinct quick-search endpoint: `/wp-json/turbosearch/v1/suggest?q=taboo` → **404**
JSON (provider keeps `hasQuickSearch = false`).

Card grammar on every listing surface is now the 2026-11 theme's
`article.loop-post > figure > a + h2 > a` (see *Drift and fix*); the run's enumeration used
that skeleton and every sampled page parsed with its exact card count.

---

## Drift and fix (TDD red → green, v28 → v29)

**Condition: the theme renamed its card classes, which emptied every listing.** The old
parser keyed on `header.entry-header > a` + `h2.entry-title`; the live pages carry
`figure.film1k-card__media > a.film1k-card__link` + `h2.film1k-card__title > a`:

```
$ grep -c 'entry-title' home_20261125.html            → 1   (a CSS rule in the inline stylesheet)
$ grep -c 'film1k-card__title' home_20261125.html     → 24  (one per listing card)
$ grep -o '<article[^>]*>' home_20261125.html | head -1
  <article  class="loop-post post-84151 post type-post … hentry category-classic-erotica …">
$ sed -n 's/.*\(<h2 class="film1k-card__title">\).*/\1/p' home_20261125.html | head -1
  <h2 class="film1k-card__title">
```

With the old selectors `Film1k.parseList` returned **0** items on `/`, `/page/2/`,
`/category/*`, `/tag/*`, search pages and Related Videos (enumeration broke — the D5 trigger
for re-probing this surface). Same theme change moved the Related Videos header from
`h3` to `h2.page-header` (`Film1kParse.relatedOf` found no heading → `recommendations`
empty).

**Fix (structural, not a selector swap):** `Film1kParse.cardOf(article)` parses the card
skeleton — `h2 a[href]` → `header a[href]` → first `.html` link, title from the card's `h2`
text, poster from `figure img` (`data-src` when `src` is a `data:` placeholder). It parses
both the legacy fixture and the current pages, so the next cosmetic class rename cannot
empty the listings again. `relatedOf` matches the heading text at `h2, h3`.
`Film1kParseTest` covers both skeletons (fixtures: `film1k_home_20261125.html` new,
`film1k_video_related.html` legacy, `film1k_video_related_20261125.html` new Related strip):
red first (unresolved `cardOf`), green after the parse function landed. Provider version
28 → 29.

`drift_confirmed` is now **2** — still below the ≥3 hardening threshold, and this fix is
already structural.

---

## D4 — evidence rows (≥2 video pages per type, both catalogue ends)

`stream host` = the host that actually served the stream this run. `quality` = what reaches
CloudStream: the Byse playback payload's `label` (1080p on all sampled pages) and the
AbyssPlayer adapter's per-source quality (360p/480p).

| type | sample URL (listing) | video page | instrument tier | stream host | quality | verdict |
|---|---|---|---|---|---|---|
| new / latest | `/` p1, `/page/2/` p1 | `the-story-of-o-the-series-submission-1992-eng-sub`, `yataktan-yataga-1979-zerrin-egeliler` | plain-curl + browser UA | film1k.xyz → sprintcdn `master.m3u8` (both) | 1080p (payload label) | **pass** 2/2 resolved |
| old (ascending sort) | `/?order=asc` p1, `/page/360/` tail | `love-gambler-1971`, `paranormal-sexperiments-2016` | plain-curl + browser UA | `love-gambler`: abyssplayer.com → enc-dec → sora CDN (302 → trycloudflare) `206 video/mp4`; `paranormal…`: film1k.xyz → sprintcdn HLS 200 | 480p (abyss source) / 1080p (byse) | **pass** 2/2 resolved |
| popular | `/most-viewed` p1, `/most-liked` p1 | `burning-desires-of-a-housewife-2006`, `9-songs-2004` | plain-curl + browser UA | film1k.xyz → sprintcdn `master.m3u8` (both) | 1080p | **pass** 2/2 resolved |
| categories | `/category/classic-porn` p1, `/category/action` p1 | `entrechattes-linfirmiere-1978`, `black-shampoo-1976`, `model-wife-1990` | plain-curl + browser UA | film1k.xyz → sprintcdn `master.m3u8` (all three) | 1080p | **pass** 3/3 resolved |
| tags | `/tag/1970s` p1, `/tag/english` p1 | `the-blood-rose-1970`, `french-teen-1977` | plain-curl + browser UA | film1k.xyz → sprintcdn `master.m3u8` (both) | 1080p | **pass** 2/2 resolved |
| genres | — | — | — | — | — | **absent** (`/genre/<slug>`, `/genres` 404 — recorded, never a finding) |

11 video pages sampled across the five exposed types (D4 minimum is 10); **11/11 resolved
end-to-end from the runner** — 10 Byse HLS (`200 application/vnd.apple.mpegurl`) and 1
Abyss (sora CDN `302` → `206 video/mp4`). Nothing was `Suspected` this run: the sora CDN's
datacenter-IP 403 (recorded in FINDINGS-654) is avoided by the provider's own
`Referer: https://abyssplayer.com` + redirect following; the bare-URL 403 is an instrument
artifact, reproduced and recorded below.

### Host coverage across the 11 samples

| host | pages | registry / chain | this run |
|---|---|---|---|
| `film1k.xyz` (Byse) | 10 | provider's own chain (`bysePlayback` → shared `solvePow`/`postJson`), not a HostRegistry row | PoW solved, AES-256-GCM payload decrypted, `master.m3u8` **200** |
| `abyssplayer.com` / `player.abyssplayer.com` | 1 | ✅ `shared/HostRegistry.kt` → `AbyssPlayer()` + `AbyssPlayerEmbed()` | datas → `enc-dec.app/api/dec-abyss` → sora **206** |

---

## Stream transcripts

Byse (film1k.xyz) — every sampled page carries one `/e/{code}` source; the chain is the
provider's: captcha → PoW → verify → playback → AES-256-GCM:

```
$ POST https://film1k.xyz/api/videos/esinwkckyhki/embed/captcha/  {}
  → {"pow_nonce":"68be26…","pow_difficulty":16,"pow_token":"j5yyjw…","expires_in":1800,
     "algorithm":"sha256-leading-zero-bits"}
$ POST …/captcha/verify/  {"pow_token":"…","solution":"90565"}
  → {"status":"ok","token":"Q0meVJ…","expires_in":1800}
$ POST …/playback/  {"fingerprint":{}}  -H 'X-Captcha-Token: …'
  → {"playback":{"algorithm":"AES-256-GCM","version":6,"key_parts":[…30…],
                 "iv":"Ni5s0MqKyb11VVSQ","payload":"QZyjzOg4…"}}
  key = b64url(key_parts[version-1]) + b64url(key_parts[31-version])   # 16+16 = 32 bytes
  plaintext: {"sources":[{"quality":"x","label":"1080p",
    "mime_type":"application/vnd.apple.mpegurl",
    "url":"https://edge1-moscow-sprintcdn.owphbf24.com/hls2/02/04390/esinwkckyhki_x/master.m3u8?t=…"}]}
$ GET <that master.m3u8> -H 'Referer: https://film1k.xyz/'
  → 200 application/vnd.apple.mpegurl
  #EXT-X-STREAM-INF:…RESOLUTION=1904x1024…
```

All 10 Byse pages: `200 application/vnd.apple.mpegurl` (codes `1xtkkavm2wyv`,
`dyz3y20b3lg8`, `q5yuqkbn5xlk`, `lrxb1x4rancc`, `hjutnp11la77`, `58gkkmdad6xp`,
`x6vlyzotc6zs`, `64kqb130mn8r`, `zeejuubphqgx`; each page 1 source).

Abyss (love-gambler-1971) — page embed is `https://player.abyssplayer.com/?v=Hfp8ZtL21`;
`Film1kParse.abyssUrl` returns the apex form `abyssplayer.com/?v=Hfp8ZtL21` and **both
apex and subdomain serve the same embed** (200, 9.8 KB, `<title>Love Gambler (1971).mkv</title>`,
`const datas = "eyJzbHVn…"`):

```
$ POST https://enc-dec.app/api/dec-abyss  {"text":"<datas>"}  -H 'Origin: https://enc-dec.app'
  → {"sources":[{url:"https://hgexcwir16.sssrr.org/sora/819633925/…",type:"360p",status:true},
                {url:"…",type:"360p"},{url:"…",type:"360p"},{url:"…",type:"480p",status:true}]}
$ GET <480p url>  (no Referer)                          → 403 text/html (CF "Attention Required")
$ GET <480p url> -H 'Referer: https://abyssplayer.com'   → 302 location: https://…trycloudflare.com/sora/…
$ GET <that 302>-L -H 'Referer: https://abyssplayer.com' -H 'Range: bytes=0-64'
  → 206 video/mp4  (body starts ftypisom)
```

The 403 without a Referer is the host's hotlink rule — the `AbyssPlayer` adapter sets
`Referer: https://abyssplayer.com` and the app's HTTP stack follows the 302, so the shipped
path resolves. (FINDINGS-654 recorded the bare URL's 403/404 as `Suspected`; this run shows
it is referer-shaped, not IP-shaped, for this host today.)

---

## Load-surface spot checks (same 11 pages, no drift)

- title `h1.title` 11/11 (`og:title` 11/11; the card title and `og:title` agree modulo the
  site's own ` - Watch Free…` wrapper)
- poster `meta[property=og:image]` 11/11
- plot `meta[property=og:description]` 11/11
- info block `strong:containsOwn(Runtime/Genres/Actors)` **10/11** — `love-gambler-1971`
  publishes no info block at all (site-side content gap, not drift)
- year: `og:title` carries `(<year>)` on 10/11; `entrechattes-linfirmiere-1978` titles it
  bare (`Entrechattes (L'infirmière) 1978 …`), so `Film1kParse.yearOf` returns null —
  site-side title shape, provider behaviour unchanged (backlog)
- related videos: 20 `article.loop-post` cards on **11/11** pages, fetched by the fixed
  `relatedOf` (h2 header) and parsed by `cardOf`
- player strip `#aa-videos-op` present on 11/11 (the #558 scoping still holds; the abyss
  embed on `love-gambler` sits inside it)
- search ↔ load: `?s=housewife` p1 contains `burning-desires-of-a-housewife-2006`, title +
  poster agree with its load page

---

## Findings lifecycle

`gh issue list --search film1k --state all` returns no standing issue for this condition:
the open Film1k-keyed issues are #1435 (canary expectation, this run contributes the live
pair above), #654/#707/#721 (closed deep pass, search pagination), #496/#558 (closed embed
families). The card-markup drift is **new** — fixed in this run (D1: `drift_confirmed` = 1
at run start, below the ≥3 hardening threshold, and the fix is structural anyway), so no
issue is filed and nothing is duplicated.

`shared/` was not the root cause (the card parse is provider-local), so no other provider
is bumped.

---

## Delivery gate

- `./gradlew Film1k:test` → BUILD SUCCESSFUL (provider suite 24 tests + shared suites)
- `./gradlew Film1k:make` → BUILD SUCCESSFUL → `Film1k/build/Film1k.cs3`
- `.pi/skills/audit-providers/scripts/check-findings.sh` → PASS (registry untouched in the
  diff, ADR-0013)
- `.pi/skills/verify-provider/scripts/verify.sh` (live, browser UA + `Referer:
  https://abyssplayer.com`) → **RESULT: PASS**: search 24/24 cards p1⊥p2; home 24/24
  p1⊥p2; 5/5 video pages; **5/5 streams** (4× `200 application/vnd.apple.mpegurl`,
  1× `206 video/mp4`); related 20/20 per page; tags present 5/5; LoadResponse 7/7;
  search↔load agreement exercised via `?s=housewife`

---

## Backlog (not fixed here)

- `Film1k.mainPage` still maps only the homepage + six `/category/*` rows; the site now
  advertises `/most-viewed` and `/most-liked` (rows work, cards parse) and an ascending
  `?order=asc` sort. Row-surface work, outside the video-source leg.
- `yearOf` only matches a parenthesized year; pages that title the year bare
  (`… 1978 …`) yield `year = null` (listing/load metadata, pre-existing).
- `love-gambler-1971` (and similar old uploads) carry no Runtime/Genres/Actors info block —
  site content gap, provider already null-safe.
- The `/categories` index lists 23 tag slugs while the homepage links 48 distinct `/tag/`
  targets (site-side curation, not a provider concern).

## Registry deltas (posted as a comment on #1462, never in the diff — ADR-0013)

`verdict: ok-drift` (was `ok`), `tier: plain-curl` (browser UA; Byse HLS + Abyss sora mp4
both runner-resolvable), `drift_confirmed: 2`, `standing_issue: null`,
`last_deep: 2026-11-25`, plus one `last_runs` entry (`false_positives: []`,
`escalation_tier: unavailable`).
