# FINDINGS-1439 — HQPorner video sources (S1 of #1354)

Run: 2026-11-21 (`05-implement`, story **S1** of parent spec #1354). **Supersedes
#1436's ask for HQPorner** (the deep-tier queue issue named HQPorner and MissAV;
this story's video-source pass covers HQPorner end-to-end, so that half of #1436 is
paid for here — only MissAV's pass remains there).

Scope per the spec: the **video-source leg** (D2) across the D3 listing types —
`new`/`latest`, `old`, `popular`, `genres`, `categories`, `tags`. Listing
correctness (pagination/card fields) is out of scope beyond enumeration.

Site: `https://hqporner.com` — plain-curl tier throughout (no TLS tier needed; see
Phase 0). Pinned desktop UA for every request
(`Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) Gecko/20100101 Firefox/130.0`);
mobile UA 302s to `m.hqporner.com`, whose cards do not match. Video pages additionally
**404 without a Referer** (`Oops, page not found!` on a plain fetch, `200` with any
Referer) — every probe below sends `Referer: https://hqporner.com/`, as the provider does.

## Phase 0 — calibration (ran before any provider probing)

| canary | expected | observed (plain-curl) | reading |
|---|---|---|---|
| `film1k-challenge-pair` | plain 403 / TLS 200 | plain **200** / TLS unavailable | **expectation-stale** — open #1435; per the spec this run records the live pair and does **not** take the `environment-degraded` branch for this canary |
| `eporner-healthy` | plain 200 | plain **200** | match — instrument healthy |
| `pandamovies-redirect-origin` | plain 302 | plain **302 → https://pandamovies.org/** | match (redirect-following intact) |

```
$ curl -s -A "$UA" -o /dev/null -w '%{http_code} -> %{redirect_url}\n' https://www.film1k.com/
200 ->
$ curl -s -A "$UA" -o /dev/null -w '%{http_code} -> %{redirect_url}\n' https://www.eporner.com/
200 ->
$ curl -s -A "$UA" -o /dev/null -w '%{http_code} -> %{redirect_url}\n' https://pandamovies.pw/
302 -> https://pandamovies.org/
$ .pi/skills/site-probe/scripts/impersonate.sh https://www.film1k.com/
impersonate.sh: curl_cffi missing. Install it, then re-run:
  pip install curl_cffi
```

- **#1434 (open)**: the TLS-impersonated tier is unusable in this container →
  `escalation_tier: unavailable`. Only `Blocked` verdicts downgrade to `Suspected`;
  **this run had zero `Blocked` verdicts**, so nothing was downgraded.
- **#1435 (open)**: film1k's stale expectation is recorded, not treated as degradation.

## Listing enumeration (the D3 types as the site actually serves them)

`/hdporn/<n>` is the newest-first catalogue; it is also how the deep ends are reached
(no `?sort=` parameter exists). `new`, `popular`, `categories` and `tags` are pageable;
each page below was fetched with the desktop UA and card-counted.

| type | first page | last page | evidence |
|---|---|---|---|
| `new` / `latest` | `/hdporn/1` — 50 unique cards, Next → `/hdporn/2` | `/hdporn/1374` — 39 cards, no Next (oldest ids ≈ 751/755) | `/hdporn/1354` still has Next → `/hdporn/1355` |
| `old` | — | — | **no dedicated oldest-first listing**: `/old`, `/latest`, `/new`, `/?sort=oldest` all 404. The oldest end is the catalogue tail (`/hdporn/1374`), sampled there. |
| `popular` | `/top/1` (all-time) — 50 cards, Next → `/top/2` | `/top/1374` — 39 cards, no Next | `/top/week/1` 50 → Next, `/top/month/1` 50 → Next |
| `categories` | `/category/amateur/1` — 50 cards, Next → `/category/amateur/2` | `/category/amateur/52` — 22 cards, no Next | `/category/4k-porn/1` 50 → Next |
| `tags` | `/?q=creampie&p=1` — 50 cards, Next → `&p=2` | `/?q=creampie&p=161` — 43 cards, no Next | site's tag links are `/?q=<tag>` |
| `genres` | **absent** | **absent** | `/genres`, `/genre/<x>`, `/tags`, `/tag/<x>` all 404; the site models taxonomy as `Categories` (`/category/<slug>`) plus `Girls` (actress/studio). Recorded absent, never a finding. |

```
/hdporn/1        200 cards=50 unique=50 next=/hdporn/2
/hdporn/2        200 cards=50 unique=50 next=/hdporn/3
/hdporn/1354     200 cards=50 unique=50 next=/hdporn/1355
/hdporn/1373     200 cards=50 unique=50 next=/hdporn/1374
/hdporn/1374     200 cards=39 unique=39 next=None        ← catalogue end
/top/1           200 cards=50 unique=50 next=/top/2
/top/1374        200 cards=39 unique=39 next=None        ← popular end
/top/week/1      200 cards=50 unique=50 next=/top/week/2
/top/month/1     200 cards=50 unique=50 next=/top/month/2
/category/amateur/1   200 cards=50 unique=50 next=/category/amateur/2
/category/amateur/52  200 cards=22 unique=22 next=None   ← category end
/?q=creampie&p=1      200 cards=50 unique=50 next=/?q=creampie&p=2
/?q=creampie&p=161    200 cards=43 unique=43 next=None   ← tag end
```

Non-findings recorded for scope reasons (listing work is out of scope): the provider's
`mainPage` has no `new`/`latest` row (only `/top`, `/top/week`, `/top/month` and the
category rows) — the site does serve the newest-first listing, but adding a row is a
listing change, not a video-source fix, so it is **not** in this PR.

## Evidence rows

`type | sample URL | instrument tier | stream host | quality | verdict`

| type | sample URL | tier | stream host | quality | verdict |
|---|---|---|---|---|---|
| new | `/hdporn/128699-dont_involve_your_agent_in_this.html` (`/hdporn/1`) | plain-curl | mydaddy.cc → `s62.bigcdn.cc` | 360/720/1080p | **pass** |
| new | `/hdporn/128653-hotness_and_finesse.html` (`/hdporn/2`) | plain-curl | mydaddy.cc → `s45.bigcdn.cc` | 360/720/1080p | **pass** |
| new (mid-catalogue) | `/hdporn/65989-Twistys_-_wet_hottie_masturbating_under_the_shower.html` (`/hdporn/1100`) | plain-curl | **flyflv** `www.flyflv.com` → `video.flyflv.com` | 720p/360p | **FAIL → fixed** (host not selected) |
| new (mid-catalogue) | `/hdporn/65988-Twistys_-_gorgeous_aria_amor_babe_showing_her_ass.html` (`/hdporn/1100`) | plain-curl | **flyflv** → `video.flyflv.com` | 720p/360p | **FAIL → fixed** (host not selected) |
| old | `/hdporn/1308-MILF_and_shy_guy.html` (`/hdporn/1374`) | plain-curl | **hqwo.cc** | 360/720p | **FAIL → fixed** (no adapter row) |
| old | `/hdporn/1307-MILF_in_pantyhose.html` (`/hdporn/1374`) | plain-curl | **hqwo.cc** | 360/720p | **FAIL → fixed** (no adapter row) |
| old | `/hdporn/5623-Tess_Taylor_Arlington.html` (`/hdporn/1354`) | plain-curl | **hqwo.cc** | 360/720p | **FAIL → fixed** (no adapter row) |
| old | `/hdporn/5622-Tess_Taylor_Arlington---)))_720…html` (`/hdporn/1354`) | plain-curl | **hqwo.cc** | 360/720p | **FAIL → fixed** (no adapter row) |
| popular | `/hdporn/82041-seduced_by_my_stepmom.html` (`/top/1`) | plain-curl | mydaddy.cc → `s3.bigcdn.cc` | 360/720/1080p | **pass** |
| popular | `/hdporn/119039-excitement_overloaded.html` (`/top/1374`) | plain-curl | mydaddy.cc → `s81.bigcdn.cc` | 360/720/1080p | **pass** |
| popular | `/hdporn/128611-treat_me_as_you_wanna_be_treated_son.html` (`/top/week/1`) | plain-curl | mydaddy.cc → `s47.bigcdn.cc` | 360/720/1080p | **pass** |
| popular | `/hdporn/128512-wide_and_wet.html` (`/top/month/1`) | plain-curl | mydaddy.cc → `s35.bigcdn.cc` | 360/720/1080p | **pass** |
| categories | `/hdporn/128624-vampires_are_horny_on_Halloween.html` (`/category/amateur/1`) | plain-curl | mydaddy.cc → `s12.bigcdn.cc` | 360/720/1080p | **pass** |
| categories | `/hdporn/128614-mamasan_delivered_a_good_slut.html` (`/category/amateur/1`) | plain-curl | mydaddy.cc → `s29.bigcdn.cc` | 360/720/1080p | **pass** |
| categories | `/hdporn/38835-Catie_Minx_for_breakfast.html` (`/category/amateur/52`) | plain-curl | **hqwo.cc** | 360/720p | **FAIL → fixed** (no adapter row) |
| categories | `/hdporn/128688-pray_and_suffer_4K.html` (`/category/4k-porn/1`) | plain-curl | mydaddy.cc → `s77.bigcdn.cc` | 360/720/1080/**2160**p | **pass** |
| tags | `/hdporn/128689-im_better_for_you_than_any_other_girl.html` (`/?q=creampie&p=1`) | plain-curl | mydaddy.cc → `s47.bigcdn.cc` | 360/720/1080p | **pass** |
| tags | `/hdporn/8425-Creampie-Mature-_Viana.html` (`/?q=creampie&p=161`) | plain-curl | **hqwo.cc** | 360/720p | **FAIL → fixed** (no adapter row) |
| genres | — | — | — | — | **absent** (site exposes categories/girls only) |

Every row above is a *stream* verdict: the chain is video page → player iframe → player
body → mp4 anchors/source tags → `206 video/mp4` (Range 0-64). The hqwo/flyflv rows are
**FAIL as shipped** because the app never got that far — the provider's iframe selector
ignored flyflv and `loadExtractor` had no row for either host.

### Pre-fix dispatch evidence (why hqwo/flyflv produced zero links)

`loadExtractor` (pinned `com.lagradost:cloudstream3:pre-release` classes.jar, fetched
`bootstrapCloudstream`) iterates registered adapters last→first and dispatches on
`target.startsWith(adapter.mainUrl)`; only if nothing matches does it try the
Levenshtein mirror fallback, which requires `partialRatio > 80`. Probed on the JVM
against `sharedHostRegistry()` (138 rows):

```
hqwo player  https://hqwo.cc/player/b214…?img=x   prefix matches: []   max fuzzy: 71  (Streamwish@https://swhoi.com)
flyflv player https://www.flyflv.com/movies/player/5135  prefix: []   max fuzzy: 78  (HlsFree@https://www.hlsfree.com)
mydaddy      https://mydaddy.cc/video/a4f0…/      prefix: [MyDaddy]  max fuzzy: 100 (MyDaddy@https://mydaddy.cc)
```

So hqwo (fuzzy 71) and flyflv (fuzzy 78) both fall below the 80 threshold: **zero links**
emitted for those videos in the app. mydaddy is the only generation that resolved.

### Host census (how much of the catalogue is affected)

54 video pages sampled across `/hdporn/50 … /hdporn/1354` (2 cards per page):

| player host | videos | share |
|---|---|---|
| `mydaddy.cc` | 36 | 67% |
| `hqwo.cc` | 13 | 24% |
| `www.flyflv.com` | 5 | 9% |

The generations are ordered by age: newest uploads are mydaddy, the tail (oldest
catalogue) is hqwo, and flyflv sits in the mid/old band. Because the **tail of every
pageable type** is where hqwo lives, the defect hit `old`, the category ends and the tag
ends — i.e. every D3 type except `genres` (absent).

### Raw transcripts (non-pass rows)

hqwo (dispatch gap, streams themselves serve):

```
$ curl -sL -A "$UA" -e https://hqporner.com/ https://hqporner.com/hdporn/1308-MILF_and_shy_guy.html
200 … <iframe width="560" height="350" src="//hqwo.cc/player/57ed818e23cc700ce5ad08bf11c4a5c4?img=…" …
$ curl -sL -A "$UA" -e https://hqporner.com/ https://hqwo.cc/player/57ed818e23cc700ce5ad08bf11c4a5c4?img=…
200 … <a href='//hqwo.cc/pubs/6b01ec480e021/360.mp4' …> : <a href='//hqwo.cc/pubs/6b01ec480e021/720.mp4' …
$ curl -sL -A "$UA" -e https://hqporner.com/ -H 'Range: bytes=0-64' -o /dev/null -w '%{http_code} %{content_type} %{size_download}' \
    https://hqwo.cc/pubs/6b01ec480e021/360.mp4
206 video/mp4 65
```

flyflv (host not in the selector; source-tag grammar; CDN is referer-sensitive):

```
$ curl -sL -A "$UA" -e https://hqporner.com/ https://hqporner.com/hdporn/65989-Twistys_-_wet_hottie_masturbating_under_the_shower.html
200 … <iframe width="560" height="350" src="//www.flyflv.com/movies/player/5135" …
$ curl -sL -A "$UA" -e https://hqporner.com/ https://www.flyflv.com/movies/player/5135
200 … <source src='//video.flyflv.com/key=…,end=…,ip=…/buffer=1600K/hd/5135.mp4' type='video/mp4' label='720p' default/>
      <source src='//video.flyflv.com/key=…,end=…,ip=…/speed=89600/buffer=875K/sd/5135.mp4' type='video/mp4' label='360p' />
    … poster="https://static.flyflv.com/movies/005/315/5135/thumbs/12.jpg"
$ # referer sensitivity of the stream host (the fix emits the flyflv referer, not hqporner's):
ref=https://hqporner.com/      403 text/plain
ref=https://www.flyflv.com/   206 video/mp4
ref=(none)                    206 video/mp4
```

Bindings note: flyflv stream paths embed `ip=<requester>` — the app always fetches the
player page itself, so the token matches the app's IP.

## Confirmed conditions (fixed in this run)

1. **`hqwo.cc` player had no HostRegistry row** (`Correctness / data-completeness`).
   ~24% of the catalogue (older uploads) emitted zero links. Fix: `Hqwo` row in
   `shared/` subclassing `MyDaddyExtractor` (identical body grammar), pinned by
   `HostRegistryTest` and the shared parse fixture.
2. **`www.flyflv.com` player was not selected at all** (`Correctness / data-completeness`).
   ~9% of the catalogue (mid/old Twistys-era uploads) emitted zero links: the iframe
   selector only knew mydaddy/hqwo, and even if selected there was no adapter row.
   Fix: `PLAYER_IFRAME` selector adds `flyflv`, a shared `FlyFlv` adapter parses the
   source-tag grammar, and it emits the flyflv referer the stream CDN requires. The
   player body's `poster="…"` also now feeds the existing `PlayerPosterParse` for
   library loads (the same selector gap had left those posters null).

Both are provider-side coverage gaps, not site drift (the site has served these three
generations unchanged; only the resolution path missed them). They are fixed in-run per
D1, TDD-first:

- `HQPorner/src/test/resources/issue1439/hqwo_player.html` — real hqwo player body
  (2 quality anchors).
- `HQPorner/src/test/resources/issue1439/flyflv_player.html` — real flyflv player body
  (2 source tags + poster).
- red → green: `PlayerSourceParseTest` (8 tests) + the new `HostRegistryTest`
  "hqporner player generations have explicit registry rows" assertion (both failed on
  the pre-fix tree: unresolved Parse symbols / no rows).
- `shared/src/main/kotlin/com/kraptor/HostAdapters.kt`: `MyDaddyParse` (extracted from
  the old inline regex — it now also rejects non-URL anchors), `Hqwo`, `FlyFlvParse`,
  `FlyFlv`.
- `shared/src/main/kotlin/com/kraptor/HostRegistry.kt`: `Hqwo()`, `FlyFlv()` rows.
- `HQPorner/src/main/kotlin/com/kraptor/HQPorner.kt`: one `PLAYER_IFRAME` selector used
  by both `loadLinks` and `playerPoster`; `loadLinks` now counts real emissions and
  returns `matched && sources > 0` (fix-provider contract; the old code returned `true`
  unconditionally).
- `HQPorner/build.gradle.kts`: `version = 25`.
- No other provider references hqwo/flyflv, so only HQPorner bumps (the shared rows are
  additive for everyone else).

## Verification (delivery gate)

`./gradlew HQPorner:test` — green (147 tests incl. the 8 new parse tests and the shared
registry test). Clean `./gradlew HQPorner:make` — green (`HQPorner/build/HQPorner.cs3`).

`.pi/skills/verify-provider/scripts/verify.sh` — plain HTTP, desktop UA, site Referer.
Two invocations because the stream CDNs are referer-sensitive per generation (the global
`--header` applies to every fetch):

**Run A — hqporner Referer (mydaddy + hqwo samples)**

```
check 1 search  /?q=creampie and &p=2 → 200, 'div.6u section' 61 matches each, no dup cards
check 1a home   /top/1 and /top/2 → 200, 61 matches each
check 1b        no distinct quick-search endpoint (hasQuickSearch=false) — NOTE
check 2/3       128699, 128653, 82041, 128688, 128689 (mydaddy) + 1308 (hqwo)
                streams → 206 video/mp4 (six videos)
check 4 related 'div.4u section' 33-47 matches/page, no empty/dup/self cards
check 2a        tags/actors/duration present on all 6 sampled pages
check 5         search↔load agreement on 128689 (creampie tag landing)
check 6         posters/plot/tags/duration/actors/recommendations all populated
RESULT: PASS
```

**Run B — flyflv Referer (the third generation)**

```
check 1/1a      search + home PASS (same selectors)
check 2/3       65989 (flyflv) stream → 206 video/mp4, related 33 matches
check 2a/5/6    PASS (no poster on the *video page* — site doesn't expose it)
RESULT: PASS
```

Non-finding site behaviours recorded (observed, not filed):

- **4K pages intermittently serve a reduced body** (`~50 KB`, video + ads but **no
  related-videos grid**) vs the full body (`~225 KB`, 20 related cards). Run A's first
  attempt hit the reduced variant for `/hdporn/128688` and failed *only* the related
  check; the retry hit the full body and passed. Site-side rolling variant, not a
  selector defect (`recommendations` is simply empty on those responses).
- `/hdporn/<n>` never 404s past the catalogue end; out-of-range pages clamp to a
  smaller listing (39–50 cards) instead of an empty page.
- Video pages 404 without any Referer (anti-hotlink); the provider always sends one.
- flyflv videos get no *video-page* poster (no `og:image` anywhere on the site); the
  player-body poster path is now wired, which is the #518 behaviour for library loads.

## Findings lifecycle / dedup

Searched open **and** closed issues for `HQPorner`, `hqwo`, `flyflv`:
#1436 (deep-tier queue — superseded for HQPorner by this story), #518 (poster, closed),
#431 (hqwo player support, closed — the body grammar it added is what the new row
reuses), #527 (tags, closed), #818 (API-contract audit, closed), #116/#158/#237 (older
card/search drift, closed). **No standing issue describes hqwo/flyflv host coverage**, so
these are new conditions, fixed here rather than filed. `drift_confirmed` is recorded as
2 in the registry entry (two confirmed broken conditions fixed); `chronic` stays false
(below the 3+ threshold).

## Registry

`audits/findings.json` is **not** touched in this diff (ADR-0013). The provider entry +
one `last_runs` entry (`false_positives: []`) are posted as a
`<!-- findings-registry-entry -->` comment on the story issue for `registry_sync`.

Expected shape: verdict `ok-drift` (confirmed conditions found and fixed),
`tier: plain-curl`, `escalation_tier: unavailable` (#1434), `last_deep: 2026-11-21`,
`surfaces` as above.
