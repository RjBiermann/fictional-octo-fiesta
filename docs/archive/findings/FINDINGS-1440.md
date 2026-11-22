# FINDINGS-1440 — MissAV video sources (S2 of #1354)

Run: 2026-11-21 (`05-implement`, story **S2** of parent spec #1354). **Supersedes
#1436's ask for MissAV** (that deep-tier queue issue named HQPorner *and* MissAV;
S1 (#1439) paid the HQPorner half, this pass pays the MissAV half).

Scope per the spec: the **video-source leg** (D2) across the D3 listing types —
`new`/`latest`, `old`, `popular`, `genres`, `categories`, `tags` — ≥2 video pages per
type, `loadLinks` resolved end-to-end. Listing correctness (pagination/card-field
correctness) is checked only as far as enumeration needs; the site was not being
re-probed for a `drift_confirmed ≥ 1` reason (registry carries `drift_confirmed = 0`).

Site: `https://missav.live`. Instrument tier: **plain-curl on every surface**, desktop
UA (`Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0`),
`Referer: https://missav.live/` on stream fetches (what the provider emits).
Runner IP country: **US** (`57.151.123.250`, AS8075 Microsoft/Azure).

## Phase 0 — calibration (ran before any provider probing)

| canary | expected | observed (plain-curl) | reading |
|---|---|---|---|
| `film1k-challenge-pair` | plain 403 / TLS 200 | plain **200** / TLS unavailable | **expectation-stale** — open #1435; per the spec this run records the live pair and does **not** take the `environment-degraded` branch for this canary |
| `eporner-healthy` | plain 200 | plain **200** | match — instrument healthy |
| `pandamovies-redirect-origin` | plain 302 / TLS 200 | plain **302 → https://pandamovies.org/** | match (redirect-following intact) |

```
$ UA="Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0"
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.film1k.com/
200
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.eporner.com/
200
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://pandamovies.pw/
302 https://pandamovies.org/
$ .pi/skills/site-probe/scripts/impersonate.sh https://missav.live/
impersonate.sh: curl_cffi missing. Install it, then re-run:
  pip install curl_cffi
```

- **#1434 (open)** — TLS-impersonated tier unusable in this container →
  `escalation_tier: unavailable`. Only `Blocked` verdicts downgrade to `Suspected`;
  **this run produced zero `Blocked` verdicts**, so nothing was downgraded.
- **#1435 (open)** — film1k's stale expectation recorded, not treated as degradation.
- No genuine `environment-degraded` mismatch → the run's instrument is healthy.

## Listing enumeration (the D3 types as the site serves them)

Every URL below was fetched with the desktop UA and card-counted on the site's card
grammar (`div.thumbnail.group`). MissAV URL-shortcuts 301 into a per-row `dm<id>`
canonical form (`/en/new` → `/dm539/en/new`); `?page=N` survives the redirect (NiceHttp
follows it, unchanged since #274).

| type | first page | far end | evidence |
|---|---|---|---|
| `new` / `latest` | `/en/new` → `/dm539/en/new` — 12 cards, paginator `?page=2 … ?page=2000` | `/en/new?page=2000` — 12 cards (`siro-5568`, `mla-257`, …) | `/en/release` ("New Releases", `/dm635/en/release`) p1/p2 12+12, **overlap 0** |
| `old` | — | — | **absent**: `/en/old`, `/en/oldest`, `/en/latest`, `/en/classic` all 404; the listing **Sort** menu offers only `released_at, published_at, saved, today_views, weekly_views, monthly_views, views` (no ascending/oldest order) on `/en/new`, on genre pages and on maker pages. The oldest end is reached only as the catalogue tail — sampled at `?page=1999`/`?page=2000` (p1999∩p2000 = 0) |
| `popular` | `/en/weekly-hot` → `/dm170/en/weekly-hot` — 12 cards, paginator to `?page=10` | `?page=2` — 12 cards | p1∩p2 **0**; provider rows `/dm169/en/weekly-hot?sort=weekly_views`, `/dm263/en/monthly-hot?sort=views`, `/dm301/en/today-hot` all 200/12 cards |
| `genres` | `/en/genres` (index, 12 genre links + sub-genres) → `/en/genres/Creampie` → `/dm133/en/genres/Creampie` — 12 cards | `?page=2` — 12 cards | p1∩p2 = 4 (site cache skew between two CF-cached pages, same class as #641 — not drift; genre rows are not used by the provider) |
| `categories` | **absence of a `categories` route** (`/en/categories` 404, `/en/series` 404) — the site's category surface is **makers**: `/en/makers` index (12 rows/2-per-cell + `?page=2 … 10`) → `/en/makers/Prestige` → `/dm68/en/makers/Prestige` — 12 cards | `?page=2` — 12 cards | maker p1∩p2 = 1 (same cache skew); the `/en/makers` *index* itself carries 0 video cards (maker links + "N videos" counts only) |
| `tags` | — | — | **absent**: `/en/tags` 404; the site models tag semantics as **genres** (already covered above) — no separate tag endpoint exists in the nav or the sitemap links |

Provider-row enumeration check (D5 trigger: none — no drift): all **22 `mainPage`
rows** return 200 with 12 cards each (weekly/monthly hot, `/en/new`,
`/en/english-subtitle`, uncensored-leak, FC2, Madou, K-Live, C-Live, Tokyo Hot, HEYZO,
1pondo, Caribbeancom(+PR), 10musume, Pacopacomama, Gachinco, XXX-AV, Married Slash,
Naughty 4610, Naughty 0930).

## Evidence rows

`type | sample URL | instrument tier | stream host | quality | verdict`

Stream leg = video page → eval-packed player config (`getAndUnpack`) → its
`…/<uuid>/playlist.m3u8` master → HLS. Quality column = variants in the master playlist
the provider hands to the player (the provider emits the master; CloudStream renders the
variants — no per-quality `ExtractorLink` is needed).

| type | sample URL | tier | stream host | quality | verdict |
|---|---|---|---|---|---|
| new (p1) | `/en/036c-2626` (`/en/new`) | plain-curl | `c.surrit.com` | 360/480/720/1080p | **FAIL as shipped → fixed** (host was hardcoded to the apex) |
| new (p2) | `/en/gvh-899` (`/en/new?page=2`) | plain-curl | `c.surrit.com` | 360/480/720/1080p | **FAIL as shipped → fixed** |
| new (catalogue tail) | `/en/siro-5568` (`/en/new?page=2000`) | plain-curl | `surrit.com` (apex) | 360/480/720/1080p | **pass** |
| old | — | — | — | — | **absent** (no ascending sort anywhere; tail sampled as `new`) |
| popular (p1) | `/en/dldss-575` (`/en/weekly-hot`) | plain-curl | `surrit.com` (apex) | 360/480/720/1080p | **pass** |
| popular (p2) | `/en/hmn-915` (`/en/weekly-hot?page=2`) | plain-curl | `surrit.com` (apex) | 360/480/720p | **pass** |
| genres (p1) | `/en/noskn-106` (`/en/genres/Creampie`) | plain-curl | `c.surrit.com` | 360/480/720/1080p | **FAIL as shipped → fixed** |
| genres (p2) | `/en/fc2-ppv-5010663` (`/en/genres/Creampie?page=2`) | plain-curl | `c.surrit.com` | 360/480/720p | **FAIL as shipped → fixed** |
| categories → makers (p1) | `/en/abf-399` (`/en/makers/Prestige`) | plain-curl | `surrit.com` (apex) | 360/480/720p | **pass** |
| categories → makers (p2) | `/en/abf-395` (`/en/makers/Prestige?page=2`) | plain-curl | `surrit.com` (apex) | 360/480/720p | **pass** |
| tags | — | — | — | — | **absent** (genres carry tag semantics) |

Ura set (evidence for **#1430**, stream layer only — the *rows* question stays out of
scope): `/en/abf-400-uncensored-leak` (apex) and `/en/noskn-106-uncensored-leak`
(`c.surrit.com`) resolve **identically** to the main set — same single pack → same
master-playlist → variant grammar, 200 `audio/x-mpegurl`, 4 and 3 variants
respectively, no DRM, no embed hop, no account/VIP gate. The ura copies are ordinary
catalogue videos with a `-uncensored-leak` slug; nothing at the stream layer
distinguishes them.

## Confirmed condition (fixed in this run)

**The stream host is hardcoded to the apex `surrit.com`; the site's own player config
names `c.surrit.com` for most of the catalogue, and a UUID minted in the `c.` bucket
403s on the apex host.** (`Correctness / data-completeness` — the app emitted a stream
link that cannot play.)

The pack decodes to three URLs that name the bucket explicitly:

```
$ python3 unpack.py  # Dean-Edwards decode of the page's player pack
036c-2626  source='https://c.surrit.com/ab2b36f7-…/playlist.m3u8';source842='…/720p/video.m3u8';source1280='…/1080p/video.m3u8';
abf-399    source='https://surrit.com/93153cd4-…/playlist.m3u8';     source842='…/720p/video.m3u8';source1280='…/720p/video.m3u8';
```

The provider kept only the 36-char id from that string and rebuilt
`https://surrit.com/<id>/playlist.m3u8` — correct while every page used the apex, wrong
for the `c.` bucket the site now serves most pages from:

```
# 036c-2626 (config host = c.surrit.com) — the shipped URL vs the site's own URL
$ curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -A "$UA" -e https://missav.live/ \
    https://surrit.com/ab2b36f7-e1df-467e-8589-87d1f991d6ae/playlist.m3u8
403 application/xml
$ curl -s -A "$UA" -e https://missav.live/ https://surrit.com/ab2b36f7-…/playlist.m3u8
<?xml version="1.0" encoding="UTF-8"?>
<Error><Code>BucketPunish</Code><Message>BucketPunish</Message>
  <RequestId>6B0220B5692CA33238C10388</RequestId>
  <HostId>surrit.oss-eu-central-1.aliyuncs.com</HostId><EC>0003-00000908</EC></Error>
$ curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -A "$UA" -e https://missav.live/ \
    https://c.surrit.com/ab2b36f7-e1df-467e-8589-87d1f991d6ae/playlist.m3u8
200 audio/x-mpegurl
$ curl -s -A "$UA" -e https://missav.live/ https://c.surrit.com/ab2b36f7-…/playlist.m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TOKEN=ilDZ4cTMmJTM5QGNzUWNhxXN3ADO8BTNy4yMyEjLxUTMucTN
#EXT-X-STREAM-INF:BANDWIDTH=1477624,…,RESOLUTION=640x360,CLOSED-CAPTIONS=NONE
360p/video.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2529877,…,RESOLUTION=854x480,CLOSED-CAPTIONS=NONE
480p/video.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=4362040,…,RESOLUTION=1280x720,CLOSED-CAPTIONS=NONE
720p/video.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=7763007,…,RESOLUTION=1920x1080,CLOSED-CAPTIONS=NONE
1080p/video.m3u8
```

`BucketPunish` is the **origin's** answer (Aliyun OSS, `surrit.oss-eu-central-1`) — the
apex bucket does not contain `c.`-bucket ids. It is not a Cloudflare challenge and not a
runner-IP block, which is what the earlier audit reports recorded when every page still
used the apex host (`#302`, `#327`: "surrit 403 = runner-IP block, verify in-app"). That
older reading is superseded: the 403 is **host-specific and per-video**, and the same
runner fetches the same ids from `c.surrit.com` fine.

Host census (94 video pages sampled across `/en/new` p1–p6, `/en/weekly-hot` p1,
`/en/genres/Creampie` p1, `/en/makers/Prestige` p1 — one sample per video):

| config host | videos | share | apex-hardcoded URL |
|---|---|---|---|
| `c.surrit.com` | 72 | 77% | **403 BucketPunish** |
| `surrit.com` (apex) | 22 | 23% | 200 (unchanged) |

The `c.` host serves *both* buckets (apex ids answer 200 `application/octet-stream`
there), so the defect is not fixable by "just switch the host" without betting on
another hardcode: the fix reads the URL the page itself names.

### Fix (TDD-first, red → green)

- `MissAVParse.parsePlaylistUrl(unpacked: String?): String?` — returns the absolute
  `…/<uuid>/playlist.m3u8` URL the player config carries (host included, verbatim);
  when a pack carries only the bare id it keeps the legacy apex form (pre-#1440
  behaviour); `null`/blank → `null` (never a fabricated host).
- `MissAV.loadLinks` — emits `parsePlaylistUrl(getAndUnpack(response.text))` instead of
  rebuilding the URL against `https://surrit.com/`. The referer/header block is unchanged.
- Fixtures (live captures, 2026-11-21): `MissAV/src/test/resources/issue1440/unpacked-c-surrit.txt`
  (from `/en/036c-2626`) and `unpacked-apex-surrit.txt` (from `/en/abf-399`) — the exact
  `getAndUnpack` output of each page's player pack.
- `MissAVParseTest` +3 tests (c-bucket kept, apex kept, legacy fallback + null cases).
  Red on the pre-fix tree: `e: … Unresolved reference 'parsePlaylistUrl'` (6 sites).
- `MissAV/build.gradle.kts`: `version = 29` → `30`.

Post-fix resolution of every row above (`OLD` = shipped URL, `NEW` = page's own URL):

```
036c-2626          OLD 403 application/xml   NEW 200 audio/x-mpegurl            variants=4
gvh-899            OLD 403 application/xml   NEW 200 audio/x-mpegurl            variants=4
siro-5568          OLD 200 vnd.apple.mpegurl NEW 200 vnd.apple.mpegurl          variants=4
dldss-575          OLD 200 audio/x-mpegurl   NEW 200 audio/x-mpegurl            variants=4
hmn-915            OLD 200 audio/x-mpegurl   NEW 200 audio/x-mpegurl            variants=3
noskn-106          OLD 403 application/xml   NEW 200 audio/x-mpegurl            variants=4
fc2-ppv-5010663    OLD 403 application/xml   NEW 200 audio/x-mpegurl            variants=3
abf-399            OLD 200 audio/x-mpegurl   NEW 200 audio/x-mpegurl            variants=3
abf-395            OLD 200 vnd.apple.mpegurl NEW 200 vnd.apple.mpegurl          variants=3
abf-400-ura        OLD 200 audio/x-mpegurl   NEW 200 audio/x-mpegurl            variants=4
noskn-106-ura      OLD 403 application/xml   NEW 200 audio/x-mpegurl            variants=3
```

Segment-level proof (the master is a real VOD ladder, not a stub):

```
$ curl -s -A "$UA" -e https://missav.live/ https://c.surrit.com/ab2b36f7-…/720p/video.m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-TOKEN=lRjN1gTYhNWM5QGNzUWNhxXN3ADO8BTNy4yMyEjLxUTMucTN
#EXTINF:4.004000,
video0.jpeg
```

## Recorded, not findings

1. **Listing cache skew between CF-cached pages** (genre p1∩p2 = 4, maker p1∩p2 = 1,
   `/en/new` p1/p2 already 0 this run): the site caches listing pages for 12 h
   (`cache-control: max-age=43200`), so two page fetches minutes apart can see different
   windows. Site-side; the provider's own rows are handled by `MissAVParse.filterSeen`
   (#641) and no row is added here. Not drift.
2. **No `old` listing and no `tags`/`categories` routes** — recorded `absent` per D3,
   never a finding.
3. **Quality metadata**: the provider emits the master playlist, which carries the
   site's own 360/480/720/1080 ladder (3–4 variants per video, above). No per-quality
   `ExtractorLink` and no `quality=` attribute is emitted because the site itself plays
   the master; CloudStream selects the variant. Not a data-completeness gap.
4. **Recommendations** come from the Recombee API, not the DOM: the HMAC-signed
   `POST /missav-default/recomms/items/<id>/items/` with the embedded public token still
   returns 12 items (200) — `load()`'s `recommendations` field is live (no related
   selector exists in the page, so `verify.sh` check 4 is not applicable; evidence here
   instead).

## Dedup (findings lifecycle)

`gh issue list --search MissAV / surrit / BucketPunish --state all`: no open standing
issue for this condition. The only closed mentions of the surrit 403 are the audit
reports #302 / #327, which recorded it as an unfixable runner-IP block — superseded
above, and neither is a standing issue for the host bug. Nothing reopened, nothing filed
as a duplicate: the confirmed condition is fixed in this run (D1).

## Verification (delivery gate)

```
$ ./gradlew bootstrapCloudstream            ✓ (sha-pinned classes.jar into mavenLocal)
$ ./gradlew MissAV:test                     ✓ 31 tasks; MissAVParseTest 10/10
                                              (3 new #1440 tests green)
$ ./gradlew MissAV:make                     ✓ clean → MissAV/build/MissAV.cs3 (v30)
$ .pi/skills/audit-providers/scripts/check-findings.sh
findings-lint: PASS
$ .pi/skills/verify-provider/scripts/verify.sh …   RESULT: PASS
```

`verify.sh` invocation (post-fix; search `/en/search/abf` p1/p2, home
`/dm169/en/weekly-hot?sort=weekly_views` p1/p2, quick search `/en/search/suggest?q=abf`,
5 video pages spanning both buckets, `--stream-url` position-matched to each page's own
config URL):

```
── check 1: search pages (2)     200, 12 cards each, no duplicate cards
── check 1a: homepage pages (2)  200, 12 cards each, no duplicate cards
── check 1b: quick search (1)    200, 12 cards
── check 2: video pages (5)      abf-399 / abf-395 / 036c-2626 / gvh-899 / noskn-106 → 200
   streams 5/5 → 206 (audio/x-mpegurl | application/vnd.apple.mpegurl), distinct paths
   tags/actors/year/duration present on all 5
── check 5: search ↔ load agreement — titles agree; 2 poster-variant NOTEs
                                    (cover-t.jpg card vs cover-n.jpg og:image — same artwork, #680)
── check 6: LoadResponse fields — recommendations/tags/plot/duration/year/actors/posters all populated
RESULT: PASS
```

`old`, `tags` and `categories` are absent/unmapped surfaces, so no `--home-url` row
exists for them; the `categories`-analogue (makers) rows were resolved manually above.

In-app playback remains the maintainer's at merge: the runner can fetch the playlists and
segments, but a device-side decode of the `c.surrit.com` variants is out of reach for CI.
