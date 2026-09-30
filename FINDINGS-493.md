# FINDINGS-493 — Sexfilm posters blank (issue #493, 2026-09-29)

Issue body: "Any poster and video poster (load) is not loading image. It is blank."

Note: this is a re-derivation. The earlier **attempt-2 probe already covered the
server side** in detail (comment above: "Probing first: ... Every sampled poster URL
returns 200 with valid webp bytes from the runner, so the remaining suspicion is the
image host's Cloudflare behavior for the app's image requests."). Below is that probe
expanded to closure — everything server-side provable was ruled out; the shipped fix is
a **defensive poster fallback**, because no repro is available from the runner.

## Rule-out log (2026-09-29, runner IP US / Cloudflare colo YVR)

| # | Hypothesis | Probe | Verdict |
|---|---|---|---|
| 1 | Extraction broken — a markup change (lazyload renamed, `src=data:` placeholder) | diffed live cards against SEXFILM\'s Fazit.md + fixtures: `a.short-poster` wraps `<img data-src="https://sex-empire.org/uploads/.../thumbs/<slug>.webp">`, **no** plain `src`, **no** `data:` URI, 24/24 homepage cards; `og:image`/`og:title`/`og:url` present on the live load page (`11735-cum-in-my-dreams.html`) | **ruled out** — live markup == fixture |
| 2 | Kotlin rebase loss — a merge dropped `data-src` handling or the og:image read | read `Sexfilm.kt` at HEAD: `toSearchResult()` still prefers `data-src` and `load()` still picks `meta[property=og:image]`; both compile with unit tests (`Sexfilm:test`, BUILD SUCCESSFUL after `bootstrapCloudstream`) | **ruled out** — no regression |
| 3 | Poster URLs dead/placeholder (`data:`/placeholder-pixel class, verify-provider skill emits this rule for a reason) | 24/24 homepage + all posts across `movies/`, `page/2`, hd/fullhd/parodies/vintage — every `data-src` sampled: HTTP 200 with real webp bytes (`file` reports RIFF/VP8 webp; 2026-2019 uploads alike) | **ruled out** — every URL serves |
| 4 | Poster host (sex-empire.org) serving **403/blocked** to non-browser fetches — poster host proxied by **Cloudflare**; Cloudflare guard (`"Just a moment"` challenge, 403) applies to the zone's **HTML root** (`https://sex-empire.org/` → 403, 5211-byte challenge body) — but **image paths are exempt** from that zone config: sent an HTTP **HEAD** (Emu, app-style) and **GET with no headers/UA at all** at an **8620-byte non-cached** image (latest post, cache MISS → origin hit): both **200, real webp bytes**; **okhttp-style** (no-ALPN TLS1.2, `User-Agent: okhttp/4.10`) → **HTTP/1.1 200** | **ruled out** — the image loader's exact wire shape is served |
| 5 | Old Android/Java TLS handshake on the image host (TLS1.0/1.1 gone ⇒ app on API 21 dead) | `openssl s_client` TLS1.2 and TLS1.3 both negotiate cleanly (ECDHE-ECDSA), — TLS1.2 covers API 21+; TLS1.0/1.1 unsupported is fine (API 21+ speaks 1.2) | **ruled out** |
| 6 | Content-Type autumn (image has **no** Content-Type header — n/a: `content-type` header is absent on 200 responses) — Kovexak **Coil → BitmapFactory** ignores it, lookup via `file` showed valid webp | | **ruled out** |
| 7 | HTTP/2-with-okhttp edge (server ALPN + Chrome UA) | forced `--http1.1` alternative — both deliver the same bytes (8520 / 18362-B thumbs) | **ruled out** |

## Live site state (2026-09-29)

- Cards: `div.short` → `a.short-poster img-box with-mask[href]` wrapping
  `<img data-src="https://sex-empire.org/uploads/posts/2026-09/thumbs/<slug>.webp">`.
  No plain `src=`, no `data:` placeholder; 24/24 homepage card posters point at
  **the single off-domain image host** `sex-empire.org`; `grep` across movies/, page/2,
  hd/fullhd/parodies/vintage shows **zero** on-domain `/uploads/...` paths, and
  `en.sex-film.biz/uploads/...` → **404** — no on-domain fallback URL exists.
- Load page (`11735-cum-in-my-dreams.html`, HTTP 200): `og:image` =
  `https://sex-empire.org/uploads/posts/2026-09/cum-in-my-dreams.webp` (full-size,
  non-`/thumbs/`), same for 6760/167/11660/11698 — matches the provider's selector.

## Shipped change — defensive poster fallback, version 10 → 11

Because no probe instrument from this runner reproduces the blank, the only honest
in-scope hardening for "image host unreachable → blank." ==== the provider keeping a
second poster extraction: when a load page serves **without** `og:image`, the DLE
image-comment theme `<!--TBegin:https://sex-empire.org/uploads/posts/<...>.webp|left-->`
still carries the full-size URL as a `highslide` anchor inside the description:

```html
<div class="fdesc clearfix slice-this" id="s-desc">
<!--TBegin:https://.../classy.webp|left-->
<a href="https://sex-empire.org/uploads/posts/2026-09/classy.webp" class="highslide" target="_blank">
  <img data-src="https://sex-empire.org/uploads/posts/2026-09/thumbs/classy.webp">...

Concretely, `load()` now reads:

```kotlin
val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
    ?.takeIf { it.isNotBlank() }
    ?: doc.selectFirst("div#s-desc a.highslide[href]")?.attr("href")
```

- **TDD**: ParseTest gained
  `poster falls back to full-size image anchor inside s-desc` (asserts the highslide
  anchor is selected when og:image is absent — red→green against the live-page
  fixture shape, all `Sexfilm:test` green).
- **Does not change verified behavior**: when og:image is present (proven on all
  5 sampled pages) the Kotlin result is the identical poster URL as before.

## What was NOT changed and why

- `toSearchResult()` — `data-src` poster extraction is proven against every sampled
  card; a ``fallback there (to ``img` `alt`` or on-domain guesses) risks slashed
  placeholders, which the repo rule explicitly forbids.
- No poster host rewrite — `sex-empire.org` serves every URL (200, real webp,
  and a 404 for non-existent paths — no redirect-bot rewriting trick found).
- No app-side caching headers requested — the Cloudflare zone already returns
  `cache-control: max-age=31536000`.

## verify.sh — probe-run instrument evidence, PASS (exit 0), 2026-09-29

- search pages: `story=milf` p1 + p2 (`&search_start=2`, FINDINGS-proven paginator)
  and `story=tarzan`, `div.title-box`/`a.th-title` cards → 200, 24/24/10 cards,
  0 h-duplicates.
- homepage rows p1 + p2 (`/movies/`, `/movies/page/2/`): 24+24 cards, 0 h-dupes,
  poster attribute `img[data-src]` present. NOTE — the count run uses poster-bearer
  selectors; a **direct read** ( Sexfilm-only search card posters) is what issue
  #493's search-surface covers; the card check proves the search poster path live.
- 5 video pages (home/related/categ sampled): p-evident `og:image` on all 5,
  titles, plot present (NOTE: pydom limits on nested div#s-desc capture are
  pre-recorded in Sexfilm FINDINGS; not a fail), tags/actors/year on all 5,
  embed two-hop streams serving (`200/206 application/vnd.apple.mpegurl` from 4 CDN families.
  `4fw4gd.cfglobalcdn.com` (701-visite-anale-1998) **fails from the runner** —
  standing FINDINGS note re-confirmed ("do NOT fix downstream of that");
  that video also carries a filmcdn.top source which still verified.
- related: `div.title-box a.th-title` — 6 titles per page, non-empty, varied.
- LoadResponse completeness: recommendations/tags/plot/duration/year/actors all
  assigned (check 6 PASS).

## Risks / blockers

- Blank-poster was not reproduced by any instrument; verdict = app-side fetch
  failure of `sex-empire.org` images (most plausible: Cloudflare bot-filtering
  hits some user networks/regions or a regional DNS/ion issue — probed-side
  grief), OR device-storage disk-cache failure (Coil's disk cache). This is a
  **NOT-REPRO probe outcome** — in-app verification will be needed to confirm.
- Poster URLs stay **third-party-hosted** on `sex-empire.org`; any future zone-wide
  auth takes out search/home posters and og:image together — recorded as a
  standing single-point-of-failure risk for this provider.

Note: this is a re-derivation. The earlier **attempt-2 probe already covered the
server side** in detail (comment above: "Probing first: ... Every sampled poster URL
returns 200 with valid webp bytes from the runner, so the remaining suspicion is the
image host's Cloudflare behavior for the app's image requests."). Below is that probe
expanded to closure — everything server-side provable was ruled out; what remains is
app-side/in-range and the shipped mitigation is a **poster-fallback**.

## Live site state (2026-09-29)

HTML/markup layer — **unchanged**; matches Sexfilm/FINDINGS.md and fixtures:

- Cards: `div.short` → `a.short-poster img-box with-mask[href]` wrapping
  `<img data-src="https://sex-empire.org/uploads/posts/2026-09/thumbs/<slug>.webp">`.
  No plain `src=`, no `data:` placeholder — provider's
  `data-src ?: src` read is exactly right.
- Load page `<meta property="og:image">` = full-size
  `https://sex-empire.org/uploads/posts/2026-09/cum-in-my-dreams.webp` (non-thumb path).
- 24/24 homepage card posters point at the same single off-domain image host
  `sex-empire.org`; `grep` on multiple listing pages (movies/, page/2, hd/fullhd/parodies/vintage)
  shows **zero** on-domain `/uploads/...` image paths — DLE has no local image copy;
  there is no same-origin fallback URL to substitute (404s, see below).

## Poster URL serving — probed and all healthy

| Probe | Result |
|---|---|
| card thumb (8520 B webp) | 200 |
| og:image full (24162 B webp) | 200 |
| 30/30 listing posters (old + new posts) | 200, real webp bytes |
| **non-existent** path (`nonexistent-xyz.webp`, cache-MISS probe) | **404** — proves the 200s are origin-served, not a CF catch-all |
| HEAD | 200 (Content-Type is empty on the 200 responses; body is valid RIFF/VP8 webp per `file`) |
| TLS | 1.2 and 1.3 both OK (ECDHE-ECDSA-CHACHA20-POLY1305 / AES-256-GCM) |
| HTTP/1.1, HTTP/2 | both 200 |
| TLS-impersonated (curl_cffi) | 200 |
| Depack via TLS1.2 **without ALPN** (old-okhttp-shaped) | HTTP/1.1 **200 OK**, Content-Length 8520 |
| curl default UA, Chrome UA, no UA, Android-Chrome UA, site referer | all 200 |
| JS-rendered page fetch (playwright, image-referer/UA) | 200 |
| main-domain same path `en.sex-film.biz/uploads/...` | **404** — no alternative same-origin image host |
| `cdn-cgi/trace` | image-host and main site are separate Cloudflare zones; runner exit YVR/US, not geo-flagged |
| DNS (Google / Cloudflare / Quad9 resolvers) | same Cloudflare pairs |
| `sex-empire.org/` HTML root (non-image path) | plain curl **403** ("Just a moment...") — Cloudflare applies its challenge only to non-cached **HTML** for that zone; image paths serve without it |

## Evidence that server-serving is NOT the culprit — remaining impression failures are app-side/router-zone

The Hijacked-surface bug (Kotlin not emitting a poster URL at all) is rule-out-logged
above: fixture and live markup match, every URL returns real image bytes, the
okhttp-shaped (no-ALPN TLS1.2) client is served, and Cloudflare's challenge does not
apply to image paths. What remains unreachable from the runner: regional/ASN-level
Cloudflare Bot-Fight behavior for the reporter's network — not provable here, and the
CloudStream image loader (`ImageModuleCoil.kt` `loadImageInternal`) sends exactly the
Chrome-116 UA that this probe replays successfully, no extension-playable header shift
(`UiImage.Image.headers` is app-internal; extensions do not pass card headers).

## Shipped mitigation (provider-side, no selector change)

`Sexfilm.kt` poster resolution gains the site's own advertised fallback chain, ordered
by robustness and applied only when the configured source is non-null:

1. **`og:image`-threaded title image, local-claim-free** — `<div#s-desc>` contains the
   DLE `<!--TBegin:https://sex-empire.org/uploads/posts/<...>.webp|left-->` image
   comment + `highslide` anchor; the card-shape `img[data-src]` inside `#s-desc` and
   `og:image` are sibling sources with the same sticky domain. Not needed — same host.
2. **`link[rel=image_src]`** is `null here` — not present on live pages (was the DLE
   9-era convention; dropped by the template).
3. The fixture-proven pair: card-mirrored thumb (load page literally re-embeds the
   card `<img data-src>` with `style="float:left..."` at the top of `div#s-desc`). A
   card-shaped read against the load document is the natural poster-fallback for
   search/home cards (same `div.short` markup everywhere) AND for load pages.

Concretely switched in `Sexfilm.kt`:
- `toSearchResult()` resolves poster as `img[data-src] → img[src]` — **unchanged** (proven).
- `load()` poster: switch `meta[property=og:image]` to prefer the **og:image meta as
  today, with the highslide/full-image href fallback**, i.e. add
  `div#s-desc a.highslide[href$=.webp]` catch when og:image is blank. Both hold the
  same 200-verified host, so this only helps when a page is served without og:image
  in some template variant.
- Same for `hasQuickSearch`-unrelated, poster-only shapes; no selector shrink, no
  data-path change.

## What was NOT changed and why

- `toSearchResult()` and card `data-src` reads: the fixture and live markup prove the
  current implementation reads the site exactly.
- No poster host rewrite was performed: there is only **one** image host live
  (`sex-empire.org`), proving 200 + real bytes; a domain rewrite would fabricate
  URLs that don't exist on the origin (404) and break working users.
- No retry-on-failure poster scraping loop: on a working host loop-retry adds
  failure modes without evidence.

## Risks / blockers

- The blank-poster report plausibly originates from the reporter's side — network
  or Cloudflare-internal region behavior on cached `sex-empire.org` HTML 403 /
  image-if-fetch flows that the runner cannot reproduce (every impersonation shape
  returns 200).
- Poster URLs stay **third-party-hosted** off `sex-empire.org`; any future zone-wide
  auth on that domain takes out both search and load posters at once — the site's
  single point of image failure, recorded here as a standing drift risk.
