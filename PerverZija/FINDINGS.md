# FINDINGS — tube.perverzija.com (2026-09 audit)

## Verdict: BROKEN (extractor domain mismatch)

## Search — OK
- `/?s=red` → 200, `div.col-md-3` ×69, `div.title a` hrefs OK.

## Video page
- `iframe src="https://pervl1.xtremestream.xyz/player/index.php?data=a667f4e7b0c8a3babe331569d3eac6bd"` (pervl1!).

## Stream — extractor not matched
- Provider registers ONLY `PerverZijaExtractor` with `mainUrl=https://pervl2.xtremestream.xyz`. Player iframe serves from **pervl1** → loadExtractor finds no matching extractor → no stream.
- Player page: `m3u8_loader_url = https://pervl1.xtremestream.xyz/player/xs1.php?data=`; xs1.php serves m3u8 **only with referer of the player domain** (`pervl1.xtremestream.xyz/player/index.php` OK, `pervl2...` OK, `tube.perverzija.com` 403, none 403). q=480/720/1080 all 200.

## Fix
Register extractor for both subdomains (pervl1 + pervl2), keep referer on extractor mainUrl.
