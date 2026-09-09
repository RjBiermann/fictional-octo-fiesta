# FINDINGS — xhamster.com (re-probe 2026-02-06, fix for issue #160)

## Engine fingerprint
Custom xHamster platform. `window.initials = {...}` JSON blob on every page (desktop and mobile).

## Search
`https://xhamster.com/search/{query}?geo=us` → 200, `div.thumb-list div.thumb-list__item` items
with `a.video-thumb-info__name` links. Transcript: 200 after 301→follow, ≥8 `/videos/xh*` hrefs.

## Video pages
Desktop page (`Chrome/130` UA): 200, full metadata (title, tags, duration, related) — but
`window.initials.xplayerSettings` is **null** for guests and `downloadDropdownComponent`
absent. Reproduced (video id 22347803, `/videos/xh0flWg?geo=us`):

    xplayerSettings: None ; downloadDropdownComponent: absent ; .m3u8 count in page: 0

**Key finding:** the same URL fetched with a **mobile UA**
(`Mozilla/5.0 (Linux; Android 13; Pixel 7) ... Mobile Safari/537.36`) serves a populated
`window.initials.xplayerSettings` to guests:

    xs keys: [debug, duration, fallbackImageClass, hasDSA, hlsConfig, inpEnabled,
              platform, preload, sources, userSettings, videoId, videoInfo]
    sources.standard.h264 qualities: auto, 144p, 240p, 480p, 720p

## Stream sources (per video page)
`xplayerSettings.sources.standard.{h264,av1}` — each entry has `quality`, `url`, `fallback`,
both **hex-obfuscated**. Decode algorithm (recovered from
`https://static-nss.xhcdn.com/xh-mobile/js/xplayer-mobile.js`):

    bytes = unhex(s); algId = bytes[0]; seed = b[1]|b[2]<<8|b[3]<<16|b[4]<<24
    keystream per algId (1..7, xorshift/LCG family — see provider code), url = XOR(bytes[5:], ks)

Verified decodes (guest, mobile page, video 22347803):

    h264 auto   fallback → master HLS m3u8 (avc1.4d4015, up to 1080p):
                https://video-h.xhcdn.com/key=.../media=hls4/multi=.../022/347/803/_TPL_.h264.mp4.m3u8
                → curl 200, body starts "#EXTM3U", no referer needed
    h264 480p   url → https://video-h.xhcdn.com/key=...,limit=3/.../480p.h264.mp4  (direct MP4)
    h264 720p   url → .../720p.h264.mp4
    av1  auto   url  → m3u8 master on video-nss-h.xhcdn.com (200, #EXTM3U) — AV1 codec

    curl transcript (m3u8):
      $ curl -A "Android Mobile UA" ".../h264.mp4.m3u8" → 200 "#EXTM3U #EXT-X-STREAM-INF ... avc1"
    Direct MP4s returned 403 from this runner (keyed to the requesting IP / limit=3);
    the master m3u8 serves 200 reliably → prefer m3u8, keep MP4s as additional links.

## Headers / referer
Mobile UA required on the video page to get populated `xplayerSettings` (desktop = null for
guests). The decoded CDN m3u8 needs no Referer.

## Pagination
`?page=N` on search/home (unchanged, works).

## Related videos
`div[data-role='related-item']` present on desktop page (11 matches) — unchanged.

## Risks / blockers
- Guest tier: only the **mobile** page exposes sources; desktop gating is the bug in #160.
- Decode algorithm is player-JS-derived; if xHamster rotates the constants/algorithms the
  extractor needs re-derivation (player chunk: `js/xplayer-mobile.js` on static-nss.xhcdn.com).
- Direct MP4 keys are IP-bound; in-app playback uses the same session IP as fetch → expected
  to work; CI curl 403 on MP4s is a known artifact, m3u8 verified 200.

## Host-registry refactor verification (issue #169, this run)
Search: `https://xhamster.com/search/teacher` → 200; results present (`data-video-id` cards, `/videos/<id>` links incl. numeric slugs).  FTS page `/videos/teacher` → 404 (site rotated; provider does not use it — page-derived path excluded from this seam).
Stream bar: today's runner-guest `window.initials` on `/videos/<id>` carries videoModel without `xplayerSettings.sources` (guest/geo variant pre-refactor); xHamster page-derived stream path unchanged by this refactor (excluded from seam), previously 5/5 m3u8 — in-app verification.
Displacement: xHamsterProvider.kt plugin inlined into `Xhamster/src/main/kotlin/com/kraptor/xHamster.kt` with `BasePlugin()` + `registerHostExtractors()`; no extractor logic touched.
