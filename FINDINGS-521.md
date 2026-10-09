# FINDINGS — Sexfilm poster re-probe (issue #521, 2026-09-30)

Issue: "Posters are not loading in feed and video details" — recurrence of #493 (2026-09-29,
6 days earlier, NOT-REPRO'd server-side). Per the drift-recurrence rule this is a hardening
case, not a selector swap: the #493 FINDINGS already predicted this failure mode as the
provider's standing risk ("poster URLs stay third-party-hosted on sex-empire.org ... single
point of image failure").

## What the site serves today (curl transcripts)

Feed card (e.g. /movies/, HTTP 200, byte-identical shape to the #493 probe):

```
$ curl -s https://en.sex-film.biz/movies/ | grep -o '<img[^>]*data-src[^>]*>' | head -3
<img data-src="https://sex-empire.org/uploads/posts/2026-10/thumbs/sexual-healing.webp" alt="Sexual Healing" width="171" height="200" />
<img data-src="https://sex-empire.org/uploads/posts/2026-10/thumbs/burn.webp" alt="Burn" width="171" height="200" />
<img data-src="https://sex-empire.org/uploads/posts/2026-10/thumbs/southern-exposure.webp" alt="Southern Exposure" width="171" height="200" />
```

Load page og:image (11755-sexual-healing.html, HTTP 200):

```
<meta property="og:image" content="https://sex-empire.org/uploads/posts/2026-10/sexual-healing.webp">
```

Every poster URL across all four probed surfaces (home, /porno-video/, /movies/vintage/,
/movies/hd-porno-movies/ + 2 load pages + search) resolves to the **single host**
`sex-empire.org` — no second host anywhere in site HTML.

## Serving checks (all pass from the runner — NOT-REPRO confirmed)

- Thumb + full-size og URLs: 200, real `RIFF ... Web/P (VP8, 427x600)` bytes
  (`/tmp/p1.webp: RIFF ... 34090 bytes`). Content-Type header absent on 200s (matches #493).
- HEAD: 200. No UA (`okhttp/4.9.3`): 200. Full browser shape (Chrome-116 UA + image Accept
  chain + referer): 200 (11468 bytes thumb).
- `http://` scheme: 301 → `https://` (same host) → 200. No downstream-host divergence.
- TLS chains complete on both `sex-empire.org` and `en.sex-film.biz`:
  `Google Trust Services WE1 → GTS Root R4 (cross-signed by GlobalSign Root CA)` — no
  incomplete-chain "works on desktop fails on Android" trap.
- On-domain mirrors: none. `en.sex-film.biz/uploads/...` (thumb + full) → 404 (as #493).
  Subdomain mirror probes: `www./img./cdn./i./static.sex-empire.org` → fail or Cloudflare
  520; no image mirror exists.
- IPv6 not testable from the runner (no v6 egress).

## Root-cause analysis

Not proved either way from the runner: every wire shape it can send is served. The only
variable the runner cannot occupy is the **app's actual image request on the reporter's
device/network** — #493's recorded remaining suspects: network-level Cloudflare
bot-filtering for the reporter's ASN, or device cache. Two recurrences with identical site
behavior strengthen former over latter.

## Fix lever (this repo's proven pattern) — `posterHeaders`

The vendored CloudStream API supports `posterHeaders` on search/home/load responses, and
in-repo providers already ship it for exactly this class of problem:

- `Mangoporn/.../Mangoporn.kt:146` — `imageHeaders` = image Accept chain, set on every
  `newMovieSearchResponse` + `newMovieLoadResponse` (all poster surfaces).
- `JavGuru`, `HQPorner`, `WatchPorn` — poster/referer header variants.

Minimal hardening fix: Sexfilm sets deterministic browser-shaped `posterHeaders`
(Chrome-116 UA + image Accept chain + site referer) on **every** poster surface it emits
(cards via `toSearchResult()` → home/search/recommendations, and load responses). This
overrides whatever default header shape the app's image loader sends on the reporter's
device with a shape identical to a real browser GET of the image — the strongest
runner-defensible mitigation for ASN-level Cloudflare bot-filtering. No parsing change:
`Parse` is untouched, no fixture/test change needed (the shipped header set re-verified 200
against a live thumb/full URL, transcript above).

## What was NOT done and why

- No host rewrite: no on-domain or second image host exists to move to (evidence above).
- No proxy passthrough: no API for it; adding an image proxy would be an unrequested service.
- Version bump in `Sexfilm/build.gradle.kts` per repo rule.

## Verify (2026-09-30)

`Sexfilm:make` + `Sexfilm:test` BUILD SUCCESSFUL (.ParseTest 7/7 green, unchanged).
`verify.sh` PASS, exit 0 — search p1+p2 (milf, search_start pagination) + tarzan p1
(agreement), home /movies/ p1+p2 (72 cards each, no href/title dups), 5 video pages
across two queries + embedded/related listings: `div#video_container` ≥1 each, two-hop
embed streams 200/206 `application/vnd.apple.mpegurl` (premilkyway ×3, filmcdm ×2),
tags/actors/year present on all 5, related 6 per page, LoadResponse fields all assigned.
Harness NOTEs, understood:
- search/home card selectors here are `div.title-box a.th-title` (leaf anchor) because
  pydom's documented nested-div truncation empties `a.th-title` inside `div.short`; card
  posters are therefore not asserted inside check_listing for this run — poster ground
  truth is asserted above (30/30 thumb URLs 200 + valid webp, og:image full-size 200) and
  the provider's `posterHeaders` was re-verified 200 against live thumb + full URLs.
- no --quick-search-url: Sexfilm FINDINGS records no distinct quick-search endpoint
  (hasQuickSearch default false). No --video-duration-selector: FINDINGS records the
  meta[itemprop=duration] pydom void-meta limit; duration is assigned in load().
