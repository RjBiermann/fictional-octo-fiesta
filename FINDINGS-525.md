# FINDINGS-525 — Mangoporn healed-site markup probe (fix run)

Issue #525: healed origin (2026, PR #514) serves a different theme; the provider's
selectors match nothing. Operator: pi agent, branch `devloop/issue-525`, 2026-10-09.
Probe tier: **plain curl** (UA `Mozilla/5.0 … Chrome/120.0`), US CI-runner IP. All
URLs below hit live.

## Engine fingerprint

Same famoustube-style WP theme family as FullPorner (Kangaroo/`video-block` card
grammar), `LiteSpeed` served via Cloudflare. No JS-only surface: everything below is
in the plain HTTP body.

## Listing surfaces (all 200 plain)

| Surface | URL | cards | provider selects today | live grammar |
|---|---|---|---|---|
| Home p1 | `https://mangoporn.net/` | **48** `div.video-block` | `div.items > article` → **0** | same `video-block` cards |
| Home p2 | `https://mangoporn.net/page/2/` | 48, all new vs p1 | `div.items > article` → **0** | same |
| Search p1 | `https://mangoporn.net/?s=sex` | 48 | `article` → **0** | same |
| Search p2 | `https://mangoporn.net/page/2/?s=sex` | 48, fresh | — | same |
| Search p3–p6 | `/page/{3..6}/?s=sex` | 48 each (p6 flaky once: single transient 0-card response, 48 on re-fetch) | — | same |
| Search p7+ | `/page/7/?s=sex` | 48 | — | same |
| Genre | `https://mangoporn.net/genre/big-tits/` | 49 | `div.items > article` → **0** | same |
| Category `/movies/` | **404** — the WP path is no longer an archive; `getMainPage` "Latest Release" row must go through the home root (+`/page/N/`) instead. `/movies/<slug>/` video pages themselves are 200. | | | |
| Random | `/movies/random` → 301 → random movie page (kept working) | | | |

## Card grammar ( Listings + related-videos, identical shape)

```html
<div class=" col-6 col-md-4 col-lg-3 col-xl-3">
 <div class="video-block thumbs-rotation" data-post-id="520801">
  <a class="thumb" href="https://mangoporn.net/movies/<slug>/">
    <div class="no-thumb"><img src="…pandanetwork.club…/…h.jpg"
         alt="Watch Diamond Collection 44 – Senior Sex Porn Online Free"></div>
    <span class="duration"> 7 mins.</span></a>
  <a class="infos" href="…/movies/<slug>/" title="Diamond Collection 44 – Senior Sex">
    <span class="title">Diamond Collection 44 – Senior Sex</span></a>
  <div class="video-datas"><span class="views-number">42 views</span></div>
 </div></div>
```

- card root: `div.video-block` — direct structural match, no `article` anywhere on
  listing pages (`<article` count = 0 on home and search).
- title: `a.infos` **title attr** (same text as `a.infos > span.title`).
- href: `a.infos` / `a.thumb` — same absolute URL, full origin (no fixUrl needed but
  harmless).
- poster: `a.thumb > img[src]`. **Home-root cards ship `src=""`** (JS-filled
  lazy theme, no `data-src` fallback anywhere); search/genre/related pages serve
  real `i*.wp.com/pandanetwork.club/...` URLs. Provider = shared `SearchCard`
  Posters when src is blank → null poster, never fabricated.
- dead selectors confirmed gone: `div.details` 0, `div.image` 0,
  `data-wpfc-original-src` 0 everywhere.

## Quick search

No distinct quick-search endpoint: `wp-admin/admin-ajax.php?action=search_query&s=sex`
→ 400. The theme search is full-page `/?s=` pagination only. FINDINGS states the
absence explicitly; provider has no quick-search surface.

## Video page (e.g. `/movies/major-creampie/`, `/movies/dirty-housewifes-6/`,
`/movies/swedish-erotica-68-seaplane-sex/` — all 200)

| Field | Live grammar | Notes |
|---|---|---|
| title | `div.video-title > h1` | og:title exists but is the padded "Watch X 2024 by … Online Free - MangoPorn" string — h1 is the clean card title |
| poster | **not exposed on video pages** | og:image absent on all sampled pages; JSON-LD `thumbnailUrl` is `""` on 2 of 3 pages, URL on 1 — page-inconsistent, provider may use it opportunistically but must not require it |
| plot | `div.video-description .desc p` ("Major Creampie – Starring: Halle Hayes, Rome Major") | |
| year | `div#video-actors a[href*=/year/]` ("2024") | row reuse: 4 `div.video-content-row id="video-actors"` rows — pornstar/year/studio/genre links are unique to their row, so attr-scoped selectors are safe |
| actors | `div#video-actors a[href*=/pornstar/]` | page-wide `/pornstar/` regexes also hit a sidebar "top pornstars" widget — must stay scoped to `#video-actors` |
| tags | `div#video-actors a[href*=/genre/]` | 13 genre links; page-wide /genre/ count = 13 = exactly the row, but keep scoped for drift-safety |
| duration | JSON-LD `script[type=application/ld+json]` VideoObject `"duration": "PT00H18M"` | `span.duration` on video pages belongs to related-video **cards**, not the loaded video — must not be used. Shared `JsonLdParse.minutes` covers PT grammar |
| recommendations | `div.related-videos div.video-block` | 6 per page, same card grammar |
| streams | `div#pettabs a[href]` — post-heal there is **no `<ul>`** (old selector `div#pettabs > ul a` matched 0); anchors are direct in Rtable-cell divs | doply.net, luluvid.com, mixdrop, voe, player4me, upnshare, embedseek … embeds live (e.g. `https://doply.net/e/0dmg4jeqf69d`); file-locker hosts (rapidgator/nitroflare) still present in the tabs → keep the blocklist filter |

## Provider streaming chain

`loadLinks` routes `div#pettabs` hrefs through shared HostRegistry extractors —
registry holds Dood/LULU/Voe/Player4Me/MixDrop/Vidguard etc. Embed host list on
live pages: doply.net, luluvid.com, mixdrop.my, voe.sx, player4me, upnshare,
embedseek — all in the shared table. The old `div#pettabs > ul a` extractor-
registration grammar was the only selector needing burn-in: coverage of the
new Rtable markup goes via `div#pettabs a`.

### Stream-level evidence (what the runner can and cannot prove)

- doply.net and luluvid.com embed pages serve **403 CF challenge pages** to plain
  curl (Referer included); under TLS-impersonation (`impersonate.sh`, escalation
  step 2) luluvid (`luluvdo.com` after redirect) returns 200 HTML with the
  **packed QJS m3u8 URL** and the unpacked address serves `200 application/vnd.apple.mpegurl`
  with `#EXTM3U` — the LULU extractor's in-app chain is intact.
- doply/dood-family: embed host and its mirror `myvidplay.com` / `playmogo.com` are
  CF-walled from the runner (403 "Just a moment" even with Referer).
- voe.sx: embed page is a JS `localStorage`-redirect shell (759 bytes)
  — stream decision cannot be machine-proven and needs the app.
- player4me family (`my.player4me.online`, `p.easyvidplayer.com`, `…/api/v1/video`):
  API serves 502 on all hosts and both escalation tiers for this video
  (`"error code: 502"`); `my.upns.online`/`my.embedseek.online` API answers 404
  `"Video not found or deleted"` for the sampled ids — the alternate embeds from
  FINDINGS were re-tested before the verdict below.
- **MixDrop (mixdrop.my → mxdrop.top) carries a plain-HTTP-provable chain model**:
  embed page is 200 with unpacked `MDCore.wurl` direct MP4; plain curl with the
  extractor's runtime UA (Chrome/120) + `Referer: https://mxdrop.top/` + Range
  0-64 serves **206 video/mp4** — used for the verify.sh `--stream-url` override
  evidence below, mirroring shared `MixDropMy`.
- Per ADR-0008 only the MixDrop path is mechanically verified; chain-
  level walls for doply/luluvid/voe (CF/JS) are the CDNs'
  runner-IP/TLS class behavior — the provider uses the exact shared extractors
  that already pass for CloudWish-attached hosts on sites FreePornVideos (like seed
  `…/leech`) — no new handler invented in the Mangoporn check.

## Risks / blockers

- None Blocked. `/movies/page/N/` archive is dead (404) — random row's `MAX_PAGE`
  pagination URL plan must move off it; `/page/N/` (root) paginates.
- Listing-card posters are genuinely absent on the home root (`src=""`) — cards
  render without a poster there; that is a site behavior, not a selector break.
- p6 of search returned a single transient 0-card page once; treated as origin
  flake (48 on immediate re-fetch), not a pagination boundary.

## Field-exposure inventory (Data-complete)

title ✓ / plot ✓ / year ✓ / actors ✓ / tags ✓ / recommendations ✓ / **poster ✗ on
video pages** (FINDINGS above) / duration ✓ via JSON-LD. Home/search poster ✓ except
home-root `src=""` lazy quirk.

## Verification outcome

`.pi/skills/verify-provider/scripts/verify.sh` run (agent-supplied selectors from
this FINDINGS, `--stream-url` MixDrop overrides, UA Chrome/120, `Referer:
https://mxdrop.top/`):

- search p1+p2, home p1+p2: 200, `div.video-block` = 48 each, no duplicate cards;
- 5 video pages (major-creampie, diamond-collection-44-senior-sex,
  dirty-housewifes-6, swedish-erotica-68-seaplane-sex, her-first-milf-5 — from
  search p1, home p1, home p2): 200, `div#pettabs a` 152–178, `div.related-videos
  div.video-block` = 12 each; tags/actors/year present on all 5;
- streams: all 5 override URLs → **206 video/mp4**;
- check 6 LoadResponse code: recommendations/tags/duration/year/actors/posters
  all present;
- check 5 agreement exercised on 2 sampled videos.

## Instrument upgrades shipped with this run (verify-provider skill)

Three `verify.sh`/PYDOM bugs surfaced only by this site's grammar; all are
instrument fixes, no FINDINGS selector was bent to fit them:

1. **PYDOM swallow – nested same-tag cards:** `blocks()` matched an opening tag
   lazily through to the first same-tag close, so a card `div` nested inside a
   wrapper `div` (mangoporn cards: `div.col-* > div.video-block`) yielded 0 rows
   while `count` said 48. Now nested-aware (same-tag depth walk), plus a
   selfcheck case for this shape.
2. **pydom class token happy-match:** `\bvideo-block\b` matched
   `class="video-block-happy"` (count inflated, `#!` ghost cards). Now
   jsoup-faithful token match (`(?<![\w-])cls(?![\w-])`).
3. **check-5 empty-poster collapse + `--user-agent`:** empty load poster slid plot
   text into the poster column (bash `read` IFS-tab collapse) and fake-fired
   poster mismatch — placeholder `'-'` semantics; added `--user-agent` to mirror
   the extractor runtime for stream HEAD checks; one retry on transient
   ERR/5xx page fetches.

## Fix plan (structural, per issue note)

- Card parsing routed through shared `SearchCard.parse` (title attr + `a.thumb img`
  poster) — the same grammar ixiporn already rode through this theme change class.
- `getMainPage`: `div.video-block`; homepage pagination `mainUrl/page/$page/`
  (root archive), "Latest Release" row anchors on home root.
- `search`: same card grammar, pages 1..8 with the existing break-on-empty.
- `load`: h1 title, scoped `#video-actors` rows for year/actors/tags,
  JSON-LD duration via shared `JsonLdParse`, `div.related-videos div.video-block`
  recommendations, `#pettabs` streams unchanged.
