# FINDINGS-417.md — Cat3Movie (issue: "Women at Play movie is not playing")

Probe date: 2026-09-16. UA used throughout:
`Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36`
(all cat3movie.org fetches 200 with it; plain curl gets CF-blocked empty bodies — the gate
recorded in FINDINGS-411/#411).

## Reported movie state

`https://cat3movie.org/women-at-play-1985` — 200, `h1.entry-title` = "Women at Play (1985)",
`"post_id":34514` (first occurrence in HTML; related-card payloads are escaped and carry no
earlier `"post_id":` key), `body[data-nonce="bc43d77bbc"]` (data-nonce precedes the first
page-level `"nonce":"2ca2de7c22"` — `Parse.streamConfig` picks correctly). Note
`/watch-women-at-play-1985` returns 404 today (watch- URL form is not linked from the page
either); the provider's `loadLinks` uses the base movie URL, unaffected.

## Symptom replay — the exact provider chain, per server (2026-09-16)

player.php (per-server referer, X-Requested-With, cache-buster — exactly what `loadLinks` sends):

```
sv1 → 200 → <iframe src="https://hlsfree.com/embed/hls/937">
sv2 → 200 → <iframe src="https://cdn.loadvid.com/videos/play/HobLHIBkiruljbWPNyLO">
             embed 403 "Access Restricted" — blob-gated, unchanged, no adapter (known)
sv3 → 200 → <iframe src="https://hlsfast.com/#9qgavp"> →
      api/v1/video?id=9qgavp (Referer https://hlsfast.com/) →
      200 {"message":"Video not found or deleted"}   ← dead upstream, not a provider defect
```

sv1 hlsfree leg (what `loadExtractor(embed,…)` runs) — fully healthy today:

```
embed    GET hlsfree.com/embed/hls/937 (Referer cat3movie.org) → 200
token    defaultHlsUrl → token=0f2404b0bdc6 (rotates per embed-page load)
manifest GET /api/hls/serve?token → 200 application/vnd.apple.mpegurl, #EXTM3U, 489 segments
bogus token → 404 "Token not found or expired" (the #247 preflight/2-attempt retry covers this)
segment  s1.cat3hls.com/0dd2ec367e36e79d/daa556d9fc629eff.jpg → 403 without referer,
         with Referer: https://hlsfree.com/ → 200, 3,398,100 bytes MPEG-TS (image/png type)
fresh-token replay → 200 mpegurl (re-fetch of another page's token also 200 — matches the
         recorded CF max-age=86400 caching behaviour)
```

Same live replay for heat-1986, joy-1983, blue-money-1972, baby-cat-1983,
bamboo-house-of-dolls-1973 — all 5 sv1 chains → 200 mpegurl.

## Conclusion on the report (does not reproduce server-side)

Every leg the provider exercises for women-at-play-1985 works from this runner, and the chain
was already verified healthy when #250 probed the same movie. The playable sv1 HlsFree source
exists and the #247 preflight only emits it when the manifest actually returns 2xx `#EXTM3U`.
Remaining in-app failure modes (datasource header path, CF challenge on the user's client,
stale first-play token cache) are app-side; nothing further is fixable server-side.

## Latent bug found and fixed on this run (the minimal change)

The **HlsFast** link is emitted with `referer` only — **no `headers` map**. Live probe on
the-sadist-of-notre-dame-1979 (sv1/sv2 dead, sv3 hlsfast is the only source):

```
api      api/v1/video?id=iuba9o (Referer hlsfast.com) → 200 hex (AES chain, key/iv intact)
cfNative hlsfast.com/v4/pl/sn3k.evercresthospitality.space/3ae/iuba9o/… → 200 mpegurl
media    index-f1-v1-a1.m3u8 → 200 application/vnd.apple.mpegurl
seg      seg-1-f1-v1-a1.woff2 → 403 WITHOUT Referer: https://hlsfast.com/,
         200 WITH it (166,249 bytes) — segments are referer-gated like hlsfree's (#247)
```

Same symptom class #247 fixed for hlsfree: without `ExtractorLink.headers` some datasource
paths drop `Referer` on segment fetches → ExoPlayer 2004 `ERROR_CODE_IO_BAD_HTTP_STATUS` →
"not playing". Fix: `headers = mapOf("Referer" to "https://hlsfast.com/")` on the emitted
link (Cat3Movie.kt, hlsfast branch of `loadLinks`), version 8 → 9. No Parsing/test changes.

## verify.sh run (recorded 2026-09-16)

Mechanically asserted:

- check 1 search `/search/women-at-play` → 200, `a.halim-thumb` 8 matches
- check 2 video pages 5× (women-at-play-1985, heat-1986, joy-1983, baby-cat-1983,
  bamboo-house-of-dolls-1973): all 200, stream selector present, plot selector NOTE
  (tool's `field` only reads `attr="content"`, not inner text — direct probe shows the plot
  IS on page: "Stage director Wallis Greene …" at `article#post-34514.item-content`)
- check 4 streams (position-matched `--stream-url` tokens, one per video page — the static
  page carries no stream URL, chain-resolved; Javbangers precedent): **5/5 → 200
  application/vnd.apple.mpegurl**
- check 6 LoadResponse completeness with `--provider-src Cat3Movie`: pass
- agreement (video0 IS the first search card
  `<a class="halim-thumb" href="…/women-at-play-1985" title="Women at Play (1985)">`,
  re-probed directly — tool NOTE'd it only because sampling query was exact-match)

Tool-shape FAILs (site-architecture, both recorded in Cat3Movie/FINDINGS.md already):

- home duplicate cards: site renders the latest-10 carousel widget **plus** the main grid
  (11 hrefs ×2 within one page) — provider's `Parse.homeCards` dedupes by href (post-15919
  renders ×3 in the grid, same recorded shape as #203/#250);
- stream path shared across videos: `/api/hls/serve` is one path, per-token video (_CF-cached_
  per-token) — path-distinctness can't express token-varying proxies.

## State at end of run

- No reproducible server-side defect for women-at-play-1985; chain re-proven end-to-end.
- Shipped: hlsfast segment-referer header map hardening, version bumped to 9.
