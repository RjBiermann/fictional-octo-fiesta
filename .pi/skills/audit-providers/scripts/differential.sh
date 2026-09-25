#!/usr/bin/env bash
# Differential probe — decides Suspected findings from multiple vantage points.
# Usage: scripts/differential.sh <url>
# Tiers: plain curl | TLS-impersonated (curl_cffi) | residential proxy ($RESIDENTIAL_PROXY_URL).
# Without the proxy secret, print HITL instructions instead of guessing.
set -euo pipefail
url="${1:?usage: differential.sh <url>}"
ua="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

echo "== differential: $url"

plain=$(curl -sS --http2 --compressed -L -A "$ua" \
  -H "Accept: text/html,application/xhtml+xml" -H "Accept-Language: en-US,en;q=0.9" \
  --max-time 25 -o /tmp/diff_plain.html -w "%{http_code}" "$url" 2>/dev/null || echo 000)
echo "plain-curl:        $plain"

tls=$(python3 - "$url" "$ua" <<'EOF' 2>/dev/null || echo 000
import sys
try:
    from curl_cffi import requests
    r = requests.get(sys.argv[1], impersonate="chrome", timeout=25)
    print(r.status_code)
except Exception:
    print(000)
EOF
)
echo "tls-impersonated:  $tls"

if [ -n "${RESIDENTIAL_PROXY_URL:-}" ]; then
  res=$(curl -sS --http2 --compressed -L -x "$RESIDENTIAL_PROXY_URL" -A "$ua" \
    -H "Accept: text/html,application/xhtml+xml" --max-time 30 \
    -o /tmp/diff_res.html -w "%{http_code}" "$url" 2>/dev/null || echo 000)
  echo "residential-proxy: $res"
else
  echo "residential-proxy: NOT CONFIGURED"
  echo "  HITL: run this same command from a residential machine (laptop/phone hotspot)"
  echo "  and paste the HTTP code back into the finding — the differential is the deciding tier."
fi

echo "verdict rule: all-agree = confirmed; challenge only on plain-curl = Suspected (probe-IP artifact)"
