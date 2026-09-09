# FINDINGS — javseen.tv (issue #145 re-probe, 2026-09-14)

## Verdict: provider code intact; primary javhdz embed host had no extractor → added Javhdz/Javhdz2

## Search
- AJAX JSON `https://javseen.tv/search/video/?ajax=search_results&s=teacher&o=recent` → 200,
  `{"status":1,"html":…}` with 25 × `li id=\"video-…\"` items — provider's JSONObject+Jsoup
  path intact. Transcript: curl returns status 1 and escaped `video-285505` etc.
- Note: plain HTML search pages (`/?s=`, `/search/video/?s=`) return a JS-driven landing page
  with **no server-rendered results** — AJAX JSON is the only search source (unchanged since #118).
- verify.sh check-1 caveat: the script matches selectors on literal HTML, so the JSON-wrapped
  (backslash-escaped) markup yields 0 matches even though the endpoint is healthy. Manual
  transcript provided instead.

## Video pages (5 probed: recent ×4, one older)
- `/285509/dmsm-6863-…/`, `/285508/mosaic-babd-024-…/`, `/285506/oba-303-…/`,
  `/285505/waaa-629-…/`, `/285504/english-sub-huntc-553-…/` — all 200, each with
  4–7 × `button.button_choice_server[data-embed]` (base64). Provider load() intact.

## Related videos
- **No related-videos section** — commented-out `No related videos found!` placeholder
  (matches #118 finding; provider's `ul.videos.related li` returns empty, which is correct).

## Stream sources (base64 data-embed decoded, per live pages)
| # | Host | Status 2026-09-14 (runner, datacenter IP) |
|---|---|---|
| 1 | `stream3.javhdz.today/embed.php?p=…` (also seen `stream2.` on other pages) | **Cloudflare "Just a moment" 403** for runner — was a no-op (`loadExtractor` no match). **Fix: added `Javhdz`/`Javhdz2` extractors** (SavedVids playlist pattern — NOT verified: javhdz has 403'd in every probe #97/#118/#126/#145; no decoded payload from this host was ever observed). Hypothesis only; unverified until in-app. |
| 2 | `mycloudz.cc/v/…` | 200, packed eval player; unpack (VidHidePro path) yields `dramiyos-cdn.com/hls2/...master.m3u8` — m3u8 fetch 403 for runner (IP/geo-gated CDN), URL shape valid |
| 3 | `cloudwish.xyz/e/…` | 404 on probe (per-token expiry); #118 confirmed packed eval player intact |
| 4 | `streambeast.upn.one/#…` | 200 shell (Playerupnone/VidStack registered) |
| 5 | `dooood.com/e/…` | 301 → playmogo.com → **403 Cloudflare** for runner (may work in-app) |
| 6 | `streamtape.net/e/…` | 404 (tokens expire fast, per #118) |
| 7 | `turbovid.vip/t/…` | **200**; `data-hash` m3u8 `cdn1.turboviplay.com/.../…m3u8` → **200 `application/vnd.apple.mpegurl`**, master playlist with variant streams — **verified playable from runner** |

## Fix applied
- New `Javseen/src/main/kotlin/com/byayzen/Javhdz.kt`: `Javhdz` (stream3) + `Javhdz2`
  (stream2) — extracts `"playlist":"…m3u8"` from the embed page (SavedVids pattern),
  registered in `JavseenPlugin.load()` next to `registerSharedExtractors()`.
- `version` 10 → 11.

## Risks / blockers
- Cloudflare blocks javhdz.today and dooood/playmogo from datacenter IPs — the javhdz
  playlist regex is copied from SavedVids purely on URL-shape resemblance (embed.php?p=);
  there is NO observed javhdz payload in any probe (#118 recorded only a 403) to ground the
  "same player family" assumption. Could not be executed from this runner; verify in-app
  BEFORE trusting it.
- CloudWish/StreamTape sample embed tokens 404 quickly; not a provider bug.
- turbovid/mycloudz streams verified/shape-verified; runner got 403 only on mycloudz's CDN.

## Host-registry refactor verification (issue #169, this run)
Search: AJAX `…/search/video/?ajax=search_results&s=teacher&o=recent` → 200, JSON html field contains 30 cards (`video-285505` etc.).
Stream chain: data-embed base64 decode → turbovid.vip embed → m3u8 → 200 `mpegurl` (4/5 posts; 5th post had no data-embed upstream).
Javhdz embeds present in pages; javhdz/stream 403s from runner (CF, pre-existing & documented) — in-app-only bar.
Displacement: Javhdz/Javhdz2 adapters moved to `shared/` `com.kraptor.HostAdapters` — code unchanged, registered via `registerHostExtractors()`; javseen's two decode paths (base64 embed and direct) migrated to shared `decodeBase64()`.

## Actors fix (issue #209, 2026-09-09 run)
- Actor is exposed ONLY in the description meta: `…and pornstar Hatano Yui and …`
  (old-style) or `…type mosaic pornstar Momono Yume and …` (word between "type" and
  "pornstar"). No per-video pornstar anchors on the page — all `/pornstar/` hrefs are the
  footer "JAV Idols" directory. Both old code branches (`a[href*='/pornstar/']` in the
  detail box, regex `type pornstar (.+) and`) were dead → actors always empty.
- Fix: pure `JavseenParse.extractActors` (one regex `pornstar\s+(.+?)\s+and\s+` covers both
  shapes) wired into `load()`. Version 12 → 13.
- Fixture tests red→green: `Javseen/src/test/kotlin/com/byayzen/JavseenParseTest.kt`, 4 tests pass.
- Verification 2026-09-09: verify.sh RESULT: PASS (search 2 pages ×30 cards, homepage
  2 pages ×30 cards, 3 sampled pages 200 + embed buttons, cloudwish two-hop streams
  resolved → 206 application/vnd.apple.mpegurl on all three; load-response fields
  actors/tags/plot/duration/year/posters populated in Kotlin). All-surface
  grep evidence: video-285424 actors=[Hatano Yui] tags=10 duration=8280 year=2022;
  video-285650 [Momono Yume]/15/8400/2022; video-285600 [Sanae Misono]/14/3600/2025.
  quick search: none on site — hasQuickSearch=false confirmed.
