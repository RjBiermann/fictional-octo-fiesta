# FINDINGS-460 — live-site audit of all 25 provider directories

Audit-only run (issue #460): probe every provider's live site, record what works and what is
broken. No code changed. Working region of prober: EU IP, Chrome UA + implicit
`Accept-Encoding` via `curl --compressed`; hard-walled requests re-shot with the
`site-probe/scripts/impersonate.sh` Chrome-TLS instrument.

## Summary matrix

| Provider | Site | Search | Video page | Stream evidence | Verdict |
|---|---|---|---|---|---|
| AllClassicPorn | https://allclassic.porn | 200, `a.th.item` cards ×60 | 200 | `video_url: 'https://…get_file/…'` present on both probed pages | OK |
| Cat3Film | https://cat3film.com | `_ajax/search?q=` 200 JSON (`{"results":[…]}`; empty result only for terms with no matches, e.g. "milf") | — | — | OK |
| Cat3Movie | https://cat3movie.org | `article.thumb` cards (50) | 200, `data-nonce` + `post_id` present | player.php sv1–3 → hlsfast iframe both answered | OK |
| EPorner | https://www.eporner.com | 200 cards | 200, `EP.video.player.hash` present | xhr with base36-converted hash → `available:true` + mp4/HLS sources | OK |
| Eroticmv | https://eroticmv.com | `?s=milf` 200 cards | 200 | `video_embed=33322` present | OK |
| Film1k | https://www.film1k.com | 403 to plain curl, **200 via Chrome-TLS impersonation** | — | — | OK (fingerprint wall; see notes) |
| FreePornVideos | https://www.freepornvideos.xxx | 403 curl, 200 impersonated; `custom_list_videos…items` block + cards | 200 | `<video><source src='…get_file/…mp4'>` present | OK (fingerprint wall) |
| FullPorner | https://fullporner.com | 403 curl, 200 impersonated; `video-card` ×57 | /watch/<id> 200 | embed iframe `//xiaoshenke.net/video/…` | OK (fingerprint wall) |
| HQPorner | https://hqporner.com | 200, `section:has(a.image)` rows | 200 | `mydaddy` embeds present (×3) on probed page | OK |
| JavGuru | https://jav.guru | 200 page-1/2 differ | 200 | `iframe_url:"aHR0…"×5` (base64 embeds) present | OK |
| Javbangers | https://www.javbangers.com | `/search/big-tits/` 200, `video-item` ×75 (page ≥2 is 404 by site design; provider `hasNext=false`) | 200 | `video_url: '…get_file/…275849.mp4/?v-acctoken=…'` | OK |
| Javmost | https://www.javmost.ws | `showlist2/search/1/milf/` 200 JSON w/ result array | (load is embed-driven via `loadExtractor`) | — | OK |
| Javseen | https://javseen.tv | ajax search 200 JSON-wrapped `li id="video-"` ×~60 | — | homepage ajax `browse_videos` 200 ×~60 | OK |
| Javtiful | https://javtiful.com | 200 page-1/2 differ | 200 | `.mp4` present | OK |
| Mangoporn | https://mangoporn.net | **522** on home and search | — | — | **DOWN** |
| MissAV | https://missav.live | 200, cards `href="https://missav.live/milf-091"` | 200 | packed JS → surrit id; `surrit.com/…/playlist.m3u8` 200 `#EXTM3U` via Chrome-TLS (403 to plain curl) | OK |
| Neporn | https://neporn.com | async search 200 page-1/2 differ | 200 | `video_url` present | OK |
| PandaMovies | https://pandamovies.pw | **522** home and search | — | — | **DOWN** |
| PerverZija | https://tube.perverzija.com | 200, cards present, page-1/2 differ | 200 | iframe `pervl4.xtremestream.xyz/player/index.php?data=…` present | OK |
| PornXP | https://pxp.news | real search `/tags/milf` 200 `item_cont` ×36, page-2 200 differently-sized | 200 | `<video id="player" poster …>` + mp4 | OK |
| Porntrex | https://www.porntrex.com | ajax search 200; `video-list/video-item` cards + `/video/<id>/` links present; page-2 (from=2) differs | 200 | `flashvars` + `video_url` present | OK |
| Sexfilm | https://en.sex-film.biz | GET search 200, `short-poster` ×24 | 200 ×3 probed pages (11618, 11658) | `filmcdm.top/e/…` 200 embed page w/ description | OK |
| WatchPorn | https://watchporn.to | async search 200 page-1/2 differ | 200 (58740, 8332) | `.mp4` present | OK |
| XMoviesForYou | https://xmoviesforyou.com | search **403 Cloudflare challenge to plain curl and 200 via Chrome-TLS** | 200 | streamtape link present on probed page | OK-with-cloudflare-caveat (see notes) |
| Xhamster | https://xhamster.com | 200; search results in embedded JSON `searchResult.videoThumbProps` | 200 | `"sources":{"mp4":{"144p":"https://video7.xhcdn.com/…` present | OK |
| ixiporn | https://ixiporn.org → live at ixiporn.live | `/search/milf` 200 (redirects to .live), `div.video-block` cards | 200 | `.mp4` present | OK |

## Findings (broken / drifting)

### PandaMovies — site dead (522)
- URL probed: `https://pandamovies.pw/` and `https://pandamovies.pw/?s=milf`
- Cloudflare **522** (origin unreachable), 7.2 KB error page both cases.
- Successor-looking `www.pandamovies.com` exists but is a hard Cloudflare challenge page
  ("Just a moment…" body, no content) — no unlock tested positive via Chrome-TLS impersonation.
- Prior drift evidence: #457 "issue with pandamovies" (closed) followed a #444 search fix (#446). 1
  closed drift-history issue → below hardening threshold, but nothing to fix while the origin is down.

### Mangoporn — site dead (522)
- URL probed: `https://mangoporn.net/`, `https://mangoporn.net/?s=milf`
- Cloudflare **522** both.
- `mangoporn.com` redirects to https://wank.com/ (200, 549 KB) — plausibly the rebrand, but it is a
  different name/content shape; re-probe + FINDINGS would be required before pointing the provider there.
- Prior drift: #33 closed drift, #456 follow-up. 2 closed drift/broken issues.

### XMoviesForYou — Cloudflare challenge on search (recurrence)
- URL probed: `https://xmoviesforyou.com/search?q=milf` + `&page=1`
- Plain curl + desktop UA: **403 "Just a moment…" challenge** (5.5 KB), while the homepage fetched 200
  earlier in the same run. Chrome-TLS impersonation returns 200 (105 KB) with real cards.
- History: #450 (challenge on search) → closed; #467 (recurrence, same failure) → closed. 4 closed
  issues on this provider, at least 2 of them drift-flavored — this is the second occurrence of the
  identical root cause, which speaks to brittleness rather than bad luck.

## Notes
- The three "fingerprint-wall" sites (Film1k, FreePornVideos, FullPorner) serve 403 to curl but 200 to
  a Chrome-tier TLS client. In-app OkHttp requests are neither fingerprint; whether real clients pass
  was not tested here — worth an in-app spot check by a maintainer if users report those providers.
- Verified stream serving: MissAV playlist (`#EXTM3U` stream), EPorner xhr (mp4 src), plus stream-bearing
  pages above — every provider whose stream extraction depends on a downloader extractor
  (`loadExtractor`) was checked only at embed/URL level, not through the adapter itself.
- Prober region: EU. KVS-style sites (Porntrex, WatchPorn, Neporn, FreePornVideos, Javseen) and
  xHamster/MissAV catalogs can differ per region; a runner elsewhere may see different cards.
