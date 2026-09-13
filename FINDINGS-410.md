# FINDINGS — issue #410 (Cat3Film: "Video doesn't play. No links found")

Probed 2026-09-13 from this runner (SJC egress), main branch = shipped build v8
(`origin/builds:plugins.json` Cat3Film version 8, same code as HEAD).

## Server-side chain: fully intact — every step re-verified live

- `GET /the-handmaiden` → 200, `.epbtn[data-ep]` present (also 4× `.info-people`
  genre/country/director/cast blocks, `section#related` 4 cards).
- `GET /watch/the-handmaiden?sv=1&part=1` → 200, `data-ep="405"`; markup identical to
  the committed fixture `watch-handmaiden.html` (diff of data-ep/wserver-name/epname
  extracts: empty). Hache still renders 14 epbtns across "Season 1"/"Season 2" panes.
- `GET /api/v1/episodes/{id}/sources` → 200 `application/json`,
  `{"sources":[{"file":"https://cat3.asuka-vod.site/<token>","type":"hls"}],"subs":[],"success":true}`
  — verified for 17 episodes (449–462, 405, 886, 927, 1204, 1, 1765, 1795, 1796). No
  `success:false`, no empty sources, no rel URLs.
- `/token/index.m3u8` → 200 `application/vnd.apple.mpegurl`, valid media playlist
  (#EXTM3U, 2173 segments `seg-*.jpg`), also served from two *other* IPs
  (allorigins proxy) → CDN is not challenging other IPs and the token is not IP-bound.
- Homepage `/movies` + `?page=2`: 30 `a.card` each, page 2 fresh — same as FINDINGS#292.
- `/_ajax/search?q=handmaiden` → 200 JSON, unchanged.

## Transport-fingerprint probe (the variable curl cannot cover)

Raw **okhttp 5.0.0-alpha.12** client (the HTTP stack NiceHttp/CloudStream build on)
with NiceHttp-like Chrome UA, no cookies, cold session:

- `/the-handmaiden` → 200 html
- `/watch/the-handmaiden?sv=1&part=1` (referer detail) → 200 html
- `/api/v1/episodes/405/sources` → 200 json
- `<cdn>/index.m3u8` (referer `cat3film.com/`) → 200 mpegurl
- `<cdn>/index.json` → 200 (same playlist) — NOTE: from **plain curl** `/index.json`
  got 403 "Just a moment…" while `/index.m3u8` got 200; okhttp fingerprint passes both.
  CF decisions on `cat3.asuka-vod.site` are fingerprint/IP-scoped, not path-scoped.

## Conclusion

No site-side drift vs FINDINGS #292/#409: parsing selectors, episode ids, sources API
shape and the stream URLs are all byte-identical to ground truth; the whole chain
returns valid data even over an okhttp fingerprint. The report ("No links found") is
therefore one of:

1. **CF challenge on the fetching client, device/network-scoped** — the `sources`
   fetch (`/api/v1/episodes/{id}/sources`) has **no Cloudflare handling**: NiceHttp
   sends a bare request; on a challenged client it returns "Just a moment…" HTML,
   Jackson `readValue<SourcesJson>` throws, the catch swallows, `loadLinks` returns
   `true` with **zero callback invocations** → CS3 shows "No links found". This is the
   only silent-zero-links path in the code.
2. Same class on the `/watch/...` fetch — but that path masks challenge as
   `episodes.ifEmpty { newEpisode("1") }`, which still yields a (wrong) link, so it
   cannot produce "No links found" by itself.

Fix per repo precedent (FullPorner/Film1k): wire `CloudflareKiller` as the interceptor
on Cat3Film's fetches, and move the suffix logic into the pure `Parse.streamUrl()`
so the appended-URL rule is unit-covered (TDD).

## verify.sh result (run 2026-09-11)

## verify.sh rerun post-fix (2026-09-13)

- home `/movies` + `?page=2`: 30 cards each, no duplicates — PASS
- video pages 5×: 200, `h1[class=info-title]` title, `a.badge` year on all 5,
  `section#related a.card` 4 recs each — PASS
- streams (positional --stream-url, chain = sources API → /index.m3u8): 5/5 → 200
  application/vnd.apple.mpegurl — PASS
- search/_ajax: FAIL-by-tool (JSON endpoint has no `a.card`; same recording as
  FINDINGS#292) — provider implements exactly this endpoint; agreement re-proven
  via curl: `?q=hache` → slug/title match `/hache` detail page.
- check 6 skipped fields (no --load-response); RESULT FAIL is solely the
  search FAIL-by-tool above.
- `./gradlew Cat3Film:test` → PASS — 7/7 incl. new Parse.streamUrl tests.
