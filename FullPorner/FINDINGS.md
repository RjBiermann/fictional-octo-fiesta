# FINDINGS — fullporner.com (current ground truth: 2026-10-16 deep pass, issue #644)

## Verdict: ok-drift — every surface healthy on the TLS-impersonated tier

Plain curl is challenge-walled on **both** fullporner.com and xiaoshenke.net from the CI
runner (`cf-mitigated: challenge`; runner IP US, AS8075) — the #461 suspected runner-IP
class, never a standalone finding. Screened evidence tier: TLS-impersonated
curl_cffi/chrome (the instrument real clients present). Historical note: this file's
pre-#524 "BLOCKED from CI" record was the same unscreened challenge artifact; retired by
the escalation ladder protocol.

Full run evidence: `FINDINGS-644.md` (repo root). Run report in
`audits/findings.json` → providers.FullPorner + last_runs.

## Engine fingerprint

Custom xiaoshenke app (layout CSS/JS served from `static.xiaoshenke.net`), full-SSR
listing pages, RTA-labelled. Film1k/FreePornVideos/Javbangers suite.

## Surfaces (2026-10-16, all 200 TLS)

- **Home rows** `${mainUrl}/home/${page}` (Featured), `/category/<slug>/${page}` — 24
  cards/page, p1∩p2 = 0 on both probed rows. Card: `div.video-block div.video-card`,
  title `div.video-card-body div.video-title a`, poster `div.video-card-image a img`
  (real URL in `data-src`; `src` is a blank.gif placeholder).
- **Search** `/search?q=<query>&p=<page>` — 24/page, p1∩p2 = 0, agreement with sampled
  video exercised. No JSON API.
- **Quick search — none.** `/search/suggest?q=` is the regular search page for the
  literal word "suggest" (canonical `/search?q=suggest`), `/api/search`,
  `/wp-json/wp/v2/search` → 404. `hasQuickSearch` stays false (explicit absence).
- **Video pages** `/watch/<hash>` — title `div.single-video-title h2`, plot
  `meta[name=description]` (fixed site-suffix stripped by `parsePlot`), tags
  `p.tag-link span a` (inside `div.single-video-title` — #526 condition confirmed fixed
  live), duration `div.video-info > div:last-child` ("36:24" h:m:s clock), upload epoch
  `span.create` (UTC seconds), actors `single-video-info-content p a`
  (only rendered where the site has a pornstar; blanks filtered), recommendations
  `div.video-recommendation div.video-card`.

## Stream sources (2026-10-16 grammar — CHANGED from all prior records)

Embed `//xiaoshenke.net/video/<videoId>/<n>` is now a **fluidPlayer** page: playlist
derived from two static JS locals — `var id = "<videoId>"` (reversed glyph-wise by site
JS) and `var quality = parseInt("<mask>")` where mask bits = 1=360/2=480/4=720/8=1080.
**No jwplayer `configs` exists anymore** (the provider's old WebView hook matched
nothing; all inline scripts carry a dead type attribute). Resolution verified live:
`https://xiaoshenke.net/vid/<reversedId>/<q>` → 206 `video/mp4` for every mask-present
quality, **Referer required** (xiaoshenke or fullporner both work; none → 404 hotlink
wall). Provider now parses this statically (`FullPornerParse.parseEmbedSources`,
TDD-tested on fixture embeds) with WebView+loadExtractor kept as fallback.

Embed page itself: 200 without referer, but challenge-walled to plain curl from the
runner — in-app devices see it through residential TLS.

## Poster

No og:image on watch pages. Poster = `https://xiaoshenke.net/vid/<videoId reversed>/720/i`
→ 302 → `v7.xiaoshenke.net/s0/…` → 200 (re-verified 2026-10-16). Same reversed-id
grammar as the stream URLs.

## Headers / referer

TLS tier + browser UA for everything; stream/poster requests need the xiаoshenke-family
Referer (provider ships `referer = mainUrl`). No cookies/age-gate found on any surface.

## Risks / blockers

- Cloudflare challenge to plain curl from datacenter IPs on both domains — suspected
  runner-IP artifact (#461 exclusion); **do not re-file as Blocked without the
  residential differential**.
- Known site-content quirk: near-duplicate uploads share one title on listing pages
  (e.g. "Raw Passion Unleashed…" twice with different ids on search amateur p1).
