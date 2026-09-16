#!/usr/bin/env bash
# TLS-impersonated fetch — probe-instrument escalation step 2 (see SKILL.md).
# Site answers curl with 403/challenge but is fine for real clients → retry here.
# Transcripts from this script ARE evidence (plain HTTP, only the TLS fingerprint differs).
#
# usage: impersonate.sh [-H 'Name: value' ...] URL
# prints: status line + headers on stderr, response body on stdout
set -euo pipefail

if ! python3 -c 'import curl_cffi' 2>/dev/null; then
  echo "impersonate.sh: curl_cffi missing. Install it, then re-run:" >&2
  echo "  pip install curl_cffi" >&2
  exit 2
fi

exec python3 - "$@" <<'EOF'
import sys
from curl_cffi import requests

args = sys.argv[1:]
headers = {}
while len(args) > 1 and args[0] == "-H":
    k, _, v = args[1].partition(":")
    headers[k.strip()] = v.strip()
    args = args[2:]
url = args[0]

r = requests.get(url, headers=headers, impersonate="chrome", timeout=30, allow_redirects=True)
print(f"HTTP {r.status_code} {r.url}", file=sys.stderr)
for k, v in r.headers.items():
    print(f"{k}: {v}", file=sys.stderr)
sys.stdout.buffer.write(r.content)
EOF
