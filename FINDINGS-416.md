# FINDINGS-416 — Cat3Film (issue: movies shown as TV shows, videos don't play)

Probe date: 2026-09-16, UA `Mozilla/5.0 (Windows NT 10.0; …) Chrome/124` unless noted.

## Symptom 1: movies render as TV shows

`load()` unconditionally returns `newTvSeriesLoadResponse` (Cat3Film.kt:118). Movies wrap their
full-length real episode as "Episode 1" of a fake series.

Type is distinguishable two ways on the live site:
- Detail page badges row: movies render `<span class="badge">Movie`, series render
  `<span class="badge">TV` (no "Movie" badge on any series). Verified:
  - Movie: `/the-handmaiden`, `/russian-lolita` (1h 33m), `/impregnation-nation`,
    `/conflict-of-emotions` → badge set e.g. `2h 25m / Movie / HD / EN Sub / favBadge`
  - Series: `/hache`, `/hot-line`, `/risque-business-japan`, `/virgin` → badges `HD / TV` (0× "Movie")
- Search JSON also carries `format: "Movie"|"TV"` per result (future tie-breaker).

Validation: movies discovered via the `/movies` home row carry the "Movie" badge, so the
existing search→load flow types correctly too (search only returns slugs, format not plumbed).

## Symptom 2: videos don't play — stream chain probed end-to-end, healthy

In-app playback cannot be reproduced from the runner, but every hop the provider resolves
now serves real data (cloud-run probe, no challenge):

1. Detail pages 200 (okhttp UA too).
2. Watch page `?sv=1&part=1` → 200; movies have one `.epbtn` with `data-ep` (e.g. 405).
3. `GET /api/v1/episodes/{id}/sources` → 200 JSON `{"sources":[{file,type:"hls"}],success:true}`
   for ids 405 (movie), 449/457/462 (series S1/S2).
4. Stream CDN **changed** since #410: `cat3.asuka-vod.site` → `abyssssss.top` (segments on
   `player.abyssssss.top/…/seg-N-v1-a1.jpg`). The bare token URL still 404s; `/token/index.m3u8`
   suffix still required (m3u8 200 `application/vnd.apple.mpegurl`, VOD playlist; segments 200).
5. m3u8 serves with or without Referer and with `okhttp/4.10.0` UA — no access wall from here.

So the old FINDINGS.md note "CDN is CF-challenged" is obsolete for the new CDN. Remaining
no-play path in-app is the CF-challenge-on-fetch case already handled by CloudflareKiller
(#410); nothing further to fix server-side. The most likely user-visible break is the
TV-show rendering of movies (Symptom 1), which this fix removes; if users still report
zero links in-app after merge, the #410 CloudflareKiller path is the suspect, not this code.

## Upstream build-env note (`bootstrapCloudstream`)

`pre-release` classes.jar digest changed upstream (moving tag refreshed):
expected `1719357c…` → actual `26b2b91f…`. Downloaded asset verified identical to the jar
mavenLocal was already serving; `expectedSha` bumped in root `build.gradle.kts` (commented).
Root change limited to the digest — review as a deliberate pin bump.

## Fix (minimal)

- `Parse.movieTypeTag(html)` (pure, unit-tested) returns "Movie"/"TV" from `.badges .badge`.
- `load()` returns `newMovieLoadResponse(title, url, TvType.NSFW, epData)` for movies —
  `epData` is the single full-length `.epbtn[data-ep]` id, so `loadLinks` receives the real
  sources-API episode id instead of the old fallback `"1"` (which would 404-kill playback if
  the watch-page parse ever came back empty). Series path unchanged.
- Fixtures: `detail-hache.html` (series, fresh probe), probe copy of handmaiden detail page;
  tests red→green in `Cat3FilmParseTest`. Version bumped 9 → 10.

## verify.sh result (2026-09-16)

- check 1a homepage `/movies` + `?page=2`: 200, 30 cards each, page 2 fresh — PASS
- check 2 video pages 5× (the-handmaiden, hache, russian-lolita, conflict-of-emotions,
  virgin): 200, `h1.info-title`, tags/actors/year/duration present on all 5 — PASS
- 4 related cards per video — PASS
- streams positional (chain `.epbtn[data-ep]` → `/api/v1/episodes/{id}/sources` →
  `/index.m3u8`, abyssssss.top): 5/5 → 200 `application/vnd.apple.mpegurl` — PASS
- check 6 (code half): recommendations/tags/plot/duration/year/actors/**score**/posters ≥1 — PASS
- check 1 search: **FAIL-by-tool** (unchanged from #292) — the site's only search surface is
  the JSON endpoint `/_ajax/search?q=` (HTML `/search?q=` is 404); verify.sh asserts HTML
  cards. Agreement re-proven by curl 2026-09-16:
  `_ajax/search?q=hache` → `{"slug":"hache","title":"Hache","format":"TV"}` matches `/hache`;
  `_ajax/search?q=handmaiden` → `slug:"the-handmaiden","format":"Movie"` matches
  `/the-handmaiden`. Site shape, not a provider defect. (Mini-parser also can't walk
  `.class tag` chains — tags/actors asserted with `a[href^="/genre/"]`, `a[href^="/actor/"]`.)
