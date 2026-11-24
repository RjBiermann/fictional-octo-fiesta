# FINDINGS-1457 — PornXP video sources (S19 of #1354)

Video-source leg (D2) of the PornXP spec (`pxp.news`). Scope: for every D3 listing type
(`new`/`latest`, `old`, `popular`, `genres`, `categories`, `tags`) open ≥2 video pages,
sampled at both ends of the catalogue where the type is pageable, and resolve `loadLinks`
end-to-end — video page → `#player > source` mp4 → CDN object → quality passed to
CloudStream where the site exposes it.

Provider state at run start: registry `last_deep` = 2026-10-16, `drift_confirmed` = **0**,
verdict at last deep = `ok` (#632). Provider `version` = 24.

**Result: the video-source leg is clean — no provider condition confirmed, no code change,
`version` stays 24.** 16/18 sampled video rows serve 206 `video/mp4` end-to-end. The two
non-pass rows are **dead CDN objects at the site source** (404 from nginx on
`https.pornxp.sh`), identical in the site's own player config and on the `pornxp.farm`
mirror; there is no alternate stream on those pages and no provider selector is missing.
`genres` and `categories` are **absent** on the site and recorded as such, never as findings.

Run: `05-implement` for #1457, 2026-11-24, plain-curl tier, runner IP US.
Workflow run: `https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/45429835116`.

Out of scope per spec: a full listing audit (pagination/card-field correctness was
#632–#659) and *rows* work. Listing correctness was checked only as far as enumeration
needed; `drift_confirmed` = 0 and enumeration stayed intact.

---

## Phase 0 — canary calibration (before touching the site)

| canary | expected | observed | reading |
|---|---|---|---|
| `eporner-healthy` | plain-curl 200 | plain-curl **200** (313 334 B — real homepage) | match — instrument healthy |
| `film1k-challenge-pair` | plain-curl 403, tls-impersonated 200 | plain-curl **200** (215 012 B) | **expectation-stale** (#1435 open) — live pair recorded here; **no** `environment-degraded` branch taken |
| `pandamovies-redirect-origin` | plain-curl 302, tls-impersonated 200 | plain-curl **302 → https://pandamovies.org/** | plain-tier match; the TLS leg cannot run (#1434) |

```
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://www.eporner.com/   → 200 313334
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://www.film1k.com/    → 200 215012
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://pandamovies.pw/      → 302 https://pandamovies.org/
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://pxp.news/           → 200 167810
$ python3 -c 'import curl_cffi'
ModuleNotFoundError: No module named 'curl_cffi'      # #1434 still open at run time
```

- **#1434 (open)**: the TLS-impersonated tier is unusable in this container →
  `escalation_tier: unavailable`. This run had **zero** `Blocked` verdicts, so nothing was
  downgraded.
- **#1435 (open)**: film1k's stale expectation is recorded, not treated as degradation.
- Desktop UA for every request:
  `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36`.
  No proxy, no impersonation, no browser instrument anywhere below.

---

## Listing-type inventory (D3)

Cards are `.item_cont` everywhere (`.item_title`, `.item_thumb img`, `.item_dur`,
`.item_tags`), and `?page=N` is the only pagination parameter. A type the site does not
expose is recorded **`absent`**, never as a finding.

| type | surface | ends sampled | evidence |
|---|---|---|---|
| `new` / `latest` | **`/`** (New Videos; `/new/` is the identical listing — same first `data-id`s) | p1 / p2 (start) and p5721 (tail) | p1 **36** cards, p2 **36**; p5721 **17**, no `>` next; p5722 **0** cards |
| `old` | **absent as a route** — `/old/`, `/latest/`, `/?order=asc`, `/?sort=oldest`, `/?order=oldest` all ignored/404 (`order`/`sort` params return the same first `data-id`s as `/`) | catalogue tail sampled under `new`/tail | `/?order=asc` first ids == `/` first ids (`29334217`, `66940073`, `40716716`) |
| `popular` | **`/best/`** (All Time Best Videos) | p1 / p2 (start) and p1341 (tail) | p1 **25**, p2 **20**, p3 **25**; p1340 **34** with next, p1341 **23** no next, p1342 **0** |
| `tags` | **`/tags/<tag>`** (the site's only taxonomy; `/tags/` itself is a 0-byte 200) | `/tags/ATKGirlfriends` p1 + p121, `/tags/Vintage` p1 | ATK p1 **36**, p2 **36**, p120 **36** with next, p121 **32** no next, p122 **36** generic fallback (0/36 ATK-tagged) |
| `genres` | **absent** — `/genres/` 404, `/genre/` 404, `/genres` 200 0 bytes, `/genre/<x>` 404. No genre vocabulary in the nav or card tags | — | routing transcript below |
| `categories` | **absent** — `/categories/` 404, `/category/` 404, `/categories` 200 0 bytes. The site models taxonomy only as tags (studios/performers/subjects share `/tags/`) | — | routing transcript below |

Extra site rows (not D3 types, recorded for completeness): `/released/` = New Releases
(p1 36, p2 36, tail p5721 17), `/hd/` = Top HD (p1 36, p2 36, tail p2998 23); both use the
same cards/stream path. The provider's `mainPage` includes `/hd/` but not `/released/`;
adding a row is listing work, not a video-source fix, so it is not in this PR.

```
$ curl -s -A "$UA" -o /dev/null -w '%{http_code} %{size_download}\n' https://pxp.news/genres/      → 404 548
$ curl -s -A "$UA" -o /dev/null -w '%{http_code} %{size_download}\n' https://pxp.news/genre/       → 404 548
$ curl -s -A "$UA" -o /dev/null -w '%{http_code} %{size_download}\n' https://pxp.news/genres        → 200 0
$ curl -s -A "$UA" -o /dev/null -w '%{http_code} %{size_download}\n' https://pxp.news/categories/  → 404 548
$ curl -s -A "$UA" -o /dev/null -w '%{http_code} %{size_download}\n' https://pxp.news/category/    → 404 548
$ curl -s -A "$UA" -o /dev/null -w '%{http_code} %{size_download}\n' https://pxp.news/categories   → 200 0
```

Tag-page boundary (the provider's `Parse.searchHasNext` reads the site's own `>` control;
the generic fallback is not a tag page and carries no `>`):

```
/tags/ATKGirlfriends?page=120  → 200 cards=36 next=1  (36/36 ATK-tagged)
/tags/ATKGirlfriends?page=121  → 200 cards=32 next=0  (32/32 ATK-tagged)  ← real last page
/tags/ATKGirlfriends?page=122  → 200 cards=36 next=0  (0/36 ATK-tagged)  ← generic fallback
```

Site-side observation, **not** a finding: during the run the tag routes transiently served
the generic newest grid for every tag (all cards `Morning Sex` / `Hegre` etc.) for about a
minute, then returned tag-specific cards again; `/tags/ATKGirlfriends?page=1` with a
cache-buster recovered immediately. This is a site/cache transient in the listing layer,
outside D2 and not a provider selector defect. The tag catalogue also grew since #632:
ATK's last page is now **121**, not the 119 recorded in the 2026-10-16 pass.

---

## Evidence rows (D4)

`type | sample URL | listing position | instrument tier | stream host | quality | verdict`
— ≥2 rows per present type, both catalogue ends where the type is pageable. All URLs are
`https://pxp.news` + path; every row used the plain-curl tier and the desktop UA.

| # | type | sample URL | listing position | tier | stream host | quality | verdict |
|---|---|---|---|---|---|---|---|
| 1 | new | `/videos/29334217396` | `/` p1 | plain-curl | `sd.pornxp.sh` | 360/720/1080p | **pass** |
| 2 | new | `/videos/66940073506` | `/` p1 | plain-curl | `look.pxp.network` | 360/720/1080p | **pass** |
| 3 | new | `/videos/99449636399` | `/` p2 | plain-curl | `watch.pxp.network` | 360/720/1080p | **pass** |
| 4 | new | `/videos/928959391288` | `/` p2 | plain-curl | `sd.pornxp.sh` | 360/720/1080p | **pass** |
| 5 | old (catalogue tail) | `/videos/69558834624` | `/` p5721 (oldest end) | plain-curl | `cc.pornxp.sh` | 360p | **pass** |
| 6 | old (catalogue tail) | `/videos/23893624696` | `/` p5721 (oldest end) | plain-curl | `cc.pornxp.sh` | 360p | **pass** |
| 7 | old (catalogue tail) | `/videos/9128498874` | `/` p5721 (oldest end) | plain-curl | `https.pornxp.sh` | 360/720/240p advertised | **Suspected** — site-side dead object (404) |
| 8 | popular | `/videos/245008591800` | `/best/` p1341 (tail) | plain-curl | `https.pornxp.sh` | 360/180p advertised | **Suspected** — site-side dead object (404) |
| 9 | popular | `/videos/811805181980` | `/best/` p1 | plain-curl | `xxx.pornxp.sh` | 360p | **pass** |
| 10 | popular | `/videos/96082620600` | `/best/` p2 | plain-curl | `d.pornxp.sh` | 360/720/1080p | **pass** |
| 11 | popular | `/videos/24078228504` | `/best/` p2 | plain-curl | `cc.pornxp.sh` | 360/720p | **pass** |
| 12 | popular | `/videos/80964695150` | `/best/` p1341 (tail) | plain-curl | `c.pornxp.sh` | 360p | **pass** |
| 13 | tags (`ATKGirlfriends`) | `/videos/55337788126` | tag p1 | plain-curl | `sv.pornxp.sh` | 360p | **pass** |
| 14 | tags (`ATKGirlfriends`) | `/videos/17686704675` | tag p1 | plain-curl | `xxx.pornxp.sh` | 360p | **pass** |
| 15 | tags (`ATKGirlfriends`) | `/videos/20173527180` | tag p121 (last page) | plain-curl | `xxx.pornxp.sh` | 360/720/1080p | **pass** |
| 16 | tags (`ATKGirlfriends`) | `/videos/89285200232` | tag p121 (last page) | plain-curl | `xxx.pornxp.sh` | 360/720/1080p | **pass** |
| 17 | tags (`Vintage`) | `/videos/65314737308` | tag p1 | plain-curl | `cc.pornxp.sh` | 360p | **pass** |
| 18 | hd (extra provider row) | `/videos/67234666847` | `/hd/` p2998 (tail) | plain-curl | `sz.pornxp.sh` | 360/720/1080p | **pass** |
| 19 | genres | — | **absent** (`/genres/`, `/genre/` 404; `/genres`, `/genre` 0-byte 200) | plain-curl | — | — | **absent** |
| 20 | categories | — | **absent** (`/categories/`, `/category/` 404; `/categories` 0-byte 200) | plain-curl | — | — | **absent** |

Every `pass` row is a *stream* verdict: video page 200 → `#player > source` mp4 URL →
HTTP `Range: bytes=0-64` → **206 video/mp4** (65 B), for every advertised source (1–3 per
page). No iframe, no embed host, no DRM, no geo-wall on any sampled page.

### Raw transcripts — non-`pass` rows

Both dead rows carry the same shape: the video page is 200 and the player is the site's own
`fluidPlayer` over `#player > source`; every advertised source 404s at the CDN with an nginx
HTML body. The page has no alternate iframe or download link, and the `pornxp.farm` mirror
serves the identical dead URLs, so no provider selector or fallback is missing.

```
# /videos/9128498874 — "Mature Pinup Style Glass The Ideal Figure" (uploaded 2018.07.06)
$ curl -s -A "$UA" https://pxp.news/videos/9128498874
200 … <video id="player" poster="/912849889674.jpg">
      <source src="//https.pornxp.sh/3GbLJU7rrWF85GY8A9r6lpJ_g/9128498882/360.mp4" title="360p" type="video/mp4">
      <source src="//https.pornxp.sh/eo1fuF7PNPAi5bRPh9SaB0xDg/91284988202/720.mp4" title="720p" type="video/mp4">
      <source src="//https.pornxp.sh/IFKA6E7E84no5Aalr9XykgiiQ/9128498855/240.mp4" title="240p" type="video/mp4">
$ curl -s -A "$UA" -H 'Range: bytes=0-64' -o /dev/null -w '%{http_code} %{content_type} %{size_download}' \
    https://https.pornxp.sh/3GbLJU7rrWF85GY8A9r6lpJ_g/9128498882/360.mp4
404 text/html 548
$ # same 404 for 720p and 240p; same 404 with a browser UA, a pxp.news Referer, and over http://
$ # same path on cc./xxx./sd./d./sv./sz./c./ce./cdrn. → 404, so the object is dead, not the host name
$ curl -sL https://porn-xp.eu/videos/9128498874   # mirror → https://pxp.farm/videos/9128498874
200 … identical <source src="//https.pornxp.sh/…" …> (site-side, both mirrors)

# /videos/245008591800 — "Best Of December 2018" (uploaded 2019.05.29)
$ curl -s -A "$UA" https://pxp.news/videos/245008591800
200 … <source src="//https.pornxp.sh/jjP6fi7yNrdG5t4HH99EXqGww/24500859109/360.mp4" title="360p" type="video/mp4">
      <source src="//https.pornxp.sh/lZGgtg7QZSPn5FDWK99QuUL6A/2450085956/180.mp4" title="180p" type="video/mp4">
$ curl -s -A "$UA" -H 'Range: bytes=0-64' -o /dev/null -w '%{http_code} %{content_type} %{size_download}' \
    https://https.pornxp.sh/jjP6fi7yNrdG5t4HH99EXqGww/24500859109/360.mp4
404 text/html 548
```

Census of the dead-source class (not a provider condition): across the 21 catalogue-tail
pages sampled for the host/status census plus the `/best/` tail, **~4 of ~67** sampled
videos had 404 CDN objects (9128498874, 325424221995, 50196982140, 245008591800); every
other sampled source returned 206. Page 2000 of `/` also serves `https.pornxp.sh` sources
that return 206, so this is per-object purge/expiry at the site CDN, not a dead host and not
a selector miss. No alternate source exists on those pages; a provider cannot synthesize
one. Recorded, not filed (same class as the dead `mixdrop.to` alternate in FINDINGS-906 /
#702, closed not-actionable).

---

## Stream resolution / hosts / quality

`loadLinks` is already the complete chain for this site:

- `app.get(data.substringBefore('#'))` → `.document` (server-rendered page).
- `document.select("#player source")` → 1–3 `<source src=… title="…p" type="video/mp4">`.
- `fixUrl(videoUrl)` resolves the scheme-relative `//host/…` URL; the host is never
  hardcoded, so the observed rotation is safe.
- `quality = getQualityFromName(source.attr("title"))`; advertised qualities include
  360/720/1080, 360/720, 360/180 and 360-only — every rendered quality is passed through.
- `referer = mainUrl`; the CDN does not require it (below), so this is harmless.

Hosts observed this run: `sd.`, `cc.`, `c.`, `d.`, `xxx.`, `sv.`, `sz.`, `look.pxp.network`,
`watch.pxp.network`, `https.pornxp.sh`. The provider extracts the host from the page on every
request, exactly as the #632 pass required; no change.

Referer/UA sensitivity on a live row (`/videos/29334217396`, 360p):

```
ref=https://pxp.news/   206 video/mp4 65
no referer              206 video/mp4 65
no User-Agent           206 video/mp4 65
```

Code inspection (no fix needed, recorded so the next run can diff):

- `load()` reads `.player_details h1`, `#player[poster]`, `#desc`, `.tags a`,
  `.item_cont` recommendations and the `Uploaded: yyyy.mm.dd` year line — all present on
  every sampled page. Duration is not rendered on video pages; the existing card
  `.item_dur` → `#<minutes>` piggyback (#331) remains the only source (tested by
  `ParseTest.kt`).
- `loadLinks()` counts actual `<source>` elements and returns `sources.isNotEmpty()`. No
  sampled page carried a `<source>` without `src`, so the empty-src edge is not confirmed;
  no speculative change made.
- No `HostRegistry`/extractor registration is involved: these are direct mp4 URLs on the
  site's own CDN family, not an embed host.

---

## Confirmed conditions

**None provider-side.** The video-source leg has no missing selector, no unregistered
embed host, no DRM-only source, and no quality-dropping path. The only non-pass rows are
site-side dead CDN objects (above), which no provider change can repair and which the site
itself serves from both mirrors. Per D1 the run therefore ships no code change and no
version bump; escalation to a structural fix is not triggered because no third drift is
confirmed (registry `drift_confirmed` remains 0).

## Findings lifecycle / dedup

Searched open **and** closed issues for `PornXP` and the hosts in play. Existing records:
#632 (deep pass, all surfaces healthy), #235/#330/#331 (historical load/search/duration
fixes, closed), #476 (search zero-results, closed 2026-09-25 — re-checked live this run,
stays clear), #829 (API-contract audit, closed). **No standing issue describes dead PornXP
CDN objects or a video-source selector gap**, so no duplicate was filed; the dead-source
class is recorded here as site-side evidence, not a finding.

---

## Verification (delivery gate)

`./gradlew PornXP:test` — **BUILD SUCCESSFUL** (378 tests, 0 failures; shared + provider
suites, including the existing `ParseTest` card/duration/search fixtures).

Clean `./gradlew clean PornXP:make` — **BUILD SUCCESSFUL**, `PornXP/build/PornXP.cs3`
161 446 B.

`.pi/skills/verify-provider/scripts/verify.sh` (live, plain-curl, desktop UA):

```
── check 1: search pages (2)
GET https://pxp.news/tags/ATKGirlfriends → 200; 'div.item_cont' matches: 36
GET https://pxp.news/tags/ATKGirlfriends?page=2 → 200; 'div.item_cont' matches: 36
NOTE: repeated poster on search across different videos (episodes of one series share the series poster — not a card duplicate):
poster	/images/fluid_spinner.svg	search0,…  (lazy-loader spinner; real poster is in data-src)

── check 1a: homepage pages (2)
GET https://pxp.news/ → 200; 'div.item_cont' matches: 36
GET https://pxp.news/?page=2 → 200; 'div.item_cont' matches: 36
NOTE: repeated poster on home across different videos (same lazy-spinner reason)

── check 1b: quick search (0)
NOTE: no --quick-search-url — no distinct quick-search endpoint (2.js only rewrites the
form submit to /tags/; standing record from #632)

── check 2: video pages (6 URLs)
GET https://pxp.news/videos/55337788126 → 200; 'source[src]' matches: 1
GET stream (https://sv.pornxp.sh/…/360.mp4…) → 206 video/mp4
GET https://pxp.news/videos/20173527180 → 200; 'source[src]' matches: 3
GET stream (https://xxx.pornxp.sh/…/360.mp4…) → 206 video/mp4
GET stream (https://xxx.pornxp.sh/…/1080.mp4…) → 206 video/mp4
GET stream (https://xxx.pornxp.sh/…/720.mp4…) → 206 video/mp4
GET https://pxp.news/videos/69558834624 → 200; 'source[src]' matches: 1
GET stream (https://cc.pornxp.sh/…/360.mp4…) → 206 video/mp4
GET https://pxp.news/videos/811805181980 → 200; 'source[src]' matches: 1
GET stream (https://xxx.pornxp.sh/…/360.mp4…) → 206 video/mp4
GET https://pxp.news/videos/29334217396 → 200; 'source[src]' matches: 3
GET stream (https://sd.pornxp.sh/…/360.mp4…) → 206 video/mp4
GET stream (https://sd.pornxp.sh/…/1080.mp4…) → 206 video/mp4
GET stream (https://sd.pornxp.sh/…/720.mp4…) → 206 video/mp4
GET https://pxp.news/videos/67234666847 → 200; 'source[src]' matches: 3
GET stream (https://sz.pornxp.sh/…/360.mp4…) → 206 video/mp4
GET stream (https://sz.pornxp.sh/…/1080.mp4…) → 206 video/mp4
GET stream (https://sz.pornxp.sh/…/720.mp4…) → 206 video/mp4
NOTE: no poster on any sampled page — site does not expose it; excluded from distinctness
field 'tags': present on all 6 sampled pages
NOTE: no --video-actors-selector — site exposes no separate actor field (tags only)
NOTE: no --video-year-selector — year is the "Uploaded: yyyy.mm.dd" line inside #desc,
not a dedicated element
NOTE: no --video-duration-selector — site does not expose duration on video pages (#331)
── check 5: search ↔ load agreement
── check 6: LoadResponse completeness (code vs FINDINGS)
field 'recommendations': 2 assignment(s) in PornXP
field 'tags': 2 assignment(s) in PornXP
field 'plot': 1 assignment(s) in PornXP
field 'duration': 1 assignment(s) in PornXP
field 'year': 1 assignment(s) in PornXP
field 'posters': 2 assignment(s) in PornXP
RESULT: PASS
```

Invocation:

```bash
.pi/skills/verify-provider/scripts/verify.sh \
  --search-url 'https://pxp.news/tags/ATKGirlfriends' \
  --search-url 'https://pxp.news/tags/ATKGirlfriends?page=2' \
  --search-selector 'div.item_cont' --search-title-selector 'div.item_title' \
  --search-poster-selector 'img' \
  --home-url 'https://pxp.news/' --home-url 'https://pxp.news/?page=2' \
  --home-selector 'div.item_cont' \
  --video-url 'https://pxp.news/videos/55337788126' \
  --video-url 'https://pxp.news/videos/20173527180' \
  --video-url 'https://pxp.news/videos/69558834624' \
  --video-url 'https://pxp.news/videos/811805181980' \
  --video-url 'https://pxp.news/videos/29334217396' \
  --video-url 'https://pxp.news/videos/67234666847' \
  --stream-selector 'source[src]' --stream-quality-attr title \
  --related-selector 'div.item_cont' \
  --video-title-selector 'div.player_details h1' --video-plot-selector 'div#desc' \
  --video-tags-selector 'div.tags a' \
  --provider-src 'PornXP' \
  --load-response 'recommendations,tags,plot,duration,year,posters'
```

Check 5 exercised agreement on `/videos/55337788126`: search card `.item_title`
`Virtual Vacation Hawaii 8/16` equals load `.player_details h1` (poster comparison is skipped
because the site exposes no `og:image` for the harness to read).

`verify.sh` notes are site-shape limits recorded by #632, not provider defects: the
regex-DOM card parser reads the lazy spinner (real poster is `data-src`), and per-video
poster/plot extraction only reads a `content=` attribute (pxp.news has no og: metas; the
provider reads `#player[poster]` and `#desc` correctly). The sampled pages themselves are
all 200 with real sources.

---

## Registry write (ADR-0013 — nothing in the PR diff)

The provider entry + one `last_runs` entry (`false_positives: []`,
`escalation_tier: unavailable`) are posted as a comment on #1457 beginning
`<!-- findings-registry-entry -->` and followed by a fenced json object, for the
orchestrator's `registry_sync`. Expected shape: verdict **`ok`**, `tier: plain-curl`,
`last_deep: 2026-11-24`, `drift_confirmed: 0`, `chronic: false`.

## Gates

- `.pi/skills/audit-providers/scripts/check-findings.sh` → **PASS** (registry untouched)
- `./gradlew PornXP:test` → **BUILD SUCCESSFUL** (378 tests, 0 failures)
- `./gradlew clean PornXP:make` → **BUILD SUCCESSFUL**, `PornXP/build/PornXP.cs3` 161 446 B
- `.pi/skills/verify-provider/scripts/verify.sh` (live) → **RESULT: PASS**
- No confirmed condition → **no code change, no version bump (PornXP stays 24)**
