# FINDINGS — jav.guru (2026-09 audit, updated by fix for #98; re-probed 2026-09-07 for #117)

## Verdict: FIXED (stream extraction) — #117 re-probe: chain intact, 5/5 videos yield ≥1 m3u8

## Re-probe 2026-09-07 (issue #117)
- Post pages now carry **6** base64 iframe_url entries (params xd/ud/td/cd/hd/od), all
  decoding to `https://jav.guru/searcho/?<p>=…&bg=<poster>`.
- Each searcho page: plain `window.cfg` JS (cid/base/rtype/keys) + `div#<cid>` with the
  data-attr token (filled WITHOUT cookies too — the #98 cookie note no longer applies);
  `?xr=<reversed token>` → 302 → per-server embed:
  - xd → `https://javclan.com/e/<hash>` (packed jwplayer; Javclan extractor)
  - ud → 301 `emturbovid.com/t/<id>` → `turbovidhls.com` page with literal
    `urlPlay = 'https://cdn2.turboviplay.com/.../<id>.m3u8'` → 200 application/vnd.apple.mpegurl
    (NiceHttp follows the 301; caught by loadLinks' literal-m3u8 regex)
  - td → `javlesbians.com` (Javlesbians/Voe extractor; followRedirects path)
  - cd → `vidara.to/e/...`, od → `maxstream.org/embed-*.html` — no extractor in repo, skipped
  - hd → `vide0.net/e/...` (extractor exists; currently 403s without webview cookies)
- The `/searcho/` fallback branch in loadLinks called loadExtractor on a URL no extractor
  matches — replaced with followRedirects re-resolution (the only code change).
- verify.sh's single-hop stream check still cannot express this multi-hop chain; stream
  verification done by replicating the extractor logic end-to-end (chain.py) over 5 varied
  videos — 5/5 yielded ≥1 playable m3u8 (javclan hls2/hls4 and/or turboviplay, both
  confirmed 200 application/vnd.apple.mpegurl).
- Old hosts: javclan.com still serves all posts (via searcho redirect); javggvideo.xyz no
  longer appears on post pages (extractor kept, harmless).

## Search — OK
- `https://jav.guru/page/1/?s=jun` → 200; `div.inside-article` ×24.

## Video pages — OK
- `/1038289/`, `/1038335/`, `/1038373/` (search), `/864198/`, `/864199/` (category/jav-uncensored):
  `h1.tit1`, `div.large-screenshot img` (verify.sh: matches 1 on probed pages), `"iframe_url":"base64"` ×2-7.

## Stream chain (multi-hop)
1. base64 iframe → `https://jav.guru/searcho/?xd=...`
2. searcho page: `window.cfg = {cid, base, rtype, keys:['data-*']}`; token = reversed concat of
   the named data attrs on `div#<cid>.stream-box`. NOTE: attrs are empty unless the request
   carries the cookies from the video page (curl with `-b` cookie jar) — without them no token.
3. `?xr=<reversed token>` → 302 → `https://javclan.com/e/<hash>`.
4. javclan player page is fully packed `eval(p,a,c,k,e,d)`. After unpack, `links = {
   "hls2":"https://<sub>.premilkyway.com/hls2/.../master.m3u8?t=...&e=129600&...",
   "hls3":"https://<sub>.workflowmanagement.sbs/.../master.txt",
   "hls4":"/stream/.../master.m3u8" }`, player uses `links.hls4||links.hls3||links.hls2`.
5. Live checks (2026-09): hls2 → 200 application/vnd.apple.mpegurl; hls4 (prefixed
   https://javclan.com) → 200 mpegurl; hls3 → 403. Extractor emits hls2 + hls4 in that order.

## Fix (this change)
- shared `Javclan` extractor: JsUnpack the packed script, parse `links.hls2/hls4`, emit M3U8.
- Duplicate lowercase `javclan` class removed (consolidated into `Javclan`).

## Headers / referer
- searcho + javclan requests need cookies from the video page and a Referer; extractor sends
  `referer = <javclan embed url>` and `Origin: https://javclan.com` on the m3u8 link.

## Pagination
- `/page/N/?s=` for search (page 2 returns different items).

## Risks / blockers
- No Cloudflare/IP blocks observed.
- hls3 mirror 403s — intentionally not emitted.
- verify.sh's single-hop stream check cannot express this 4-hop chain (no literal m3u8 on any
  single page); stream verification done by replicating the extractor logic end-to-end over 5
  varied videos (see PR evidence, 5/5 PASS).
