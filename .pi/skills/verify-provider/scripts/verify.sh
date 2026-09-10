#!/usr/bin/env bash
# verify.sh — mechanical live-site checks for a CloudStream provider (verify-provider skill).
# The agent supplies selectors/URLs from FINDINGS; this script asserts them. Exit 0 = all pass.
#
# Distinct bar (glossary: Distinct) — per-video identity fields (title, plot, poster, stream)
# must differ across sampled videos; search results must not duplicate a video; a video's
# search entry and its load page must agree on title and poster. Repeatable fields (tags,
# actors, year, duration, score) are NEVER distinctness-checked — they legitimately repeat.
#
# Checks:
#  1. search page(s) fetch, ≥1 card each, no duplicate cards (within or across pages);
#     page 2 sharing any card with page 1 = duplicate = FAIL (pagination must return new items)
#  1a. homepage page(s) (getMainPage rows): same bar as search — page 1 always, page 2 too
#      unless FINDINGS records "homepage does not paginate"
#  1b. quick search, when FINDINGS records a distinct quick-search endpoint: fetch, ≥1 card,
#      no duplicate cards (single page, no pagination)
#  2. every video page fetches, matches --stream-selector, yields a title; poster/plot are
#     all-or-none across sampled pages (some-but-not-all = FAIL; none = site doesn't expose)
#  2a. tags/actors/year/duration selectors (from FINDINGS): all-or-none across sampled pages;
#      a selector omitted = NOTE — FINDINGS must then state the site doesn't expose the field
#  3. every stream URL per video page (≤5) serves video; stream paths distinct within a page
#     and across videos
#  4. related-videos selector matches; rec titles non-empty, distinct, not the video itself
#  5. a video that appears in search results agrees with its load page (title + poster)
#  6. --load-response fields are populated in the provider Kotlin (code half of Data-complete;
#     the live half is checks 1–5)
set -euo pipefail

UA="Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0"
SEARCH_URLS=() SEARCH_SELECTOR="" STREAM_SELECTOR="" QUALITY_ATTR="res" RELATED_SELECTOR=""
PROVIDER_SRC="" LOAD_RESPONSE=""
SEARCH_TITLE_SEL="" SEARCH_POSTER_SEL="" VIDEO_TITLE_SEL="" VIDEO_POSTER_SEL="" VIDEO_PLOT_SEL=""
HOME_URLS=() HOME_SELECTOR="" QSEARCH_URLS=() QSEARCH_SELECTOR=""
VIDEO_TAGS_SEL="" VIDEO_ACTORS_SEL="" VIDEO_YEAR_SEL="" VIDEO_DURATION_SEL=""
VIDEO_URLS=()
STREAM_URL_OVERRIDES=()  # --stream-url (repeatable): JS-built stream URLs per video page,
                         # position-matched to --video-url; agent supplies from FINDINGS chain
                         # evidence, script still asserts serving/content-type/distinctness
HEADERS=(-A "$UA")

usage() { grep '^#' "$0" | sed 's/^# \{0,1\}//' | head -26; exit 2; }
[[ $# -eq 0 ]] && usage
while [[ $# -gt 0 ]]; do
  case "$1" in
    --search-url) SEARCH_URLS+=("$2"); shift 2;;
    --search-selector) SEARCH_SELECTOR="$2"; shift 2;;
    --search-title-selector) SEARCH_TITLE_SEL="$2"; shift 2;;
    --search-poster-selector) SEARCH_POSTER_SEL="$2"; shift 2;;
    --home-url) HOME_URLS+=("$2"); shift 2;;
    --home-selector) HOME_SELECTOR="$2"; shift 2;;
    --quick-search-url) QSEARCH_URLS+=("$2"); shift 2;;
    --quick-search-selector) QSEARCH_SELECTOR="$2"; shift 2;;
    --video-tags-selector) VIDEO_TAGS_SEL="$2"; shift 2;;
    --video-actors-selector) VIDEO_ACTORS_SEL="$2"; shift 2;;
    --video-year-selector) VIDEO_YEAR_SEL="$2"; shift 2;;
    --video-duration-selector) VIDEO_DURATION_SEL="$2"; shift 2;;
    --video-url) VIDEO_URLS+=("$2"); shift 2;;
    --video-title-selector) VIDEO_TITLE_SEL="$2"; shift 2;;
    --video-poster-selector) VIDEO_POSTER_SEL="$2"; shift 2;;
    --video-plot-selector) VIDEO_PLOT_SEL="$2"; shift 2;;
    --stream-selector) STREAM_SELECTOR="$2"; shift 2;;
    --stream-url) STREAM_URL_OVERRIDES+=("$2"); shift 2;;
    --stream-quality-attr) QUALITY_ATTR="$2"; shift 2;;
    --related-selector) RELATED_SELECTOR="$2"; shift 2;;
    --provider-src) PROVIDER_SRC="$2"; shift 2;;
    --load-response) LOAD_RESPONSE="$2"; shift 2;;
    --header) HEADERS+=(-H "$2"); shift 2;;
    *) usage;;
  esac
done
(( ${#VIDEO_URLS[@]} >= 1 )) || { echo "FAIL: at least one --video-url required" >&2; exit 2; }
(( ${#SEARCH_URLS[@]} >= 1 )) || { echo "FAIL: at least one --search-url required" >&2; exit 2; }
if [[ $(printf '%s\n' "${VIDEO_URLS[@]}" | sort | uniq -d | wc -l) -gt 0 ]]; then
  echo "FAIL: duplicate --video-url inputs (self-comparison would fake-FAIL distinctness)" >&2; exit 2
fi
[[ -n "$VIDEO_TITLE_SEL" ]] || VIDEO_TITLE_SEL="meta[property=og:title]"
[[ -n "$VIDEO_POSTER_SEL" ]] || VIDEO_POSTER_SEL="meta[property=og:image]"
[[ -n "$VIDEO_PLOT_SEL" ]] || VIDEO_PLOT_SEL="meta[property=og:description]"

fail=0

# ── python helper: selector counting / extraction / normalization / duplicate detection ──
# ponytail: regex "DOM" — a card is the LAST selector part's opening tag through its closing
# tag, so a card whose inner HTML contains another same-tag element truncates the block.
# Upgrade to a real DOM walk if a false positive ever slips through.
PYDOM=/tmp/verify_pydom.py
cat > "$PYDOM" <<'PY'
import re, sys, html as htmlmod, base64
from urllib.parse import urlsplit

def compile_simple(s):
    m = re.match(r'([a-zA-Z][a-zA-Z0-9]*)((?:[.#][\w-]+|\[[^\]]+\])*)$', s)
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
                a, op, v = m2.group(1), m2.group(2), m2.group(3).strip("\"'")
                got = re.search(rf'\b{re.escape(a)}\s*[\*\^\$|]?\s*=\s*["\']([^"\']*)', attrs)
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

def blocks(html, sel):
    """(attrs, inner) per element matching the LAST part of a (possibly chained) selector."""
    tag, match = compile_simple(sel.split()[-1])
    for blk in re.finditer(rf'<{tag}\b([^>]*)>(.*?)</{tag}\s*>', html, re.S):
        if match(blk.group(1)):
            yield blk.group(1), blk.group(2)

def inner_text(s):
    s = re.sub(r'<[^>]+>', ' ', s)
    s = htmlmod.unescape(s)
    return re.sub(r'\s+', ' ', s).strip()

def sub_field(innerHTML, sel, attr):
    """attr value (or stripped inner text when attr=='text') of the first match in a block."""
    if not sel:
        return ''
    tag, match = compile_simple(sel.split()[-1])
    if attr != 'text':
        for blk in re.finditer(rf'<{tag}\b([^>]*)>', innerHTML):
            if match(blk.group(1)):
                m = re.search(rf'{re.escape(attr)}\s*=\s*["\']([^"\']*)', blk.group(1))
                if m and m.group(1):
                    return m.group(1)
        return ''
    for _, inner in blocks(innerHTML, sel):
        return inner_text(inner)
    return ''

def url_path(u):
    u = htmlmod.unescape((u or '').strip().replace('\\/', '/'))
    if u.startswith('//'):
        u = 'https:' + u
    return urlsplit(u).path.rstrip('/') or u

def cards_html(html, selector, title_sel, poster_sel):
    """TSV href/title/poster per card. Default title = card text; default poster = first img."""
    for attrs, inner in blocks(html, selector):
        m = re.search(r'href\s*=\s*["\']([^"\']*)', attrs)
        href = m.group(1) if m else ''
        if not href:  # card root wraps the link (article.loop-post > a): take the inner a href
            href = sub_field(inner, 'a', 'href')
        title = sub_field(inner, title_sel, 'text') if title_sel else inner_text(inner)
        poster = sub_field(inner, poster_sel, 'src') if poster_sel else sub_field(inner, 'img', 'src')
        if '/img/placeholder.png' in poster or poster.startswith('data:'):  # lazy-poster theme: real URL is in data-src
            poster = sub_field(inner, poster_sel or 'img', 'data-src')
        print(f'{href}\t{title}\t{poster}')

def stream_urls(html):
    pats = [r'video_url\s*:\s*\'(http[^\']+)',
            r'<source[^>]*src=["\'](http[^"\']+)',
            r'<source[^>]*src=["\'](//[^"\']+)',
            r'"contentUrl"\s*:\s*"((?:[^"\\]|\\.)+)',
            r'property=["\']og:video(:secure_url)?["\']\s+content=["\']([^"\']+)',
            r'(https?://[^"\'\s]+\.m3u8[^"\'\s]*)',
            r'itemprop=["\']contentUrl["\']\s+content=["\']([^"\']+)',
            r'content=["\']([^"\']+)["\']\s+itemprop=["\']contentUrl']
    out, seen = [], set()
    for pat in pats:
        for m in re.finditer(pat, html):
            link = m.group(m.lastindex) if m.lastindex else m.group(0)
            if re.search(r'\.(jpe?g|png|webp|gif)([?#]|$)|gravatar\.com', link):
                continue  # poster/avatar image, not a stream
            link = ('https:' + link if link.startswith('//') else link.replace('\\/', '/')).strip()
            mm = re.match(r'https?://([A-Za-z0-9+/=]+)\.m3u8$', link)
            if mm:  # eroticmv-style: "http://<base64>.m3u8" hides the real HLS URL
                try:
                    tok = mm.group(1) + '=' * ((4 - len(mm.group(1)) % 4) % 4)
                    link = base64.b64decode(tok).decode()
                except Exception:
                    pass
            if link not in seen:
                seen.add(link)
                out.append(link)
                if len(out) >= 5:
                    return out
    return out

def dups(colspec):
    """stdin: id TAB …columns…; prints 'field value ids' for values shared by different ids
    (across pages/videos) or repeated within one id (within a page)."""
    cols = colspec.split(',')
    rows = [l.rstrip('\n').split('\t') for l in sys.stdin if l.strip()]
    for ci, col in enumerate(cols, start=1):
        seen = {}
        for r in rows:
            v = url_path(r[ci]) if col in ('href', 'poster', 'stream') else inner_text(r[ci]).lower()
            if not v:
                continue
            seen.setdefault(v, []).append(r[0])
        for v, ids in sorted(seen.items()):
            if len(set(ids)) > 1 or len(ids) > 1:
                print(f'{col}\t{v}\t{",".join(ids)}')

cmd = sys.argv[1]
if cmd == 'count':  # selector file → match count of the LAST selector part
    sel, html = sys.argv[2], open(sys.argv[3], encoding='utf-8', errors='replace').read()
    parts = [compile_simple(p) for p in sel.split()]
    if any(c is None for c in parts):
        print(-1)
    else:
        for tag, match in parts[:-1]:
            if count_simple(tag, match, html) == 0:
                print(0); sys.exit(0)
        tag, match = parts[-1]
        print(count_simple(tag, match, html))
elif cmd == 'field':  # selector attr file → first matching attr ('text' → inner text)
    sel, attr = sys.argv[2], sys.argv[3]
    html = open(sys.argv[4], encoding='utf-8', errors='replace').read()
    if attr == 'text':
        for _, inner in blocks(html, sel):
            print(inner_text(inner)); break
    else:
        tag, match = compile_simple(sel.split()[-1])
        for blk in re.finditer(rf'<{tag}\b([^>]*)>', html):
            if match(blk.group(1)):
                m = re.search(rf'{re.escape(attr)}\s*=\s*["\']([^"\']*)', blk.group(1))
                if m:
                    print(m.group(1))
                break
elif cmd == 'cards':  # selector title_sel poster_sel file → TSV ('-' = default)
    cards_html(open(sys.argv[5], encoding='utf-8', errors='replace').read(),
               sys.argv[2], '' if sys.argv[3] == '-' else sys.argv[3],
               '' if sys.argv[4] == '-' else sys.argv[4])
elif cmd == 'streams':  # file → up to 5 unique absolute stream URLs
    for u in stream_urls(open(sys.argv[2], encoding='utf-8', errors='replace').read()):
        print(u)
elif cmd == 'path':  # url → normalized path (scheme/query stripped)
    print(url_path(sys.argv[2]))
elif cmd == 'normcards':  # id href title poster TSV → id path title poster
    for l in sys.stdin:
        f = (l.rstrip('\n').split('\t') + [''] * 4)[:4]
        print(f'{f[0]}\t{url_path(f[1])}\t{f[2]}\t{f[3]}')
elif cmd == 'dups':  # colspec; stdin TSV, id first
    dups(sys.argv[2])
PY
py() { python3 "$PYDOM" "$@"; }
norm() { python3 -c 'import sys,html,re;print(re.sub(r"\s+"," ",html.unescape(sys.stdin.read())).strip().lower())'; }

# ── listing-page check shared by search / homepage / quick search ──
# Fetch each URL, require 200 + ≥1 card, dedupe within/across pages (a card repeating on
# page 2 = pagination returning the same items = FAIL), write normalized TSV to $6.
check_listing() {  # $1=urls-array-name $2=prefix $3=selector $4=title_sel $5=poster_sel $6=out.tsv
  local -n _urls=$1; local prefix=$2 sel=$3 tsel=$4 psel=$5 out=$6
  local raw=/tmp/verify_${prefix}_raw.tsv i SU F code n dups_out
  : > "$raw"
  for i in "${!_urls[@]}"; do
    SU="${_urls[$i]}"; F="/tmp/verify_${prefix}_$i.html"
    code=$(curl -sL "${HEADERS[@]}" -o "$F" -w '%{http_code}' --max-time 30 "$SU") || code="ERR"
    n=$(py count "$sel" "$F")
    echo "GET $SU → $code; '$sel' matches: $n"
    if [[ "$code" == ERR ]] || ! [[ "$code" =~ ^2 && $n -ge 1 ]]; then
      echo "FAIL $prefix ($SU)"; fail=1
    fi
    py cards "$sel" "${tsel:--}" "${psel:--}" "$F" \
      | awk -F'\t' -v p="${prefix}$i" 'BEGIN{OFS="\t"}{print p,$1,$2,$3}' >> "$raw"
  done
  dups_out=$(py dups 'href,title' < "$raw")
  if [[ -n "$dups_out" ]]; then
    echo "FAIL duplicate $prefix cards (same video twice on a page or across pages):"
    echo "$dups_out"; fail=1
  fi
  poster_dups=$(py dups 'poster' < "$raw")
  if [[ -n "$poster_dups" ]]; then
    echo "NOTE: repeated poster on $prefix across different videos (episodes of one series share the series poster — not a card duplicate):"
    echo "$poster_dups" | head -3
  fi
  py normcards < "$raw" > "$out"
}

# two-hop embed fallback (filmcdm-style sites: stream lives behind a packed embed page)
embed_stream_url() {  # video page → first m3u8 from its click-loaded embed pages
  python3 - "$1" <<'PY'
import re, sys, urllib.request
html = open(sys.argv[1], encoding='utf-8', errors='replace').read()
embeds = re.findall(r'https://(?:filmcdm\.top|s2\.filmcdn\.top)/e/[A-Za-z0-9_\-]+', html)
embeds.sort(key=lambda u: 's2.filmcdn' in u)  # cfglobalcdn sibling is geo-blocked on many runners
if not embeds:
    sys.exit(0)
req = urllib.request.Request(embeds[0], headers={'User-Agent': 'Mozilla/5.0'})
try:
    page = urllib.request.urlopen(req, timeout=30).read().decode('utf-8', 'replace')
except Exception:
    sys.exit(0)
m = re.search(r"eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)\)", page, re.S)
if m:  # Dean Edwards packer: decode the jwplayer config like the extractor does
    payload, radix, keys = m.group(1).replace("\\'", "'"), int(m.group(2)), m.group(4).split('|')
    digits = '0123456789abcdefghijklmnopqrstuvwxyz'
    def to_radix(n, r):
        return (to_radix(n // r, r) + digits[n % r]) if n >= r else digits[n]
    kmap = {to_radix(i, radix): v for i, v in enumerate(keys) if v}
    page = re.sub(r'\b[a-z0-9]+\b', lambda w: kmap.get(w.group(0), w.group(0)), payload, flags=re.I)
m = re.search(r'"hls\d":"([^"]*master\.m3u8[^"]*)"', page) or re.search(r'https://[^\'"\s]{20,}?\.m3u8[^\'"\s]*', page)
if m:
    link = m.group(m.lastindex) if m.lastindex else m.group(0)
    if not link.startswith('http'):
        link = 'https://' + re.sub(r'^https?://', '', embeds[0]).split('/')[0] + link
    print(link)
PY
}

# ── check 1: search pages ──
echo "── check 1: search pages (${#SEARCH_URLS[@]})"
check_listing SEARCH_URLS search "$SEARCH_SELECTOR" "$SEARCH_TITLE_SEL" "$SEARCH_POSTER_SEL" /tmp/verify_cards_norm.tsv

# ── check 1a: homepage pages (getMainPage rows) ──
echo "── check 1a: homepage pages (${#HOME_URLS[@]})"
if (( ${#HOME_URLS[@]} >= 1 )); then
  check_listing HOME_URLS home "${HOME_SELECTOR:-$SEARCH_SELECTOR}" - - /tmp/verify_home_norm.tsv
  (( ${#HOME_URLS[@]} >= 2 )) || echo "NOTE: single --home-url — add page 2 as well unless FINDINGS records 'homepage does not paginate'"
else
  echo "FAIL: no --home-url — homepage rows (getMainPage) unverified"; fail=1
fi

# ── check 1b: quick search ──
echo "── check 1b: quick search (${#QSEARCH_URLS[@]})"
if (( ${#QSEARCH_URLS[@]} >= 1 )); then
  check_listing QSEARCH_URLS qsearch "${QSEARCH_SELECTOR:-$SEARCH_SELECTOR}" "${SEARCH_TITLE_SEL:--}" "${SEARCH_POSTER_SEL:--}" /tmp/verify_qsearch_norm.tsv
else
  echo "NOTE: no --quick-search-url — required when FINDINGS records a distinct quick-search endpoint; its absence must be explicit in FINDINGS"
fi

# ── checks 2/3: video pages, identity fields, streams ──
echo "── check 2: video pages (${#VIDEO_URLS[@]} URLs)"
V_TSV=/tmp/verify_videos.tsv   # id \t urlpath \t title \t poster \t plot
: > "$V_TSV"
: > /tmp/verify_all_streams.tsv
for i in "${!VIDEO_URLS[@]}"; do
  VU="${VIDEO_URLS[$i]}"; F="/tmp/verify_video_$i.html"
  code=$(curl -sL "${HEADERS[@]}" -o "$F" -w '%{http_code}' --max-time 30 "$VU") || code="ERR"
  n=$(py count "$STREAM_SELECTOR" "$F")
  echo "GET $VU → $code; '$STREAM_SELECTOR' matches: $n"
  if [[ "$code" == ERR ]]; then echo "FAIL video page ($VU)"; fail=1
  else [[ "$code" =~ ^2 ]] && (( n >= 1 )) || { echo "FAIL video page ($VU)"; fail=1; } fi

  vtitle=$(py field "$VIDEO_TITLE_SEL" content "$F")
  [[ -z "$vtitle" ]] && vtitle=$(py field "$VIDEO_TITLE_SEL" text "$F")  # h1.title-style page titles
  vposter=$(py field "$VIDEO_POSTER_SEL" content "$F")
  vplot=$(py field "$VIDEO_PLOT_SEL" content "$F")
  vpath=$(py path "$VU")
  if [[ -z "$vtitle" ]]; then echo "FAIL video title missing ($VU)"; fail=1; fi
  printf '%s\t%s\t%s\t%s\t%s\n' "video$i" "$vpath" "$vtitle" "$vposter" "$vplot" >> "$V_TSV"

  # ── streams: every extracted URL (≤5) must serve video ──
  # --stream-url override (position-matched): for sites whose streams are JS-built at play
  # time the static page has no stream URL — the agent supplies the URL the provider's chain
  # resolves (evidence in FINDINGS); the mechanical checks below are unchanged.
  if [[ ${#STREAM_URL_OVERRIDES[@]} -gt 0 ]]; then
    (( i < ${#STREAM_URL_OVERRIDES[@]} )) || { echo "FAIL: --stream-url count (${#STREAM_URL_OVERRIDES[@]}) must match --video-url count (${#VIDEO_URLS[@]})"; fail=1; }
    [[ -n "${STREAM_URL_OVERRIDES[$i]:-}" ]] && surls=("${STREAM_URL_OVERRIDES[$i]}") || surls=()
  else
    mapfile -t surls < <(py streams "$F")
  fi
  if [[ ${#surls[@]} -eq 0 ]] && declare -F embed_stream_url >/dev/null && [[ ${#STREAM_URL_OVERRIDES[@]} -eq 0 ]]; then
    surls=($(embed_stream_url "$F" || true))
    [[ ${#surls[@]} -gt 0 ]] && echo "  (two-hop embed resolved: ${surls[0]:0:80}…)"
  fi
  if [[ ${#surls[@]} -eq 0 ]]; then
    echo "FAIL: no absolute stream URL extracted from $STREAM_SELECTOR ($VU)"; fail=1
  fi
  qn=$(py field "$STREAM_SELECTOR" "$QUALITY_ATTR" "$F" 2>/dev/null || true)
  [[ -n "$qn" ]] && echo "  quality attr '$QUALITY_ATTR' present (informational)"
  SP=/tmp/verify_streams_$i.paths; : > "$SP"
  for su in "${surls[@]:-}"; do
    [[ -z "$su" ]] && continue
    py path "$su" >> "$SP"
    hdr=$(curl -sL "${HEADERS[@]}" -H "Range: bytes=0-64" -o /dev/null -w '%{http_code} %{content_type}' --max-time 30 "$su") || hdr="000 ERR"
    if [[ "$hdr" == 403* || "$hdr" == 000* ]]; then
      # rotating-redirect hosts (e.g. sora CDN → per-request tunnel) round-robin; a dead
      # tunnel 403s one hop and slow hops time out. One retry picks a live/fast tunnel.
      sleep 2
      hdr=$(curl -sL "${HEADERS[@]}" -H "Range: bytes=0-64" -o /dev/null -w '%{http_code} %{content_type}' --max-time 60 "$su") || hdr="000 ERR"
    fi
    echo "GET stream (${su:0:80}…) → $hdr"
    if [[ "$hdr $su" =~ ^20[06] ]]; then
      if ! echo "$hdr" | grep -qiE 'video/mp4|video/webm|application/vnd.apple.mpegurl|mpegurl'; then
        body=$(curl -sL "${HEADERS[@]}" --max-time 30 "$su" | head -c 64 || true)
        if [[ "$body" == *'#EXTM3U'* ]]; then
          echo "  (m3u8 playlist body, content-type $(echo "$hdr" | cut -d' ' -f2))"
        else
          echo "FAIL stream content-type ($VU: $su)"; fail=1
        fi
      fi
    else
      echo "FAIL stream request ($VU: $su → $hdr)"; fail=1
    fi
  done
  d=$(sort "$SP" | uniq -d)
  if [[ -n "$d" ]]; then echo "FAIL duplicate stream paths within $VU:"; echo "$d"; fail=1; fi
  awk -v v="video$i" '{print v"\t"$0}' "$SP" >> /tmp/verify_all_streams.tsv

  # ── related videos: selector + sanity ──
  if [[ -n "$RELATED_SELECTOR" ]]; then
    r=$(py count "$RELATED_SELECTOR" "$F")
    echo "GET $VU → '$RELATED_SELECTOR' matches: $r"
    (( r >= 1 )) || { echo "FAIL related videos ($VU)"; fail=1; }
    py cards "$RELATED_SELECTOR" - - "$F" > /tmp/verify_rel.tsv
    empty_titles=$(awk -F'\t' '$2 == ""' /tmp/verify_rel.tsv | wc -l)
    (( empty_titles == 0 )) || { echo "FAIL empty recommendation title(s) ($VU)"; fail=1; }
    self_hits=$(cut -f1 /tmp/verify_rel.tsv | while read -r h; do py path "$h"; done | { grep -Fx "$vpath" || true; } | wc -l)
    (( self_hits == 0 )) || { echo "FAIL recommendations contain the video itself ($VU)"; fail=1; }
    dups_out=$(awk -F'\t' 'BEGIN{OFS="\t"}{print NR"_r",$1,$2,$3}' /tmp/verify_rel.tsv | py dups 'href,title')
    if [[ -n "$dups_out" ]]; then echo "FAIL duplicate recommendations ($VU):"; echo "$dups_out"; fail=1; fi
  fi
done

# identity-field exposure: all-or-none across sampled pages
for col in 4 5; do
  name=$([[ $col == 4 ]] && echo poster || echo plot)
  have=$(awk -F'\t' -v c=$col '$c != "" { n++ } END { print n+0 }' "$V_TSV")
  total=${#VIDEO_URLS[@]}
  if (( have > 0 && have < total )); then
    echo "FAIL: $name present on $have/$total video pages — inconsistent page shape (check --video-${name}-selector)"; fail=1
  elif (( have == 0 )); then
    echo "NOTE: no $name on any sampled page — site does not expose it; excluded from distinctness"
  fi
done
# ── check 2a: optional field exposure (tags/actors/year/duration) — all-or-none ──
for fname in tags actors year duration; do
  eval "sel=\$VIDEO_${fname^^}_SEL"
  if [[ -z "$sel" ]]; then
    echo "NOTE: no --video-$fname-selector — exposure not asserted; FINDINGS must state whether the site exposes $fname"
    continue
  fi
  have=0
  for F in /tmp/verify_video_*.html; do
    v=$(py field "$sel" text "$F"); [[ -n "$v" ]] && have=$((have+1))
  done
  total=${#VIDEO_URLS[@]}
  if (( have > 0 && have < total )); then
    echo "FAIL: $fname present on $have/$total video pages — inconsistent page shape (check --video-$fname-selector)"; fail=1
  elif (( have == 0 )); then
    echo "NOTE: --video-$fname-selector matches nothing on any sampled page — verify it against FINDINGS"
  else
    echo "field '$fname': present on all $total sampled pages"
  fi
done

# cross-video distinctness on exposed identity fields
COLSPEC='href,title,poster'
[[ $(awk -F'\t' '$5 != ""' "$V_TSV" | wc -l) -eq ${#VIDEO_URLS[@]} ]] && COLSPEC='href,title,poster,plot'
dups_out=$(py dups "$COLSPEC" < "$V_TSV")
if [[ -n "$dups_out" ]]; then
  echo "FAIL cross-video identity collisions (different videos share a title/poster/plot):"
  echo "$dups_out"; fail=1
fi
dups_out=$(py dups 'stream' < /tmp/verify_all_streams.tsv)
if [[ -n "$dups_out" ]]; then
  echo "FAIL stream path shared by different videos (same link everywhere = wrong selector):"
  echo "$dups_out"; fail=1
fi

# ── check 5: search ↔ load agreement ──
echo "── check 5: search ↔ load agreement"
agreed=0
while IFS=$'\t' read -r vid vpath vtitle vposter vplot; do
  row=$(awk -F'\t' -v p="$vpath" '$2 == p { print; exit }' /tmp/verify_cards_norm.tsv)
  [[ -z "$row" ]] && continue
  agreed=$((agreed+1))
  ctitle=$(cut -f3 <<< "$row"); cposter=$(cut -f4 <<< "$row")
  ctitle=$(printf '%s' "$ctitle" | norm)
  vtitle=$(printf '%s' "$vtitle" | norm)
  # strip constant og:title wrapper some sites add ("Watch X - Site"); bare card text otherwise
  if [[ "$vtitle" == watch\ * ]]; then vtitle="${vtitle#watch }"; fi
  if [[ "$ctitle" == watch\ * ]]; then ctitle="${ctitle#watch }"; fi
  vtitle=$(printf '%s' "$vtitle" | sed -E 's/ - erotic movies$//')
  ctitle=$(printf '%s' "$ctitle" | sed -E 's/ - erotic movies$//')
  if [[ "$ctitle" != "$vtitle" ]]; then
    echo "FAIL title mismatch for $vpath: search='$ctitle' load='$vtitle'"; fail=1
  fi
  if [[ -n "$cposter" && -n "$vposter" && "$(py path "$cposter")" != "$(py path "$vposter")" ]]; then
    echo "FAIL poster mismatch for $vpath: search='$cposter' load='$vposter'"; fail=1
  fi
done < "$V_TSV"
if (( agreed == 0 )); then
  echo "NOTE: no sampled video appears in the search page(s) — pick a --search-url whose query matches a sample video to exercise agreement"
fi

# ── check 6: LoadResponse completeness (code vs FINDINGS) ──
echo "── check 6: LoadResponse completeness (code vs FINDINGS)"
field_pattern() {
  case "$1" in
    recommendations) echo 'recommendations\s*=';;
    tags) echo 'tags\s*=';;
    plot) echo 'plot\s*=';;
    duration) echo 'duration\s*=|addDuration\s*\(';;
    year) echo 'year\s*=';;
    actors) echo 'addActors|actors\s*=';;
    score) echo 'addScore|score\s*=';;
    posters) echo 'posterUrl\s*=|backgroundPosterUrl\s*=';;
    *) echo '';;
  esac
}
if [[ -n "$LOAD_RESPONSE" ]]; then
  IFS=',' read -ra FIELDS <<< "$LOAD_RESPONSE"
  if [[ -z "$PROVIDER_SRC" || ! -e "$PROVIDER_SRC" ]]; then
    echo "FAIL: --load-response requires --provider-src pointing at the provider directory"; fail=1
  else
    for f in "${FIELDS[@]}"; do
      pat=$(field_pattern "$f")
      if [[ -z "$pat" ]]; then
        echo "FAIL: unknown load-response field '$f' (recommendations,tags,plot,duration,year,actors,score,posters)"; fail=1
      else
        n=$( { grep -rE "$pat" --include='*.kt' "$PROVIDER_SRC" || true; } | wc -l)
        echo "field '$f': $n assignment(s) in $PROVIDER_SRC"
        (( n >= 1 )) || { echo "FAIL load-response: '$f' never populated"; fail=1; }
      fi
    done
  fi
fi

if (( fail )); then echo "RESULT: FAIL"; exit 1; else echo "RESULT: PASS"; fi
