#!/usr/bin/env bash
# verify.sh — mechanical live-site checks for a CloudStream provider (verify-provider skill).
# The agent supplies selectors/URLs from FINDINGS; this script asserts them. Exit 0 = all pass.
set -euo pipefail

UA="Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0"
SEARCH_URL="" SEARCH_SELECTOR="" VIDEO_URL="" STREAM_SELECTOR="" QUALITY_ATTR="res"
HEADERS=(-A "$UA")

usage() { grep '^#' "$0" | sed 's/^# \{0,1\}//' | head -12; exit 2; }
[[ $# -eq 0 ]] && usage
while [[ $# -gt 0 ]]; do
  case "$1" in
    --search-url) SEARCH_URL="$2"; shift 2;;
    --search-selector) SEARCH_SELECTOR="$2"; shift 2;;
    --video-url) VIDEO_URL="$2"; shift 2;;
    --stream-selector) STREAM_SELECTOR="$2"; shift 2;;
    --stream-quality-attr) QUALITY_ATTR="$2"; shift 2;;
    --header) HEADERS+=(-H "$2"); shift 2;;
    *) usage;;
  esac
done
fail=0

sel_count() {  # selector html_file → count (supports tag, tag.class, tag[attr], tag[attr*=val])
  python3 - "$1" "$2" <<'PY'
import re, sys
sel = sys.argv[1]
html = open(sys.argv[2], encoding='utf-8', errors='replace').read()
m = re.match(r'([a-zA-Z]+)((?:\.[\w-]+|\[[^\]]+\])*)$', sel)
if not m:
    print(-1); sys.exit(1)
tag, rest = m.group(1).lower(), m.group(2)
n = 0
for blk in re.finditer(rf'<{tag}\b([^>]*)>', html):
    attrs = blk.group(1)
    ok = True
    for cls in re.findall(r'\.([\w-]+)', rest):
        if not re.search(rf'class="[^"]*\b{re.escape(cls)}\b', attrs): ok = False; break
    for attr in re.findall(r'\[([^\]]+)\]', rest):
        m2 = re.match(r'([\w-]+)([*^$|]?=)(.*)$', attr.strip())
        if m2:
            a, op, v = m2.group(1), m2.group(2), m2.group(3).strip('\"\'')
            got = re.search(rf'{re.escape(a)}\s*=\s*["\']([^"\']*)', attrs)
            got = got.group(1) if got else ''
            if op == '*=':
                if v not in got: ok = False; break
            elif op == '^=':
                if not got.startswith(v): ok = False; break
            else:
                if got != v: ok = False; break
        else:
            if not re.search(rf'{re.escape(attr)}[=\s>]', attrs): ok = False; break
    n += ok
print(n)
PY
}

echo "── check 1: search page"
code=$(curl -sL "${HEADERS[@]}" -o /tmp/verify_search.html -w '%{http_code}' --max-time 30 "$SEARCH_URL")
n=$(sel_count "$SEARCH_SELECTOR" /tmp/verify_search.html)
echo "GET $SEARCH_URL → $code; '$SEARCH_SELECTOR' matches: $n"
[[ "$code" =~ ^2 ]] && (( n >= 1 )) || { echo "FAIL search"; fail=1; }

echo "── check 2: video page + stream"
code=$(curl -sL "${HEADERS[@]}" -o /tmp/verify_video.html -w '%{http_code}' --max-time 30 "$VIDEO_URL")
n=$(sel_count "$STREAM_SELECTOR" /tmp/verify_video.html)
echo "GET $VIDEO_URL → $code; '$STREAM_SELECTOR' matches: $n"
[[ "$code" =~ ^2 ]] && (( n >= 1 )) || { echo "FAIL video page"; fail=1; }

stream_url=$(python3 -c "
import re,sys
html=open('/tmp/verify_video.html').read()
for m in re.finditer(r'<source[^>]*src=\"([^\"]+)\"', html):
    u=m.group(1)
    if u.startswith('http'): print(u); break
" 2>/dev/null || true)
if [[ -n "$stream_url" ]]; then
  hdr=$(curl -s "${HEADERS[@]}" -H "Range: bytes=0-64" -o /dev/null -w '%{http_code} %{content_type}' --max-time 30 "$stream_url")
  echo "GET stream → $hdr"
  echo "$hdr $stream_url" | grep -qE '(^20[06])' && echo "$hdr" | grep -qiE 'video/mp4|video/webm|application/vnd.apple.mpegurl|mpegurl' || { echo "FAIL stream content-type"; fail=1; }
else
  echo "FAIL: no absolute stream URL extracted from $STREAM_SELECTOR"; fail=1
fi

if (( fail )); then echo "RESULT: FAIL"; exit 1; else echo "RESULT: PASS"; fi
