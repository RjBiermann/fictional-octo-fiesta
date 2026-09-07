# FINDINGS — javseen.tv (2026-09 audit)

## Verdict: OK

## Search
- ajax `https://javseen.tv/search/video/?ajax=search_results&s=red&o=recent` → JSON `{"status":1,"html":...}` with `li id="video-284049"` — provider's JSONObject+Jsoup path matches `li[id^=video-]`.

## Video page
- `/284049/ebod-002-e-body-red-apple/` → 3 `button_choice_server` with `data-embed` base64:
  - MyFast `stream3.javhdz.today/embed.php?p=...` → **Cloudflare "Just a moment" for runner** (may differ on device)
  - Streamwish `https://cloudwish.xyz/e/652nrni4yacb` → packed eval player; unpack gives `/stream/...master.m3u8` + full `https://<sub>.premilkyway.com/hls2/.../master.m3u8?t=...` → **200 m3u8** (CloudWish extractor handles)
  - Dood embed
