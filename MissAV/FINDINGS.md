# FINDINGS — missav.live (2026-09 audit)

## Verdict: OK (CF risk on stream CDN)

## Search
- `https://missav.live/en/search/red` → 200 with `grid grid-cols-2` cards (`/en/<slug>`).
- Home `dm170/en/weekly-hot` → cards `div.thumbnail.group` with video slugs (`/en/snos-334`). NOTE: `dm169` in provider is 301-redirected to `dm170` (dmNK counter rotates; NiceHttp follows).

## Video page / stream
- Video page contains packed eval; unpack yields `https://surrit.com/<36-char-uuid>/playlist.m3u8` (e.g. for snos-334: `c659215e-5e61-4cd1-9534-3eed843d768a`).
- **Stream CDN surrit.com returns 403 CF challenge for this runner IP** (all referer variants). Flow/selector in provider (getAndUnpack → `[a-f0-9-]{36}`) matches live page.
## Risks
- surrit.com Cloudflare-challenges datacenter IPs — verify in-app; on-device should pass.
