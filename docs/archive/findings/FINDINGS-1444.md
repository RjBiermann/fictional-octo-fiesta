# FINDINGS-1444 — Javseen video sources (S6 of #1354)

Run: 2026-11-22 (`05-implement`, story **S6** of parent spec #1354). Scope per the spec: the
**video-source leg** (D2) across the D3 listing types — `new`/`latest`, `old`, `popular`,
`genres`, `categories`, `tags` — ≥2 video pages per type, sampled at both catalogue ends where
the type is pageable, with `loadLinks` resolved end-to-end. Listing correctness
(pagination/card-field correctness, #632–#659) and *rows* work are out of scope.

Site: `https://javseen.tv` — **plain-curl on every surface**, desktop UA
(`Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0`). All listings are
AJAX JSON (`{"status":1,"html":"<li id=\"video-…\">…"}`); the shell HTML has no server-rendered
cards. Runner IP country: **US** (Cloudflare edge `IAD` on the embed/avgle probes; no
geo-gate observed).

Registry at start: `last_deep` 2026-10-13, `drift_confirmed` 0, verdict `ok`, standing issue
#477 (closed, search-shell condition). Dedup searched open **and** closed issues for this
provider: #1444 (this spec), #1430 (open, MissAV+JavSeen *rows* — out of scope), #822/#650/#631
(closed prior audits), #539 (closed duration sentinel), #477 (closed search shell), #208/#209
(closed listing/actor fixes), #145/#118 (closed embed-host drift), #31 (closed). No standing
issue covers the condition found in this run.

## Phase 0 — calibration (ran before any provider probing)

| canary | expected | observed (plain-curl) | reading |
|---|---|---|---|
| `film1k-challenge-pair` | plain 403 / TLS 200 | plain **200** / TLS unavailable | **expectation-stale** — open #1435; this run records the live pair and does **not** take the `environment-degraded` branch |
| `eporner-healthy` | plain 200 | plain **200** | match — instrument healthy |
| `pandamovies-redirect-origin` | plain 302 / TLS 200 | plain **302 → https://pandamovies.org/** | match (redirect-following intact) |
| `javseen.tv` (site itself) | — | plain **200** (63 328 B home) | probe tier available; no challenge |

```
$ UA="Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0"
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.film1k.com/
200
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.eporner.com/
200
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://pandamovies.pw/
302 https://pandamovies.org/
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://javseen.tv/
200
$ .pi/skills/site-probe/scripts/impersonate.sh https://www.film1k.com/
impersonate.sh: curl_cffi missing for python3. Install it, then re-run:
```

- **#1434 (open)** — TLS-impersonated tier unusable → `escalation_tier: unavailable`. Only
  `Blocked` verdicts downgrade to `Suspected`; **this run produced zero `Blocked` verdicts**.
- **#1435 (open)** — film1k's stale expectation is recorded, not treated as degradation.
- No genuine `environment-degraded` mismatch → the instrument is healthy.

## Listing enumeration (the D3 types as the site actually serves them)

All counts below are AJAX JSON card counts (`<li id="video-N">`). Page-1 vs page-2
intersections were re-checked on each pageable axis.

| type | first page | far end | evidence |
|---|---|---|---|
| `new` / `latest` | `/recent/?ajax=browse_videos` — 30 cards (290349…290320) | `/recent/9653/?ajax=browse_videos` — 19 cards (oldest uploads, `video-19`…`video-1`) | `/recent/2/` 30 cards (290319…290290); p1∩p2 = **0**; pagination last page 9653 |
| `old` | — | — | **absent as an exposed listing type**: `/old/` **404**, `/oldest/` **404**; the sort menu (popular, releaseday, rated, discussed, downloaded, longest, watched) has no oldest/ascending control; `/recent/?o=oldest` is the ignored-parameter shell (no cards). Recorded `absent` per D3, never a finding |
| `popular` | `/popular/?ajax=browse_videos` — 30 cards (698, 1070, 897, …) | `/popular/9653/?ajax=browse_videos` — 19 cards (290335…290353) | `/popular/2/` 30 cards (996, 1484, …); p1∩p2 = **0**; all-time/today/week/… tabs are the sort vocabulary |
| `genres` | video pages expose `Genre:` → bare-slug taxonomy (`/solowork/`, `/mature-woman/`, `/mosaic/`); `/solowork/?ajax=category_videos` — 30 cards | `/solowork/recent/3843/?ajax=category_videos` — 28 cards (12176…12135) | `/solowork/recent/2/` 30 cards (290300…290250), p1∩p2 = **0**; `/mosaic/recent/2/` 30 cards. The same route family also backs the category index (below) |
| `categories` | `/categories/` index — 79 `li[id^=category-]` entries; `/amateur/?ajax=category_videos` — 30 cards (290348…290224) | `/amateur/recent/1826/?ajax=category_videos` — 19 cards (24596…29018) | `/amateur/recent/2/` 30 cards (290224…290077); p1∩p2 = **1** (290224 is site-side repeated at the page edge — listing correctness out of scope); requests past the last page clamp to 1826 |
| `tags` | `/tag/uncensored/?ajax=browse_videos` — 30 cards (290298…289335); `/tag/mature-woman/?ajax=browse_videos` — 30 cards (290351…) | `/tag/uncensored/?ajax=browse_videos&page=396` — 4 cards (68254…68004); `/tag/mature-woman/?ajax=browse_videos&page=1459` — 17 cards (217…129) | page 2 via `?page=2` 30 cards (289282…288355); the response's own `next_url` now spells `/tag/uncensored/2/`, which also returns 200 — the shipped `?ajax=browse_videos&page=N` form still works (no change needed) |

```
/recent/ p1 30 · p2 30 (∩ 0) · /recent/9653/ 19 (last)
/popular/ p1 30 · p2 30 (∩ 0) · /popular/9653/ 19 (last)
/solowork/ p1 30 · p2 30 (∩ 0) · /solowork/recent/3843/ 28 (last)
/amateur/ p1 30 · p2 30 (∩ 1 edge item) · /amateur/recent/1826/ 19 (last)
/tag/uncensored/ p1 30 · p2 30 · p396 4 (last)
/tag/mature-woman/ p1 30 · p1459 17 (last)
/old/ 404 · /oldest/ 404
```

`genres` and `categories` converge on the same bare-slug route family (`/categories/` indexes
the genre routes; watch pages label them `Genre:`), while `tags` is a distinct `/tag/<slug>/`
taxonomy exposed as the watch page's `tags:` row. That is site taxonomy, not a provider defect —
both types were sampled through their own surfaces.

## Evidence rows

`type | sample URL | instrument tier | stream host | quality | verdict`

Every row was resolved the way `loadLinks` does it: watch page → base64 `data-embed` →
embed host → adapter → stream. `quality` is the ladder the master playlist exposes (the site
does not expose a quality label in `load()`).

| type | sample URL | tier | stream host | quality | verdict |
|---|---|---|---|---|---|
| `new` / `latest` (p1) | `/290349/mosaic-hyk-025-paragraph-v-declaration-transformation-maki-hojo/` | plain-curl | `cloudwish.xyz` → `/stream/…/master.m3u8` (also `mycloudz.cc` → `acek-cdn`) | 480/720/1080 | **pass** |
| `new` / `latest` (last p9653) | `/1/temptation-tour-out-in-your-sister-in-laws-relatives-of/` | plain-curl | `mycloudz.cc` → `acek-cdn.com` hls2 | 720p | **pass** |
| `old` | — | — | — | — | **absent** (no exposed oldest sort; `/old/` 404) |
| `popular` (p1) | `/698/watchhd-e3hdh9lalv-jufd-744-studio-fitch-sandwiched-between-the-mother-and-daughter-sleeping-in-the-shape-of-lust/` | plain-curl | `cloudwish.xyz` (hls2 only) → `cdn-centaurus.com` | — | **Suspected** (CDN timeout from runner; no hls4 variant) |
| `popular` (p1) | `/1070/star-782-studio-sod-create-i-became-small-and-my-longing-wife-shiraishi-mariana/` | plain-curl | `cloudwish.xyz` (hls2 only) → `premilkyway.com` | — | **Suspected** (403 from runner; DNS/TLS reachable) |
| `popular` (last p9653) | `/290335/mosaic-jur-062-diamond-newcomer-nana-miura-32-years-old-av-debut-a-100-carat-beauty-shining-beautifully-and-seductively-blu-ray-disc/` | plain-curl | `cloudwish.xyz` → `/stream/…/master.m3u8`; `mycloudz.cc` → `acek-cdn` | 480/720/1080 (cloudwish) | **pass** |
| `popular` (last p9653) | `/290353/mosaic-mida-844-i-want-to-feel-good-with-you-momo-inoue-will-gently-tease-you-with-whispered-sweet-dirty-talk-and-a-cute-cunning-blowjob-providing-a-healing-brain-melting-masturbation-support/` | plain-curl | `javtiful.com/embed` → signed `cloudflarestorage` mp4 | 720p | **pass after fix** (pre-fix: only source had no registry row → zero links) |
| `genres` (`solowork` p1) | `/290351/mosaic-jura-210-first-time-filming-of-a-married-woman-chapter-two-shiori-narumiya/` | plain-curl | `cloudwish.xyz` → `/stream/…/master.m3u8` | 480/720/1080 | **pass** |
| `genres` (`solowork` last p3843) | `/12176/prtd-008-bondage-drug-agent-2-hours-to-rescue-i-will-never-give-up-sho-nishino/` | plain-curl | `mycloudz.cc` → `acek-cdn.com` hls2 | 480p | **pass** |
| `genres` (`solowork` last p3843) | `/12135/snis-619-breasts-are-porori-bibi-saotome-s1-no-1-style/` | plain-curl | `filemoon.sx` → redirected to DoodStream Turnstile; `playmogo.com` Turnstile | — | **Suspected** (host-side challenge, D8) |
| `genres` (`mosaic` p2) | `/290290/mosaic-ssni-210-golden-ratio-rich-muscle-body-miyu-yanagi/` | plain-curl | `cloudwish.xyz` → `/stream/…/master.m3u8` | 480/720/1080 | **pass** |
| `categories` (`amateur` p1) | `/290348/sgsr-382-big-boobs-specialty-convenient-for-you-who-can-t-get-away-from-boobs-4-hours-of-extremely-loose-housewives-who-can-be-fucked-12-people/` | plain-curl | `mycloudz.cc` → `acek-cdn.com`; `cloudwish.xyz` → `/stream/…` | 720p (mycloudz) / 480-1080 (cloudwish) | **pass** |
| `categories` (`amateur` last p1826) | `/18619/hawa-132-studio-kosumosu-eizou-please-do-as-much-as-you-like-in-me-too-tender-amateur-wife-keeps-her/` | plain-curl | `mycloudz.cc` → `acek-cdn.com` hls2 | 360p | **pass** |
| `categories` (`amateur` last p1826) | `/24596/srs-068-studio-prestige-a-slut-documentary-little-miyu-a-maid-cafe-employee-file-08-08/` | plain-curl | `playmogo.com` only → Turnstile | — | **Suspected** (host-side challenge, D8) |
| `tags` (`uncensored` p1) | `/290298/10musume-10-032826-01-a-former-caregiver-with-a-generous-bust-and-a-strong-sense-of-service-a-busty-former-caregiver-with-a-heart-full-of-dedication/` | plain-curl | `cloudwish.xyz` → `/stream/…/master.m3u8` | 480/720/1080 | **pass** |
| `tags` (`mature-woman` p1) | `/290350/english-sub-pfas-032-reiwa-the-swap-couple-swapping-married-woman-sister-swapping-alliance/` | plain-curl | `mycloudz.cc` → `dramiyos-cdn.com` hls2 | 720p | **pass** |
| `tags` (`uncensored` last p396) | `/68004/watch-cwdv-24-catwalk-poison-dv-24-dirty-idol-hitomi-oki/` | plain-curl | `avgle.com/embed` → **404** (host-side) | — | **Suspected** (dead embed host, not an adapter gap) |
| `tags` (`mature-woman` last p1459) | `/217/venu-692-studio-venus-relatives-phase-sweat-feel-hot-body-stuffy-uterus-parent-child-of-instinct-tha/` | plain-curl | `cloudwish.xyz` hls2 / `mycloudz.cc` → CDN timeout; `playmogo.com` Turnstile | — | **Suspected** (host/CDN-side) |

Representative pass transcript (`/290349/`, new p1):

```
$ curl -s -A "$UA" https://javseen.tv/290349/mosaic-hyk-025-paragraph-v-declaration-transformation-maki-hojo/ \
    | grep -o 'data-embed="[^"]*"' | head -1
data-embed="aHR0cHM6Ly9jbG91ZHdpc2gueHl6L2UveXRyZW5vOGxkeGt3"
$ echo aHR0cHM6Ly9jbG91ZHdpc2gueHl6L2UveXRyZW5vOGxkeGt3 | base64 -d
https://cloudwish.xyz/e/ytreno8ldxkw
$ curl -s -A "$UA" -e https://javseen.tv/ https://cloudwish.xyz/e/ytreno8ldxkw \
    | grep -o 'var links={[^;]*'
var links={"hls4":"/stream/YJjuEQnsWDRfoIFS8XEb-Q/kjhhiuahiuhgihdf/1795399668/70328231/master.m3u8", …}
$ curl -s -o /dev/null -w '%{http_code} %{content_type}\n' -A "$UA" \
    -e https://javseen.tv/ https://cloudwish.xyz/stream/YJjuEQnsWDRfoIFS8XEb-Q/kjhhiuahiuhgihdf/1795399668/70328231/master.m3u8
200 application/vnd.apple.mpegurl
# master playlist: RESOLUTION=852x480 / 1280x720 / 1920x1080
```

The provider's two live decode paths were both exercised: `load()` base64-decodes the
`button.button_choice_server` rows into the JSON `episodes` payload, and `loadLinks` re-decodes
the same anchors; the samples above use that decoded embed list.

## Confirmed condition (fixed in this run)

`javtiful.com/embed/<id>` had **no `HostRegistry` row**, and some catalogue videos carry it as
their **only** source — `/290353/` (popular, last page) is a one-embed page:

```
$ curl -s -A "$UA" https://javseen.tv/290353/…/ | grep -o 'data-embed="[^"]*"'
data-embed="aHR0cHM6Ly9qYXZ0aWZ1bC5jb20vZW1iZWQvMTE1MjY1"
$ echo aHR0cHM6Ly9qYXZ0aWZ1bC5jb20vZW1iZWQvMTE1MjY1 | base64 -d
https://javtiful.com/embed/115265
```

Before the fix, `loadExtractor` matched no adapter, so `loadLinks` emitted **zero** links for
that video. The embed is a live watch-config JSON block whose `playback` endpoint resolves a
signed mp4:

```
$ curl -s -A "$UA" -e https://javseen.tv/ https://javtiful.com/embed/115265 \
    | grep -o '<script id="watch-config"[^>]*>.*</script>' | head -c 220
<script id="watch-config" type="application/json">
    {…,"playback":{"endpoint":"/video/115265/playback","ticket":"p1.115265.0.…"},…}
$ curl -s -A "$UA" -e https://javtiful.com/embed/115265 \
    -X POST https://javtiful.com/video/115265/playback \
    --data-urlencode 'ticket=p1.115265.0.…' -H 'Origin: https://javtiful.com'
{"success":true,"sources":[{"src":"/media/video/115265/720p?expires=…&signature=…",
  "type":"video/mp4","size":720,"label":"720p"}],"defaultQuality":720}
$ curl -sL -o /dev/null -w '%{http_code} %{content_type}\n' -H 'Range: bytes=0-1024' \
    -A "$UA" -e https://javtiful.com/embed/115265 \
    'https://javtiful.com/media/video/115265/720p?expires=…&signature=…'
206 video/mp4
```

Fix, TDD-first red → green at a pure Parse seam (no listing/search change):

- `shared/.../JavtifulEmbed.kt` — `JavtifulEmbedParse.playback(html)` extracts
  `playback.endpoint`/`ticket` from the watch-config JSON; `JavtifulEmbedParse.sources(json)`
  extracts `src`/`type`/`size` from the playback response. `JavtifulEmbed` POSTs the ticket and
  emits the signed source (720p quality, `VIDEO` type). Registered in `shared/HostRegistry.kt`
  as `https://javtiful.com/embed` — the single row that answers the prefix.
- Fixtures: `shared/src/test/resources/javtiful_embed_watch_config.html` and
  `javtiful_playback.json` (live captures, 2026-11-22).
- Tests: `shared/src/test/kotlin/com/kraptor/JavtifulEmbedParseTest.kt` — 4 parse tests plus a
  registry-dispatch test. **Red** before the functions existed (`UNRESOLVED_REFERENCE` at
  `JavtifulEmbedParse`), **green** after (5/5 in `Javseen:test`).
- `Javseen/build.gradle.kts` `version` **30 → 31** (a shared-row change only reaches users
  through a provider rebuild; Javseen is the only provider whose pages emit this host).

## Scope notes (not conditions)

- **`old` is absent site-side** — no route (`/old/`, `/oldest/` 404) and no ascending sort in
  the menu. `/recent/9653/` reaches the oldest uploads only as the far end of `new`.
- **Popular p1 and some far-end tags videos are CDN/timeout-Suspected from this runner**:
  `cdn-centaurus.com` times out, `premilkyway.com` 403s for hls2-only pages, `dramiyos-cdn.com`
  intermittently times out, and `playmogo.com`/`filemoon.sx` serve Turnstile. None is a
  registered-adapter gap — the hosts are registered or challenge the datacenter IP (D8).
- **`avgle.com/embed/<id>` is dead host-side**: every sampled embed (68004, 68016, 68069)
  answers **404** while `https://avgle.com/` answers 200. No adapter can fix a 404; recorded
  Suspected, never a finding.
- **`mixdrop.nu` does not resolve** (no DNS) on the sampled far-end video `/12137/`; host-side,
  not an adapter gap.
- **`f4splay.vip`** (observed once, `/290351/`) has no row; its `/api/play/<id>` answered
  **403 `adblock:true`** to plain curl, so it is not resolvable from this tier and the video
  has three registered alternatives. Recorded as observed, not a confirmed condition.
- **Tags pagination shape changed in the response** (`next_url` now `/tag/<slug>/<n>/`), but the
  shipped `?ajax=browse_videos&page=N` returns the same page-2 cards; no provider change.
- **Related videos are absent** (commented-out placeholder, unchanged since #145/#650); the
  provider's empty `recommendations` is correct.
- **Quick search is absent** (no suggest endpoint; unchanged since #145) — `hasQuickSearch`
  stays false.
- **Actors** are only in the description meta (regex already shipped), **tags/year/duration**
  are populated and present on all six verify samples; no data-completeness gap found.
- **No DRM, geo-wall or challenge** was observed on a video page from this runner; only the
  embed CDNs above were instrument-limited.

## Verification (delivery gate)

- `./gradlew Javseen:test` — **green** (shared suite + `JavtifulEmbedParseTest` 4/4 +
  `JavtifulEmbedRegistryTest` 1/1, `HostRegistryTest` incl. the new row's uniqueness/shadowing
  invariants).
- `./gradlew Javseen:make` — clean `Javseen.cs3` at **version 31**.
- `.pi/skills/audit-providers/scripts/check-findings.sh` — **PASS**.
- `.pi/skills/verify-provider/scripts/verify.sh` — video/stream/field checks **pass**, with the
  known instrument limitation recorded by the #650 deep pass: listing endpoints are
  JSON-escaped AJAX payloads, so the CSS harness sees 0 cards on search/home even though the
  endpoints are healthy (manual transcripts above: 30 cards/page, p1∩p2 = 0 on `recent` and
  `popular`, 29/30 new on `amateur`). Stream checks: **6/6** overrides served — 4 ×
  `cloudwish.xyz` `200 application/vnd.apple.mpegurl` (480/720/1080 masters), 1 ×
  `mycloudz`→`acek-cdn` `200 application/vnd.apple.mpegurl` (720p), 1 × javtiful signed mp4
  `206 video/mp4` (720p). Field checks: tags/actors/year/duration present on all 6 sampled
  pages; `LoadResponse` fields populated in Kotlin.
- In-app playback stays the maintainer's at merge.

## Registry write

The provider entry and one `last_runs` entry (with `false_positives: []`) are posted as a
comment on #1444 beginning `<!-- findings-registry-entry -->`, not in this diff (ADR-0013).
