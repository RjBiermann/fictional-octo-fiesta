# FINDINGS-1464 — FreePornVideos (freepornvideos.xxx) video-source pass

Run: 2026-11-26. Issue **#1464** — *S26 — FreePornVideos video sources*, story 26 of 27 of the
video-sources audit (#1354). Scope is the **video-source leg (D2/D3/D4)**: one row per listing
type, ≥2 video pages per type sampled at both catalogue ends where the type is pageable, and
`loadLinks` resolved end-to-end (stream reachable, embed/delivery host identified, quality
carried). Listing correctness was touched only as far as enumerating videos needed it (D5).

**Verdict: `ok-drift` — one confirmed delivery-chain condition, fixed in this run (v28 → v29).**
`drift_confirmed` stays **0** (no issue is filed — the condition is fixed here, and
`drift_confirmed` counts only *closed confirmed* issues); `last_deep` **2026-10-17 → 2026-11-26**.
Instrument tier: **plain-curl with a browser UA**. `escalation_tier: unavailable` (#1434: no
`curl_cffi`, no `playwright` in this container). No `Blocked` was recorded, so nothing needed
downgrading: all 25 sampled stream URLs resolved to `206 video/mp4` from the runner.

---

## Phase 0 — calibration (ran before touching the site)

| canary | expected | observed (2026-11-26 04:44Z) | reading |
|---|---|---|---|
| `film1k-challenge-pair` | plain 403 / TLS 200 | **plain 403** (5244 B challenge) / browser UA **200** (217 494 B) | plain expectation matches today; TLS tier not runnable (#1434) |
| `eporner-healthy` | plain 200 | plain **200** (7 394 B) | match |
| `pandamovies-redirect-origin` | plain 302 / TLS 200 @ .org | plain **302**, `location: https://pandamovies.org/` | match (no `-L`) |

```
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' https://www.film1k.com/
  403 5244
$ curl -s -o /dev/null -m 25 -A "$CHROME_UA" -w '%{http_code} %{size_download}\n' https://www.film1k.com/
  200 217494
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' https://www.eporner.com/
  200 7394
$ curl -s -o /dev/null -w '%{http_code} -> %{redirect_url}\n' https://pandamovies.pw/
  302 -> https://pandamovies.org/
```

**#1435, live pair:** `film1k-challenge-pair` is marked `expectation-stale`, so the
`environment-degraded` branch was **not** taken. The live pair is plain-curl **403** (the
recorded expectation holds today) and browser-UA **200** — the datacenter challenge is
UA-conditional from this IP. #1435 stays open for the maintainer's expectation decision;
FreePornVideos itself never presented a challenge to a browser UA.

**Escalation tier: unavailable (#1434).** Neither the TLS rung nor the browser rung exists here:

```
$ python3 -c 'import curl_cffi'    → ModuleNotFoundError: No module named 'curl_cffi'
$ python3 -c 'import playwright'   → ModuleNotFoundError: No module named 'playwright'
$ bash .pi/skills/site-probe/scripts/impersonate.sh https://www.freepornvideos.xxx/
  impersonate.sh: curl_cffi missing for python3. Install it, then re-run: …
```

Phase 0 was otherwise healthy (all three applicable canaries matched), so
`escalation_tier: unavailable` goes into the registry `last_runs` entry and **no finding is
filed from challenge evidence**. It did not bind: every sampled page and stream was reachable
on the plain tier once the browser UA was used.

---

## Instrument note — UA-conditional Cloudflare, unchanged

Bare curl (UA `curl/8.x`) is challenged by Cloudflare on the HTML routes; a Chrome UA passes:

```
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' https://www.freepornvideos.xxx/
  403 5252                      # body starts "<title>Just a moment...</title>"
$ curl -s -o /dev/null -A "$CHROME_UA" -w '%{http_code} %{size_download}\n' https://www.freepornvideos.xxx/
  200 229445
```

CloudStream's client is the same class as the passing one — `library/.../MainAPI.kt` pins
`USER_AGENT` to a Chrome 149 UA for both `app.*` requests and the player's datasource
(`OkHttpDataSource`/`CronetDataSource` in `CS3IPlayer`). Recorded as instrument context, not
a finding (#449/#466 remain `suspected_excluded`).

---

## D3 — listing types

| type | exists? | evidence |
|---|---|---|
| new / latest | **yes** | `/latest-updates/<page>/` — 24 video cards on p1, 6181 pages (`/latest-updates/6182/` → 404) |
| old | **absent as a sort** | `/old/` → **404**, `/old-videos/` → 404. The ascending end is only reachable by pagination, so it was sampled at the tail: `/latest-updates/6181/` (11 cards, ids `9366xxxx` vs `9382xxxx` at the head) |
| popular | **yes** | `/most-popular/`, `/most-popular/week/`, `/most-popular/month/`, `/top-rated/` all 200 |
| genres | **absent** | `/genres/` → **404**, `/genre/` → 404 — the genre-like vocabulary *is* the `categories` taxonomy (recorded, never a finding) |
| categories | **yes** | `/categories/` (index, 200, 347 KB) and `/categories/<slug>/` — e.g. `vintage`, 24 cards p1 |
| tags | **yes** | `/tags/` (index, 200) and `/tags/<slug>/<page>/` — `blonde` 24 cards p1; a bare `/tags/<slug>/` also 200 |

```
$ for u in old old-videos genres genre most-popular top-rated tags categories; do
    printf '%s ' "$u"; curl -s -o /dev/null -m 25 -A "$CHROME_UA" -w '%{http_code}\n' "https://www.freepornvideos.xxx/$u/"
  done
old 404 · old-videos 404 · genres 404 · genre 404 · most-popular 200 · top-rated 200 · tags 200 · categories 200

$ curl -s -o /dev/null -m 25 -A "$CHROME_UA" -w '%{http_code}\n' https://www.freepornvideos.xxx/latest-updates/6181/
  200        # last page: 11 video cards, <title>New Videos … #6181
$ curl -s -o /dev/null -m 25 -A "$CHROME_UA" -w '%{http_code}\n' https://www.freepornvideos.xxx/latest-updates/6182/
  404
```

### Pagination (path-based `/<page>/`, p1 ∩ p2 by video href)

| surface | p1 | p2 | overlap | past the tail |
|---|---|---|---|---|
| `/latest-updates/` | 24 | 24 | **0** | `/latest-updates/6182/` → 404 |
| `/most-popular/week/` | 24 | 12 | 4 | — (dynamic weekly ranking churn) |
| `/top-rated/` | 24 | 12 | 0 | — |
| `/categories/vintage/` | 24 | 8 | **0** | — |
| `/tags/blonde/` | 24 | 12 | **0** | — |

Page 2+ renders **12** cards (not 24) on every surface sampled; p1 carries 24. Site-side
listing population, uniform across surfaces, not drift. The `most-popular/week` 4-item overlap
is ranking churn between two probes taken minutes apart on a *dynamic* weekly list — page
numbering still advances and the other four surfaces are disjoint.

Card grammar unchanged: `div.item`; one ad card per listing carries `class="item
ourdreamai-native"` and an `iframe` in `div.img`, which the provider's `div.img iframe` skip
(#286) drops. `searchCard(this, "a.thumb_title", posterSel = "img.thumb")` still reads the
title/poster from the remaining cards.

Search: `/search/<slug>/<page>/`, `casting` p1/p2 → cards match
`#custom_list_videos_videos_list_search_result_items > div.item` (24 p1 / 24 p2, 0 overlap).
Quick search: **no distinct endpoint** found in the homepage JS (`main.deps.*.js`) or markup;
provider keeps `hasQuickSearch = false` — recorded, not drift.

---

## D4 — video pages (≥2 per type, both catalogue ends where pageable)

| sampled from | video page | tiers | tags | actors | stream verdict |
|---|---|---|---|---|---|
| new `/latest-updates/1/` | `/videos/93823330/boot-camp-rules-drop-and-give-us-your-cock-layla-quinns-debut/` | 2160p/720p/480p | ✓ | ✓ | pass |
| new `/latest-updates/1/` | `/videos/93823331/cherry-candle-and-millie-morgan-in-a-steamy-69/` | 2160p/720p/480p | ✓ | ✓ | pass |
| old tail `/latest-updates/6181/` | `/videos/93663442/good-sex-better-than-everything/` | 720p/480p | ✓ | ✓ | pass |
| old tail `/latest-updates/6181/` | `/videos/93664256/dancing-in-the-morning/` | 720p/480p | ✓ | ✓ | pass |
| popular `/most-popular/week/` | `/videos/93758936/stop-looking-at-stepmoms-ass/` | 720p/480p | ✓ | ✓ | pass |
| popular `/most-popular/week/` | `/videos/93769400/best-of-zz-danny-d/` | 2160p/720p/480p | ✓ | ✓ | pass |
| categories `/categories/vintage/` | `/videos/93364766/martinique-is-a-bbw-in-some-hardcore-action-with-martineque/` | 720p/480p | ✓ | ✓ | pass |
| categories `/categories/vintage/` | `/videos/93369782/faith-leon-fucks-a-handsome-stranger-on-the-diner-counter/` | 720p/480p | ✓ | ✓ | pass |
| tags `/tags/blonde/` | `/videos/93822775/rate-how-i-fuck-taking-first-place-in-my-stepbros-sex-rankings/` | 2160p/720p/480p | ✓ | ✓ | pass |
| tags `/tags/blonde/` | `/videos/93822793/a-handmaids-purpose-lexis-womb-is-warm-and-ready/` | 2160p/720p/480p | ✓ | ✓ | pass |

### Evidence rows (required shape)

`type | sample URL | instrument tier | stream host | quality | verdict`

| type | sample URL | instrument tier | stream host | quality | verdict |
|---|---|---|---|---|---|
| new / latest | `https://www.freepornvideos.xxx/videos/93823330/…` | plain-curl (Chrome UA) | `www.freepornvideos.xxx/get_file/*` → 302 → `d494211.fpvcdn.com` (signed) | 2160p / 720p / 480p (`label`) | **pass** (206 `video/mp4`) |
| old (catalogue tail) | `https://www.freepornvideos.xxx/videos/93663442/…` | plain-curl (Chrome UA) | same chain | 720p / 480p | **pass** (206 `video/mp4`) |
| popular | `https://www.freepornvideos.xxx/videos/93758936/…` | plain-curl (Chrome UA) | same chain | 720p / 480p | **pass** (206 `video/mp4`) |
| genres | — | — | — | — | **absent** (`/genres/` 404 — site exposes categories only) |
| categories | `https://www.freepornvideos.xxx/videos/93364766/…` | plain-curl (Chrome UA) | same chain | 720p / 480p | **pass** (206 `video/mp4`) |
| tags | `https://www.freepornvideos.xxx/videos/93822775/…` | plain-curl (Chrome UA) | same chain | 2160p / 720p / 480p | **pass** (206 `video/mp4`) |

Exposure inventory (unchanged from the 2026-10-17 deep pass, still live on all 10 pages):

- title: `div.headline > h1` (`Channel - Models - Title / DD.MM.YYYY`); `og:title` carries the
  display title only, which is also the search-card `a.thumb_title` text (agreement PASS).
- poster: `meta[property=og:image]` (= `img.freepornvideos.xxx/<n>/<id>/medium@2x/1.jpg`).
- plot: `meta[property=og:description]` — populated on all 5 verify samples; **population gap**
  recorded in the 2026-10 deep pass stands (some videos carry the literal
  `Default site description.`, e.g. the `netvideogirls` casting sample 93539328).
- tags: details-block `Categories:` cell → `a[href*=categories]` (present 10/10).
- actors: details-block `Pornstars:` cell → `a[href*=models]` — **site-side population gap**:
  the search-sampled casting video `/videos/93539328/` carries **no Pornstars cell** (0 hits)
  while the other 9 pages carry 1–3 actors. Provider degrades to an empty list (honest), so
  `--video-actors-selector` is not asserted by verify.sh and this note is the required record.
- duration: `meta[property=video:duration]` (seconds) with JSON-LD `VideoObject.duration`
  fallback (both agree where both exist) — verify duration selector PASS 5/5.
- year: **JSON-LD only** (`VideoObject.uploadDate`), no flat selector — noted, covered by
  `--load-response year`.
- related videos: `#list_videos_related_videos_items > div.item` — 15–16 cards per page
  (verify related check PASS).

No `<iframe>` player anywhere: the only iframes on a video page are two ad slots
(`a.labadena.com/api/spots/...`, `class="na"`), so every source is a direct mp4.

---

## Streams — the 2026-11 signed-CDN redirect (the D4 focus)

The video-js player still carries one `<source>` per tier, but `get_file` no longer serves
bytes directly — it 302s to a **signed, IP-bound, expiring CDN URL**:

```
$ curl -s -D - -o /dev/null -A "$CHROME_UA" -r 0-1023 \
    'https://www.freepornvideos.xxx/get_file/8512/d62716720a6c72bcecb80f09bacf0bc56e00d4b2be/93823000/93823330/93823330_2160m.mp4/'
HTTP/2 302
content-type: text/html; charset=UTF-8
location: https://d494211.fpvcdn.com/key=XrWXEn8HBoTz4m8TyoIHMA,end=1795672114,ip=172.175.67.193,country=US,speed=1.5,buffer=1.5,reftag=/disk10/2041000/2041278/2041278_2160.mp4
server: cloudflare
```

The redirect target serves the media to *any* UA / no referer (the origin does not — a bare
curl UA on `get_file` gets the CF challenge):

```
$ curl -sL -A "$CHROME_UA" -r 0-4095 -o dl.bin -D - '<the fpvcdn.com location above>'
HTTP/2 206
content-type: video/mp4
content-length: 4096
content-range: bytes 0-4095/535443556
$ file dl.bin
dl.bin: ISO Media, MP4 Base Media v1 [ISO 14496-12:2003]

$ curl -s -r 0-1023 -o /dev/null -w '%{http_code} %{content_type}\n' '<the location>'     # curl UA
  206 video/mp4
$ curl -s -r 0-1023 -o /dev/null -w '%{http_code}\n' '<the location>'                      # no UA
  206
```

- **Quality is carried**: each `<source label="2160p|720p|480p">` maps 1:1 to its own signed
  URL; `getQualityFromName(label)` gets 2160/720/480. Only the `selected="true"` tier is
  guaranteed to exist per video (2160p appears on newer uploads only).
- **No embed host**: `fpvcdn.com` is a direct mp4 CDN, not an embed player — no
  `shared/` `HostRegistry` adapter applies, and none is needed.
- **Chain shape**: 25/25 sampled sources (10 videos, 5 listing types) returned
  `206 video/mp4`. Paths are per-video and per-tier (`…/<id>_<q>m.mp4/`), no repeats.
- **Token lifetime ~20–60 min** (`end=` = now + ~1 h at 04:48, now + 20 min at 05:01), and
  `ip=` is bound to the requesting address at mint time.

### Origin-side anti-leech cap (observed, then cleared)

After ~30 `get_file` resolutions from the runner IP inside ~8 minutes, the origin refused
**every** video's `get_file` (fresh tokens included) with a KVS-level error, referer-independent:

```
HTTP/2 410
content-type: text/html; charset=UTF-8
content-length: 23
kvs-errno: 4

body: Access denied (errno 4)
```

Already-signed `fpvcdn.com` URLs kept serving `206 video/mp4` while this lasted. It cleared
~8 minutes later (`302` again at 05:01Z), i.e. a transient per-IP burst limit, not a per-video
or provider condition. Recorded as a **risk**: it is why the provider must not spend extra
`get_file` requests per tier (see fix), and why the audit's own resolution volume matters.

---

## Drift and fix (TDD red → green, v28 → v29)

### Condition — `get_file` now redirects (delivery chain changed); the provider resolved it per tier

Shipped `loadLinks()` followed the redirect with `allowRedirects = true` and emitted
`response.url`, i.e. **one extra `get_file` request per tier** at load time (3 per video) just
to learn the `Location`, holding the media response body open each time (the leak the shared
`TurboEmbed` documents and avoids). Consequences of the new signed-CDN chain:

1. it triples the origin requests that the per-IP cap above counts, and
2. it hands the player a token minted at *load* time (`end=` ≈ +20–60 min, `ip=` = the
   resolving address) instead of at play time — a paused/resumed or later-started download
   can outlive the `end` stamp.

CloudStream's player follows redirects itself and sends the same Chrome UA as the provider
(`USER_AGENT` in `library/.../MainAPI.kt`; `OkHttpDataSource`/`CronetDataSource` with
`setResetTimeoutOnRedirects(true)` in `CS3IPlayer`), so the correct emission is the
`get_file` URL **unchanged** — one request per played tier, token minted per play.

Fix: the source tiers move behind a pure Parse seam and `loadLinks` emits them verbatim.

- new seam `FreePornVideosParse.sources(doc): List<StreamSource>` (`label`, `url` from
  `video source`), shared-fixture tested against `freepornvideos_video_93820265.html`
  (2160p/720p/480p); a src-less mirror is dropped, an absent label stays `null` (never a
  fabricated quality), and a removed-video page with no `<source>` yields an empty list.
- `loadLinks()` no longer issues any request per tier; it passes `source.url` with
  `referer = data` and `quality = getQualityFromName(source.label)`.
- red → green: `sources drop src-less mirrors…` and `stream sources parse from video-js source
  markup…` failed against the stub (`emptyList()`) and pass with the implementation;
  `…no source elements…` pins the `player_404` page shape.

```
$ ./gradlew FreePornVideos:test        → BUILD SUCCESSFUL (198 tests, 0 failed)
$ ./gradlew FreePornVideos:make        → BUILD SUCCESSFUL → FreePornVideos/build/FreePornVideos.cs3
```

---

## Delivery gate

- `.pi/skills/audit-providers/scripts/check-findings.sh --block <registry comment>` — PASS
  (payload linted before posting; see the registry comment on #1464).
- `./gradlew FreePornVideos:test` — BUILD SUCCESSFUL.
- `./gradlew FreePornVideos:make` — BUILD SUCCESSFUL (clean artifact).
- `.pi/skills/verify-provider/scripts/verify.sh` against the live site — **RESULT: PASS**:
  search p1/p2 (24+24, disjoint), home `latest-updates` p1/p2 (24+24, disjoint), 5 video pages
  (2–3 sources each), **11/11 streams `206 video/mp4`** via the shipped `get_file` URLs
  (the player's redirect-following path), tags + duration present on 5/5, related-videos
  selector resolves (15–16 cards), search↔load agreement on `og:title` = card title,
  `LoadResponse` completeness PASS. Actors and year are NOTEd in FINDINGS (site-side population
  gap / JSON-LD-only) as the skill requires.

Instrument tier for the gate: plain-curl with the Chrome UA (the plain-HTTP class the app
itself uses). No `Blocked` anywhere, so no downgrade applied.

---

## Findings lifecycle

- Standing issue **#536** (details-block grammar) — CLOSED, fix on master, re-confirmed live
  this run (Channel/Network/Categories/Pornstars cells + meta/JSON-LD duration). Not drift,
  not re-fixed.
- `suspected_excluded`: #449, #466 (Cloudflare datacenter challenge) — not reopened, not
  counted as drift.
- This run's condition is new, is **fixed in this run**, and files no issue (fix policy D1);
  dedup searched open **and** closed issues for the provider before deciding
  (#643, #816, #536, #449, #466, #101, #15, load-check/status reports — none covers the
  stream chain).

## Risks / blockers

- **Transient per-IP `get_file` cap** (`410 kvs-errno: 4`, ~8 min) after a burst of
  resolutions — recorded above; the fix removes the provider's extra per-tier requests.
  In-app usage (1–3 `get_file` requests per video, minted per play) stays well under it.
- Instrument tier capped at plain-curl (no TLS-impersonated rung, #1434) — irrelevant here:
  the site answers a browser UA on the plain tier.
- Probe region: runner (US datacenter). No geo-wall observed. In-app playback stays the
  maintainer's check at merge.
