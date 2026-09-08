# FINDINGS — Sexfilm (en.sex-film.biz)

## Engine fingerprint
DataLife Engine (DLE). Evidence: `/engine/classes/min/index.php?g=general` script tags,
`/engine/skins/flags/ru.png`, `/templates/Default/style/engine.css` on homepage.
Video URLs are `<id>-<slug>.html` (e.g. `/6760-tarzan-x-shame-of-jane.html`).

## Search
`GET https://en.sex-film.biz/index.php?do=search&subaction=search&story={query}` works.
Transcript: `story=tarzan` returned 10 results including
`https://en.sex-film.biz/6760-tarzan-x-shame-of-jane.html` and `/469-aphrodisiac-2018.html`.
Failed/unused patterns: `/?s=`, `/search/{q}/` (no such route). Result items are
`div.short.nl.nl2` blocks (same as listings). No GET pagination of search results.

## Video pages
Listings: homepage, `/movies/` (+ `/movies/page/N/`), `/hd-porno-movies/`, `/fullhd-porn-movie/`,
`/porno-parodies/`, `/porno-video/`, `/vintagexxx/`. Category page 2 `/movies/page/2/`
returned different items (11640-thr3e-11.html etc.).
Item selector (all listings): `div.short` →
`a.short-poster[href]`, `img[data-src]` (poster, may be off-domain `sex-empire.org`),
`a.th-title` (title).
Probed video pages (all HTTP 200, all contain ≥1 click-loaded iframe embed):
- https://en.sex-film.biz/6760-tarzan-x-shame-of-jane.html (home)
- https://en.sex-film.biz/11698-any-friend-of-my-daughters.html (home)
- https://en.sex-film.biz/167-pirates.html (home)
- https://en.sex-film.biz/11660-cookies-cream.html (category /porno-video/)
- https://en.sex-film.biz/701-visite-anale-1998.html (related)
- https://en.sex-film.biz/8142-the-last-fight.html (related)
Meta: `og:title`, `og:image`; `h1#s-title`, `div#s-desc` description present.

## Related videos
Present: `div.st-capt` "Related Videos" followed by the same `div.short` items
(`div.sect-c div.short a.short-poster[href]`) on every probed video page.

## Stream sources (per video page)
Embeds are injected by inline JS on click: `s2.src = "https://HOST/e/KEY"` patterns per
`#video_container` / `#video3_container` / `#trailer_container`.
Embed hosts seen: `filmcdm.top`, `s2.filmcdn.top`, `playmogo.com`.

### filmcdm.top/e/<key>  (primary, works)
Embed page contains Dean Edwards packed JW8 config. Unpacked:
`var links={"hls4":"/stream/wZQa_8JfewrhI-5muzm77Q/kjhhiuahiuhgihdf/1788880273/54961927/master.m3u8",
"hls3":"https://g6m5vwc8rl.workflowmanagement.sbs/.../master.txt",
"hls2":"https://g6m5vwc8rl.premilkyway.com/hls2/01/10992/szi90mpsvuw7_,l,n,h,.urlset/master.m3u8?t=..."}`
Check: `hls4` (relative → https://filmcdm.top/stream/...) → HTTP 200
`application/vnd.apple.mpegurl`, valid `#EXTM3U` master with 3 video variants + audio tracks.
hls2 also 200. No referer required (200 without).

### s2.filmcdn.top/e/<base64>  (secondary)
Page contains a (commented-out) IP-locked signed m3u8:
`https://4fw4gd.cfglobalcdn.com/secip/1/<sig>/<base64 requester-ip>/<expiry>/hls-vod-s03/flv/api/files/videos/<...>.mp4.m3u8`
Regex-extractable directly. ⚠️ cfglobalcdn.com **TCP connection times out from this runner**
(84.16.243.199:443). Included in extractor but may be geo-blocked.

### playmogo.com/e/<key>
HTTP 403 (bot wall) to the runner even with referer — not used.

## Headers / referer
No referer required for filmcdm.top master m3u8 (200 without). Sending site referer anyway
is harmless. UA standard Chrome works everywhere.

## Pagination
Listings: `/{section}/page/N/` (confirmed: `/movies/page/2/` → 24 different items).
Search: page 1 only (DLE GET search has no usable pagination links).

## Risks / blockers
- None on main site (no Cloudflare).
- `cfglobalcdn.com` (filmcdn.top-only videos, e.g. `701-visite-anale-1998.html`) **TCP-times-out
  from this runner** — such videos may need in-app verification/VPN. Every other probed video
  also carries a filmcdm.top source, which verified fine.
- playmogo 403s the runner — skipped.
