# FINDINGS-1459 — Sexfilm video sources (S21 of #1354)

Video-source leg (D2) of the Sexfilm spec (`en.sex-film.biz`). Scope: for every D3 listing
type (`new`/`latest`, `old`, `popular`, `genres`, `categories`, `tags`) open ≥2 video pages,
sampled at both ends of the catalogue where the type is pageable, and resolve `loadLinks`
end-to-end — video page → click-injected player iframe → stream URL reachable (200/206),
embed host identified, quality passed to CloudStream where the site exposes it.

Provider state at run start: registry `last_deep` = 2026-10-16, `drift_confirmed` = 0,
verdict at last deep = `ok` (#634), provider `version` = 26.

**Result: one confirmed provider-side condition, fixed in this run (v26 → v27).**
`filmcdm.top/e/<id>` now 302s part of the catalogue to the **vsembed.com** SPA player. That
page carries no packed JW config, so the provider's packed-JS branch had nothing to read and
the page emitted **zero links**; vsembed's per-tier download API (`/api/download/tiers`,
`/api/download/get` → 302 signed mp4) is the same platform `shared/`'s `TurboEmbed` already
implements for turboembed.com. Fix = one shared registry row (`vsembed.com`) plus a redirect
seam in `addSource`. Freshly resolved rows on that class now serve `206 video/mp4`
(1080p/720p/540p tiers).

The remaining non-pass rows are **site-side, not provider selectors**: video pages whose only
player is `s2.filmcdn.top` (a WASM/adscore-gated POST API — `{"407":"1"}` without the full
signed payload, no media request even in a headless browser), `playmogo.com` (Cloudflare
Turnstile shell), one `premilkyway` edge that stalls on its signed playlist, and the
`filmcdm.top/e/` (empty-id) placeholders the site itself writes on old titles. One
data-completeness finding is filed for the s2-only class on this issue's thread; no provider
selector is missing for it — there is no plain-HTTP stream surface to select.

Run: `05-implement` for #1459, 2026-11-25, **plain-curl** on every surface, runner IP
US/Virginia (Azure), desktop UA
`Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36`,
`Referer: https://en.sex-film.biz/` where recorded.

Out of scope per spec: a full listing audit (pagination/card-field correctness was
#632–#659) and *rows* work. `drift_confirmed` was 0 and enumeration stayed intact, so the
listing layer was probed only as far as the D3 types needed.

---

## Phase 0 — canary calibration (before touching the site)

| canary | expected | observed | reading |
|---|---|---|---|
| `eporner-healthy` | plain-curl 200 | plain-curl **200** (7 394 B — the site's own age-verification page, `<title>Age verification – Eporner</title>`) | recorded expectation matches (200) — instrument healthy; body shape unchanged from FINDINGS-1457 |
| `film1k-challenge-pair` | plain-curl 403, tls-impersonated 200 | plain-curl **200** (214 506 B) | **expectation-stale** (#1435 open) — live pair recorded here; **no** `environment-degraded` branch taken |
| `pandamovies-redirect-origin` | plain-curl 302, tls-impersonated 200 | plain-curl **302 → https://pandamovies.org/** | plain-tier match; the TLS leg cannot run (#1434) |
| site under audit | — | plain-curl **200** (26 564 B) | probe tier available; no challenge on any probed path |

```
$ UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36'
$ curl -s -o /dev/null -A "$UA" -w '%{http_code} %{size_download}\n' https://www.eporner.com/    → 200 7394
$ curl -s -o /dev/null -A "$UA" -w '%{http_code} %{size_download}\n' https://www.film1k.com/     → 200 214506
$ curl -s -o /dev/null -A "$UA" -w '%{http_code} %{redirect_url}\n' https://pandamovies.pw/       → 302 https://pandamovies.org/
$ curl -s -o /dev/null -A "$UA" -w '%{http_code} %{size_download}\n' https://en.sex-film.biz/     → 200 26564
$ python3 -c 'import curl_cffi'
ModuleNotFoundError: No module named 'curl_cffi'
```

- **#1434 (open)** — the TLS-impersonated tier is unusable in this container →
  `escalation_tier: unavailable`. This run produced **zero** `Blocked` verdicts, so nothing
  was downgraded. (A headless Chromium **is** present and was used once as a
  diagnosis-only rung for the s2 player — its output is a Lead, recorded as such below.)
- **#1435 (open)** — film1k's stale expectation is recorded, not treated as degradation.
- No genuine `environment-degraded` mismatch → instrument healthy.

---

## Listing-type inventory (D3)

Cards are `div.short` (`.short-poster img`, `.th-title`) everywhere; pagination is DLE
`page/N/`. `page/1/` answers `301 → /<canonical>/`, `page/last+1/` answers `404`.
A type the site does not expose is recorded **`absent`**, never as a finding.

| type | surface | ends sampled | evidence |
|---|---|---|---|
| `new` / `latest` | **`/movies/`** (also the homepage rows) | p1 / p2, plus the catalogue tail p341 | p1 **24** cards, p2 **24**, p1∩p2 = **0**; p341 **15**, p342 **404** |
| `old` | **absent as a route**: `/old/`, `/oldest/`, `/movies/old/`, `/movies/latest/` are 404, and `?sort=asc` / `?order=asc` / `?sort=oldest` are 301-stripped to `/en/movies/` (sort ignored) | catalogue tail of `new` (p340/p341) sampled instead | transcripts below |
| `popular` | **`/top.html`** — Top 100, 27 items per page, JS pagination `POST /engine/mods/custom/ajax.php` (`name=top100&cstart=<page>&action=getpage`) | page 1 and page 4 (last) | p1 **27** `li.top100-item`, `cstart=2` → distinct set, `cstart=4` → distinct set (no `page/` route: `/top.html/page/2/` 404) |
| `tags` | **`/tags/<tag>/`** — the site's only taxonomy; catalogue `/tags/` lists **427** tag anchors | `/tags/teen/` p1 … last p7; `/tags/all%20sex/` catalogue end p492 | teen: p1 **24**, p7 **9**, p8 **404**; all-sex: p492 **11**, p493 **404** |
| `categories` | **`/movies/studios/<studio>/`** — `/studios.html` lists 11 studios and links only those paths (`/watch/studio/` is a 200 stub, not a listing) | `/movies/studios/private/` p1 … last p34 | p1 **24**, p2 **24**, p34 **17**, p35 **404** |
| `genres` | **`absent`**: `/genres/`, `/genre/`, `/genres` 404; `/categories/`, `/category/`, `/categories` 404. The site models subject taxonomy as `/tags/` only (no genre vocabulary in the nav or on cards) | — | transcripts below |

Extra site surfaces (not D3 types, recorded for completeness): `/watch/year/<yyyy>/`
("Premieres", p1 24, p2 24 — 24 cards even for `/watch/year/1990/`), `/collections/`
(13 curated *.html* links, no collection pages of its own), `/porno-video/` (Scenes) and
`/videos/`-style rows that all reuse the same cards and stream path.

Provider-row check (D5 trigger: none — `drift_confirmed` was 0 and enumeration did not
break): every provider `mainPage` row answered 200 with cards (`/movies/`, `/porno-video/`,
`/movies/hd-porno-movies/`, `/movies/fullhd-porn-movie/`, `/movies/porno-parodies/`,
`/movies/vintage/`).

```
$ curl -s ... https://en.sex-film.biz/movies/                             → 200 24762  cards=24
$ curl -s ... https://en.sex-film.biz/movies/page/2/                      → 200 25448  cards=24   (overlap with p1: 0)
$ curl -s ... https://en.sex-film.biz/movies/page/341/                    → 200 21091  cards=15
$ curl -s ... https://en.sex-film.biz/movies/page/342/                    → 404 12237  cards=0
$ curl -s ... 'https://en.sex-film.biz/movies/?sort=asc'                  → 301 -> https://en.sex-film.biz/en/movies/
$ curl -s ... 'https://en.sex-film.biz/movies/?order=asc'                 → 301 -> https://en.sex-film.biz/en/movies/
$ curl -s ... 'https://en.sex-film.biz/movies/page/1/?sort=oldest'        → 301 -> https://en.sex-film.biz/en/movies/
$ curl -s ... https://en.sex-film.biz/old/                               → 404 11794
$ curl -s ... https://en.sex-film.biz/oldest/                            → 404 11794
$ curl -s ... https://en.sex-film.biz/movies/old/                        → 404 11794
$ curl -s ... https://en.sex-film.biz/movies/latest/                     → 404 11794
$ curl -s ... https://en.sex-film.biz/genres/                            → 404 11794
$ curl -s ... https://en.sex-film.biz/genre/                             → 404 11794
$ curl -s ... https://en.sex-film.biz/categories/                        → 404 11794
$ curl -s ... https://en.sex-film.biz/category/                          → 404 11794
$ curl -s ... https://en.sex-film.biz/tags/                              → 200 66346   (427 tag anchors)
$ curl -s ... https://en.sex-film.biz/tags/teen/                         → 200 26964   cards=24
$ curl -s ... https://en.sex-film.biz/tags/teen/page/7/                  → 200 16958   cards=9
$ curl -s ... https://en.sex-film.biz/tags/teen/page/8/                  → 404 12208
$ curl -s ... 'https://en.sex-film.biz/tags/all%20sex/page/492/'         → 200 18842   cards=11
$ curl -s ... 'https://en.sex-film.biz/tags/all%20sex/page/493/'         → 404 12236
$ curl -s ... https://en.sex-film.biz/studios.html                       → 200 15067   (11 studio rows)
$ curl -s ... https://en.sex-film.biz/movies/studios/private/            → 200 26331   cards=24
$ curl -s ... https://en.sex-film.biz/movies/studios/private/page/34/    → 200 23214   cards=17
$ curl -s ... https://en.sex-film.biz/movies/studios/private/page/35/    → 404 12503
$ curl -s ... https://en.sex-film.biz/top.html                            → 200 20862   li.top100-item=27
$ curl -s -H 'Referer: https://en.sex-film.biz/top.html' --data 'name=top100&cstart=2&action=getpage' \
       https://en.sex-film.biz/engine/mods/custom/ajax.php                → 200 9187    li.top100-item=27 (set ≠ p1)
$ curl -s ... https://en.sex-film.biz/watch/year/2026/                   → 200 26073   cards=24 (Premieres)
$ curl -s ... https://en.sex-film.biz/watch/year/2026/page/2/            → 200 25609   cards=24
```

Quick search: **absent** (unchanged from #632) — the DLE `do=suggest` endpoint serves a full
unfiltered HTML page, not AJAX; the provider keeps `hasQuickSearch` unset.

---

## Player inventory (what a video page actually carries)

Every video page injects **up to four** same-origin-free iframes from `s2.src = "<url>"`
assignments inside `<script>` click handlers:

| container | host | shape as of this run |
|---|---|---|
| `#video_container` | `morencius.com/embed/<id>` or `s2.filmcdn.top/e/<b64token>` | packed Dean-Edwards JW config (works) / WASM-gated player (below) |
| `#video2_container` | `filmcdm.top/e/<id>` | **packed JW config** (works), **302 → `vsembed.com/<code>`** (new shape — fixed this run), or an **empty id `https://filmcdm.top/e/`** placeholder |
| `#video3_container` | `playmogo.com/e/<id>` | Cloudflare **Turnstile** captcha shell (200, 5.3–5.8 kB, `challenges.cloudflare.com/turnstile`) |
| `#trailer_container` | `voe.sx/e/<id>` | trailer only (`itemprop="embedUrl"`), JS token redirect page (747 B) |

`Parse.embeds` matches filmcdm/s2/morencius (never playmogo/voe), so the provider's ceiling is
"whatever those three hand over". Two shapes hand over nothing:

1. **`s2.filmcdn.top` (WASM/adscore player).** The embed page is a 135 kB JS shell
   (`embed.232.js`, 173 kB obfuscated) whose player POSTs
   `/player/get_md5.php` with `htoken`, `sh`, `secure`, `adb`, `v`, `token`, `gt`,
   `wasmcheck`, `adscore`, `click_hash`, `clickx`, `clicky` — client-computed proof-of-work.
   The provider's s2 branch (a `cfglobalcdn.com … .m3u8` regex, live at the 2026-10-16 pass)
   finds **0 matches** in any sampled s2 page now.
2. **`playmogo.com`** — Turnstile captcha, unchanged in effect from the 2026-10-16 "403 from
   the runner" note (only the gate's shape changed: 403 challenge → 200 captcha shell).

Neither is a provider selector; both are hosts with **no plain-HTTP stream surface**. The s2
class is filed as a data-completeness finding (below).

---

## Evidence rows (D4)

`type | sample URL | instrument tier | stream host | quality | verdict`
— ≥2 rows per present type, both catalogue ends where the type is pageable. All rows
plain-curl, desktop UA, `Referer: https://en.sex-film.biz/`; "resolved" means the **exact
chain the provider runs** (embed page → unpack → m3u8 → variant → first segment) or, for the
vsembed rows, the chain the fixed provider now runs (302 → tiers → `download/get` 302 → mp4).

| type | sample URL | tier | stream host | quality | verdict |
|---|---|---|---|---|---|
| new (p1) | `/11918-friends-neighbours-4.html` | plain-curl | `*.premilkyway.com` (filmcdm hls2) + `*.acek-cdn.com` (morencius) | 720p (master `1280x720`; site `span.gvhd` HD 720P) | **pass** — m3u8 200, variant 200, `seg-1` **206 video/MP2T** |
| new (p1) | `/11841-pussymans-sexiest-ladies.html` | plain-curl | `*.premilkyway.com` + `*.dramiyos-cdn.com` | 480p/720p | **pass** — m3u8 200 |
| new (p2) | `/11802-our-first-orgy.html` | plain-curl | `*.premilkyway.com` | 720p | **pass** — packed JW config, master 200 |
| old (tail p341) | `/59-private-tropical-16-sun-beach-and-sex-2005.html` | plain-curl | `s2.filmcdn.top` only | — | **Suspected** — s2 WASM-gated, 0 links (transcript below) |
| old (tail p341) | `/4-dont-tell-my-wife-i-buttfucked-her-best-friend-9-2019.html` | plain-curl | `s2.filmcdn.top` only (+ playmogo Turnstile) | — | **Suspected** — same |
| popular (Top 100 p1) | `/1778-body-heat-2010.html` | plain-curl | `vsembed.com` → CDN `94.131.217.43` (**fixed this run**) | 1080p/720p/540p (tier labels) | **pass** — 1080p mp4 **206 video/mp4** |
| popular (Top 100 p1) | `/944-kill-bill-a-xxx-parody-2015.html` | plain-curl | `vsembed.com/uk6g` → CDN (**fixed this run**) | 1080p/720p/540p | **pass** — 1080p mp4 **206 video/mp4** |
| tags (teen p1) | `/11914-real-naked-and-uncensored-anna-claire-clouds-is-your-anime-milf.html` | plain-curl | `*.cdn-centaurus.com` (filmcdm) + `*.acek-cdn.com` (morencius) | 480p/720p/1080p | **pass** — m3u8 200, `seg-1` **206 video/MP2T** |
| tags (teen tail p7) | `/1617-private-specials-132-teens-in-stockings-2016.html` | plain-curl | `vCVkQwY2zRcK.premilkyway.com` (filmcdm) | 480p/720p/1080p advertised | **Suspected** — signed playlist stalls (curl 28, 0 bytes, 30 s); the same page's `hls4` serves the ad-image playlist |
| tags (all-sex tail p492) | `/40-private-gold-57-big-member-2002.html` | plain-curl | `s2.filmcdn.top` only | — | **Suspected** — same s2 class |
| categories (studio `private` p1) | `/11867-private-pov.html` | plain-curl | `*.premilkyway.com` + `*.acek-cdn.com` | 480p/720p | **pass** — m3u8 200, `seg-1` **206 video/MP2T** |
| categories (studio `private` tail p34) | `/18-private-xxx-39-sex-bites-2008.html` | plain-curl | `s2.filmcdn.top` only | — | **Suspected** — same s2 class |
| popular (Top 100 p4) | `/763-the-job.html` | plain-curl | `vsembed.com` (filmcdm 302) | 1080p… | **pass** — 302 → vsembed, same chain as rows 6–7 |
| genres | — | — | — | — | **absent** (site has no genre route) |

### Non-pass transcripts (raw)

**(a) `filmcdm` ad-image playlist vs the real CDN playlist** — the provider emits both; the
real one is first in the unpacked config (`hls2`), the `/stream/…` one is the site's
ad-substituted variant and is recorded because a future reader will hit it:

```
$ curl -s "$UA" https://filmcdm.top/stream/<sig>/<…>/master.m3u8      → 200 144 application/vnd.apple.mpegurl
   #EXT-X-STREAM-INF:…RESOLUTION=1280x720… → index-v1-a1.m3u8
$ curl -s "$UA" https://filmcdm.top/stream/<sig>/<…>/index-v1-a1.m3u8 → 200 200062
   #EXTINF:10.000,
   https://p19-ad-site-sign-sg.tiktokcdn.com/ad-site-i18n-sg/…-ttam-origin.image?…
$ curl -s -r 0-200000 <that “segment”>                                → 200 1348030 image/png
$ file ad.img → PNG image data, 1 x 1, 8-bit/color RGBA     # 906 such entries
```

The same page's `hls2` (direct CDN) is the real stream:

```
$ curl -s "$UA" https://znOMC6AzQ2DC.premilkyway.com/hls2/…/master.m3u8    → 200 540 application/vnd.apple.mpegurl
$ curl -s "$UA" …/index-f2-v1-a1.m3u8                                     → 200 220186   # 906 × #EXTINF
$ curl -s "$UA" -r 0-300000 …/seg-1-v1-a1.ts                              → 206 300001 video/MP2T
```

**(b) `s2.filmcdn.top` — the WASM/adscore class (site-side, no plain-HTTP surface)**

```
$ curl -s "$UA" -H 'Referer: https://en.sex-film.biz/' \
    https://s2.filmcdn.top/e/V0d1b0o2RnFhVUJrYTBKZVFpUFFRUT09          → 200 135657 text/html
$ grep -o 'https://[a-z0-9.-]*cfglobalcdn.com[^" ]*\.m3u8' page         → (no matches)
$ grep -o '"/player/[a-z_]*\.php[^"]*"' page
    → /player/embed_player.php?vid=
$ python3 - <<'PY'  # the player's own API, minimal payload
    POST https://s2.filmcdn.top/player/get_md5.php  {"v":"<token>","ver":"4","token":"<md5 from page>"}
PY
{"407":"1"}                       # full signed payload (wasmcheck/adscore/click_hash/…) required
{"try_again":"1","updatecxt":"1"} # without "ver"
```

Headless-Chromium diagnosis (Lead, not evidence — ADR-0008): 25 s + three synthetic clicks on
the player area produced **no media request at all** (only `cdn-cgi/trace`, Yandex metrika,
`commentsmodule.com` 403s, `videocdnmetrika.com`); the rendered DOM has no `<video>`/`<source>`.
The old `cfglobalcdn.com` signed-m3u8 the provider regex looks for is simply not in the page
any more.

**(c) `playmogo.com` — Turnstile shell**

```
$ curl -s "$UA" -H 'Referer: https://playmogo.com/' https://playmogo.com/e/6o4nrl8y29ka → 200 5380
  <title>… - DoodStream.com</title>  <script src="//challenges.cloudflare.com/turnstile/v0/api.js">
  (no /pass_md5/ anywhere → the registered dood-family adapter cannot start)
```

**(d) `filmcdm.top/e/` empty-id placeholder** (site writes an `<iframe src>` with no id on
old titles):

```
<iframe … s2.src = "https://filmcdm.top/e/"        >   # video2_container
<iframe … s2.src = "https://morencius.com/embed/"  >   # video_container
```

**(e) `1617` signed-CDNa edge stall**

```
$ curl -sS "$UA" -H 'Referer: https://en.sex-film.biz/' \
   "https://vCVkQwY2zRcK.premilkyway.com/hls2/01/03576/3ea9msz6ewzk_,n,h,x,.urlset/master.m3u8?t=…"
curl: (28) Operation timed out after 30002 milliseconds with 0 bytes received
$ getent hosts vCVkQwY2zRcK.premilkyway.com → 203.188.166.54   # DNS fine; other premilkyway edges 200
```

---

## Fix (TDD red → green)

Confirmed condition (**Correctness / data-completeness**): `filmcdm.top/e/<id>` 302s part of
the catalogue to the **vsembed.com** player; its page is a vite SPA (`/assets/index-*.js`) whose
playback comes from `/api/download/tiers?s=<code>` + `/api/download/get?s=<code>&t=<tier>`
(302 → signed mp4). The provider fetched that page, found no packed config, and emitted
nothing — 3 of 15 census pages (10, 20, 190 below) plus the Top-100 samples 1778/944/763.

Structural fix, following the #1448/#1439 precedent (shared adapter + registry dispatch, no
provider-side host branch, additive rows):

1. `shared/src/main/kotlin/com/kraptor/TurboChain.kt` — `TurboEmbedParse.code(url, host)`
   (host is now a parameter; default keeps the turboembed.com call sites identical),
   `TurboEmbed.mainUrl/name` made overridable, and a `turboEmbed(url, name)` mirror factory.
2. `shared/src/main/kotlin/com/kraptor/HostRegistry.kt` — one row:
   `turboEmbed("https://vsembed.com", "VsEmbed")`.
3. `Sexfilm/src/main/kotlin/com/sexfilm/Sexfilm.kt` — `addSource` now inspects the **final**
   URL of the embed fetch (`Parse.vsembedRedirect(res.url)`) and hands a vsembed target to
   `loadExtractor` (the registry row) instead of trying to unpack a JW config that isn't
   there; `subtitleCallback` is threaded through for that dispatch.
4. `Sexfilm/build.gradle.kts` — `version = 26 → 27`. Only Sexfilm bumps: no other provider in
   this repo references filmcdm/vsembed (rows are additive; precedent #1439).

Red → green (tests written first, against fixtures/strings captured live this run):

```
$ ./gradlew Sexfilm:test            # RED: "Unresolved reference 'vsembedRedirect'" +
                                    #      "Unresolved reference 'code(url, host)'"
$ ./gradlew Sexfilm:test            # GREEN: BUILD SUCCESSFUL
```

New/extended tests: `Sexfilm/src/test/kotlin/com/sexfilm/ParseTest.kt`
(`vsembed redirect target is dispatched to the shared row` — positive, whitespace-tolerant,
query-carrying, and four negatives incl. the `vsembed.com.evil.example` lookalike),
`shared/src/test/kotlin/com/kraptor/TurboChainParseTest.kt`
(`vsembed resolves the same code grammar on its own host`, `vsembed tiers carry the exposed
quality labels`) with fixture `shared/src/test/resources/turbo-chain/vsembed-tiers.json`
(live capture: 1080p/720p/540p).

Post-fix chain, straight from the run:

```
GET https://filmcdm.top/e/vsj7cqe8n5li                     → 302 location: https://vsembed.com/r0hg
GET https://vsembed.com/api/download/tiers?s=r0hg           → 200 {"data":{"tiers":[{"file":"1080p",…},{"file":"720p",…},{"file":"540p",…}]}}
GET https://vsembed.com/api/download/get?s=r0hg&t=1080p     → 302 location: https://94.131.217.43/e/…/dl/wn6b/1080p?n=Body%20Heat%20-%201080p.mp4
GET <that url> -r 0-100000                                  → 206 video/mp4 (ISO Media, MP4 Base Media v1)
```

Quality reaches CloudStream from the tier label (`getQualityFromName("1080p")`), so the
provider now passes the quality the site exposes for this class; for the packed-JW class the
master playlist advertises `RESOLUTION=1280x720` (and 852x480/1920x1080 where present) and the
app derives it from the manifest.

### Post-fix census (15 catalogue positions, one video each, `/movies/page/{2…335}/` mid-card)

| outcome | count | pages |
|---|---|---|
| resolvable — packed JW config | 3 | p2, p80, p160 |
| resolvable — vsembed (fixed this run) | 3 | p10, p20, p190 (**zero links before the fix**) |
| zero links — `s2.filmcdn.top` only | 9 | p40, p60, p100, p130, p220, p250, p280, p310, p335 |

`drift_confirmed` = 1 (the vsembed condition, confirmed and fixed here). The s2-only class is
**site-side** (no plain-HTTP surface exists to select) and is filed as one
data-completeness finding on this issue's thread rather than counted as provider drift.

---

## Delivery gate

```
$ ./gradlew Sexfilm:test        → BUILD SUCCESSFUL (shared + provider suites, 0 failures)
$ ./gradlew Sexfilm:make        → BUILD SUCCESSFUL → Sexfilm/build/Sexfilm.cs3
$ .pi/skills/audit-providers/scripts/check-findings.sh → findings-lint: PASS   (registry untouched in this diff)
```

`.pi/skills/verify-provider/scripts/verify.sh` (live, plain-curl, desktop UA) — **RESULT:
PASS**; invocation and full output below.

```
── check 1: search pages (2)
GET https://en.sex-film.biz/index.php?do=search&subaction=search&story=lesbian → 200; 'div.short' matches: 24
GET https://en.sex-film.biz/index.php?do=search&subaction=search&story=lesbian&search_start=2 → 200; 'div.short' matches: 24
── check 1a: homepage pages (2)
GET https://en.sex-film.biz/movies/ → 200; 'div.short' matches: 24
GET https://en.sex-film.biz/movies/page/2/ → 200; 'div.short' matches: 24
NOTE: repeated poster on home across different videos (episodes of one series share the series poster — not a card duplicate):
poster	/templates/Default/dleimages/no_image.jpg	home0,home1
── check 1b: quick search (0)
NOTE: no --quick-search-url — required when FINDINGS records a distinct quick-search endpoint; its absence must be explicit in FINDINGS
── check 2: video pages (6 URLs)
GET https://en.sex-film.biz/11918-friends-neighbours-4.html → 200; 'div.fplayer' matches: 1
GET stream (https://znOMC6AzQ2DC.premilkyway.com/hls2/…/master.m3u8?t=…) → 200 application/vnd.apple.mpegurl
GET https://en.sex-film.biz/11841-pussymans-sexiest-ladies.html → 200; 'div.fplayer' matches: 1
GET stream (https://jZdDKEFw9oEu0.premilkyway.com/hls2/…/master.m3u8?t=…) → 200 application/vnd.apple.mpegurl
GET https://en.sex-film.biz/1778-body-heat-2010.html → 200; 'div.fplayer' matches: 1
GET stream (https://94.131.217.43/…/dl/wn6b/1080p…) → 206 video/mp4        ← the fixed vsembed row
GET https://en.sex-film.biz/11867-private-pov.html → 200; 'div.fplayer' matches: 1
GET stream (https://jZdDKEFw9oEu0.premilkyway.com/hls2/…/master.m3u8?t=…) → 200 application/vnd.apple.mpegurl
GET https://en.sex-film.biz/11914-…-anime-milf.html → 200; 'div.fplayer' matches: 1
GET stream (https://vwyn3lxe5fv5.cdn-centaurus.com/hls2/…/master.m3u8?t=…) → 200 application/vnd.apple.mpegurl
GET https://en.sex-film.biz/11840-pussyman-2-the-prize.html → 200; 'div.fplayer' matches: 1
GET stream (https://94.131.217.43/…/dl/lgmp9/720p…) → 206 video/mp4        ← the fixed vsembed row
field 'tags': present on all 6 sampled pages
field 'actors': present on all 6 sampled pages
field 'year': present on all 6 sampled pages
field 'duration': present on all 6 sampled pages
── check 5: search ↔ load agreement
NOTE: no sampled video appears in the search page(s) — pick a --search-url whose query matches a sample video to exercise agreement
── check 6: LoadResponse completeness (code vs FINDINGS)
field 'posters': 2 assignment(s) in Sexfilm
field 'plot': 1 assignment(s) in Sexfilm
field 'tags': 1 assignment(s) in Sexfilm
field 'actors': 1 assignment(s) in Sexfilm
field 'year': 1 assignment(s) in Sexfilm
field 'duration': 2 assignment(s) in Sexfilm
field 'recommendations': 2 assignment(s) in Sexfilm
RESULT: PASS
```

```
.pi/skills/verify-provider/scripts/verify.sh \
  --search-url 'https://en.sex-film.biz/index.php?do=search&subaction=search&story=lesbian' \
  --search-url 'https://en.sex-film.biz/index.php?do=search&subaction=search&story=lesbian&search_start=2' \
  --search-selector 'div.short' --search-title-selector 'a.th-title' \
  --search-poster-selector 'a.short-poster img' \
  --home-url 'https://en.sex-film.biz/movies/' --home-url 'https://en.sex-film.biz/movies/page/2/' \
  --home-selector 'div.short' \
  --video-url '<11918>'  --stream-url '<premilkyway hls2>' \
  --video-url '<11841>'  --stream-url '<premilkyway hls2>' \
  --video-url '<1778>'   --stream-url '<94.131.217.43 … 1080p mp4>' \
  --video-url '<11867>'  --stream-url '<premilkyway hls2>' \
  --video-url '<11914>'  --stream-url '<cdn-centaurus hls2>' \
  --video-url '<11840>'  --stream-url '<94.131.217.43 … 720p mp4>' \
  --stream-selector 'div.fplayer' --related-selector 'div.sect-c div.short' \
  --video-title-selector 'h1#s-title' --video-plot-selector 'div#s-desc' \
  --video-tags-selector "a[href*='/tags/']" --video-actors-selector "a[href*='/watch/name/']" \
  --video-year-selector "span.gv a[href*='/watch/year/']" --video-duration-selector 'meta[itemprop=duration]' \
  --provider-src 'Sexfilm' \
  --load-response 'posters,plot,tags,actors,year,duration,recommendations'
```

Harness notes (site shape, not provider defects):

- `--stream-url` is position-matched because the streams only exist in click-injected JS
  (`s2.src`); `div.fplayer` is the container selector.
- Home cards repeat `/templates/Default/dleimages/no_image.jpg` — the site's own lazy
  placeholder; the real poster is on the card's `img[data-src]` (the provider reads it).
- Check 5 is a NOTE only because no sampled video is in the `lesbian` search page. Agreement
  was checked by hand instead, on `story=pussyman` p1: card title *Pussyman 2: The Prize*
  equals load `h1#s-title`; the poster legitimately differs (card `/thumbs/…webp`, load page
  full-size `…webp` — the provider reads `og:image` on load, which is correct).

---

## Findings lifecycle

Dedup (open **and** closed, `gh issue list --search`): no standing issue covers the vsembed
condition (fixed here, nothing filed) or the s2/playmogo class — the earlier
data-completeness findings (#176, #213, #277, #335) are closed and unrelated in mechanism.
One finding filed for the s2-only class (`triage/from-agent`, evidence quoted above); it is
**not** counted as provider drift, and `suspected_excluded`/false-positive bookkeeping starts
empty for this run.

## Registry write

Nothing in the diff (ADR-0013). The provider entry (verdict `ok-drift`, tier `plain-curl`,
`drift_confirmed` 1) plus this run's `last_runs` entry (`false_positives: []`) are posted on
the issue thread as a `<!-- findings-registry-entry -->` comment for `registry_sync`.
