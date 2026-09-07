# FINDINGS — freepornvideos.xxx (2026-09 audit)

## Verdict: BLOCKED from CI (verification impossible from runner)

## Evidence
- `GET https://www.freepornvideos.xxx/search/red/` → **403**, body: `Just a moment...` (Cloudflare JS challenge, `challenges.cloudflare.com`).
- API `/api/json/public/...` also 403-challenged. Whole domain is behind CF for datacenter IPs.
- Site is ALIVE (not DEAD) — app on residential IP likely still fine.

## Recommended outcome
Verify in-app; if broken on-device, run fix-provider with the new selectors.
