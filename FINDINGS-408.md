# FINDINGS-408.md — issue #408: 24-site site-probe sweep (2026-09-13)

Runner: Debian CI container, curl_cffi chrome-impersonation (Cloudflare TLS pass-through),
UA "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Firefox/130.0". Geo-gate: IPs appear US-based
(geo=us accepted by xhamster; streams served without region wall).

Method: every site got (1) homepage fetch, (2) search via the provider's exact URL pattern
with query `milf` (and alternates where needed), (3) page-2 of the surfaces that paginate,
(4) one video page with the provider's decisive stream-extraction step re-executed live.
Where the chain was JS/extraction dependent, the provider's decisive URL (API endpoint,
player iframe, encrypted payload key) was replayed with the same parameters the Kotlin uses.

## Per-site verdicts

| Site | Reachable | Search | Pagination | Stream chain | Verdict |
|---|---|---|---|---|---|
| AllClassicPorn | 200 | `/search/milf/` 60 cards, p2 60 | OK (p2 200) | `get_file/...mp4?v-acctoken` present | OK |
| Cat3Film | 200 | `/ _ajax/search?q=` JSON (prefix-title search; `milf`/`office` empty is site semantics — `rape` → 8) | search page 1 only (JSON) | JW /api/v1 + player flow unchanged | OK |
| Cat3Movie | 200 | `/search/milf` 5 cards | p2 200 (3 cards) | player.php POST replay → 200 iframe `hlsfast.com/#ipczrl`; `/api/v1/video` decrypts (key `kiemtienmua911ca`, iv `1234567890oiuytr`) → master.m3u8 at 94.131.217.175 | OK |
| EPorner | 200 | `/search/milf/` 78 cards, p2 66 | OK | `gvideo.eporner.com/<id>/<id>.mp4` present | OK |
| Eroticmv | 200 | `/?s=milf` 1 hit (WP search semantics) | search no p2 (provider already handles) | video page: jwplayer + `vidcdn2.eroticmv.com/...m3u8` present | OK |
| Film1k | 200 (CF-challenge to plain curl; passes via chrome impersonation — in-app cloudflareKiller) | `/?s=milf` 6 cards | **DRIFT: `/page/2/?s=` hard 404, `?paged=2` also 404 — search no longer paginates** | **DRIFT: byse iframe markup lost the trailing `/`, so the old `film1k.xyz/e/{code}/` regex matched nothing**; embed host chain is now film1k.xyz + abyssplayer + **a third host, turbovidhls.com/t/{code} (JW) → cdn{N}.turboviplay.com/data3/... m3u8**, newly implemented; byse PoW `/api/videos/{code}/embed/captcha/` still 200 with pow_nonce | **FIXED** |
| FreePornVideos | 200 (CF to plain curl) | `/search/milf/1/` 25 cards, p2 25 | OK | video page `<video><source src=get_file/...mp4 label=2160p/720p/480p>` | OK |
| FullPorner | 200 (CF to plain curl) | `/search?q=milf&p=1` 24 cards, p2 24 | OK | watch page iframe `xiaoshenke.net/video/{code}/4` present | OK |
| HQPorner | 200 | `/?q=milf&p=1` 52 cards, p2 52 | OK | iframe `mydaddy.cc/video/<id>/` → `s29.bigcdn.cc/pubs/.../360|720|1080.mp4` (MyDaddyExtractor chain intact) | OK |
| ixiporn | 200 | `/page/1?s=milf` 30 cards, p2 30 | OK | `cdn2.ixifile.xyz/...mp4` present | OK |
| Javbangers | 200 | `/search/jav/`... search returns 75 `div.video-item` cards | p1 only (provider matches) | `www.javbangers.com/get_file/...mp4?v-acctoken` present | OK |
| JavGuru | 200 | `/page/1/?s=milf` 24 cards, p2 24 | OK | watch page wp-btn-iframe + `iframe_url` base64 entries present | OK |
| Javmost | 200 | `/showlist2/milf/1/search/` JSON 24 results | OK (hasNext when non-empty) | replay: `/ri3123o235r/` POST with group/codes/value from page → 200 `{"data":["https://emturbovid.com/t/..."]}` | OK |
| Javseen | 200 | `/search/video/?ajax=search_results&s=milf&o=recent` JSON envelope (status/html/pagination/total), 30 `li[id^=video-]` in html field — provider parses envelope | envelope handles pages | `button_choice_server` data-embed base64 (mycloudz/Cloudwish/StreamBeast…) present; related `ul.videos.related li` present | OK |
| Javtiful | 200 | `/search?q=milf` 24 cards, p2 24 | OK | `frontWatchConfig` JSON still carries playerSources mp4s | OK |
| MissAV | 200 | `/en/search/milf` 14 cards, p2 14 | OK | video page packed-eval JW source (source1280/playlist) present — provider evaluates packed JS | OK |
| Neporn | 200 | `/search/milf/` 24 cards, p2 (async block) 24 | OK | flashvars `video_url: 'get_file/.../31211_720p.mp4/?v-acct...'` present | OK |
| PerverZija | 200 | `/?s=milf` 68 `div.col-md-3:not(#sidebar)` cards, p2 68 | OK | watch page iframe `j2.xtremestream.xyz/player/index.php?data=...` (variant-subdomain path intact) | OK |
| Porntrex | 200 | `/search/milf/` ≥100 cards, p2 86 | OK | `get_file/.../2913997[_1080p|720p].mp4/` present | OK |
| PornXP | 200 | `/tags/milf` 36 cards, p2 (`?page=2`) 36 | OK (`#pages` ">" control) | `<video id=player><source src=//cdrn.pornxp.sh/.../360.mp4 title=360p>` — `#player source` intact | OK |
| Sexfilm | 200 | `index.php?do=search&subaction=search&story=milf` 24 `div.short` cards, p2 (&search_start=2) 24 | OK | watch page contains `morencius.com/embed/...` + `filmcdm.top/e/...` (Parse.embeds unchanged) | OK |
| WatchPorn | 200 | async search block 35 cards `div.thumb.item`, p2 35 | OK | `get_file/...` present | OK |
| Xhamster | 200 | `/search/milf` results present (initials JSON); p2 `?page=2` 28+ | OK | mobile page `window.initials` → `xplayerSettings.sources.standard.av1/h264` hex-obfuscated URLs still present (decodeXhUrl path intact), `?geo=us` honored | OK |
| XMoviesForYou | 200 | `/search?q=milf` 24 `a.group.flex.flex-col` cards, p2 `?page=2` 24 | OK | home/search cards 24; `/api/related/{postId}` endpoint unchanged | OK |

Notes on duplicate-overlap spot checks (p1 vs p2 same-size pages, e.g. MissAV/PZ/PornXP/
Eroticmv): my sweep compared all anchors inside cards, so shared nav/sidebar links produce
expected overlap; card-sets still change materially item-to-item (p2 carries fresh video
ids). Not evidence of duplicate flood; machine-level dup assertion is verify.sh's bar.

## Drift found → fix

- **Film1k** (issue drift): three drifts, all fixed:
  1. `search` paginated via `$mainUrl/page/$page/?s=$q`; the live site now returns a hard
     HTTP 404 for page 2 of any search (homepage `/page/2/` still fetches fine, 24
     `article.loop-post` — homepage pagination unaffected). Fix: search is single-page —
     `page > 1` immediately returns an empty list with `hasNext = false`.
  2. The Byse embed regex `film1k\.xyz/e/([a-zA-Z0-9]+)/` demanded a trailing slash; the
     markup is now slash-free (`film1k.xyz/e/{code}` and the fake-extension video source
     `/e/{code}/{slug}.mp4`), so **byse extraction was completely broken** before this
     change — first-match regex rewared as a pure Parse function (`byseCode`).
  3. `turbovidhls.com/t/{code}` JW embeds — **a new third embed host** sharing the video
     pages — had no extraction path at all. Added: fetch the embed page, pull the
     `cdn{N}.turboviplay.com/data3/{code}/{code}.m3u8` master (subdomain-anchored regex,
     negative test for look-alike hosts), fall through to abyssplayer if extraction fails.

  `Film1k/build.gradle.kts` version 7 → 8.

## Validation status

- `Film1k:test` and a clean `Film1k:make` build pass on this branch (see commit history).
- **Live `verify.sh` verification is OUTSTANDING for the maintainer**: film1k.com sits
  behind a Cloudflare managed challenge that 403s the runner (same as FINDINGS-2026-09-10);
  the probe evidence above was gathered with browser-TLS impersonation, so the new
  turbovid extraction path was confirmed only at the URL-replay level, not end-to-end
  through the provider in-app.

## Blocked/dead sites
None — all 24 reachable and extracting.
