# FINDINGS-1463 — Javtiful (javtiful.com) video-source pass

Run: 2026-11-25. Issue **#1463** — *S25 — Javtiful video sources*, story 25 of 27 of the
video-sources audit (#1354). Scope is the **video-source leg (D2/D3/D4)**: one row per
listing type, ≥2 video pages per type sampled at both catalogue ends, and `loadLinks`
resolved end-to-end. Listing correctness is re-probed only as far as this run's own
enumeration needed it (D5) — and it needed all of it, see *Drift and fix*.

**Verdict: `ok-drift` — two confirmed conditions, both fixed in this run (v28 → v29).**
`drift_confirmed` 1 → **3**; `last_deep` 2026-10-17 → **2026-11-25**.
Instrument tier: **plain-curl** (no UA trickery needed on this site; the provider's own
`okhttp/4.9.2` UA answers 200 on both the page and the handshake). `escalation_tier:
unavailable` (#1434 — `curl_cffi` and `playwright` are absent from this container; Phase 0
transcript below). No `Blocked` recorded, so nothing is downgraded; all nine sampled streams
were resolved to bytes from the runner.

Both conditions are >= the third-drift threshold, so the fixes are **structural**, not
another selector swap: the stream leg now speaks the site's documented two-step handshake
(shared parser, no DOM selector at all), and the year anchors on the `<time datetime>`
element plus its own "Added on" label instead of a container class.

---

## Phase 0 — calibration (ran before touching the site)

| canary | expected | observed (2026-11-25) | reading |
|---|---|---|---|
| `film1k-challenge-pair` | plain 403 / TLS 200 | **plain 403** (5244 B challenge) / browser UA **200** (217 494 B) | plain expectation matches today; TLS tier not runnable (#1434) |
| `eporner-healthy` | plain 200 | plain **200** (316 722 B) | match |
| `pandamovies-redirect-origin` | plain 302 / TLS 200 @ .org | plain **302**, `location: https://pandamovies.org/` | match (no `-L`, the 302 is the evidence) |

```
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' https://www.film1k.com/
  403 5244
$ curl -s -o /dev/null -m 25 -A "$FIREFOX_UA" -w '%{http_code} %{size_download}\n' https://www.film1k.com/
  200 217494
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' https://www.eporner.com/
  200 316722
$ curl -sI https://pandamovies.pw/ | head -4
  HTTP/2 302
  location: https://pandamovies.org/
```

**#1435, live pair:** the `film1k-challenge-pair` expectation is `expectation-stale`, so the
`environment-degraded` branch was **not** taken (per the issue): this run records the live
pair instead — plain-curl 403 (expectation holds) and a browser UA 200, i.e. the datacenter
challenge is **UA-conditional** from this IP. #1435 stays open for the maintainer's
expectation decision; Javtiful itself never presented a challenge on either tier.

**Escalation tier: unavailable (#1434).** Neither the TLS rung nor the browser rung exists here:

```
$ python3 -c 'import curl_cffi'    → ModuleNotFoundError: No module named 'curl_cffi'
$ python3 -c 'import playwright'   → ModuleNotFoundError: No module named 'playwright'
$ bash .pi/skills/site-probe/scripts/impersonate.sh https://javtiful.com/
  impersonate.sh: curl_cffi missing for python3. Install it, then re-run: …
```

Phase 0 was otherwise healthy (2/2 applicable canaries matched, film1k adjudicated as the
live pair above), so `escalation_tier: unavailable` goes in the registry `last_runs` entry
and **no finding is filed from challenge evidence**. It did not bind: every sampled page and
stream was reachable on the plain tier.

---

## D3 — listing types

| type | exists? | evidence |
|---|---|---|
| new / latest | **yes** | `/videos` — 23 non-partner cards/page + 1 partner ad, 4498 pages |
| old | **absent as a sort** | `?sort=oldest`, `?sort=old`, `?order=asc`, `?sort=added_old`, `?sort=ascending` all return the **newest** listing (23/23 href overlap with `/videos`); the ascending end of the catalogue is reached only by deep pagination (tail `/videos?page=4498`) |
| popular | **yes** | `/videos?sort=most_viewed`, `?sort=most_liked` (nav also offers `popular_today/week/month`, `added_today/week/month`) |
| genres | **absent** | `GET /genres` → 404, `GET /genre/milf` → 404 — the genre-like vocabulary *is* the `category` taxonomy below (recorded, never a finding) |
| categories | **yes** | `/category/<slug>` — 21 slugs on `/categories`; the provider's 21 category rows are exactly that set |
| tags | **absent as a route** | `GET /tags` → 404, `GET /tag/milf` → 404, `GET /tag/solowork` → 404. Watch pages expose tag chips, but they link to `/search?q=<tag>` — a search query, not a tag listing (recorded, never a finding) |

```
$ for u in videos?sort=oldest videos?sort=top_rated genres genre/milf tags tag/milf categories category/milf; do …
  videos?sort=oldest   → 200, 23 cards, <title>Latest JAV Videos …
  videos?sort=top_rated→ 200, 23 cards, <title>Most Liked JAV Videos …   (live alias, 23/23 overlap with most_liked)
  genres               → 404  <title>Page not found | Javtiful
  genre/milf           → 404
  tags                 → 404
  tag/milf             → 404
  categories           → 200  (21 /category/ slugs, 0 /tag/ slugs)
  category/milf        → 200  23 cards
```

`?sort=top_rated` survives as a **live alias** of `most_liked` (the provider's "Top Rated"
row is functional through it; pagination anchors canonicalise to `most_liked`) — recorded as
a resilience watch in the 2026-10 pass and still true today, not drift.

### Provider rows (all 25 checked, drift_confirmed ≥ 1 → D5 re-probe)

Every `mainPage` row answers **200 with 23 non-partner cards** on page 1:

```
/videos  /videos?sort=most_viewed  /videos?sort=top_rated  /uncensored
/category/{female-investigator,chinese-av,female-boss,mature-woman,cosplay,amateur,
housekeeper,nurse,female-student,school-girls,office-lady,sister-in-law,hypnosis,
beautiful-girl,bbw,drama,married-woman,milf,female-teacher,affair,big-tits}   → 200 ×23
```

### Pagination / tails (query-param, `?page=N`; p1 ∩ p2 = 0 on every sampled row)

| surface | p1 | p2 | tail page | past the tail |
|---|---|---|---|---|
| `/videos` (new) | 23 | 23, disjoint | `?page=4498` **22 cards, Next disabled** | `?page=4499` → 200, **0 cards**, Next disabled |
| `/videos?sort=most_viewed` | 23 | 23, disjoint | `?page=4498` 22, Next disabled | `?page=4499` 0 cards |
| `/uncensored` | 23 | 23, disjoint | `?page=436` **19**, Next disabled | `?page=437` 0 cards |
| `/category/milf` | 23 | 23, disjoint | `?page=50` **10**, Next disabled | `?page=51` → **clamps to page 50** (identical 10 hrefs), Next disabled |
| `/category/big-tits` | 23 | 23, disjoint | `?page=2056` **22**, Next disabled | `?page=2057` clamps to page 2056 |
| `/search?q=kana` | 23 | 23 (`?page=2&q=kana`), disjoint | — | — |

`hasNextPage()` (`a.pagination__link` with text `Next` and no `is-disabled`) is correct at
both ends: enabled on every p1/p2 sampled, disabled on every tail — so no row can walk into
the empty/clamped page.

Search: `/search?q=kana` p1/p2 → 23 + 23 cards, **0 href overlap** (verify.sh check 1 PASS).
No distinct quick-search endpoint: `/search/suggest?q=`, `/suggest?q=`, `/quick-search?q=`,
`/api/search?q=` → all **404**, and the search input carries no `autocomplete` attribute →
`hasQuickSearch` stays `false` (recorded, not drift).

Card grammar (unchanged from the 2026-10 redesign): `article.video-card`
(`:not(.video-card--partner)` drops the one script-only ad card per listing),
`a.video-card__title`, lazy poster in `img[data-front-lazy-src]`.

---

## D4 — video pages (≥2 per type, both catalogue ends)

| sampled from | video page | year | tags | actors | stream |
|---|---|---|---|---|---|
| new p1 | `/video/116259/meyd-144` | 2026 | 5 chips | 1 | 720p mp4, 206 |
| new **tail p4498** | `/video/761/mond-141` | 2020 | 5 | 1 | 720p mp4, 206 |
| popular `most_viewed` p1 | `/video/61787/mdsr-0006-2` | 2024 | 5 | 1 | 720p mp4, 206 |
| popular `most_liked` p1 | `/video/115362/dldss-578-reducing-mosaic` | 2026 | 7 | 1 | 720p mp4, 206 |
| popular **tail p4498** | `/video/115291/dvdes-669` | 2026 | 6 | 2 | 720p mp4, 206 |
| category `milf` p1 | `/video/115705/jux-858-reducing-mosaic` | 2026 | 9 | 1 | 720p mp4, 206 |
| category `big-tits` **tail p2056** | `/video/765/rbd-687` | 2018 | 9 | 1 | 720p mp4, 206 |
| `uncensored` p1 | `/video/116251/fc2ppv-5013698` | 2026 | 7 | 0 | 720p mp4, 206 |
| `uncensored` **tail p436** | `/video/1083/heyzo-1816` | 2018 | 7 | 1 | 720p mp4, 206 |
| search p1 | `/video/115676/madv-646-reducing-mosaic` | 2026 | 11 | 1 | 720p mp4, 206 |

Field exposure on every sampled page (all-or-none, verify.sh check 2a PASS):
`div.watch-title h1`, `meta[property=og:image]`, `meta[property=og:description]`,
`a.watch-link-chip` (categories + tags), `time[datetime]` (Added on). **Actors are optional
by site reality** — FC2 releases (e.g. FC2PPV-5013698) carry no `a.watch-actor-card`, so no
`--video-actors-selector` is asserted; empty-list handling in code is correct.
**Duration is JSON-LD only** (`VideoObject.duration`, `PT1H58M47S` → shared
`JsonLdParse.minutes`); `#watch-config` carries no duration, so verify.sh cannot extract it —
exposure asserted here and covered by `--load-response duration`.

Related videos: `div.video-grid.related-grid article.video-card` — **two grids per page**
("More from Actresses" 8 + "Related" 12 → 20 cards; FC2 releases 12, no actresses panel),
plus the script-only partner ad card which the provider drops (no `a.video-card__title`).

---

## Streams — the 2026-11 two-step handshake (end-to-end, the D4 focus)

`script#watch-config` **no longer carries `playerSources`**. It now carries a one-shot
handshake, and the sources come back from a form POST:

```
$ grep -c playerSources  live-watch.html → 0      old 2026-10 save → 1
$ grep -o '"playback":{[^}]*}' live-watch.html
  "playback":{"endpoint":"/video/116259/playback","ticket":"p1.116259.0.1795650218.01b4…"}

$ curl -sX POST https://javtiful.com/video/116259/playback \
    -H 'Content-Type: application/x-www-form-urlencoded' --data-urlencode 'ticket=p1.116259.…'
  {"success":true,"sources":[{"src":"/media/video/116259/720p?expires=1795653837&signature=1827fbac…",
   "type":"video/mp4","fallbackSrc":null,"size":720,"label":"720p"}],"defaultQuality":720}

$ curl -sL -r 0-1023 -o /dev/null -w '%{http_code} %{content_type}\n' \
    'https://javtiful.com/media/video/116259/720p?expires=…&signature=…'
  302 → https://8d5fff6dafa213ef6b9210db39c5f56e.r2.cloudflarestorage.com/apac2/2026/11/VID-41893DEE/…_720p.mp4?X-Amz-…
  206 video/mp4        (Content-Range: bytes 0-1023/1304161548, Accept-Ranges: bytes)
```

- **Quality is passed through**: the response's `size` (720) + `label` (`720p`) +
  `defaultQuality` all agree; the provider hands `BuildConfig`-style `quality = 720`.
- **No embed host**: the stream is same-origin `/media/video/<id>/720p` (302 → R2 presigned
  mp4), so no `shared/` `HostRegistry` adapter is involved for the watch-page leg. The
  `javtiful.com/embed/<id>` pages carry the **same** handshake and are already answered by
  `shared/`'s `JavtifulEmbed` (#1444) — reused here, not duplicated.
- **Headers**: nothing required. A bare `okhttp/4.9.2` UA answers 200 on the watch page and
  200 on the POST; the POST also answers 200 with only the `ticket` form field (no cookie,
  no referer, no `X-Requested-With`). The provider sends `Origin` + `X-Requested-With` anyway
  (mirrors the browser call and the shared adapter).
- **Shape uniformity**: **16/16** videos sampled across every era and surface (ids 743…116259,
  newest/most_viewed/uncensored/category/search/tails) returned **exactly one** source —
  `video/mp4`, `size` 720, `fallbackSrc: null`. No HLS, no multi-quality ladder. The
  extensionless path means the MIME must keep deciding the type (issue #240).
- **Ticket lifetime**: the JS renews on HTTP 409 (`{"renewTicket": …}`); the provider fetches
  the watch page and trades the ticket within the same call, so the window is request
  latency. Residual risk recorded, not fixed (a 409 just yields no links for that load).

Nine of those videos were resolved **end-to-end to bytes** in this run (see the D4 table);
the rest were shape samples.

---

## Drift and fix (TDD red → green, v28 → v29)

### Condition 1 — the stream config shape moved (all streams dead)

The shipped parser read `playerSources[]` from `#watch-config`. The live page carries no such
key (`grep -c playerSources` → 0), so `loadLinks` returned `false` on **every** video: the
provider enumerated fine and played nothing. Fixed by speaking the handshake through the
already-tested shared parser (`JavtifulEmbedParse.playback` / `.sources`, #1444) and
absolutising the page-relative `/media/video/…` path:

```
$ grep -c playerSources  Javtiful/src/test/resources/javtiful-watch.html  (2026-10-09 save) → 1
$ grep -c playerSources  Javtiful/src/test/resources/javtiful-watch-20261125.html          → 0
```

- new fixtures: `javtiful-watch-20261125.html` (137 595 B live save) +
  `javtiful-playback-116259.json` (the captured POST response)
- `JavtifulPlaybackTest` (red → green): handshake sources are absolute with `size` 720 and
  type `video/mp4`; the pre-drift save yields nothing even with a valid playback body; junk /
  `{"success":false}` / blank bodies yield nothing; blank/null html yields nothing; the
  handshake mp4 is not mistaken for HLS with `video/mp4` but still is with a `mpegurl` MIME.
- `JavtifulParseTest`: the stale `watch-config script carries playerSources` test replaced by
  `live watch page carries the playback handshake not inline sources` (asserts the live save
  has the handshake + the old save has the inline list — the drift, pinned both ways).

### Condition 2 — the "Added on" meta row left `div.watch-detail` (year lost)

`addedOnYear()` anchored on `div.watch-detail:contains(Added on) time[datetime]`. The meta
rows moved one level out, to `div.watch-detail-fact` / `.watch-detail-date`, so the selector
matched nothing and `LoadResponse.year` went null on every video:

```
old save (2026-10):   <div class="watch-detail"><strong>Added on</strong>
                        <span class="watch-detail-value"><time datetime="2025-04-18T08:38:24+07:00">…
live (2026-11-25):    <div class="watch-detail-fact watch-detail-date">
                        <strong>Added on</strong>
                        <span class="watch-detail-value"><time datetime="2026-11-26T12:48:30+07:00">…
```

The rows kept the label text and the `<time datetime>` element, so the anchor is now
**structural**: the "Added on" label's own row (`:containsOwn`), then the timestamp inside it,
with the page's first `time[datetime]` as fallback (a watch page carries exactly one). Two new
tests, both red first: the live save must yield 2026, and a synthetic page whose meta rows use
*renamed* classes (two timestamps, the labelled one second) must yield the labelled one — the
old container-class selector fails that test by construction.

Drift count: `drift_confirmed` 1 → **3**, i.e. over the ≥3 hardening threshold — hence both
fixes above are structural (protocol-level handshake; label/timestamp anchor) rather than
another class swap.

---

## verify.sh record (4 invocations)

Main run: search p1/p2 + 7 video pages + 7 position-matched `--stream-url` overrides +
related + tags + year + `LoadResponse(recommendations,tags,plot,duration,year,actors)` on
home row `/videos` p1/p2. Row runs: `?sort=most_viewed` p1/p2, `/category/milf` p1/p2,
`/uncensored` p1/p2 — **RESULT: PASS** on all three row runs.

- Search/home/row checks: 200, 23 cards each page, 0 href overlap p1↔p2 everywhere.
- Streams: 7/7 `206 video/mp4` from the overridden handshake URLs (all distinct paths).
- Fields: tags present 7/7, year present 7/7; poster/plot all-or-none PASS.
- **1 instrument-limited FAIL line** in the main run: `duplicate recommendations` on 2 of 7
  pages (`/video/84692/gdhh-200` at rows 1_r,18_r; `/video/45341/pmtc-059` at 3_r,17_r) — the
  same video sits in **both** related grids ("More from Actresses" + "Related"), and the
  later row runs came back clean (the panels rotate). Site content, not a provider defect:
  the provider dedupes by title (`distinctBy { it.name }`, test-covered). The instrument
  asserts href distinctness across the combined grids and cannot express the shape.
- NOTEs (all deliberate): no quick-search endpoint (absent, above); actors optional (above);
  duration JSON-LD-only (above).

## Gates

- `gradlew Javtiful:test` **PASS** (210 tests, incl. the shared suite) — red → green on both
  conditions as documented above.
- clean `gradlew clean Javtiful:test Javtiful:make` → `Javtiful/build/Javtiful.cs3` **PASS**.
- `.pi/skills/audit-providers/scripts/check-findings.sh --block` on the registry payload
  **PASS**.
- `.pi/skills/verify-provider/scripts/verify.sh` against the live site — see above.
- **Version bumped 28 → 29**; no `shared/` source change (the handshake parser already
  exists there from #1444 and is reused verbatim — nothing for other providers to re-bump).
