# FINDINGS-1460 — Xhamster video sources (S22 of #1354)

Video-source leg (D2) of the Xhamster spec (`xhamster.com`). Scope: for every D3 listing
type (`new`/`latest`, `old`, `popular`, `genres`, `categories`, `tags`) open **≥2 video
pages**, sampled at both ends of the catalogue where the type is pageable, and resolve
`loadLinks` **end-to-end**: stream URL reachable (200/206), embed host identified and
registered, quality passed to CloudStream where the site exposes it.

Provider state at run start: registry `last_deep` = 2026-10-16, `drift_confirmed` = 0,
verdict at last deep = `ok` (#635), provider `version` = 39.

**Result: no confirmed condition. Verdict `ok`, 0 findings filed, no code change.**
Every sampled video on every D3 type resolves to an `xhcdn.com` HLS master that serves
`206 application/vnd.apple.mpegurl` through the provider's existing mobile-UA
`xplayerSettings.sources.standard` + `decodeXhUrl` path. There is **no third-party embed
host** on any sampled page — the player is the site's own xplayer reading its own CDN, so
`shared/`'s `HostRegistry` is not on this path. Per-quality direct MP4 tiers are the known
IP-bound class (`403 text/plain "Wrong key"` from the runner; the `auto` h264/av1 HLS masters
carry the same renditions), not provider drift.

Run: `05-implement` for #1460, 2026-11-25, **plain-curl** on every surface, runner IP
US (Azure), desktop UA
`Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0`,
`Cookie: video_titles_translation=0`, desktop pages `?geo=us`.

Out of scope per spec: a full listing audit (pagination/card-field correctness was
#632–#659) and *rows* work. `drift_confirmed` was 0 and enumeration stayed intact, so the
listing layer was probed only as far as the D3 types needed.

---

## Phase 0 — canary calibration (before touching the site)

| canary | expected | observed | reading |
|---|---|---|---|
| `eporner-healthy` | plain-curl 200 | plain-curl **200** (316 847 B real homepage) | match — instrument healthy |
| `film1k-challenge-pair` | plain-curl 403, tls-impersonated 200 | plain-curl **200** (216 607 B) | **expectation-stale** (#1435 open) — live pair recorded here; **no** `environment-degraded` branch taken |
| `pandamovies-redirect-origin` | plain-curl 302, tls-impersonated 200 | plain-curl **302 → https://pandamovies.org/** (143 B) | plain-tier match |
| site under audit | — | plain-curl **200**, 350–449 kB, content-bearing | probe tier available; no challenge on any probed path |

```
$ UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/130.0.0.0 Safari/537.36'
$ curl -s -o /dev/null -A "$UA" -w '%{http_code} %{size_download}\n' https://www.eporner.com/ → 200 316847
$ curl -s -o /dev/null -A "$UA" -w '%{http_code} %{size_download}\n' https://www.film1k.com/  → 200 216607
$ curl -s -o /dev/null -A "$UA" -w '%{http_code} %{redirect_url}\n' https://pandamovies.pw/    → 302 https://pandamovies.org/
$ curl -s -o /dev/null -A "$UA" -w '%{http_code} %{size_download}\n' https://xhamster.com/newest/1?geo=us → 200 390749
$ python3 -c 'import curl_cffi'
ModuleNotFoundError: No module named 'curl_cffi'
```

- **TLS-impersonated tier is not provisioned in this container** (`curl_cffi` missing;
  `scripts/impersonate.sh` exits 2). #1434 is closed but the instrument is absent here, so
  `escalation_tier: unavailable`. This run produced **zero** `Blocked` verdicts — nothing was
  downgraded, and no challenge evidence was needed.
- **#1435 (open)** — film1k's stale expectation is recorded, not treated as degradation.
- No genuine `environment-degraded` mismatch → instrument healthy.

---

## Listing-type inventory (D3)

Cards are SSR `div[data-video-type=video]` (46 per page on every listing surface; the provider
reads the same cards from `window.initials`). Pagination is a path segment `/<n>`; the last
page returns a short list, the next one 404s.

| type | surface | ends sampled | evidence |
|---|---|---|---|
| `new` / `latest` | **`/newest/`** | p1 and p2, plus catalogue tail p1799 | p1 **46** cards, p2 **46**, p1∩p2 = **0**; p1799 **4** cards, p1800 **404** |
| `old` | **no ascending-sort route**: `/oldest/` **404**; `/newest/?sort=oldest` is accepted but returns the same descending `order:"added"` list (no `orderList` alternative; only `Trending`/`Best`) | catalogue tail of `new` (`/newest/1799`, `/newest/1700`) sampled as the old end | p1799 **4**, p1700 **46**, overlap **0** |
| `popular` | **`/most-viewed/`** (weekly/monthly/all-time variants in the nav) | p1 and last page p10 | p1 **46**, p10 **46**, overlap **0**; p11 **404** |
| `genres` | **`absent`**: `/genres/` and `/genres` **404**. The site models subject taxonomy as `/categories/` + `/tags/` only — no genre vocabulary in any card/nav | — | `/categories` 200 (index), `/tags` 200 (index); transcripts below |
| `categories` | **`/categories/<name>`** | `/categories/teen/` p1 and p1000 | p1 **46**, p1000 **46**, overlap **0** (deep pages genuinely page, not clamped) |
| `tags` | **`/tags/<tag>/`** | `/tags/teen/` p1 and p1000 | p1 **46**, p1000 **46**, overlap **0** (a different tail than the category route) |

```
$ curl -s ... https://xhamster.com/newest/1?geo=us                  → 200 390749  cards=46
$ curl -s ... https://xhamster.com/newest/2?geo=us                  → 200 362297  cards=46   (overlap p1: 0)
$ curl -s ... https://xhamster.com/newest/1799?geo=us               → 200 246350  cards=4
$ curl -s ... https://xhamster.com/newest/1800?geo=us               → 404
$ curl -s ... https://xhamster.com/oldest/?geo=us                   → 404 281915
$ curl -s ... 'https://xhamster.com/newest/?sort=oldest&geo=us'     → 200 389022  cards=46  (order:"added", same descending list)
$ curl -s ... https://xhamster.com/most-viewed/1?geo=us             → 200 372229  cards=46
$ curl -s ... https://xhamster.com/most-viewed/10?geo=us            → 200 370297  cards=46   (overlap p1: 0)
$ curl -s ... https://xhamster.com/most-viewed/11?geo=us            → 404
$ curl -s ... https://xhamster.com/categories/teen/1?geo=us         → 200 449359  cards=46
$ curl -s ... https://xhamster.com/categories/teen/1000?geo=us      → 200 421788  cards=46   (overlap p1: 0)
$ curl -s ... https://xhamster.com/tags/teen/1?geo=us               → 200 443664  cards=46
$ curl -s ... https://xhamster.com/tags/teen/1000?geo=us            → 200 423854  cards=46   (overlap p1: 0)
$ curl -s ... https://xhamster.com/genres/?geo=us                   → 404 282390
$ curl -s ... https://xhamster.com/genres?geo=us                    → 404 281918
```

Quick search: **absent** (unchanged) — `/xhr/search/suggest?q=teen` and
`/search-suggest?q=teen` both **404**; the provider correctly keeps `hasQuickSearch` unset.

---

## Evidence rows — video-source resolution per type

One row per sampled video. "stream host" is the decoded player CDN; there is no embed host
on the path. Quality = the site's own label ladder exposed in `xplayerSettings.sources.standard`
(`auto` is the multi-variant HLS master; per-quality entries are the direct MP4 tiers).

| type | sample URL | instrument tier | stream host | quality | verdict |
|---|---|---|---|---|---|
| new/latest | `/videos/we-were-justkids-1-reagon-know-how-to-give-a-blowjob-xhrFctM` | plain-curl (desktop+mobile) | video-nss-h.xhcdn.com (HLS master) | auto 1080p/720p/480p/240p/144p + 720p/480p/240p/144p MP4 | pass |
| new/latest | `/videos/my-stepmom-has-a-boyfriend-s1e4-xh6pJQh` | plain-curl | video-nss-h.xhcdn.com | auto 1080p…144p + 720p…144p MP4 | pass |
| old (tail) | `/videos/malayali-wife-hot-anal-hole-deep-lick-xhp22Sv` | plain-curl | video-nss-h.xhcdn.com | auto + av1 auto + 720p…144p MP4 | pass |
| old (tail) | `/videos/bare-feet-on-slaves-face-foot-sniffing-domination-xhSEsrA` | plain-curl | video-nss-h.xhcdn.com | auto + av1 auto + 720p…144p MP4 | pass |
| popular | `/videos/sexy-sudipa-apne-student-ko-sex-karna-sikhate-hai-xhfN6db` | plain-curl | video-h.xhcdn.com (HLS master) | auto + av1 auto + 720p…144p MP4 | pass |
| popular | `/videos/hot-girl-seduced-by-the-young-man-xhOBp1p` | plain-curl | video-nss-h.xhcdn.com | auto + av1 auto + 720p…144p MP4 | pass |
| categories | `/videos/the-8th-wonder-her-perfect-ass-gets-anal-sex-and-anal-creampie-xhhwrGY` | plain-curl | video-nss-h.xhcdn.com | auto 2160p/1080p…144p + av1 auto + 720p…144p MP4 | pass |
| categories | `/videos/susanne-lets-her-boyfriend-do-anal-for-the-first-time-xh2TF0r` | plain-curl | video-nss-h.xhcdn.com | auto + av1 auto + 720p…144p MP4 | pass |
| tags | `/videos/this-redhead-slut-loves-to-be-fucked-by-her-stepbrother-xhuCtG0` | plain-curl | video-nss-h.xhcdn.com | auto + av1 auto + 720p…144p MP4 | pass |
| tags | `/videos/my-cousin-is-friend-sex-homemade-part-continue-xheB4Jp` | plain-curl | video-nss-h.xhcdn.com | auto + av1 auto + 480p…144p MP4 | pass |

Player transcript shape (desktop and mobile both carry it; `loadLinks` uses the mobile page):

```
$ curl -s ... -A '<mobile Chrome/130>' 'https://xhamster.com/videos/hot-girl-seduced-by-the-young-man-xhOBp1p?geo=us'
  → 200, window.initials.xplayerSettings.sources.standard:
      h264[0]  quality=auto   url=07… (algo-6 hex)  fallback=04… (algo-4)
      h264[n]  quality=144p/240p/480p/720p          direct MP4 (key=…,limit=3)
      av1[0]   quality=auto
  provider decodeXhUrl(auto h264) →
      https://video-nss-h.xhcdn.com/-r79OHBqU6IavJAAvWrpFw==,1795640400/media=hls4/multi=256x144:144p,426x240:240p,854x480:480p,1280x720:720p,1920x1080:1080p/027/615/563/_TPL_.h264.mp4.m3u8
  $ curl -s -r 0-1023 '<that url>'  → 206 application/vnd.apple.mpegurl
      #EXTM3U
      #EXT-X-STREAM-INF:...RESOLUTION=256x144,CODECS="mp4a.40.2,avc1.4d4015"
      #EXT-X-STREAM-INF:...RESOLUTION=426x240,...
      #EXT-X-STREAM-INF:...RESOLUTION=854x480,...
      #EXT-X-STREAM-INF:...RESOLUTION=1280x720,...
      #EXT-X-STREAM-INF:...RESOLUTION=1920x1080,...
```

Direct per-quality MP4 tiers decode correctly but answer the runner `403 text/plain`
(9 B `Wrong key`): `key=`/`data=<ip>-mv` tokens are IP-bound at render time. This is the
recorded #338/CDN class — the same renditions are inside the `auto` HLS master, which is
what the provider's `auto` entry emits and what plays in-app. Not drift.

---

## Player / host registration

- **No third-party embed host**: the video page has no player iframe — the site's own
  xplayer reads `xplayerSettings` from `window.initials`, so `HostRegistry` dispatch never
  runs for Xhamster. Nothing to add in `shared/`.
- Stream host: `video-nss-h.xhcdn.com` / `video-h.xhcdn.com` (site CDN), no Referer needed.
- Quality: `auto` → `Qualities.Unknown` (400, multi-variant master); numeric `NNNp` labels →
  their resolution Int, exactly the site ladder. No suffix-label trap.

## Headers / referer

Search/home/load: desktop UA + `Cookie: video_titles_translation=0` + `?geo=us`.
`loadLinks`: mobile Chrome/130 UA (unchanged, still populates `standard`). Decoded CDN
m3u8 serves with the mobile UA and `Referer: <video url>` (provider sets it).

## Pagination

`/<n>` path segment per listing; p1/p2 and p1/last sets are disjoint on every sampled type
(see table). `/newest/` tail = p1799 (4 cards), p1800 = 404. Related videos: single page per
video page (11 `div[data-role=related-item]`, provider reads the JSON `videoThumbProps`
directly; the DOM anchors are present and carry the title text).

## Field exposure (this leg only)

Live pages still expose what `load()` parses: `videoEntity.title/description/thumbBig/
pornstarModels`, `videoModel.duration/created`, tags
`div[data-role='video-tags-list'] a[href*='/categories/']`, related
`videoPageComponent.relatedVideos…videoThumbProps`. Year comes from `videoModel.created`
(the S25/#834 fix). No new field gap on the video-source path.

---

## verify.sh (mechanical gate)

```
$ .pi/skills/verify-provider/scripts/verify.sh \
    --search-url 'https://xhamster.com/search/wonder/?page=1&x_platform_switch=desktop&geo=us' \
    --search-url 'https://xhamster.com/search/wonder/?page=2&x_platform_switch=desktop&geo=us' \
    --search-selector 'div[data-video-type=video]' \
    --search-title-selector 'a.video-thumb-info__name' \
    --search-poster-selector 'img.thumb-image-container__image' \
    --home-url 'https://xhamster.com/newest/1?geo=us' \
    --home-url 'https://xhamster.com/newest/2?geo=us' \
    --home-selector 'div[data-video-type=video]' \
    --video-url 'https://xhamster.com/videos/hot-girl-seduced-by-the-young-man-xhOBp1p' \
    --stream-url 'https://video-nss-h.xhcdn.com/-r79OHBqU6IavJAAvWrpFw==,1795640400/media=hls4/multi=256x144:144p,426x240:240p,854x480:480p,1280x720:720p,1920x1080:1080p/027/615/563/_TPL_.h264.mp4.m3u8' \
    --video-url 'https://xhamster.com/videos/the-8th-wonder-her-perfect-ass-gets-anal-sex-and-anal-creampie-xhhwrGY' \
    --stream-url 'https://video-nss-h.xhcdn.com/G2-JxaeHPCooi8ZIAJZz7g==,1795640400/media=hls4/multi=256x144:144p,426x240:240p,854x480:480p,1280x720:720p,1920x1080:1080p,3840x2160:2160p/029/396/428/_TPL_.h264.mp4.m3u8' \
    --video-url 'https://xhamster.com/videos/susanne-lets-her-boyfriend-do-anal-for-the-first-time-xh2TF0r' \
    --stream-url 'https://video-nss-h.xhcdn.com/f2lDG9bnX54qDTS1t7xDgw==,1795640400/media=hls4/multi=256x144:144p,426x240:240p,854x480:480p,1280x720:720p,1920x1080:1080p/022/942/827/_TPL_.h264.mp4.m3u8' \
    --stream-selector 'script#initials-script' \
    --related-selector 'div[data-role="related-item"] a.video-thumb-info__name' \
    --video-tags-selector "div[data-role='video-tags-list'] a[href*='/categories/']" \
    --provider-src 'Xhamster' \
    --load-response 'posters,plot,tags,actors,year,duration,recommendations'
```

Result: **PASS** — search p1/p2 46 cards each, home p1/p2 46 each, 3/3 video pages 200 with
`script#initials-script`, 3/3 streams `206 application/vnd.apple.mpegurl`, related 9/9 titled,
tags on 3/3, LoadResponse 7/7 fields populated in code. Harness notes:

- `--search-title-selector 'a.video-thumb-info__name'` is needed: the card's own text is the
  date/uploader/duration soup; the anchor text is the title (attr-only on a minority of cards,
  the known #635 instrument limitation — not exercised on this sample).
- `--related-selector` targets the title anchor, not `div[data-role="related-item"]`, because
  the verify regex-DOM truncates nested-div cards (recorded in FINDINGS-635).
- `--stream-url` is position-matched: streams are JS-built at play time, so fresh tokens are
  minted from the mobile page's `xplayerSettings` immediately before the run.

## Findings lifecycle

Dedup (open **and** closed, `gh issue list --search "Xhamster"`): no standing issue covers a
video-source condition. Closed history (#119, #134, #216, #242, #338, #339, #427, #515, #635,
#834) is superseded architecture work; none reopened and none counted as drift. **No finding
filed this run** — 0 conditions confirmed; `suspected_excluded`/false-positive bookkeeping
starts empty.

## Registry write

Nothing in the diff (ADR-0013). The provider entry (verdict `ok`, tier `plain-curl`,
`last_deep` 2026-11-25, `drift_confirmed` 0) plus this run's `last_runs` entry
(`false_positives: []`) are posted on the issue thread as a
`<!-- findings-registry-entry -->` comment for `registry_sync`.
