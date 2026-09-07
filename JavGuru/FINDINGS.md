# FINDINGS — jav.guru (2026-09 audit)

## Verdict: BROKEN (stream extraction)

## Search — OK
- `https://jav.guru/page/1/?s=red` → 200; `div.inside-article, article` ×78.

## Video page — OK
- `/1047142/` etc.: `h1.tit1`, `div.large-screenshot img`, `"iframe_url":"base64"` ×2-7.

## Stream — FAILS
1. base64 iframe → `https://jav.guru/searcho/?xd=...`
2. searcho page: `cid/base/rtype/keys` present → reconstructed `?xr=` URL 302s → `https://javclan.com/e/<hash>` (3 videos probed, all same).
3. javclan player page is **fully packed** `eval(function(p,a,c,k,e,d)...)` jwplayer config. Raw script contains NO literal m3u8 URL and NO `file:"..."` / `sources` script — both the provider's `hlsRegex` and the shared `Javclan`/`javclan` extractors (regex `file:"(.*?)"` on script containing `sources`) match nothing → no links emitted.
4. Stream itself is alive: after unpack, `links.hls2` = `https://jZdDKEFw9oEu0.premilkyway.com/hls2/01/14832/6gftk4793mdn_,l,n,h,.urlset/master.m3u8?t=...&...` → **200 application/vnd.apple.mpegurl** (verified manually). Same `/stream/...master.m3u8` path also exists (same-origin 403 without token, hls2 works).

## Fix direction
Unpack (JsUnpacker) the javclan payload, read `links.hls2/hls3/hls4`, emit m3u8. Watch out: shared contains TWO `javclan`-named classes (both find nothing on packed pages).
