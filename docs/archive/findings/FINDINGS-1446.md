# FINDINGS-1446 — XMoviesForYou video sources (S8 of #1354)

Deep pass on the **video-source leg only** (D2/D3/D4) for `xmoviesforyou.com`, run 2026-11-22 on
`devloop/issue-1446`. Provider `XMoviesForYou` (v27 → v28) · registry `drift_confirmed` = 1 at run
start · verdict at last deep (2026-10-14) = `ok`.

Instrument: **plain curl** (Firefox UA) — the site answered 200 on every probed surface, no
challenge anywhere. No TLS-impersonated step was needed (see Phase 0 for its availability).
Every transcript below is what the provider's own NiceHttp stack receives.

## Phase 0 — calibration (canaries, `audits/canaries.json`)

| canary | tier | expected | observed | reading |
|---|---|---|---|---|
| `eporner-healthy` | plain-curl | 200 | **200** | match — instrument healthy |
| `pandamovies-redirect-origin` | plain-curl | 302 | **302** | match |
| `film1k-challenge-pair` | plain-curl | 403 | **200** | **expectation-stale (#1435, open)** — *not* `environment-degraded`; live pair recorded, no finding filed from it |
| `film1k-challenge-pair` | tls-impersonated | 200 | **unavailable** | #1434 open — `curl_cffi` missing, `impersonate.sh` pins bare `python3` |

- `escalation_tier: unavailable` (per the run spec: #1434 still open) → **every `Blocked` this run
  downgrades to `Suspected`**. No `Blocked` verdict was in fact reached: plain curl reached every
  URL, so no challenge evidence exists to escalate.
- `expectation-stale` branch taken for film1k (per #1435): recorded, not treated as degradation.
- Consequence for this run: nothing below is verdicted from challenge evidence alone.

```
$ curl -sS -o /dev/null -w '%{http_code}' https://www.film1k.com/    → 200   (canary expects 403, #1435)
$ curl -sS -o /dev/null -w '%{http_code}' https://www.eporner.com/   → 200
$ curl -sS -o /dev/null -w '%{http_code}' https://pandamovies.pw/    → 302
```

## D3 — listing types the site exposes (enumeration only, D5)

One row per listing type. `absent` is a recorded outcome, never a finding. Card grammar on every
listing surface is unchanged since FINDINGS-535 (`a[data-video-card]`, `a.card`,
`a.group.flex.flex-col`) — re-probed only to enumerate videos (D5), not audited.

| type | sample URL | instrument tier | stream host | quality | verdict |
|---|---|---|---|---|---|
| new / latest | `https://xmoviesforyou.com/` (p1: 30 carousel + 24 grid) · `/page/2` (24 grid, **0 overlap** with p1) | plain curl | see per-page table | not exposed | **pass** |
| old | — **absent**: `/oldest /latest /new /newest /most-recent /top-rated /popular /trending` → 404; `/?sort=old` → 200 but re-serves page-1 items (sort param ignored); `/most-viewed?sort=oldest` → **identical** 100 cards | plain curl | — | — | **pass (absent)** |
| popular | `https://xmoviesforyou.com/most-viewed` (100 grid cards, no paginator — *does not paginate*) | plain curl | see per-page table | not exposed | **pass** |
| genres | `https://xmoviesforyou.com/category/anal` · `?page=2` (24 + 24, **0 overlap**) | plain curl | see per-page table | not exposed | **pass** |
| categories | `https://xmoviesforyou.com/categories` index → `https://xmoviesforyou.com/category/brazzers` · `?page=2` (24 + 24, **0 overlap**) | plain curl | see per-page table | not exposed | **pass** |
| tags | `https://xmoviesforyou.com/tags` index → `https://xmoviesforyou.com/tag/brazzersexxtra` · `?page=2` (24 + 24, **0 overlap**) | plain curl | see per-page table | not exposed | **pass** |

`genres` and `categories` are the **same** surface (one `/categories` taxonomy holding both niches
and studios); they are listed separately because D3 names both, and sampled with a niche slug
(`anal`) and a studio slug (`brazzers`) respectively.

```
$ for p in /oldest /latest /new /newest /most-recent /top-rated /popular /trending; do
    curl -sSL -o /dev/null -w "$p %{http_code}\n" https://xmoviesforyou.com$p; done
/oldest 404 · /latest 404 · /new 404 · /newest 404 · /most-recent 404 · /top-rated 404 · /popular 404 · /trending 404

$ diff <(cards /most-viewed) <(cards '/most-viewed?sort=oldest')   → identical 100 hrefs
$ diff <(cards /page/2)    <(cards '/?sort=old')                   → 0 shared hrefs; the sort URL serves page-1 items
```

Quick search: **no distinct endpoint** (re-probed 2026-11-22) — `/api/search?q=fake` 404,
`/api/suggest?q=fake` 404, `/new-search?q=fake&format=json` 200 but HTML. Provider keeps
`hasQuickSearch = false`.

```
$ curl -sSL -o /dev/null -w '%{http_code} %{content_type}\n' 'https://xmoviesforyou.com/api/search?q=fake'
404 text/plain; charset=utf-8
$ curl -sSL -o /dev/null -w '%{http_code} %{content_type}\n' 'https://xmoviesforyou.com/api/suggest?q=fake'
404 text/plain; charset=utf-8
$ curl -sSL -o /dev/null -w '%{http_code} %{content_type}\n' 'https://xmoviesforyou.com/new-search?q=fake&format=json'
200 text/html; charset=utf-8
```

## Video pages — 11 sampled, ≥2 per type, both ends of each pageable catalogue

| # | listing it came from | URL | source shape (2026-11-22) |
|---|---|---|---|
| 1 | new/latest (home p1 carousel) | `/blacked-urlilgoddess-streamer-wife-cheats-with-bbc` | inline HLS player + 3 chips |
| 2 | new/latest (home p1 carousel) | `/teamskeetallstars-princess-emily-november-spotlight-princess-emily-gets-worshipped-head-to-toe` | inline HLS player + 3 chips |
| 3 | popular (`/most-viewed` head) | `/mypervyfamily-kali-roses-my-stepsister-is-a-mean-girl` | inline HLS player + 3 chips |
| 4 | popular (`/most-viewed` head) | `/assparade-octokuro-gets-turned-on-after-yoga-session` | inline HLS player + 3 chips |
| 5 | genres (`/category/anal` head) | `/backdoorpov-lilu-moon-anal-on-board` | inline HLS player + 3 chips |
| 6 | genres (`/category/anal` head) | `/analangels-emma-tatcher-sexy-lingerie-fitting` | inline HLS player + 3 chips |
| 7 | categories (`/category/brazzers` far end) | `/brazzersexxtra-cyber-doll-the-exaggerator-shes-fucking-over-the-top` | **chips only** (older post) |
| 8 | categories (`/category/brazzers` far end) | `/brazzersexxtra-miss-lexa-getting-real-with-miss-lexa` | **chips only** (older post) |
| 9 | tags (`/tag/brazzersexxtra` head) | `/brazzersexxtra-ryan-reid-promotional-pussy` | inline HLS player + 3 chips |
| 10 | tags (`/tag/brazzersexxtra` head) | `/brazzersexxtra-erotic-medusa-not-here-for-his-wife` | inline HLS player + 3 chips |
| 11 | search (`/search?q=brazzers` p1) — agreement check | `/brazzersexxtra-kali-roses-kali-rides-the-subway` | inline HLS player + 3 chips |

All 11 → HTTP 200, `h1` title, `og:image`, `div.prose p` plot, `a[href*='/category/']` tags,
`a[href*='/pornstar/']` actors, `calendar_month` date chip. Exposure inventory (unchanged from
FINDINGS-535, re-confirmed): **duration is not exposed** (no minutes/ISO value anywhere — the
`span.runtime` card badge is the literal string `HD`); **quality is not exposed** per source (chip
labels name the host only, the HLS playlist is a single-rendition media playlist with no
`RESOLUTION`/`#EXT-X-STREAM-INF`).

## Stream sources — the drift this run confirms

### What the site serves, per watch page

1. **Site-hosted HLS (primary, never enumerated before this run).** The watch page's own player
   reads `const videoId = "<id>"; const target = "dedi_1";` and fetches
   `GET /api/stream/<videoId>?target=<target>` → `{"node": …, "url": "<m3u8>"}`, played as
   `application/x-mpegURL`. Present on **9 of 11** sampled pages; the two `/category/brazzers`
   far-end pages (02.2026 uploads) carry no player config at all — chips only.
2. **Mirror chips.** The `#downloads` panel renders one chip per mirror (STREAMTAPE / MIXDROP /
   DOODSTREAM). Chip anchors are `<a target="_blank" rel="noopener noreferrer">` whose first child
   is `<span class="material-symbols-outlined">download</span>` and whose second is the host label.

### Live evidence, per page

| # | HLS (`/api/stream/…` → m3u8) | chips found | chip resolution |
|---|---|---|---|
| 1 | 200 `application/vnd.apple.mpegurl` (`node3.eternalslumber.online`) | streamtape.com, **mxdrop.click**, playmogo.com | mxdrop → 206 video/mp4 |
| 2 | 200 (node5.eternalslumber.online) | streamtape.com, mxdrop.click, playmogo.com | mxdrop → 206 video/mp4 |
| 3 | 200 (node3.eternalslumber.online) | streamtape.com, **mixdrp.click**, playmogo.com | mxdrop (alias hop) → 206 video/mp4 |
| 4 | 200 (r1.whoopscdn.website) | streamtape.com, mixdrp.click, playmogo.com | mxdrop (alias hop) → 206 video/mp4 |
| 5 | 200 (node5.eternalslumber.online) | streamtape.com, mxdrop.click, playmogo.com | mxdrop → 206 video/mp4 |
| 6 | 200 (r1.whoopscdn.website) | streamtape.com, mxdrop.click, playmogo.com | mxdrop → 206 video/mp4 |
| 7 | — no player config | streamtape.com, **m1xdrop.bz**, dood.to | mxdrop (alias hop) → 206 video/mp4 |
| 8 | — no player config | streamtape.com, m1xdrop.bz, dood.to | mxdrop (alias hop) → 206 video/mp4 |
| 9 | 200 (r3.bestcdnfree.shop) | streamtape.com, mxdrop.click, playmogo.com | mxdrop → 206 video/mp4 |
| 10 | 200 (r2.myjobfinder.online) | streamtape.com, mxdrop.click, playmogo.com | mxdrop → 206 video/mp4 |
| 11 | 200 (node3.eternalslumber.online) | streamtape.com, mxdrop.click, playmogo.com | mxdrop → 206 video/mp4 |

Stream paths are distinct across all 11 videos (the m3u8 carries the `videoId`, the mp4 the
mixdrop file id) — the Distinct bar holds.

### Drift #2 confirmed — the mirror selector (and a whole source class) was dead

The pre-fix `loadLinks` matched `a[href*='streamtape.com'], a[href*='mixdrop'], a[href*='dood'],
a[href*='bigwarp']`. Measured against the 11 pages:

| page | old selector matched | chips on page | why the misses |
|---|---|---|---|
| 1–6, 9–11 | **1 of 3** | 3 | `mxdrop.click` has no `mixdrop` substring; `playmogo.com` has no `dood` substring |
| 7–8 | **2 of 3** | 3 | `m1xdrop.bz` likewise; `dood.to` did match |

And the site's own HLS player — the *primary* source — was never touched by `loadLinks` at all, on
any page, before this run. Two conditions, one root cause: a per-hostname allow-list cannot track a
mirror family that rotates (`mxdrop.click`, `m1xdrop.bz`, `mixdrp.click`, `miiixdrop.net`,
`miiiiixdrop.net`, `miiixdrop.top` all observed live in this single sweep) and cannot see a source
the page builds in JS. This is drift #2 for the provider; per the ≥2 rule the fix is **structural**,
not another selector swap.

Alias-hop evidence — every mixdrop alias 30x-redirects to `mxdrop.click`, which is the one hostname
worth registering:

```
$ for h in mxdrop.click m1xdrop.bz mixdrp.click miiixdrop.net miiiiixdrop.net miiixdrop.top; do
    curl -sSL -o /dev/null -w "$h %{http_code} %{url_effective}\n" "https://$h/f/033rp7xqhm63er"; done
mxdrop.click      200 https://mxdrop.click/f/033rp7xqhm63er
m1xdrop.bz        200 https://mxdrop.click/f/033rp7xqhm63er
mixdrp.click      200 https://mxdrop.click/f/033rp7xqhm63er
miiixdrop.net     200 https://mxdrop.click/f/033rp7xqhm63er
miiiiixdrop.net   200 https://mxdrop.click/f/033rp7xqhm63er
miiixdrop.top     200 https://mxdrop.click/f/033rp7xqhm63er
```

Player chain (mixdrop family): `/f/<id>` landing → `/e/<id>` packed `MDCore.wurl` → direct mp4:

```
$ curl -sSL https://mxdrop.click/e/033rp7xqhm63er | unpack(p,a,c,k,e,d)
MDCore.wurl="//30xplewoo.mxcontent.net/v2/033rp7xqhm63er.mp4?s=…&e=…&_t=…";
$ curl -sSL -H 'Range: bytes=0-64' -o /dev/null -w '%{http_code} %{content_type}\n' "<that url>"
206 video/mp4
```

### Non-`pass` row transcripts (raw)

**STREAMTAPE — `Suspected` (host-side on the probe IP, not provider grammar).** Every sampled
streamtape id answers `{"status":500}` from the datacenter runner, matching the registry note from
the 2026-10-14 deep pass. The page, the `get_video` token and the anchor are all fine; the media
edge refuses the runner's IP.

```
$ curl -sSL https://streamtape.com/v/VBrk4mZMLpSxjJ | grep -o 'get_video?id=[^"]*token=[^ ]*'
get_video?id=VBrk4mZMLpSxjJ&expires=1795440552&ip=FROsE0EnDS9X&token=5vF8H-EkMgzZ
$ curl -sSL -H 'Referer: https://streamtape.com/' \
    'https://streamtape.com/get_video?id=VBrk4mZMLpSxjJ&expires=1795440552&ip=FROsE0EnDS9X&token=5vF8H-EkMgzZ'
{"status":500,"msg":"Sorry, error on our side!"}
```

Confirmed on a second id (`1dX1l9049JfeQMo`, token `p42YYc7v5uzZ`) — same `status:500`.

**HLS node — `Origin` header is load-bearing.** `*.eternalslumber.online` answers 403 to a bare
request *and* to a request carrying only `Referer`; the same URL with `Origin` is 200:

```
$ curl -sS -o /dev/null -w '%{http_code} %{content_type}\n' \
    https://node3.eternalslumber.online/hls/vid_179297189623482/index.m3u8
403 text/html
$ curl -sS -H 'Referer: https://xmoviesforyou.com/' -o /dev/null -w '%{http_code}\n' <same>
403
$ curl -sS -H 'Origin: https://xmoviesforyou.com' -o /dev/null -w '%{http_code} %{content_type}\n' <same>
200 application/vnd.apple.mpegurl
#EXTM3U / #EXT-X-VERSION:3 / #EXT-X-TARGETDURATION:10 / #EXT-X-PLAYLIST-TYPE:VOD / segment_000.ts …
```

`*.whoopscdn.website`, `r3.bestcdnfree.shop`, `r2.myjobfinder.online` serve without the header; the
provider sends it on every HLS link so both node families play.

**DOODSTREAM chip — `Suspected (challenge from datacenter probe IP)`.** `playmogo.com/d/<id>` is a
landing page (points at `/e/<id>` and `/download`); `/e/<id>` from this IP is a Cloudflare Turnstile
shell for both desktop and mobile UAs, with no `pass_md5` token in the body. Under D8 this is
`Suspected`, never a finding, and the TLS-impersonated tier that could settle it is unavailable this
run (#1434). `dood.to/d/<id>` 200s and redirects into the same playmogo page.

```
$ curl -sSL https://playmogo.com/d/mr7w99gthuut | grep -c pass_md5
0
$ curl -sSL https://playmogo.com/e/mr7w99gthuut | grep -o 'turnstile\|pass_md5' | sort -u
turnstile
```

**`/api/stream/` rate limit — instrument note, not a defect.** Bursting the endpoint (11 samples in
~40 s) draws `429 Too Many Requests` from nginx on some requests; a 5 s spacing clears it. The app
calls it once per play, so this is a probe artefact — but it is why two pages in the first sweep
fell back to their mixdrop chip.

```
$ curl -sSL -o /dev/null -w '%{http_code}\n' 'https://xmoviesforyou.com/api/stream/179527146300162?target=dedi_1'
429
```

## Fix (D1 — audit *and* fix in this run)

Structural, in three pieces (TDD red → green at the Parse seam, ADR-0005):

1. `XMoviesForYouParse.streamEndpoint()` — reads `videoId`/`target` from the inline player config;
   null on older posts (a normal shape, tested). `loadLinks` then calls
   `/api/stream/<id>?target=<target>` and emits the m3u8 with
   `headers = {Origin, Referer}` + `referer = site/`.
2. `XMoviesForYouParse.streamLinks()` — the chip grammar, host-agnostic, two structural anchors
   unioned: anchors inside `#downloads ~ div` and any anchor carrying the `download` icon span (the
   second survives a panel heading whose id is gone). No hostname appears in the selector.
3. Alias hop + shared row: `loadLinks` follows each chip's redirect before `loadExtractor` (dispatch
   is prefix-based on the *original* URL), and `shared/HostRegistry` gains
   `mixdropMirror("https://mxdrop.click")` — the canonical every alias lands on. `playerPath()`
   moves a Dood-family landing (`/d/<id>`) onto its player path (`/e/<id>`) so the registered
   DoodStream row sees a page it can parse.

Fixtures: `src/test/resources/xmoviesforyou-watch.html` (live excerpt, 3 chips incl.
`mxdrop.click`, inline player config) and `xmoviesforyou-watch-aliases.html` (rotated aliases,
`dood.to`, no `#downloads` id, no player config).

## Verification (this run)

`.pi/skills/verify-provider/scripts/verify.sh` — **RESULT: PASS**, 11 video pages, 11 distinct
streams (6 HLS m3u8 200/206 `application/vnd.apple.mpegurl`, 5 mixdrop mp4 206 `video/mp4`), search
p1/p2 disjoint (24 `a.card` each), category rows p1/p2 disjoint (24 each), tags/actors/year present
on all 11 pages. Flags that need their FINDINGS line are satisfied here: no quick-search endpoint
(above), duration not exposed (above), recommendations come from the XHR
`/api/related/<postId>` JSON — **not DOM** — so no `--related-selector` exists to pass (re-probed
this run: 200 `application/json`, `data[]`).

Transcript of the run is in the PR body. `./gradlew XMoviesForYou:test` green (14 provider tests +
shared suite), `./gradlew XMoviesForYou:make` clean.

## Risks / blockers

- **No Cloudflare challenge on plain HTTP this run** (unlike FINDINGS-535, where every URL 403'd).
  The site is plainly reachable from the CI runner today; nothing here is verdicted Blocked.
- **streamtape media edge** refuses the runner (`status:500`) — `Suspected`, playback unverified
  from CI, unchanged from the 2026-10-14 deep pass.
- **DoodStream family** is Turnstile-walled from the runner — `Suspected`; in-app playback stays the
  maintainer's at merge.
- **`/api/stream/` is rate limited** (nginx 429 on bursts) — space probes; one call per play is fine.
- Probe region: US datacenter runner IP.
