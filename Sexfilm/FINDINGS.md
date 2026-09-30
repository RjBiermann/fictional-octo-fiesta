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
`div.short.nl.nl2` blocks (same as listings). Search GET paginates via `&search_start=N`
(see Pagination below).

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

### morencius.com/embed/<key>  (third embed host; added in issue #277 fix)
Present on sampled pages (`/11702-classy.html`, also confirmed issue ground truth on
`/11097-sara-diamantes-xxxtra-erotic-massage.html`). Same Dean-Edwards packed JW8 config
as filmcdm: unpacked links = relative `hls4` `/stream/.../master.m3u8` (resolves against
the embed host) + absolute acek-cdn `hls2`. Both served `200
application/vnd.apple.mpegurl` from the runner with UA + `-e https://morencius.com/embed/...`
(2026-09-10). Provider's existing packed-JW `addSource` path handles it unchanged;
`loadLinks` regex widened to match it.

### playmogo.com/e/<key>
HTTP 403 (bot wall) to the runner even with referer — not used.

## Headers / referer
No referer required for filmcdm.top master m3u8 (200 without). Sending site referer anyway
is harmless. UA standard Chrome works everywhere.

## Canonical section paths (issue #276, 2026-09-10)
4 of the 6 home rows 301 from top-level to `/movies/<sub>/`. The old top-level path +
`page/2/` redirected back to the section root (page 2 == page 1). Fix: mainPage now uses
the post-redirect canonical paths; `vintagexxx/` canonically lives at `/movies/vintage/`.
Canonical (post-301) paths, all 200, page 2 distinct from page 1 (differing card sets):
- `/movies/`, `/movies/page/2/`
- `/porno-video/`, `/porno-video/page/2/`
- `/movies/hd-porno-movies/`, `/movies/hd-porno-movies/page/2/` (canonical, was top-level)
- `/movies/fullhd-porn-movie/`, `/movies/fullhd-porn-movie/page/2/` (was top-level)
- `/movies/porno-parodies/`, `/movies/porno-parodies/page/2/` (was top-level)
- `/movies/vintage/`, `/movies/vintage/page/2/` (was `/vintagexxx/`)
verify.sh (2026-09-10, one run per section, page 1 + page 2 per row): PASS ×6 — no card
overlap p1↔p2 in any section; search, load fields, related all green; filmcdm two-hop
embed streams resolved by verify.sh's embed_stream_url fallback and serving
`200 application/vnd.apple.mpegurl`. Cross-section card overlaps (e.g. Movies row shares
cards with HD row) are a site reality (sections overlap), not per-row duplication.
s2.filmcdn.top cfglobalcdn variant still TCP-times-out from the runner (geo/IP-bound
token — see note above); do NOT "fix" downstream of that.

## Pagination
Listings: `/{section}/page/N/` (confirmed: `/movies/page/2/` → 24 different items).
Search: `/{section}/page/N/` for listings; search paginates via DLE `&search_start=N`
(1-based; page 1 = no param). Confirmed 2026-09-11 (issue #335):
`story=milf&search_start=2` → 200, 24 cards, 0 overlap with page 1; pagination JS is
`list_submit(N)`. Provider: `Parse.searchUrl(query, page)` appends `&search_start=$page`.

## Risks / blockers
- None on main site (no Cloudflare).
- `cfglobalcdn.com` (filmcdn.top-only videos, e.g. `701-visite-anale-1998.html`) **TCP-times-out
  from this runner** — such videos may need in-app verification/VPN. Every other probed video
  also carries a filmcdm.top source, which verified fine.
- playmogo 403s the runner — skipped.

## Update (issue #176): genre/duration metadata
Verified on 6760-tarzan-x-shame-of-jane.html and 167-pirates.html:
- `<meta itemprop="genre" content="Russian translation&nbsp;,&nbsp;Full HD porn movie&nbsp;,&nbsp;Vintage">`
  (multi-valued, `\u00a0,\u00a0`-separated) — 2 tags per page probed.
- `<meta itemprop="duration" content="PT8173S">` (ISO-8601 seconds) / `PT7797S` on pirates.
- JSON-LD `application/ld+json` @type Movie also carries description/datePublished (redundant
  with og:/div#s-desc already used).
Fix: load() now parses `meta[itemprop=genre]` → `tags` and `meta[itemprop=duration]` →
`duration` (seconds). verify.sh --load-response tags,duration: PASS.

## Audit fix (issue #213): year / actors / richer tags — verified 2026-09-09
Probed 11702-classy.html + 11698-any-friend-of-my-daughters.html (both HTTP 200):
- `parameters-info`: `span.gv a[href*=/watch/year/]` → "2024" / "2022" (year field).
- `ul.flist-col` "Casting:" li → `a[href*=/watch/name/]` anchors ("Amateur"; confirms
  anchor-list shape — take all anchors in that li).
- "Genre:" li → 8 `a[href*=/tags/]` anchors; `meta[itemprop=genre]` carries only
  "HD porn movies" (1 tag) on Classy. Tags now read the anchors, meta as fallback.
- duration meta `PT5304S` ⇔ flist "Duration: 01:28:24" — both agree.
Fix: load() populates year, actors, tags (flist anchors w/ meta fallback).
Tests: Sexfilm ParseTest (red→green) against fixture from live HTML; gradlew Sexfilm:test PASS.
verify.sh (2026-09-09): PASS. NOTES understood:
- plot/duration NOTEs are verify.sh pydom limits (`div#s-desc` nested-div capture and void
  `<meta>` text scan); both fields verified directly: `py field "div#s-desc" text` on the
  original page XML is a comment/img block + h2 text; `py field "meta[itemprop=duration]" content`
  → PT5304S. Code assigns both and app renders them.
- related checked via `div.sect-c div.short a.th-title` (anchor block shape for pydom).

## Poster ground truth re-probe (issue #493, 2026-09-29)
"Any poster and video poster (load) is not loading image. It is blank." Server side fully
ruled out (NOT-REPRO from the runner): fixture and live markup match, and every wire shape
the app can send is served.

- Markup: `div.short` → `a.short-poster img-box with-mask[href]` wrapping
  `<img data-src="https://sex-empire.org/uploads/posts/2026-09/thumbs/<slug>.webp">`.
  No plain `src`, no `data:` placeholder; load page `<meta property="og:image">` =
  full-size non-thumb path (`https://sex-empire.org/uploads/posts/2026-09/cum-in-my-dreams.webp`,
  same on 6760/167/11660/11698).
- URL serving: card thumb, og:image full, and 30/30 listing posters (2019–2026 uploads)
  → 200 with real RIFF/VP8 webp bytes; non-existent path → 404 (origin-served, no CF
  catch-all); HEAD, HTTP/1.1, HTTP/2, curl_cffi-impersonated, every UA variant incl. no-UA
  and the app's Chrome-116 shape → 200 (Content-Type header absent on 200s — Coil's
  BitmapFactory path is content-agnostic, `file` shows valid webp).
- Image host: exactly **one** live — `sex-empire.org` (Cloudflare zone; the challenge
  applies only to its **HTML root** — `https://sex-empire.org/` plain-curl 403 "Just a
  moment" — image paths are exempt and serve without it). No on-domain fallback exists:
  `en.sex-film.biz/uploads/...` → 404; zero on-domain `/uploads/` paths across movies/,
  page/2, hd/fullhd/parodies/vintage. TLS 1.2 and 1.3 both negotiate (ECDHE-ECDSA) —
  API 21+ fine; `link[rel=image_src]` is not present on live pages (dropped DLE 9-era
  convention). DNS across Google/Cloudflare/Quad9 resolvers resolves to the same
  Cloudflare pairs.
- Remaining suspects are app/regional (network-level Cloudflare bot-filtering for the
  reporter's ASN, or Coil device disk-cache failure) — not provable from the runner.

Fix shipped in #493: `Parse.poster(doc)` — `og:image` first, `div#s-desc a.highslide[href]`
fallback when og:image is absent/blank; search/eager card posters unchanged (`img[data-src]`).
Because the probe ruled out every runner-provable server cause, this fallback is defensive
hardening for an og:image-less template variant — the reported blank-poster symptom itself
has no code-level fix here and needs in-app verification to confirm.

Standing risk: poster URLs stay third-party-hosted on `sex-empire.org` — a future zone-wide
auth there takes out search, home, and load posters at once. Single point of image failure
for this provider.
