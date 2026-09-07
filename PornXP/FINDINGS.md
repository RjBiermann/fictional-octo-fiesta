# FINDINGS — pxp.news (2026-09 audit)

## Verdict: OK

## Search
- Provider `search()` uses `?q=` (not `?s=`). Audited `https://pxp.news/?s=red` → 200 with `.item_cont`; reviewer re-probed provider's actual `https://pxp.news/?q=red` → 200, `.item_cont` matched → OK.

## Stream
- video `/videos/915675372201` → `#player source` 360/1080 `https://sd.pornxp.sh/...mp4` → **206 video/mp4**.
