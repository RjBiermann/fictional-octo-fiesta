# Pipeline Verification — PerverZija (issue #909, S4 of the #903 epic)

Live run: **2026-10-23**, from the CI runner, plain HTTP, no JS, mobile UA
(`Mozilla/5.0 (Linux; Android 10) …`), theme referer `https://tube.perverzija.com/`
for every player-page fetch. Scope: the previously-failing URL set (reported #903 link
+ the census `#906` failure) and a green-class regression sweep through the shipped
chain (S3 = #908, provider version 19 → 20 this story).

Result: **the reported URL decodes end-to-end on every quality it exposes, the green
class keeps its multi-quality emission, and the one provider-side defect verification
surfaced (duplicated related card) is fixed below.** Two residual classes stay
upstream-side and are recorded with evidence, not papered over.

## 1. Previously failing set

### 1a. Reported URL from #903 — `playboyplus-natalia-starr-giddy-up` — **GREEN**

Chain replayed exactly as `PerverZijaExtractor.getUrl` / `XsftkParse.playerPlan` does it
(video page → iframe → player page → boot → `op=manifest` → XOR decode → `op=key` →
seg-0 AES-128-CBC with `IV = BE(0)` → MPEG-TS sync):

```text
GET video page                          → 200
iframe  https://pervl3.xtremestream.xyz/player/index.php?data=71eee742e4c6e094e6af364597af3f05
GET player page (referer tube.perverzija.com/) → 200
plan: XSFTK   boot.id = 71eee742…   boot.token = 3acfe9b7e97f1b14b0ed8aed.1792803600
GET proto_api.php?op=manifest           → 200, 3238 B
XOR-SHA-256("M|<id>|<token>") manifest  → qualities 1080, 720, 480
GET proto_api.php?op=key  (X-XS-P: 1)   → 200, 16 B
GET <cdn>/cdn/down/<id>/1080_enc/10800.html → 200, 2 435 360 B → AES-CBC → TS sync ✓ (80 segs)
GET <cdn>/cdn/down/<id>/720_enc/7200.html   → 200, 1 146 816 B → AES-CBC → TS sync ✓ (76 segs)
GET <cdn>/cdn/down/<id>/480_enc/4800.html   → 200,   674 736 B → AES-CBC → TS sync ✓ (74 segs)
```

The manifest byte count, quality set and segment counts are byte-identical to the
`perverzija_manifest_reported.bin` fixture (3238 B → 1080/720/480, 80/76/74 segments);
only the boot token rotated (epoch expiry), which the structural matcher tolerates.

### 1b. Census failure from #906 — `allanal-lana-anal-rebel-rhyderanal-cravings` — **upstream dead, not 2004**

```text
GET video page → 200, iframe https://mixdrop.to/e/enl4jzdjt76r6q   (not xtremestream)
GET https://mixdrop.to/e/enl4jzdjt76r6q → 302 → mxdrop.click/e/… → 200
body: <h2>WE ARE SORRY</h2><p>We can't find the video you are looking for.</p>   (no MDCore.wurl)
```

Unchanged from FINDINGS-906: the alternate embed's file is dead at the source, so no
provider-side chain can make it play. The shipped behaviour is correct and honest:
`loadLinks` routes the non-xtremestream iframe to the shared host registry (no
`mixdrop.to` row exists) and emits **no** source — never a 404-ing link. Recorded as
Blocked/upstream, not a FAIL of this provider.

## 2. Green-class regression — multi-quality emission intact

A 24-video sample taken from four listing rows (`featured-scenes`, `studio/page/2`,
`full-movie`, `tag/classic`) was replayed end-to-end through the same chain:

| family | videos | qualities observed | result |
|---|---|---|---|
| XSFTK (gated) | 23 | `1080+720+480` ×17, `720+480` ×4, single `480` ×1, single `360` ×1 | 23/23 decode ✓ |
| legacy (pre-gate) | 1 | 480+720+1080 via `xs1.php` master → media playlist → plain-TS seg-0 | 1/1 decode ✓ |

Every quality the manifest exposed produced a buildable playlist whose first segment
decrypted (gated) or served (legacy) to clean MPEG-TS sync bytes. Single-quality
manifests (`wicked-the-bride-of-double-feature-1999` → `360` only) still emit exactly
one source rather than zero — the "only one source, no qualities" class stays fixed.

Legacy family spot-check (the S3 hand-off asked for more than one live legacy id):
9 videos classified legacy, 8/9 fully green with 1–3 qualities each (e.g.
`vivid-man-of-steel-xxx-an-axel-braun-parody-2013` → 480/720/1080 plain TS;
`private-xtreme-22-a-is-for-anal-2005` → single `540` variant, green), **0**
false marker-positives (`isGatedPlayer` never suppressed a live legacy master).

## 3. Player-classification sweep (202 pages)

202 unique video pages from 21 listing/search surfaces were classified through the
shipped `PerverZijaParse.playerEmbed` + `XsftkParse.playerPlan` rules:

| class | count | share |
|---|---|---|
| XSFTK (boot binds to the `data=` id) | 180 | 89.1% |
| legacy (no gate marker, live `xs1.php`) | 18 | 8.9% |
| alternate embed host (non-xtremestream) | 3 | 1.5% |
| playerless page (`#player{display:none}`, `main_video_url=""`) | 1 | 0.5% |

Invariants the S3 hardening depends on, all confirmed live:

- **0** pages where a `XSFTK.boot(...)` call exists but does not bind to the URL's
  `data=` id (the structural argument matcher never had to fall back).
- **0** gated pages without a resolvable boot call (`FailClosed` never fired in the
  sample) — and **0** legacy pages carrying an FTK gate marker.
- **0** pages where a non-player iframe was picked over the xtremestream iframe.

## 4. Residual upstream classes (recorded, not fixed)

1. **Alternate embed hosts with no registry row** — 3/202 pages
   (`mixdrop.to` ×2, `playhydrax.com` ×1). The `mixdrop.to` files are dead at source
   (`WE ARE SORRY`); `playhydrax.com/?v=…` answers **403** from a plain runner. Routing
   is correct (registry miss ⇒ no source, never a decoy link); a `mixdrop.to` registry
   row only becomes worthwhile if a live file appears.
2. **Stale legacy master on one player host** — `pervl2.xtremestream.xyz`'s `xs1.php`
   currently answers *every* `data=` id with one fixed variant that maps to an unrelated
   dead id (`/cdn/down/4e0db76e…/1080/10800.html` → **404**), while the same video id is
   live on other hosts (`j2.`, `moviekh.`, `perv.` serve 480/720/1080 plain TS). 3 of the
   18 legacy videos sampled sit on `pervl2` (`agwentertainment-angela-loves-anal-2-2018`,
   `legalporno-linda-del-sol-first-triple-anal…paf011`, `private-milf-cafe-2009`).
   The site's own player (video.js + `m3u8_loader_url` on `pervl2`) is broken the same
   way — a host-side backend fault, not a provider grammar defect. Fixing it provider-side
   would require inventing a player-host list plus a segment-liveness probe (N extra
   requests) for a 1.5% class the site itself cannot play; out of scope per the repo's
   "fix on confirmed provider condition" rule. Recorded in FINDINGS.md for the next
   PerverZija ai-fix if the class grows.

## 5. Provider defect found by verification, fixed in this story

**Duplicated related card.** 4 of 24 sampled video pages render the same
`div.xs-related-item` twice (e.g. `pornworld-tigerr-benson-ready-for-her-gushing` →
`pornworld-nikky-dream-and-loren-minardi…` ×2; also `sinfulxxx-tiffany-tatum…`,
`vivid-be-here-now-2007`, `pornworld-blondie-fesser…`). `load()` emitted the duplicate
as two identical recommendations.

- Fixture: `src/test/resources/perverzija_video_related_dup.html` — the page's 18
  related cards, verbatim (one href twice).
- Red → green: `PerverZijaRelatedDedupTest` (6 tests) failed at `related cards are
  href-distinct` (18 vs 17) → `PerverZijaParse.related()` now dedupes by href, keeping
  the first occurrence and its order → green.

**Pre-existing red test on `main`** (surfaced by running the repo's own gate): the S3
test `legacy master is built from the origin for non player url shapes` used
`/embed/<id>` (a bare path id) and expected a Legacy master, contradicting both the
implementation contract and the sibling test `legacy master without a data id fails
closed instead of guessing` — so `:PerverZija:test` was red before this story. The URL
now carries the id as `data=` (the shape every live iframe uses), preserving the test's
intent (a non-`/player/` path still resolves the origin-derived master).

## 6. verify.sh transcripts (2 invocations)

Both invocations used the live selectors from FINDINGS: search `div.post_ajax_tm` +
title `h3 a`, home `div.col-md-3.col-xs-6`, video `iframe[src*=xtremestream]`,
related `div.xs-related-item`, tags `div.item-tax-list a[href*='/tag/']`,
actors `div.item-tax-list a[href*='/stars/']`, `--load-response` +
`--provider-src PerverZija`, no quick-search endpoint (recorded absent).

**A — legacy family (streams mechanically checked).** `/?s=realitykings` p1+p2,
`/featured-scenes/` p1+p2, three legacy videos whose master is passed as the
`--stream-url` the provider resolves:

| check | result |
|---|---|
| search p1/p2 | 200; 64+64 cards, no dupes/cross-page repeats ✓ |
| home p1/p2 | 200; 64+64 cards, disjoint ✓ |
| video ×3 | 200; `iframe[src*=xtremestream]` ×1 each; h1/og:image/og:description present ✓ |
| streams ×3 (`xs1.php` master) | **200 `text/plain` + `#EXTM3U` body ×3** ✓ |
| field exposure | tags + actors present on 3/3 ✓ (year/duration come from JSON-LD — not DOM-expressible, FINDINGS records them) |
| related ×3 | 18/18/18 cards, titles non-empty, no self-link ✓ |
| search ↔ load agreement | title agrees on 3/3; poster NOTE (card `-365x235` crop vs full-size `og:image`, same artwork) |
| LoadResponse completeness (code half) | recommendations/tags/plot/duration/year/actors/posters all populated ✓ |

`RESULT: FAIL` on exactly one check — **stream path distinctness**
(`/player/xs1.php` for all three): the HLS identity of this family lives in the
subdomain + `?data=` token, and `url_path()` keeps only the path. Manual substitution:
the three masters carry distinct tokens
(`d604dd857d8434bd10c0f2fb2c8eef03`, `d0ec7deb55fed8949efb61cf1fa39004`,
`f74d6ef882234fd34400a296b1da6149`) and each serves `#EXTM3U` with its own variant list
(same plateau as FINDINGS-651, unchanged instrument limit).

**B — gated family (reported URL + census classes).** `/?s=wicked` p1+p2,
`/full-movie/` p1+p2, videos: reported URL, `playboyplus-vero-star-serving-up-trouble`
(single 480), `vivid-the-visitor-2006` (720/480), `wicked-the-bride-of-double-feature-1999`
(single 360):

| check | result |
|---|---|
| search p1/p2 | 200; 64+64 cards, no dupes ✓ |
| home p1/p2 | 200; 64+64 cards, disjoint ✓ |
| video ×4 | 200; iframe ×1 each; h1/og:image/og:description present ✓ |
| field exposure | tags + actors on 4/4 ✓ |
| related ×4 | 18/12/12/18 cards, titles non-empty, no self-link, no duplicate hrefs on these pages ✓ |
| search ↔ load agreement | title + poster agree (poster NOTE: same artwork) ✓ |
| LoadResponse completeness (code half) | all 7 fields populated ✓ |
| streams | **`RESULT: FAIL` by instrument plateau**: the XSFTK playlist is emitted in-band as a `data:` URI (ExoPlayer opens it natively), so the page has no static m3u8/mp4 for the script to fetch. Substituted by the §1a chain transcript (manifest → key → segment → TS), which is the stronger evidence: it proves every emitted quality decodes. |

The duplicate-recommendation check the script performs on the raw DOM still flags the
site-side duplicate page (`pornworld-tigerr-benson…`) if it is sampled — the script
reads the DOM, not provider output; the provider-side dedupe is pinned by
`PerverZijaRelatedDedupTest`.

## 7. Gates

```text
$ ./gradlew bootstrapCloudstream            → BUILD SUCCESSFUL
$ ./gradlew :PerverZija:test                → BUILD SUCCESSFUL (138 tests, debug+release)
$ ./gradlew :PerverZija:make                → BUILD SUCCESSFUL (PerverZija/build/PerverZija.cs3)
$ ./gradlew test                            → BUILD SUCCESSFUL (all 24 providers, 781 tasks)
```

Provider version 19 → 20 (related-card dedupe). No change to the stream chain: the S3
fallback/decision point is untouched, so §1–§3 are a regression check on shipped code.

## #1328 — playback hardening (S4)

Live run: **2026-10-26 04:00–04:12Z**, from the CI runner, plain HTTP, no JS, mobile UA
(`Mozilla/5.0 (Linux; Android 10; K) …`), theme referer `https://tube.perverzija.com/`
on every player-page fetch. Scope: the shipped S2 multi-CDN selection and S3 `data:` budget,
replayed against the #1328 reported URL, a second gated video from a different listing row
and a different player subdomain, and one legacy (pre-gate) video. Provider version at run:
**24** (from #1345); this story is docs-only, so no version bump is expected.

### 1. Reported URL — `evolvedfightslez-jazmine-cruz-vs-nadia-white` — **GREEN**

Chain replayed exactly as `PerverZijaExtractor.emitXsftk` / `XsftkParse.cdnSources` does it
(video page → iframe → player page → boot pair → `op=manifest` → XOR decode → roster
selection → `op=key` → seg-0 of chosen and alt-family hosts → AES-128-CBC → MPEG-TS sync):

```text
GET video page                                  → 200, 369 361 B
iframe  https://pervl6.xtremestream.xyz/player/index.php?data=55ed523b2ca4371a31d7dbd7a2877bea
GET player page (referer tube.perverzija.com/)  → 200, 3 177 B
boot    XSFTK.boot("55ed523b2ca4371a31d7dbd7a2877bea",
                   "cbd4413fa0b31d17d33e04b7.1792998000", player)
GET proto_api.php?op=manifest                   → 200, application/octet-stream, 11 062 B
XOR SHA-256("M|<id>|<token>")                   → qualities 1080 (394 segs), 720 (381), 480 (372)
                                                → cdn roster 45 hosts, 2 families
                                                  (pervl6.xspcdnNN ×35 / pervl6.xscasacdnNN ×10)
shipped selection replay (XsftkParse.cdnSources):
  primary probe q=1080 pervl6.xspcdn01.click    → 206, 1 024 B   chosen, probes=1
  alt probe     q=1080 pervl6.xscasacdn01.click → 206, 1 024 B   alt,    probes=1
GET proto_api.php?op=key  (X-XS-P: 1)           → 200, 16 B
                                                 403 without X-XS-P · 403 with X-XS-P: 0
GET <chosen>/cdn/down/<id>/1080_enc/10800.html  → 200, 3 352 608 B
                                                  sha256 9252ebf3d09cfee4…
GET <alt>/cdn/down/<id>/1080_enc/10800.html     → 200, 3 352 608 B
                                                  sha256 9252ebf3d09cfee4…  (identical bytes)
AES-128-CBC decrypt, IV = BE(0)                 → clean MPEG-TS, 17 833/17 834 slot starts
                                                  0x47 (100.0%; last slot is a partial trailer)
```

The manifest head is `xspcdn01, xscasacdn01, xspcdn02, xscasacdn02, …`, so candidate #1 is
`cdn[0]` and the healthy video spent **one primary probe**. The alt source is the first host
of the other family, also accepted on its first probe. The fallback budget (6 primary / 3 alt)
was not approached because nothing was dead from CI.

### 2. Second gated video — `blacked-kelsi-monroe-fit-babe-kelsi-handles-his-bbc-at-the-gym` — **GREEN**

From `/featured-scenes/`, a different listing row and a different player subdomain
(`pervl4`, vs `pervl6` for the reported URL):

```text
GET video page                                  → 200, 387 588 B
iframe  https://pervl4.xtremestream.xyz/player/index.php?data=da40657c9fece7e48d30af42d31d4350
GET player page (referer tube.perverzija.com/)  → 200, 3 718 B
boot    XSFTK.boot("da40657c9fece7e48d30af42d31d4350",
                   "5921bb56b114771ef20ab37d.1792998000", player)
GET proto_api.php?op=manifest                   → 200, application/octet-stream, 10 332 B
XOR SHA-256("M|<id>|<token>")                   → qualities 2160 (291 segs), 1080 (215),
                                                  720 (219), 480 (221)
                                                → cdn roster 45 hosts, 2 families
                                                  (pervl4.xspcdnNN ×35 / pervl4.xscasacdnNN ×10)
shipped selection replay (XsftkParse.cdnSources):
  primary probe q=2160 pervl4.xspcdn01.click    → 206, 1 024 B   chosen, probes=1
  alt probe     q=2160 pervl4.xscasacdn01.click → 206, 1 024 B   alt,    probes=1
GET proto_api.php?op=key  (X-XS-P: 1)           → 200, 16 B
                                                 403 without X-XS-P · 403 with X-XS-P: 0
GET <chosen>/cdn/down/<id>/2160_enc/21600.html  → 200, 54 528 B, sha256 a107b649b5cdf151…
GET <alt>/cdn/down/<id>/2160_enc/21600.html     → 200, 54 528 B, same sha256 (identical bytes)
AES-128-CBC decrypt, IV = BE(0)                 → clean MPEG-TS, 290/291 slot starts 0x47
                                                  (99.7%; last slot is a partial trailer)
```

Same 45-host roster shape, same two-family split, one primary and one alt probe. The
`2160` quality is a useful extra: the manifest advertises a resolution above the reported
video's maximum and the shipped selector still probes the highest *buildable* quality first.

### 3. Legacy video — `vivid-man-of-steel-xxx-an-axel-braun-parody-2013` — **GREEN, untouched by S2**

A pre-gate page (`XSFTK` = 0, `ftk_engine` = 0, `ftk/xsad` = 0) on `j2.xtremestream.xyz`.
`playerPlan` returns `Legacy` before `emitXsftk` runs, so **no CDN selection code executes**
for this family:

```text
GET video page                                  → 200, 366 380 B
iframe  https://j2.xtremestream.xyz/player/index.php?data=ea160c1a93b80943bb632999ff3e6130
GET player page (referer tube.perverzija.com/)  → 200, 1 020 895 B
                                                (no XSFTK / ftk_engine / ftk/xsad markers)
GET https://j2.xtremestream.xyz/player/xs1.php?data=ea160c1a93b80943bb632999ff3e6130
                                                → 200, 567 B, #EXTM3U, 3 STREAM-INF
GET xs1.php?…&q=480                             → 200, 115 267 B, 1 121 EXTINF, no #EXT-X-KEY
GET <j2.xspcdn01.click>/…/480/4800.html         → 200, 567 196 B
                                                  MPEG-TS 3 017/3 017 slot starts 0x47 (100%)
GET xs1.php?…&q=720                             → 200, 127 099 B, 1 235 EXTINF
GET …/720/7200.html                             → 200, 931 164 B, 4 953/4 953 0x47 (100%)
GET xs1.php?…&q=1080                            → 200, 144 674 B, 1 378 EXTINF
GET …/1080/10800.html                           → 200, 1 368 828 B, 7 281/7 281 0x47 (100%)
```

The legacy master/variant/segment chain is byte-for-byte the family the #909 record
verified; S2 changes only the gated `emitXsftk` path.

### 4. verify.sh invocations

Both invocations used the live selectors from FINDINGS — search `div.post_ajax_tm` +
title `h3 a`, home `div.col-md-3.col-xs-6`, video `h1` for title agreement, player
`iframe[src*=xtremestream]`, related `div.xs-related-item`, tags
`div.item-tax-list a[href*='/tag/']`, actors `div.item-tax-list a[href*='/stars/']`,
`--load-response recommendations,tags,plot,duration,year,actors,posters` +
`--provider-src PerverZija`, mobile UA, no quick-search endpoint (recorded absent).

**A — legacy family.** `/?s=vivid` p1+p2, home `/` + `/page/2/`, the legacy video with its
`xs1.php` master as `--stream-url` and a player-origin Referer:

| check | result |
|---|---|
| search p1/p2 | 200; 64 + 63 cards, no duplicates/cross-page repeats ✓ |
| home p1/p2 | 200; 64 + 64 cards, disjoint ✓ |
| video | 200; iframe ×1; h1/poster/plot present ✓ |
| stream (`xs1.php` master) | 200 `text/plain` + `#EXTM3U` body ✓ |
| field exposure | tags + actors on 1/1 ✓ |
| related | 18 cards, titles non-empty, no self-link ✓ |
| search ↔ load agreement | title + poster agree (poster NOTE: `-365x235` crop vs full `og:image`, same artwork) ✓ |
| LoadResponse completeness (code half) | all 7 sampled fields populated ✓ |

The only `FAIL` is the site-side duplicate recommendation (`vivid-center-stage-2003` twice
in the raw DOM); the shipped `PerverZijaParse.related()` dedupes it by href
(`PerverZijaRelatedDedupTest`), and the script reads the DOM rather than provider output.

**B — gated family.** `/?s=blacked` p1+p2, home `/` + `/page/2/`, the reported URL and the
`pervl4` video:

| check | result |
|---|---|
| search p1/p2 | 200; 64 + 64 cards, no duplicates ✓ |
| home p1/p2 | 200; 64 + 64 cards, disjoint ✓ |
| video ×2 | 200; iframe ×1 each; h1/poster/plot present ✓ |
| field exposure | tags + actors on 2/2 ✓ |
| related ×2 | 18/18 cards, titles non-empty, no self-link, no duplicate hrefs ✓ |
| LoadResponse completeness (code half) | all 7 sampled fields populated ✓ |
| streams | **`RESULT: FAIL` by instrument plateau**: the XSFTK sources are synthesized playlists hosted on loopback (since #1353, `http://127.0.0.1:<port>/<id>.m3u8`; `data:` before that), so the page has no static m3u8/mp4 for the script to fetch. The §1–§2 chain transcripts are the stronger substitute: they prove the manifest → key → segment → TS decode for both videos. |

### 5. Residual classes / limits

- **In-app playback is maintainer-only.** Every check here is plain HTTP; how the app's
  player consumes the emitted source is not observable from CI. This lane does not pretend
  otherwise.
- **H5 is settled (2026-10-27).** The player never accepted the `data:` playlist: Cronet/
  OkHttp reject the scheme with `net::ERR_UNKNOWN_URL_SCHEME` → 2001 on every device, which
  is the #840/#1328/#1353 symptom. #1353 moved the emission to a loopback `http://` host and
  the playlist contract is pinned by `PerverZijaPlaylistContractTest`; see
  `FINDINGS-1353.md` for the emulator evidence.
- **A device-side DNS/filter block of `*.click` (H3) cannot be fixed here.** Every roster
  host is under `.click`. H3 is *not* the #1353 cause (see the `#1353` section), but a
  user-side filter would still remove the whole roster at once; the device request in
  `FINDINGS-1328.md` §7 is how to settle it if it recurs.
- **No dead candidate was observed from CI**, so this run gives no direct evidence for H1.
  `cdn[0]` answered `206` on both gated videos. A remaining device-only 2001 is therefore
  more likely H2/H3/H4/H5 than a dead primary host as seen from the runner.
- **Legacy family is unaffected by S2.** `playerPlan.Legacy` bypasses `emitXsftk`; the
  replay above is a regression check, not a changed path.
- **Version bump.** This story changes only `*.md` files. `scripts/affected-set.sh`
  classifies the diff as `none`, so the #921/#1334 PR-time bumper has nothing to bump;
  version 24 (from #1345) stays unchanged by hand. If the bumper were expected to fire for
  a docs-only PR, that would be a machinery finding — not a reason to edit `version` here.

## #1353 — root cause of the 2001 (emulator, 2026-10-27)

Device-side evidence instead of a CI replay: workspace emulator (`dev`, API 35), CloudStream
prerelease 4.8.0-PRE, PerverZija v24 then v26, two gated videos on different player
subdomains.

| run | emitted source | result |
|---|---|---|
| v24 | `data:application/vnd.apple.mpegurl;base64,…` | `CronetDataSource$OpenException: net::ERR_UNKNOWN_URL_SCHEME` → `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (2001)` on every source |
| v26 | `http://127.0.0.1:<port>/<id>.m3u8` (loopback host) | `Rendered first frame`, `PLAYING` (36 s / 15 s into 44 m videos) |

So H5 was the whole answer: the app's player datasource is HTTP-only (`CronetDataSource`, else
`OkHttpDataSource`; no `DefaultDataSource` scheme dispatch on the online path), and a `data:`
playlist can never be opened — no network, DNS, VPN or player-engine setting can change that.
`shared/XsftkPlaylistServer.kt` now hosts the synthesized playlist on loopback and
`emitXsftk` emits that URL; the `data:` URI, its base64 encoder and the #1338/S3 payload
budget were removed with it. Full evidence: `../FINDINGS-1353.md`.
