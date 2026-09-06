#!/usr/bin/env bash
# verify.sh — mechanical live-site checks for a CloudStream provider (verify-provider skill).
# The agent supplies selectors/URLs from FINDINGS; this script asserts them. Exit 0 = all pass.
# --video-url is repeatable (≥5 varied URLs per the skill); --related-selector checks the
# related-videos section on every video page.
set -euo pipefail

UA="Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0"
SEARCH_URL="" SEARCH_SELECTOR="" STREAM_SELECTOR="" QUALITY_ATTR="res" RELATED_SELECTOR=""
VIDEO_URLS=()
HEADERS=(-A "$UA")

usage() { grep '^#' "$0" | sed 's/^# \{0,1\}//' | head -14; exit 2; }
[[ $# -eq 0 ]] && usage
while [[ $# -gt 0 ]]; do
  case "$1" in
    --search-url) SEARCH_URL="$2"; shift 2;;
    --search-selector) SEARCH_SELECTOR="$2"; shift 2;;
    --video-url) VIDEO_URLS+=("$2"); shift 2;;
    --stream-selector) STREAM_SELECTOR="$2"; shift 2;;
    --stream-quality-attr) QUALITY_ATTR="$2"; shift 2;;
    --related-selector) RELATED_SELECTOR="$2"; shift 2;;
    --header) HEADERS+=(-H "$2"); shift 2;;
    *) usage;;
  esac
done
(( ${#VIDEO_URLS[@]} >= 1 )) || { echo "FAIL: at least one --video-url required" >&2; exit 2; }
fail=0

sel_count() {  # selector html_file → count of elements matching the LAST simple selector
  # (each whitespace-separated part must also match somewhere in the document).
  # Supports tag, tag.class, tag#id, tag[attr], tag[attr*=val], and space-composed chains.
  # ponytail: parts are matched independently, not as a real ancestor walk — upgrade to a
  # tiny DOM walk if a false positive ever slips through.
  python3 - "$1" "$2" <<'PY'
import re, sys
sel = sys.argv[1]
html = open(sys.argv[2], encoding='utf-8', errors='replace').read()

def compile_simple(s):
    m = re.match(r'([a-zA-Z]+)((?:[.#][\w-]+|\[[^\]]+\])*)$', s)
    if not m:
        return None
    tag, rest = m.group(1).lower(), m.group(2)
    def match(attrs):
        ok = True
        for cls in re.findall(r'\.([\w-]+)', rest):
            if not re.search(rf'class="[^"]*\b{re.escape(cls)}\b', attrs): ok = False
        for i in re.findall(r'#([\w-]+)', rest):
            if not re.search(rf'\bid\s*=\s*["\'][^"\']*\b{re.escape(i)}\b', attrs): ok = False
        for attr in re.findall(r'\[([^\]]+)\]', rest):
            m2 = re.match(r'([\w-]+)([*^$|]?=)(.*)$', attr.strip())
            if m2:
                a, op, v = m2.group(1), m2.group(2), m2.group(3).strip('\"\'')
                got = re.search(rf'{re.escape(a)}\s*=\s*["\']([^"\']*)', attrs)
                got = got.group(1) if got else ''
                if op == '*=':
                    if v not in got: ok = False
                elif op == '^=':
                    if not got.startswith(v): ok = False
                else:
                    if got != v: ok = False
            else:
                if not re.search(rf'{re.escape(attr)}[=\s>]', attrs): ok = False
        return ok
    return tag, match

def count_simple(tag, match, html):
    n = 0
    for blk in re.finditer(rf'<{tag}\b([^>]*)>', html):
        if match(blk.group(1)): n += 1
    return n

parts = sel.split()
compiled = [compile_simple(p) for p in parts]
if any(c is None for c in compiled):
    print(-1)
else:
    for tag, match in compiled[:-1]:
        if count_simple(tag, match, html) == 0:
            print(0); sys.exit(0)
    tag, match = compiled[-1]
    print(count_simple(tag, match, html))
PY
}

first_stream_url() {  # html_file → first absolute stream URL, fallbacks in FINDINGS-priority order
  python3 - "$1" <<'PY'
import re, sys
html = open(sys.argv[1], encoding='utf-8', errors='replace').read()
for pat in [r'<source[^>]*src=["\'](http[^"\']+)',
            r'"contentUrl"\s*:\s*"([^"\\]+)',
            r'property=["\']og:video(:secure_url)?["\']\s+content=["\']([^"\']+)',
            r'(https?://[^"\'\s]+\.m3u8[^"\'\s]*)']:
    m = re.search(pat, html)
    if m: print(m.group(1)); break
PY
}

echo "── check 1: search page"
code=$(curl -sL "${HEADERS[@]}" -o /tmp/verify_search.html -w '%{http_code}' --max-time 30 "$SEARCH_URL") || code="ERR"
n=$(sel_count "$SEARCH_SELECTOR" /tmp/verify_search.html)
echo "GET $SEARCH_URL → $code; '$SEARCH_SELECTOR' matches: $n"
if [[ "$code" != ERR ]]; then [[ "$code" =~ ^2 ]] && (( n >= 1 )) || { echo "FAIL search"; fail=1; }; else echo "FAIL search (fetch error)"; fail=1; fi

echo "── check 2: video pages + streams (${#VIDEO_URLS[@]} URLs)"
for VIDEO_URL in "${VIDEO_URLS[@]}"; do
  code=$(curl -sL "${HEADERS[@]}" -o /tmp/verify_video.html -w '%{http_code}' --max-time 30 "$VIDEO_URL") || code="ERR"
  n=$(sel_count "$STREAM_SELECTOR" /tmp/verify_video.html)
  echo "GET $VIDEO_URL → $code; '$STREAM_SELECTOR' matches: $n"
  if [[ "$code" == ERR ]]; then echo "FAIL video page ($VIDEO_URL)"; fail=1
  else [[ "$code" =~ ^2 ]] && (( n >= 1 )) || { echo "FAIL video page ($VIDEO_URL)"; fail=1; } fi

  if [[ -n "$RELATED_SELECTOR" ]]; then
    r=$(sel_count "$RELATED_SELECTOR" /tmp/verify_video.html)
    echo "GET $VIDEO_URL → '$RELATED_SELECTOR' matches: $r"
    (( r >= 1 )) || { echo "FAIL related videos ($VIDEO_URL)"; fail=1; }
  fi

  stream_url=$(first_stream_url /tmp/verify_video.html)
  if [[ -n "$stream_url" ]]; then
    hdr=$(curl -sL "${HEADERS[@]}" -H "Range: bytes=0-64" -o /dev/null -w '%{http_code} %{content_type}' --max-time 30 "$stream_url")
    echo "GET stream → $hdr"
    echo "$hdr $stream_url" | grep -qE '(^20[06])' && echo "$hdr" | grep -qiE 'video/mp4|video/webm|application/vnd.apple.mpegurl|mpegurl' || { echo "FAIL stream content-type ($VIDEO_URL)"; fail=1; }
  else
    echo "FAIL: no absolute stream URL extracted from $STREAM_SELECTOR ($VIDEO_URL)"; fail=1
  fi
done

if (( fail )); then echo "RESULT: FAIL"; exit 1; else echo "RESULT: PASS"; fi
