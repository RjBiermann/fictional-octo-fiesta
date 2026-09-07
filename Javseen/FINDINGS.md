# FINDINGS — javseen.tv (issue #118 re-probe, 2026-09-08)

## Verdict: provider drift confirmed — embeds moved to hosts not registered by JavseenPlugin

## Search
- ajax `https://javseen.tv/search/video/?ajax=search_results&s=nurse&o=recent` → 200, JSON `{"status":1,"html":…}` with 30 escaped `li id=\"video-…\"` items — provider's JSONObject+Jsoup path intact.

## Video pages (5 probed: recent ×2, category, search, search)
- `/285509/dmsm-6863-…/`, `/285508/mosaic-babd-024-…/`, `/285452/caribbeancom-cr-041626-001-…/`, `/285502/mida-593-…/`, `/285484/mosaic-kam-141-…/` — all 200, 6–7× `button.button_choice_server[data-embed]` (base64) each.

## Related videos
- **No related-videos section** — markup is a commented-out `No related videos found!` placeholder on all probed pages. Provider's `ul.videos.related li` selector returns empty (matches site, not a bug).

## Stream sources (per page, base64 data-embed decoded)
| Host | Coverage after fix |
|---|---|
| `cloudwish.xyz/e/…` | CloudWish (registered) — packed eval player confirmed live |
| `dooood.com/e/…` | Dooood (registered) — **Cloudflare 403 for runner** (may differ in-app) |
| `streamtape.net/e/…` | StreamTapeNet (registered) — sample link 404 (tokens expire fast) |
| `mycloudz.cc/v/…` | MyCloudZ (registered) — VidHide jwplayer confirmed live |
| `lulustream.fit/e/…` | **new LULUSTREAMFIT : LULUSTREAM** (was only lulustream.com) |
| `turbovid.vip/t/…` | **new TurbovidVip : Turtleviplay** — `data-hash` m3u8, verified `200 application/vnd.apple.mpegurl` |
| `streambeast.upn.one/#…` | **new StreamBeastUpn : Playerupnone (VidStack)** |
| `worker4.savedvids.com/embed.php?p=…` | **new SavedVids extractor** — `var FIRST = {"playlist": …master.m3u8}`, verified `200 application/vnd.apple.mpegurl` |
| `stream2.javhdz.today/embed.php?p=…` | Cloudflare "Just a moment" 403 for runner — left unhandled (same host family as savedvids; same player markup) |

## Old behavior
- `JavseenPlugin.load()` called `registerSharedExtractors(listOf(FileMoonSx(), Filemoon()))` — the full shared manifest plus `FileMoonSx()`/`Filemoon()` as first-extras. The fix drops that first list (filemoon hosts had zero overlap with live embeds) and registers the full shared manifest, which gains the new TurbovidVip/SavedVids/StreamBeastUpn/LULUSTREAMFIT extractors.

## Risks / blockers
- `dooood.com` and `javhdz.today` Cloudflare-block the runner (403 "Just a moment") — untestable from CI, verify in-app.
- Streamtape tokens single-use/short-lived → 404 on probe is expected.
- Stream content-type per-host was verified manually (turbovid, savedvids → 200 m3u8); verify.sh's in-page m3u8 fallback can't see base64 iframe embeds, so its stream step reports FAIL while search/video-page steps PASS.
