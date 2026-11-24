# FINDINGS-1456 — PerverZija video sources (S18 of #1354)

Video-source leg (D2) of the PerverZija spec (`tube.perverzija.com`). Scope: for every D3 listing
type (`new`/`latest`, `old`, `popular`, `genres`, `categories`, `tags`) open ≥2 video pages,
sampled at both ends of the catalogue where the type is pageable, and resolve `loadLinks`
end-to-end — video page → player iframe → XSFTK signed-token chain or legacy `xs1.php` master →
CDN segment → quality passed to CloudStream where the site exposes it.

Provider state at run start: registry `last_deep` = 2026-10-16, `drift_confirmed` = **0**, verdict
at last deep `ok` (#651). Provider `version` = 33. `drift_confirmed` = 0 ⇒ a targeted fix would be
allowed if a condition were confirmed; none was.

**Result: no condition confirmed on the video-source leg — the leg is clean, no code change, no
version bump (stays 33).** 17/18 sampled rows resolved end-to-end through their live chain
(gated XSFTK: boot → XOR manifest → key → AES segment → MPEG-TS; legacy: `xs1.php` master →
variant → plain MPEG-TS). The one non-resolving row is the same `mixdrop.to` alternate embed
recorded in FINDINGS-906, still dead upstream; #702 closed it as not actionable. The #1353
loopback classification still holds: every gated source is emitted as
`http://127.0.0.1:<port>/<id>.m3u8` and is **Blocked** (app-local) for `verify.sh`, not a failure.
No finding filed.

Run: `05-implement` for #1456, 2026-11-24, plain-curl tier, runner IP US.
Workflow run: `https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/45317256665`.

Out of scope per spec: a full listing audit (pagination/card-field correctness was #632–#659) and
*rows* work (row additions). Listing correctness was checked only as far as enumeration needed,
and not re-probed beyond that (`drift_confirmed` = 0, enumeration intact).

---

## Phase 0 — canary calibration (before touching the site)

| Canary | Expected | Observed | Result |
| --- | --- | --- | --- |
| `eporner-healthy` | plain-curl 200 | plain-curl **200** (314 111 B — real homepage) | match — instrument healthy |
| `film1k-challenge-pair` | plain-curl 403, tls-impersonated 200 | plain-curl **200** (253 676 B) | **expectation-stale** (#1435 open) — live pair recorded here; **no** `environment-degraded` branch taken |
| `pandamovies-redirect-origin` | plain-curl 302, tls-impersonated 200 | plain-curl **302 → https://pandamovies.org/** | plain-tier match; the TLS leg cannot run (#1434) |

```
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://www.eporner.com/   → 200 314111
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://www.film1k.com/    → 200 253676
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://pandamovies.pw/      → 302 https://pandamovies.org/
$ curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -A "$UA" https://tube.perverzija.com/ → 200 679985
$ python3 -c 'import curl_cffi'
ModuleNotFoundError: No module named 'curl_cffi'      # #1434 still open at run time
```

**#1434 is still open**, so the registry `last_runs` entry carries `escalation_tier: unavailable`
and any `Blocked` this run would downgrade to `Suspected`. None occurred — every PerverZija surface
below answered plain-curl.

Instrument = **plain-curl tier**, desktop Chrome UA
(`Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36`)
for page/listing probes and the provider's runtime player UA for stream fetches
(`Mozilla/5.0 (Linux; Android 10; K) … Chrome/125 Mobile Safari/537.36`). No proxy, no
impersonation, no browser instrument anywhere below.

---

## Listing-type inventory (D3)

The site is a WordPress tube theme (Cactus/pv). Videos are `div.col-md-3` cards; listing routes are
`/page/<n>/` with query-param orderings. Taxonomies are **Studio** (`/studio/<slug>/`,
`rel="category tag"`), **Stars** (`/stars/<slug>/`, `rel="tag"`) and **Tags**
(`/tag/<slug>/`, `rel="tag"`). The WordPress REST index reports 111 389 `post` objects; the
paginated listing exposes **1 700 pages × 64 cards** and `/page/1701/` **301 → `/`**. A type the
site does not expose is recorded **`absent`**, never assumed.

| type | surface | evidence |
| --- | --- | --- |
| `new` / `latest` | **`/` default order (date desc)** — p1 **200, 64** cards, p2 **200, 64**, last `/page/1700/` **200, 64**; `/page/1701/` **301 → /** | p1 head `pornfidelity-harlow-harrison-walk-the-streets-4-e622` `datePublished` **2026-11-24**; p1700 tail `blackvalleygirls-vienna-black…` **2019-05-14** ⇒ newest-first, both catalogue ends sampled |
| `old` | **`/?order=asc` (date ascending)** — p1 **200, 64** (oldest), p2 **200, 64**, last `/page/1700/?order=asc` **200, 64** (newest end); `/page/1701/?order=asc` **301 → /?order=asc** | asc p1 head `21eroticanal-kitana-lure…` **2018-12-28** (oldest listed); asc p1700 tail `transdaylight-ceyba-sol…` **2026-11-03** (newest) ⇒ explicit ascending ordering, both ends sampled |
| `popular` | **`/?orderby=view`** (the nav's "Most Popular" sort; the provider row `/studio/?orderby=view` is the studio index sorted by views) — p1 **200, 64**, p2 **200, 64**, last `/page/1700/?orderby=view` **200, 64**; `/page/1701/?orderby=view` **301 → /?orderby=view** | p1 head `freeusefantasy-gabbie-carter…` **2021-02-25** (most viewed); p1700 tail `pornworld-lou-lou-petite…` **2026-10-19** (least viewed) ⇒ separate ranking, both ends sampled |
| `genres` | **absent.** `/genre/` → **404**, `/genres/` → **404**; `/genre/anal/` and `/genres/anal/` **301** to a *video slug* (`/anal-beauty-lady-dee-upset-girl-cheats-with-a-bartender/`) — WordPress's slug guess, not a taxonomy. No genre vocabulary in the nav or the video-page tax list; the subject vocabulary is `Tags` | 404 (route) / 301 (slug guess) |
| `categories` | **`/studio/<slug>/`** — the theme's category taxonomy: video pages mark Studio links `rel="category tag"`, `/categories/` **301 → `/studios/`**, `/category/brazzers/` **301 → `/studio/brazzers/`**, `/category/` **404**. Index `/studios/` **200**, **1 950** studio links. `/studio/brazzers/` p1 **200, 64**, p2 **200, 64**, last `/studio/brazzers/page/124/` **200, 26**, p125 **404** | 200 |
| `tags` | **`/tag/<slug>/`** — index `/tags/` **200, 389 131 B**, **949** tag links. `/tag/anal/` p1 **200, 64**, p2 **200, 64**, last `/tag/anal/page/362/` **200, 43**, p363 **404**. Provider rows `/tag/4k-quality/` and `/tag/classic/` (classic last page 5, 11 cards) are the same taxonomy | 200 |

```
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" \
  https://tube.perverzija.com/genre/        → 404
  https://tube.perverzija.com/genres/       → 404
  https://tube.perverzija.com/genre/anal/   → 301 https://tube.perverzija.com/anal-beauty-lady-dee-upset-girl-cheats-with-a-bartender/
  https://tube.perverzija.com/category/     → 404
  https://tube.perverzija.com/categories/   → 301 https://tube.perverzija.com/studios/
  https://tube.perverzija.com/category/brazzers/ → 301 https://tube.perverzija.com/studio/brazzers/
```

Listing-card discrepancy noted, **not a finding** (out of D2 scope, `drift_confirmed` = 0 and
enumeration intact): the default `/` p1 and p2 draws overlapped by **1** card between two fetches
(`hunt4k-natasha-rios…` at p1 tail and p2 head) — the site inserts new uploads between requests and
shifts the page boundary. The video-source enumeration was unaffected; every sampled card resolved
to a live page. Pagination/card-field correctness is the #632–#659 listing audit's scope.

---

## Evidence rows (D4)

`type | sample URL | instrument tier | stream host | quality | verdict` — 18 rows, ≥2 per present
type, both catalogue ends where the type is pageable. Row 1 is the #1353 regression row (gated
XSFTK → loopback → Blocked). All URLs are `https://tube.perverzija.com` + path.

| # | type | sample URL | listing position | tier | stream host | quality | verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | **old** (Regression #1353) | `/21eroticanal-kitana-lure-sunday-rekindling/` | asc p1 head | plain-curl | `pervl6.xspcdn01.click` → loopback `127.0.0.1` | 1080/720/480 | **Blocked** (site chain green; loopback app-local) |
| 2 | new/latest | `/pornfidelity-harlow-harrison-walk-the-streets-4-e622/` | p1 head | plain-curl | `pervl11.xspcdn01.click` (`xs1.php`) | 360 | **pass** |
| 3 | new/latest | `/hunt4k-natasha-rios-ferrucio-and-the-porra-paradox/` | p1 tail | plain-curl | `pervl11.xspcdn01.click` (`xs1.php`) | 480 | **pass** |
| 4 | new/latest | `/watchyourwife-casey-calvert-25367/` | p1700 head (oldest end) | plain-curl | `j2.xspcdn01.click` → loopback | 1080/720/480 | **Blocked** (site chain green) |
| 5 | new/latest | `/blackvalleygirls-vienna-black-the-horny-black-teen-matchmaker/` | p1700 tail (oldest end) | plain-curl | `perv.xspcdn01.click` → loopback | 1080 | **Blocked** (site chain green) |
| 6 | old | `/clubsweethearts-mia-mins-dream-encounter-latina-desire/` | asc p1700 head (newest end) | plain-curl | `pervl6.xspcdn01.click` → loopback | 1080/720/480 | **Blocked** (site chain green) |
| 7 | old | `/transdaylight-ceyba-sol-and-khaos-trans-ceyba-sol-goes-deep-in-a-wet-pussy/` | asc p1700 tail (newest end) | plain-curl | `pervl8.xspcdn01.click` → loopback | 1080/720/480 | **Blocked** (site chain green) |
| 8 | old | `/private-terri-summers-terri-wears-her-helmet-while-getting-double-penetrated-by-two-guys/` | asc p1 tail | plain-curl | `mixdrop.to` → `mxdrop.click` | — | **Suspected** (alternate embed, upstream dead — see below) |
| 9 | popular | `/freeusefantasy-gabbie-carter-endless-desire-possibilities/` | view p1 head | plain-curl | `j2.xspcdn01.click` → loopback | 1080/720/480 | **Blocked** (site chain green) |
| 10 | popular | `/momswap-jenna-noelle-and-athena-anderson-lessons-on-motivation/` | view p1 tail | plain-curl | `pervm1.xspcdn01.click` → loopback | 1080/720/480 | **Blocked** (site chain green) |
| 11 | popular | `/dorcelclub-ella-hughes-and-mariska-ella-hughes-the-hostess-selected-by-mariska/` | view p1700 head | plain-curl | `pervl6.xspcdn01.click` → loopback | 1080/720/480 | **Blocked** (site chain green) |
| 12 | popular | `/pornworld-lou-lou-petite-and-danielle-maye-pussy-work-out-babes-take-a-pussy-eating-gym-break/` | view p1700 tail | plain-curl | `pervl8.xspcdn01.click` → loopback | 1080/720/480 | **Blocked** (site chain green) |
| 13 | categories (`/studio/brazzers/`) | `/babygotboobs-sophia-leone-laid-rent/` | p1 head | plain-curl | `pervl7.xspcdn01.click` → loopback | 1080/720/480 | **Blocked** (site chain green) |
| 14 | categories (`/studio/brazzers/`) | `/mommygotboobs-tanya-tate-i-sex-my-sons-role-models/` | p1 tail | plain-curl | `pervl6.xspcdn01.click` → loopback | 1080/720/480 | **Blocked** (site chain green) |
| 15 | categories (`/studio/brazzers/`) | `/bigtitsatwork-tina-kay-always-be-fucking/` | p124 head (last page) | plain-curl | `j2.xspcdn01.click` → loopback | 1080/720/480 | **Blocked** (site chain green) |
| 16 | tags (`/tag/anal/`) | `/mandyiskinky-trisha-and-mandy-bright-demon-queen/` | p1 head | plain-curl | `j2.xspcdn01.click` → loopback | 540/360 | **Blocked** (site chain green) |
| 17 | tags (`/tag/anal/`) | `/pornworld-lucy-mendez-seductive-spy-lucy-mendez-entices-clients-into-an-interracial-dp-threesome/` | p1 tail | plain-curl | `pervl4.xspcdn01.click` → loopback | 1080/720/480 | **Blocked** (site chain green) |
| 18 | tags (`/tag/anal/`) | `/private-violet-and-veronica-are-sexy-nurses-who-know-how-to-make-it-better/` | p362 head (last page) | plain-curl | `pervl4.xspcdn01.click` → loopback | 540/360 | **Blocked** (site chain green) |
| 19 | genres | — | **absent** (`/genre/`, `/genres/` 404; `/genre/<x>/` 301 → video slug) | plain-curl | — | — | **absent** |

`Blocked` on a gated row describes the **playback** leg only: the provider hands the player a
loopback URL the runner cannot fetch (see *#1353 regression* below). The site-side chain behind that
playlist was verified green for every gated row (table in *Stream chain*).

---

## Stream chain (site-level, 17/18 rows green)

Every sample was resolved with the provider's own decision order (`XsftkParse.playerPlan`):
player page → `XSFTK.boot(...)` → gated chain; no boot call and no gate marker → legacy
`xs1.php` master; gate marker without boot → fail closed. Probe used plain HTTP, the in-app referer
(`https://tube.perverzija.com/`), and the provider's segment URL shape.

| # | family | manifest | qualities | cdn[0] | segment | AES/TS | chain |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | XSFTK | 200, 8 378 B → 3 qualities | 1080/720/480 | `pervl6.xspcdn01.click` | 206 (`1080_enc/10800.html`) | key 200 (16 B), decrypt sync 0x47 | **green** |
| 2 | legacy | `xs1.php` 200 #EXTM3U | 360 (single variant) | `pervl11.xspcdn01.click` | 206 (`360/3600.html`) | plain TS sync 0x47 | **green** |
| 3 | legacy | `xs1.php` 200 #EXTM3U | 480 (single variant) | `pervl11.xspcdn01.click` | 206 (`480/4800.html`) | plain TS sync 0x47 | **green** |
| 4 | XSFTK | 200, 7 156 B | 1080/720/480 | `j2.xspcdn01.click` | 206 (`1080_enc/10800.html`) | key 200 (16 B), decrypt sync | **green** |
| 5 | XSFTK | 200, 3 412 B | 1080 | `perv.xspcdn01.click` | 206 | key 200 (16 B), decrypt sync | **green** |
| 6 | XSFTK | 200, 2 513 B | 1080/720/480 | `pervl6.xspcdn01.click` | 206 | key 200 (16 B), decrypt sync | **green** |
| 7 | XSFTK | 200, 11 520 B | 1080/720/480 | `pervl8.xspcdn01.click` | 206 | key 200 (16 B), decrypt sync | **green** |
| 8 | alternate | — | — | `mixdrop.to` → `mxdrop.click` | — | — | **upstream dead** |
| 9 | XSFTK | 200, 7 818 B | 1080/720/480 | `j2.xspcdn01.click` | 206 | key 200 (16 B), decrypt sync | **green** |
| 10 | XSFTK | 200, 14 436 B | 1080/720/480 | `pervm1.xspcdn01.click` | 206 | key 200 (16 B), decrypt sync | **green** |
| 11 | XSFTK | 200, 21 981 B | 1080/720/480 | `pervl6.xspcdn01.click` | 206 | key 200 (16 B), decrypt sync | **green** |
| 12 | XSFTK | 200, 4 813 B | 1080/720/480 | `pervl8.xspcdn01.click` | 206 | key 200 (16 B), decrypt sync | **green** |
| 13 | XSFTK | 200, 6 060 B | 1080/720/480 | `pervl7.xspcdn01.click` | 206 | key 200 (16 B), decrypt sync | **green** |
| 14 | XSFTK | 200, 8 072 B | 1080/720/480 | `pervl6.xspcdn01.click` | 206 | key 200 (16 B), decrypt sync | **green** |
| 15 | XSFTK | 200, 8 716 B | 1080/720/480 | `j2.xspcdn01.click` | 206 | key 200 (16 B), decrypt sync | **green** |
| 16 | XSFTK | 200, 2 478 B | 540/360 | `j2.xspcdn01.click` | 206 (`540_enc/5400.html`) | key 200 (16 B), decrypt sync | **green** |
| 17 | XSFTK | 200, 16 044 B | 1080/720/480 | `pervl4.xspcdn01.click` | 206 | key 200 (16 B), decrypt sync | **green** |
| 18 | XSFTK | 200, 1 941 B | 540/360 | `pervl4.xspcdn01.click` | 206 (`540_enc/5400.html`) | key 200 (16 B), decrypt sync | **green** |

Segment counts per row: 286, 283, 315, 234, 372, 151, 396, 288, 507, 284, 142, 217, 264, 321, 273,
654, 137 (`#` refers to the table above; the alternate row has none).

### Raw transcript — gated XSFTK (row 1, `21eroticanal-kitana-lure…`)

```
video page 200 → iframe https://pervl6.xtremestream.xyz/player/index.php?data=e93028bdc1aacdfb3687181f2031765d
player 200 → XSFTK.boot("e93028bdc1aacdfb3687181f2031765d", "9a51f39a74eafe89e81c460c.1795500000", player)
GET /player/proto_api.php?data=e93028bd…&op=manifest&vt=…        → 200, 8 378 B octet-stream
  XOR SHA-256("M|e93028bd…|9a51f39a….1795500000") → {"1080":{…},"720":{…},"480":{…},"cdn":[…35 hosts…]}
GET /player/proto_api.php?data=e93028bd…&op=key&vt=…   X-XS-P: 1 → 200, 16 B AES-128 key
GET https://pervl6.xspcdn01.click/cdn/down/e93028bd…/1080_enc/10800.html
                                                       Range: bytes=0-377 → 206
  openssl aes-128-cbc -K <key> -iv 000…0 -d → 0x47 … 0x47 (MPEG-TS sync at 0 and 188)
```

### Raw transcript — legacy (row 2, `pornfidelity-harlow-harrison…`)

```
video page 200 → iframe https://pervl11.xtremestream.xyz/player/index.php?data=1d98edfd003bcd59e957739802965f19
player 200, 1 021 004 B → no XSFTK.boot, no gate marker
GET /player/xs1.php?data=1d98edfd…   Referer: https://pervl11.xtremestream.xyz/ → 200
  #EXTM3U
  #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,…
  https://pervl11.xtremestream.xyz/player/xs1.php?data=1d98edfd…&q=360
GET …&q=360 → 200 media playlist, 283 segments
GET https://pervl11.xspcdn01.click/cdn/down/1d98edfd…/360/3600.html  Range: bytes=0-377 → 206
  first bytes 0x47 … 0x47 (plain MPEG-TS, unencrypted)
```

Both URLs were fetched **without** a token (`xs1.php` is tokenless and live on the pre-gate
family), with the player-origin referer; without a referer the legacy master answers **403**, which
is why the `verify.sh` invocation below passes `--header 'Referer: https://pervl11.xtremestream.xyz/'`.
Gated segment files need neither token nor referer.

### #1353 regression — loopback/XSFTK classification still holds

The provider no longer emits a `data:` URI. `PerverZijaExtractor.emitXsftk` publishes each
synthesized playlist through `XsftkPlaylistServer.publish`, which returns
`http://127.0.0.1:<port>/<id>.m3u8` (`XsftkPlaylistServer.kt`). The player's datasource is HTTP-only,
so a loopback URL is fetchable **in the app process** but not from the runner — `verify.sh`'s
`is_app_local` gate classifies it as **BLOCKED** (exit 0, `PASS (N app-local stream(s) Blocked,
pending the emulator playback tier #1360)`), never as a `data:` scheme failure and never as a
finding. This run re-ran that classification with the four gated `--stream-url` rows below; the
actual loopback port/id is assigned at publish time inside the app, so the gate was exercised with
a shape-equivalent loopback URL, while the emission path itself is read from
`XsftkPlaylistServer.publish` (`http://127.0.0.1:$port/$id.m3u8`). The device-side playback proof
remains the emulator lane (#1353/FINDINGS-1353), not this runner.

**Residual limit, stated plainly:** no live `loadLinks` invocation is asserted here — it is an
app-side `suspend` function and this repo has no local dev path (ADR-0001, ADR-0005). The site-side
chain above + the parse/extractor unit tests are this run's evidence; in-app playback stays the
maintainer's at merge.

### Embed host registration

- **xtremestream.xyz** family — registered as `PerverZijaExtractor()` in
  `shared/src/main/kotlin/com/kraptor/HostRegistry.kt` (mainUrl `https://pervl2.xtremestream.xyz`).
  `loadLinks` calls it directly because `loadExtractor` matches on `mainUrl` and would miss the
  per-video subdomains (`pervlN`/`pervmN`/`j2`/`perv`); observed this run: `pervl4`, `pervl6`,
  `pervl7`, `pervl8`, `pervl11`, `pervm1`, `perv`, `j2`. All resolved green.
- **mixdrop.to** — *not* registered (HostRegistry has `mixdrop.is`, `mxdrop.click`, `.my`, `.ag`
  rows only). One sampled old row (`private-terri-summers…`) embeds
  `https://mixdrop.to/e/e0dp3ewa7i` instead of the xtremestream player. The provider routes it
  through `loadExtractor`, gets no source, and the embed is **dead upstream**:
  `mixdrop.to/e/e0dp3ewa7i` → **302** → `mxdrop.click/e/e0dp3ewa7i` → **200 "WE ARE SORRY / We
  can't find the video you're looking for"**, no `MDCore.wurl`. This is the same condition as
  FINDINGS-906 (1/336 census) and #702, which was closed as *"Not actionable: … a registry row
  would point at a dead host."* No playable stream is missed today; no new finding (dedup, and
  dead-at-source is not a provider defect). Recorded, not filed.

### Quality

- **Gated/XSFTK**: the decoded manifest's quality keys are the provider's emitted `quality`
  (`XsftkParse.cdnSources` → `QualitySource.quality` → `this.quality = source.quality`). Observed
  catalogue shapes: 1080/720/480 (13 rows), 1080 only (1), 540/360 (2). Every rendered quality was
  passed to CloudStream; none dropped.
- **Legacy**: the provider emits the tokenless master as an `M3U8` link with
  `Qualities.Unknown`. The legacy master this run served is **single-variant** (`q=360` or `q=480`),
  so there is no higher rendition to mislabel; the app's HLS player resolves the variant from the
  master. No data-completeness gap.

---

## Pipeline verification — `verify.sh` (live): **PASS**

Two-page search, two-page home, 5 sampled videos (4 gated → loopback Blocked, 1 legacy → real
master fetched), position-matched `--stream-url` overrides, `--provider-src PerverZija`:

```
── check 1: search pages (2)
GET https://tube.perverzija.com/?s=anal → 200; 'div.col-md-3.col-sm-3' matches: 64
GET …/page/2/?s=anal → 200; 64 cards, p1↔p2 disjoint
NOTE: repeated poster on search across different videos (series poster, not a card duplicate)
── check 1a: homepage pages (2)
GET https://tube.perverzija.com/featured-scenes/   → 200; 'div.col-md-3.col-xs-6' matches: 64
GET …/featured-scenes/page/2/ → 200; 64 cards, p1↔p2 disjoint
── check 1b: quick search (0)
NOTE: no --quick-search-url — no suggest endpoint (recorded below)
── check 2: video pages (5 URLs)
GET /21eroticanal-kitana-lure-sunday-rekindling/ → 200; 'div.item-tax-list' matches: 1
BLOCKED: app-local stream (loopback host) … http://127.0.0.1:39659/21eroticanal.m3u8
GET /pornfidelity-harlow-harrison-walk-the-streets-4-e622/ → 200
GET stream (…/player/xs1.php?data=1d98edfd…) → 200 text/plain (m3u8 body)
GET /babygotboobs-sophia-leone-laid-rent/ → 200 → BLOCKED (loopback)
GET /momswap-jenna-noelle-and-athena-anderson-lessons-on-motivation/ → 200 → BLOCKED (loopback)
GET /dorcelclub-ella-hughes-and-mariska-ella-hughes-the-hostess-selected-by-mariska/ → 200 → BLOCKED (loopback)
field 'tags': present on all 5 sampled pages
NOTE: no --video-actors-selector / --video-year-selector / --video-duration-selector — exposure stated below
── check 5: search ↔ load agreement
NOTE: no sampled video appears in the search page(s) — the `anal` draw is clean; agreement proven directly below
── check 6: LoadResponse completeness (code vs FINDINGS)
field 'recommendations': 2 · 'tags': 2 · 'plot': 1 · 'duration': 2 · 'year': 2 · 'actors': 3 · 'score': 2 · 'posters': 5
RESULT: PASS (4 app-local stream(s) Blocked, pending the emulator playback tier #1360)
```

Invocation notes (the FINDINGS lines the flags require):

- **Selectors** — the provider selects `div.col-md-3:not(#sidebar)` and then filters by
  `img[title]`; the mechanical checks use the card-shape classes directly
  (`div.col-md-3.col-sm-3` on search, `div.col-md-3.col-xs-6` on listings) so the sidebar widgets
  and date/tag chips are not counted as cards. Functionally the same row set as the provider's.
- **Quick search** — no suggest/live-typing endpoint exists (the theme's search is a full-page
  submit only), so `hasQuickSearch` stays `false` and check 1b is a NOTE (standing record since
  #651, not drift).
- **Related rail** — `div.xs-related-item` matched 18 per sampled page; the provider dedups by
  href (`PerverZijaParse.related`, issue #909). The site renders 3 duplicate cards on some pages
  (observed on `watchyourwife-casey-calvert-25367`, excluded from the sample); the provider's
  dedup is why check 4 passes on the delivered draw. No self-reference, no empty titles.
- **Stream URLs** — gated pages have no static stream URL (playlist synthesized at play time), so
  their `--stream-url` entries are the loopback shape the provider emits and are **Blocked**. The
  legacy page's `--stream-url` is the live `xs1.php` master, fetched 200 with an `#EXTM3U` body.
- **Check 5 — NOTE, proven directly instead.** The `anal` search draw (chosen for a clean disjoint
  two-page pagination assertion) contains none of the 5 verify samples. Agreement was proven on
  `?s=harlow` (where `pornfidelity-harlow-harrison…` appears at card 48 of 64):

  ```
  card  a[href=/pornfidelity-harlow-harrison-walk-the-streets-4-e622/] title="PornFidelity – Harlow Harrison – Walk The Streets #4 E622"
        img.title/alt  same string; img.src …-365x235.jpg
  load  h1.text()      "PornFidelity – Harlow Harrison – Walk The Streets #4 E622"   (provider reads h1)
        og:title        "Watch PornFidelity - Harlow Harrison - Walk The Streets #4 E622 | Perverzija.com"
        og:image        …/PornFidelity-Harlow-Harrison-Walk-The-Streets-4-E622.jpg
  ⇒ title equal (card == h1); poster differs only by the WP `-365x235` crop token (same artwork,
    which verify.sh's poster_path normalizes — NOTE, not FAIL)
  ```

- **Tags / actors / year / duration** — the video page's `div.item-tax-list` exposes
  `Studio: <a rel="category tag">`, `Stars: <a rel="tag">` and `Tags: <a rel="tag">` on all 5
  sampled pages (tags asserted via `div.item-tax-list a`). `year`/`duration` come from JSON-LD
  `datePublished` / `VideoObject` (the provider reads them via `JsonLdParse`), not from a stable CSS
  element, so no `--video-year-selector` / `--video-duration-selector` exists to pass. Actors are
  in the same `Stars` block, same limitation. All are populated in code (check 6).

---

## Site-side notes (recorded, **not** provider findings)

1. **`old` is a query ordering, not a route.** `/?order=asc` is the explicit oldest-first listing;
   the default `/` is date-descending. `orderby` accepts `date|view|like|comment|title`; `?order=asc`
   is the only direction switch observed (`?order=desc` returns the default). No `/oldest/` route.
2. **The catalogue has two ends beyond the paginated index.** The oldest listing page is
   `/page/1700/` (default) at 2019-05, while `/?order=asc` reaches 2018-12; the WP REST index
   reports 111 389 posts versus 1 700 × 64 = 108 800 listing slots, i.e. some older/non-listing
   post objects are not reachable through the paginator. Enumeration for the provider is unaffected
   (it pages the same `/page/<n>/` routes).
3. **`/category/<slug>/` is a 301 alias** for `/studio/<slug>/` and `/category/` itself is 404;
   the theme's category taxonomy is Studio. Recorded because the D3 vocabulary is generic and
   "categories" maps to it here.
4. **`/genre/<slug>/` 301s to a video slug** when the slug matches a post, which can look like a
   genre route on a shallow probe; the real index `/genre/` and `/genres/` are 404 and no genre
   vocabulary exists in the markup.
5. **The related rail occasionally repeats a card** (3 duplicate hrefs on
   `watchyourwife-casey-calvert-25367`; 18 items, 15 unique). The provider dedups by href
   (#909); this is a site-render defect, not a data-completeness gap for the provider.

---

## Gates

- `.pi/skills/audit-providers/scripts/check-findings.sh` → **PASS** (registry untouched in the diff, ADR-0013)
- `./gradlew PerverZija:test` → **BUILD SUCCESSFUL** (all suites green, `shared/` tests included)
- `./gradlew PerverZija:make` → **BUILD SUCCESSFUL**, clean `PerverZija/build/PerverZija.cs3` (164 747 B)
- `.pi/skills/verify-provider/scripts/verify.sh` (live) → **RESULT: PASS** (4 app-local streams
  Blocked, pending #1360)
- No confirmed condition → **no code change, no version bump (PerverZija stays 33)**

---

## Registry write (ADR-0013 — nothing in the PR diff)

Posted as a comment on #1456 that begins with `<!-- findings-registry-entry -->` followed by a
fenced json document in the delta shape `{providers: {PerverZija: …}, last_runs: […]}` for the
orchestrator's `registry_sync`: the PerverZija provider entry (`verdict` **and** `tier`,
`last_deep` 2026-11-24, `drift_confirmed` 0, `chronic` false, `standing_issue` null) and one
`last_runs` entry with `false_positives: []` and `escalation_tier: unavailable` (#1434).
