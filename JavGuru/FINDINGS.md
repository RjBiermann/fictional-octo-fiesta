# FINDINGS — jav.guru (updated by fix for #144; re-probed 2026-09-07/14)

## Verdict: FIXED (mirror-level) — 5/5 varied videos yield ≥1 verified m3u8 (turboviplay and/or vidara)

## Re-probe 2026-09 (issue #144)
- Search `https://jav.guru/search/teacher/` → 200, 78 × `div.inside-article`. Unchanged.
- Post pages still carry 6 base64 `iframe_url` entries; all decode to
  `https://jav.guru/searcho/?<xd|ud|td|cd|hd|od>=…&bg=<poster>` (unchanged).
- The plain `?xd=` loader page (200, no redirect) now embeds the SAME gateway pattern as
  before: `window.cfg = {cid, base, rtype, keys:['data-*']}` + `div#<cid>` carrying the
  data-attr token (filled WITHOUT cookies). `?xr=<reversed token>` → 302 → per-server embed:
  - xd → `https://javclan.com/e/<hash>` — **player changed**: no more packed `eval(p,a,c,k,e,d)`;
    now a jwplayer page whose stream comes from the obfuscated `/assets/jquery/hg-function.js`
    (`encStatus('<id>',1)`). Shared `Javclan` extractor (packed-eval) no longer matches → null.
  - ud → `emturbovid.com/t/<id>` → redirect to `turbovidhls.com` page with literal m3u8
    (`https://cdn3.turboviplay.com/.../<id>.m3u8`) → 200 application/vnd.apple.mpegurl.
    Still caught by loadLinks' literal-m3u8 regex. **WORKING.**
  - td → `javlesbians.com` → now redirects to a jwplayer Vue app (eugenemakedraw.com build
    assets); no literal m3u8, no extractor matches.
  - cd → `vidara.to/e/<filecode>` — NEW simple API: `POST /api/stream` with
    `{"filecode": ..., "device": "web"}` → JSON `streaming_url` (m3u8, IP-bound token) →
    200 application/vnd.apple.mpegurl. **WORKING (new extractor added).**
  - hd → `vide0.net` → 403 from runner (unchanged). od → `maxstream.org` (no extractor match).
- m3u8s serve 200 without any Referer (turboviplay verified both with and without).

## Fix (this change, JavGuru dir only)
- New `Vidara` extractor (`VidaraExtractor.kt`): filecode from `/e/<fc>` → POST
  `https://vidara.to/api/stream` → regex `streaming_url` → M3U8 link, referer = embed URL.
- Registered via `registerSharedExtractors(listOf(Vidara()))` in the plugin class.
- Version bumped 20 → 21.
- javclan's new hg-function.js flow is fully obfuscated and was NOT reverse-engineered;
  the shared packed-eval `Javclan` extractor stays (harmlessly returns null). javlesbians /
  vide0.net / maxstream mirrors remain unhandled — every post still has ≥1 working mirror.

## Search — OK
- `https://jav.guru/page/N/?s=q` (provider) and `/search/q/` both work; `div.inside-article`.

## Video pages — OK (5 varied probed)
- search ×2, category/jav-uncensored, most-watched-rank ×2: `h1.tit1`,
  `div.large-screenshot img`, `iframe_url` base64 ×6, related items in `li img`.

## Stream verification (chain.py replication, per #117 precedent)
- verify.sh's single-hop stream check still cannot express the 4-hop searcho chain (and its
  contentUrl fallback trips on the site's escaped JSON-LD logo → curl exit 3 abort), so
  stream verification replicates loadLinks end-to-end over 5 varied videos:
  **5/5 videos yielded ≥1 m3u8, each confirmed 200 application/vnd.apple.mpegurl**
  (turboviplay on all 5; vidara on 2 of 5).

## Headers / referer
- vidara extractor sends `referer = <vidara embed url>`; m3u8s verified playable without
  special headers.

## Pagination
- `/page/N/?s=` for search; `/page/N/` for listings.

## Risks / blockers
- No Cloudflare/IP blocks observed on jav.guru / vidara.to / turbovidhls.com.
- vide0.net 403s the runner (unchanged); javclan + javlesbians mirrors now JS-gated.
- If turboviplay/vidara both die, javclan's hg-function.js deobfuscation is the upgrade path.

## Host-registry refactor verification (issue #169, this run)
Search: `https://jav.guru/search/teacher/` → 200, 24 × `div.inside-article`; `/page/2/` → 200, 24 (verify.sh check 1 PASS).
Stream chain (chain multi-hop > verify.sh single-hop; replicated manually):
iframe_url base64 → searcho (base/rtype/cid keyed attrs, reversed token) → 302 → vidara.to/e/<code> → POST /api/stream → streaming_url m3u8 → 200 `application/vnd.apple.mpegurl` (#EXTM3U). 4 posts verified end-to-end; sequential over the remaining posts of the probe did not complete in-budget (chain per-post is 4 hops).
Displacement: PerverZijaExtractor Vidara adapter moved to `shared/` — code byte-identical, registration via `registerHostExtractors()` (`loadExtractor` prefix-matched on mainUrl; reverse order = priority; Levenshtein >80 mirror-domain fallback overrides PerverZija schema/subdomain variance).
LoadResponse fields present in provider (verify.sh check 6: recommendations 2, tags 2, year 1, actors 3).
NOTE: verify.sh `field` bar cannot express `h1.titl` (mini-selector regex `[a-zA-Z]+` rejects digit tag names) and no m3u8 in page HTML (multi-hop) → check 2 stream bar N/A here, chain replicated above. og:meta absent on video pages (WP theme, anything-for-titles).
