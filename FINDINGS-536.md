# FINDINGS-536 — FreePornVideos details-block rebuild: Description:/Models:/Duration xpaths dead

Issue #536 (audit run 8 / #524 finding). Live probe 2026-10-09/10, runner IP US datacenter.
**Instrument**: plain curl → 403 CF challenge on every freepornvideos.xxx URL (canary-consistent
with FreePornVideos/FINDINGS.md "BLOCKED from CI"); TLS-impersonated fetch
(`.pi/skills/site-probe/scripts/impersonate.sh`, curl_cffi chrome fingerprint) → 200. All
transcripts below are TLS-impersonated plain-HTTP evidence. Device-app verification remains
maintainer-side (same caveat as the standing provider FINDINGS).

## Reproduce — confirmed

```
$ curl -s -o /dev/null -w "%{http_code}" https://www.freepornvideos.xxx/search/sex/1/    → 403
$ impersonate.sh https://www.freepornvideos.xxx/search/sex/1/                            → HTTP 200, 24× class="item"
```

The new details block (`div.block-details > div.info > div.item` cells, label in
`<span>Label:</span>` + links in cells,vid2 exact markup):

- `<span>Channel:</span>` + `a.btn_sponsor[href*=/sites/]`
- `<span>Network:</span>` + `a.btn_sponsor_group[href*=/networks/]`
- `<span>Categories:</span>` + `a.btn_tag[href*=/categories/]` (+ `+` expander; full list in DOM)
- `<span>Pornstars:</span>` + `a.btn_model[href*=/models/]`

**Note (corrects the issue text)**: pornstar links are `href=/models/<name>/` with
`class="btn_model"` — not `/pornstars/`. Selector keyed to `a.btn_model` inside
`div.block-details`, not to the href.

### Old selectors vs live markup (all 5 sampled pages identical exposure)

| old selector | status | evidence |
|---|---|---|
| `//div[contains(text(),'Description:')]/em` | **dead** | no `Description:` cell; no `>Description:` in body |
| `//div[contains(text(),'Models:')]/a` | **dead** | no `>Models:` in body; renamed `Pornstars:` |
| `//span[contains(text(),'Duration')]/em` | **dead** | no Duration cell |
| `//div[contains(text(),'Categories:')]/a` | questionable | label now inside `<span>Categories:</span>`; replaced by CSS `div.block-details a.btn_tag` anyway |
| `div.rating span` | **dead** | rating % UI gone; only like/dislike counts (`span.count` 9/1-shaped, vid1) in `div.rating-container voters` |

**Title-tail date claim NOT reproduced**: all 5 sampled h1s DO end with `/ DD.MM.YYYY`
(`… And Eric / 13.05.2021`, `… Horny / 09.10.2026`, …) — same calendar day as the JSON-LD
`uploadDate`. Year-relevant evidence recorded both ways regardless: fix takes year from
JSON-LD `uploadDate` (structured, h1-shaped markup already drifted once per the audit).

## Video pages sampled (5 + search-anchored, varied listings)

| # | URL | source listing | h1 tail | og:desc snippet | meta video:duration | JSON-LD | pornstars |
|---|---|---|---|---|---|---|---|
| 1 | `/videos/93404552/…thailand-sex-vacation…/` | search "sex" | `/ 13.05.2021` | "Full Length 😎 Starring Eric and Victoria Tiffani… Release date: May 13, 2021." | 2370 (s) | PT0H39M30S, up 2021-05-13 | Eric, Victoria Tiffani |
| 2 | `/videos/93820265/deprived-and-horny/` | latest-updates | `/ 09.10.2026` | "…Starring Jovan Jordan and Mckenzie Mae…" | 1894 | PT0H31M34S, up 2026-10-09 | Jovan Jordan, Mckenzie Mae |
| 3 | `/videos/93818578/…cheating-for-quick-cash…/` | most-popular/week | `/ 16.03.2026` | "…Starring Michael Fly and Vixi Rafi…" | 3725 | PT1H2M05S, up 2026-03-16 | Michael Fly, Vixi Rafi |
| 4 | `/videos/93775368/a-bollywood-tail/` | networks/brazzers-com | `/ 30.10.2023` | "…Starring  and Jasmine Sherni…" | 2211 | PT0H36M51S, up 2023-10-30 | Chris Diamond, Angel Gostosa, Zane Walker, Jasmine Sherni |
| 5 | `/videos/93796888/everyone-loves-destiny/` | categories/vintage | `/ 03.01.2025` | "…Starring  and Parker Ambrose…" | 2335 | PT0H38M55S, up 2025-01-03 | Destiny Mira, Nicole Kitt, Parker Ambrose |

Cross-check: meta seconds and JSON-LD ISO token agree on every page (2370/60=39 = PT0H39M30S; etc.).
meta seconds flooring = JSON-LD minutes — same value, one source suffices, the other is fallback.

## Exposure inventory (per field)

| field | exposed as | selector |
|---|---|---|
| title | og:title (clean, no date tail; h1 has studio-prefix + date tail) | `meta[property=og:title]` |
| poster | og:image | `meta[property=og:image]` |
| plot | meta description == og:description short snippet ("Full Length 😎 Starring X and Y. Resolution: … Release date: <Month D, YYYY>."), also JSON-LD `description` (longer, full title) | `meta[property=og:description]` |
| tags | Categories cell | `div.block-details a.btn_tag` |
| duration | `meta[property=video:duration]` seconds + JSON-LD `VideoObject.duration` | `meta[property=video:duration]` (+ JSON-LD fallback) |
| year | JSON-LD `uploadDate` (= h1 tail date) | `script[type=application/ld+json]` VideoObject |
| actors | Pornstars cell | `div.block-details a.btn_model` |
| score | **not exposed as % anymore** — like/dislike counts only; provider's `div.rating span` returns null (kept out of --load-response; not fixed in this PR) | — |

## Search

`/search/<slug>/<page>/` — 200 TLS, 24 `div.item` cards, all with
`https://www.freepornvideos.xxx/videos/<id>/<slug>/` links. Search↔load agreement: card
`93404552` href == sampled load page 1. Page 2 `/search/sex/2/` → 200, **0/24 overlap** with page 1.

## Quick search

No distinct quick-search endpoint — provider does not implement quickSearch (hasQuickSearch stays false).

## Homepage

Rows already wired (`/$row/$page/` suffix): `/latest-updates/1/` · `/2/` → 200, 24 and 24 cards,
**0/24 overlap** p1/p2. Same card class across rows (max 25 on a network row — one extra ad-shaped
card, harmless to mapNotNull). Related videos and streams unchanged — see below.

## Related videos

`div#list_videos_related_videos_items div.item` present on all 5 sampled pages (no "no related"
note needed).

## Stream sources (per video page)

Unchanged — `<video class='video-js'>` + `<source src='…/get_file/<sig>/<n>/<id>/<id>_<q>m.mp4/'
type='video/mp4' label="720p">` (single-quoted src, jsoup-safe). 2 sources per page (720p/480p,
2160p where present). Serving proof via verify run below. Provider's `video source` + label-flow intact.

## Pagination

- search: path `/search/<q>/<n>/` — p2 different items (transcript above).
- home rows: `/<row>/<n>/` — `/latest-updates/2/` different items.
- related: single block, no pagination.

## Headers / referer

No age-wall unlock observed from this IP; streams carry absolute get_file URLs (signed), replay
with `Range` + browser UA (verify transcript). Site 403s plain-datacenter UA fingerprints — same
CF class as standing FreePornVideos/FINDINGS.md.

## Risks / blockers

CF challenge for plain curl (all URLs) — verification used the TLS-impersonate instrument;
maintainer should confirm in-app on a device before merge (geo/UA caveat unchanged).
