#!/usr/bin/env bash
# Self-check for verify.sh's embedded PYDOM selector engine — offline, no network.
# Extracts PYDOM from verify.sh so the helper stays single-sourced, then asserts its
# invariants. Add a case here when changing PYDOM logic.
# Context: pre-review of PR #519 caught `tag, match = compile_simple(parts[0])` raising
# TypeError on tag-less chained first parts (.cls/#id) — the None guard was dead code.
set -euo pipefail
DIR=$(cd "$(dirname "$0")" && pwd)
PYDOM=/tmp/verify_pydom_selfcheck.py
sed -n "/cat > .\$PYDOM. <<'PY'/,/^PY$/p" "$DIR/verify.sh" | sed '1d;$d' > "$PYDOM"

bash -n "$DIR/verify.sh"   # syntax gate on the wrapper itself

# Fixture: ancestor .single-related-posts with a nested same-tag wrapper and two
# article.post-item cards; a sidebar article elsewhere on the page (the eroticmv shape).
FIX=$(mktemp /tmp/verify_selfcheck_XXXX.html)
cat > "$FIX" <<'HTML'
<div class="single-related-posts">
  <div class="inner"><article class="post-item"><a href="/a/">A</a></article></div>
  <article class="post-item"><a href="/b/">B</a></article>
</div>
<article class="post-item"><a href="/sidebar/">Sidebar</a></article>
HTML
trap 'rm -f "$FIX"' EXIT

# 1. tag-less first part (eroticmv's provider-side selector shape) must degrade to empty,
#    not crash with TypeError from unpacking compile_simple's None
out=$(python3 "$PYDOM" cards '.single-related-posts article.post-item' - - "$FIX")
[[ -z "$out" ]] || { echo "FAIL: tag-less chained selector should degrade to empty, got: $out"; exit 1; }

# 2. tag-prefixed chained selector scopes to the ancestor subtree — no sidebar leak
out=$(python3 "$PYDOM" cards 'div.single-related-posts article.post-item' - - "$FIX")
echo "$out"
grep -q '/a/' <<<"$out" || { echo "FAIL: scoped cards missing ancestor-subtree card"; exit 1; }
grep -q '/sidebar/' <<<"$out" && { echo "FAIL: cards leaked outside the ancestor scope"; exit 1; } || true

# 3. count of a tag-less selector → -1 (the graceful-dispatch shape blocks() must mirror)
[[ "$(python3 "$PYDOM" count '.single-related-posts article.post-item' "$FIX")" == "-1" ]]

# 4. mangoporn shape: card div nested inside a wrapper div whose first close comes after
#    the card opens — old blocks() swallowed the card's opening tag entirely (0 rows).
FIX2=$(mktemp /tmp/verify_selfcheck2_XXXX.html)
cat > "$FIX2" <<'HTML'
<div class="row">
  <div class="col">
    <div class="card"><a class="title" href="/one/">One</a><div class="bar"></div></div>
  </div>
  <div class="col">
    <div class="card"><a class="title" href="/two/">Two</a><div class="bar"></div></div>
  </div>
</div>
HTML
trap 'rm -f "$FIX" "$FIX2" "${TSVA:-}" "${TSVAD:-}" "${TSVB:-}" "${TSVBD:-}" "${TSVC:-}" "${TSVCD:-}"' EXIT
out=$(python3 "$PYDOM" cards 'div.card' 'a.title' - "$FIX2")
grep -q '/one/' <<<"$out" || { echo "FAIL: nested wrapper card /one/ not found"; exit 1; }
grep -q '/two/' <<<"$out" || { echo "FAIL: nested wrapper card /two/ not found"; exit 1; }

# 5. poster_path (issue #680): strip at most ONE trailing -<alnum 1-3> variant token from
#    the filename stem (before the extension) — MissAV's card img data-src serves
#    cover-t.jpg while the load page's og:image serves cover-n.jpg; same artwork.
pp() { python3 "$PYDOM" poster_path "$1"; }
[[ "$(pp 'https://c.test/covers/cover-t.jpg')" == '/covers/cover.jpg' ]] \
  || { echo "FAIL: poster_path should strip one variant token, got: $(pp 'https://c.test/covers/cover-t.jpg')"; exit 1; }
[[ "$(pp 'https://c.test/covers/cover-t.jpg')" == "$(pp 'https://c.test/covers/cover-n.jpg')" ]] \
  || { echo "FAIL: cover-t.jpg vs cover-n.jpg must compare equal after poster_path"; exit 1; }
[[ "$(pp 'https://c.test/covers/cover-t.jpg')" != "$(pp 'https://c.test/covers/other-n.jpg')" ]] \
  || { echo "FAIL: cover-t.jpg vs other-n.jpg must stay unequal after poster_path"; exit 1; }
[[ "$(pp 'https://c.test/covers/cover-t-2.jpg')" == '/covers/cover-t.jpg' ]] \
  || { echo "FAIL: poster_path must strip at most ONE token, got: $(pp 'https://c.test/covers/cover-t-2.jpg')"; exit 1; }
[[ "$(pp 'https://c.test/covers/cover-x2.jpg')" == "$(pp 'https://c.test/covers/cover-n.jpg')" ]] \
  || { echo "FAIL: multi-char variant scheme (-x2) must collapse like -t/-n"; exit 1; }
# 5b. WP thumbnail size token (-400x225, #781): card img serves the sized crop while the
#     load page's og:image serves the full-sized file — same artwork, must compare equal.
[[ "$(pp 'https://ixiporn.test/uploads/2021/02/4k-close-up-video-400x225.jpg')" == '/uploads/2021/02/4k-close-up-video.jpg' ]] \
  || { echo "FAIL: poster_path must strip a -NNNxNNN thumbnail token, got: $(pp 'https://ixiporn.test/uploads/2021/02/4k-close-up-video-400x225.jpg')"; exit 1; }
[[ "$(pp 'https://ixiporn.test/uploads/2021/02/4k-close-up-video-400x225.jpg')" == "$(pp 'https://ixiporn.test/uploads/2021/02/4k-close-up-video.jpg')" ]] \
  || { echo "FAIL: -400x225 card crop vs full-size og:image must compare equal after poster_path"; exit 1; }
# a stem that GENUINELY ends in digits-x-digits with no dash must stay untouched
[[ "$(pp 'https://c.test/covers/cover400x225.jpg')" == '/covers/cover400x225.jpg' ]] \
  || { echo "FAIL: poster_path must not strip undashed stems"; exit 1; }

# 6. url_path semantics unchanged — href/stream variant tokens are real (distinct cuts),
#    so `path` must NOT lose -t
[[ "$(python3 "$PYDOM" path 'https://c.test/covers/cover-t.jpg')" == '/covers/cover-t.jpg' ]] \
  || { echo "FAIL: url_path must keep variant tokens (href/stream semantics unchanged)"; exit 1; }

# TSV fixtures mirror verify.sh's real dedupe inputs (id first, tab-separated):
#   A: check_listing raw rows  `<prefix><page> href title poster`  → py dups 'href'
#   B: V_TSV rows              `video<N> path title poster plot`   → py dups 'href,title,poster,plot'
#   C: rec rows                `awk '{print NR"_r",$1,$2,$3}'`     → py dups 'href'

# 7. Case A (#664 → #773): the Porntrex false-FAIL repro — two DIFFERENT videos whose titles
#    both normalize to "hot japanese milf". Per-video identity is the href, so the listing
#    dedupe key is 'href' and this input is clean; a repeated title across distinct hrefs is
#    site content, not a duplicate card. The same input under the OLD 'href,title' key must
#    still report the title collision — documenting the defect this key change fixed.
TSVA=$(mktemp /tmp/verify_selfcheck_a_XXXX.tsv)
{
  printf '%s\t%s\t%s\t%s\n' home1 /video/3362506/hot-japanese-milf2 'hot japanese milf' https://c.test/p1.jpg
  printf '%s\t%s\t%s\t%s\n' home1 /video/3362381/hot-japanese-milf  'hot japanese milf' https://c.test/p2.jpg
} > "$TSVA"
out=$(python3 "$PYDOM" dups 'href' < "$TSVA")
[[ -z "$out" ]] || { echo "FAIL: same-title distinct-href cards must be clean under the 'href' key, got: $out"; exit 1; }
oldkey=$(python3 "$PYDOM" dups 'href,title' < "$TSVA")
cut -f1 <<<"$oldkey" | grep -qx title \
  || { echo "FAIL: the same input under the old 'href,title' key must document the title-collision defect, got: $oldkey"; exit 1; }
# non-vacuity: the SAME card returning on page 2 (same href, different page id) is still a
# real duplicate — the key change must not blind the dedupe
TSVAD=$(mktemp /tmp/verify_selfcheck_ad_XXXX.tsv)
{
  printf '%s\t%s\t%s\t%s\n' home1 /video/3362381/hot-japanese-milf 'hot japanese milf' https://c.test/p2.jpg
  printf '%s\t%s\t%s\t%s\n' home2 /video/3362381/hot-japanese-milf 'hot japanese milf' https://c.test/p2.jpg
} > "$TSVAD"
out=$(python3 "$PYDOM" dups 'href' < "$TSVAD")
grep -qF '/video/3362381/hot-japanese-milf' <<<"$out" \
  || { echo "FAIL: a same-href card across pages must still FAIL under the 'href' key, got: $out"; exit 1; }

# 8. Case B (#664 → #773): plot column all '-' — no sampled page exposes plot, and the '-'
#    placeholder (written to keep the TSV column count stable) is ABSENT at the dups level,
#    so an all-placeholder column can never collide. A genuinely shared plot value between
#    two videos must still collide — the distinct bar keeps its teeth on real data.
TSVB=$(mktemp /tmp/verify_selfcheck_b_XXXX.tsv)
{
  printf '%s\t%s\t%s\t%s\t%s\n' video1 /v/a 'Hot Scene 1' https://c.test/p1.jpg -
  printf '%s\t%s\t%s\t%s\t%s\n' video2 /v/b 'Hot Scene 2' https://c.test/p2.jpg -
  printf '%s\t%s\t%s\t%s\t%s\n' video3 /v/c 'Hot Scene 3' https://c.test/p3.jpg -
  printf '%s\t%s\t%s\t%s\t%s\n' video4 /v/d 'Hot Scene 4' https://c.test/p4.jpg -
  printf '%s\t%s\t%s\t%s\t%s\n' video5 /v/e 'Hot Scene 5' https://c.test/p5.jpg -
} > "$TSVB"
out=$(python3 "$PYDOM" dups 'href,title,poster,plot' < "$TSVB")
[[ -z "$out" ]] || { echo "FAIL: all-'-' plot column must be treated as absent by dups, got: $out"; exit 1; }
TSVBD=$(mktemp /tmp/verify_selfcheck_bd_XXXX.tsv)
{
  printf '%s\t%s\t%s\t%s\t%s\n' video1 /v/a 'Video A' https://c.test/p1.jpg 'Japanese babe'
  printf '%s\t%s\t%s\t%s\t%s\n' video2 /v/b 'Video B' https://c.test/p2.jpg 'Japanese babe'
} > "$TSVBD"
out=$(python3 "$PYDOM" dups 'href,title,poster,plot' < "$TSVBD")
cut -f1 <<<"$out" | grep -qx plot \
  || { echo "FAIL: a genuinely shared plot value must still collide, got: $out"; exit 1; }

# 9. Case C (#664 → #773): rec rows in check-4's shape (NR_r ids, href/title/poster) with
#    repeated titles across distinct hrefs — site content, clean under the 'href' key; a
#    repeated href (the same video linked twice in one rec list) still FAILs.
TSVC=$(mktemp /tmp/verify_selfcheck_c_XXXX.tsv)
{
  printf '%s\t%s\t%s\t%s\n' 1_r /video/3362506/hot-japanese-milf2 'hot japanese milf' https://c.test/p1.jpg
  printf '%s\t%s\t%s\t%s\n' 2_r /video/3362381/hot-japanese-milf  'hot japanese milf' https://c.test/p2.jpg
  printf '%s\t%s\t%s\t%s\n' 3_r /video/5555555/other-clip        'Other Clip'        https://c.test/p3.jpg
} > "$TSVC"
out=$(python3 "$PYDOM" dups 'href' < "$TSVC")
[[ -z "$out" ]] || { echo "FAIL: rec rows with repeated titles across distinct hrefs must be clean, got: $out"; exit 1; }
TSVCD=$(mktemp /tmp/verify_selfcheck_cd_XXXX.tsv)
{
  printf '%s\t%s\t%s\t%s\n' 1_r /video/3362381/hot-japanese-milf 'hot japanese milf' https://c.test/p2.jpg
  printf '%s\t%s\t%s\t%s\n' 2_r /video/3362381/hot-japanese-milf 'hot japanese milf' https://c.test/p2.jpg
} > "$TSVCD"
out=$(python3 "$PYDOM" dups 'href' < "$TSVCD")
grep -qF '/video/3362381/hot-japanese-milf' <<<"$out" \
  || { echo "FAIL: a repeated rec href (same video linked twice) must FAIL, got: $out"; exit 1; }

# 13. check_listing's poster NOTE runs the REAL 4-field row shape (id\thref\ttitle\tposter)
#    through the positional colspec 'href,title,poster' and keeps only the poster line (the
#    call-site $1 filter). Mirroring the real shape guards the colspec↔stdin alignment: a
#    bare 'poster' colspec on this shape reads r[1] = the href column — the #775-review
#    misalignment a 2-field fixture masked. The repeated title proves the filter drops
#    non-poster lines (the NOTE stays poster-only).
dups_out=$(printf 'v1\t/a/cool-video\tTitle A\thttps://c.test/covers/cover-t.jpg\nv2\t/b/other-video\tTitle A\thttps://c.test/covers/cover-n.jpg\n' \
  | python3 "$PYDOM" dups 'href,title,poster' | awk -F'\t' '$1=="poster"')
[[ "$dups_out" == "$(printf 'poster\t/covers/cover.jpg\tv1,v2')" ]] \
  || { echo "FAIL: dups poster column must collapse variant tokens to one artwork value on the real check_listing row shape, got: $dups_out"; exit 1; }

# 14. different artwork with variant tokens must NOT collide in the poster column
dups_out=$(printf 'v1\thttps://c.test/covers/aaa-t.jpg\nv2\thttps://c.test/covers/bbb-n.jpg\n' \
  | python3 "$PYDOM" dups 'poster')
[[ -z "$dups_out" ]] || { echo "FAIL: dups 'poster' must not collapse different artwork, got: $dups_out"; exit 1; }

# 12. href column keeps url_path semantics — variant tokens are real there, stay distinct
#    (S2 constraint: url_path/href untouched)
dups_out=$(printf 'v1\thttps://c.test/covers/cover-t.jpg\nv2\thttps://c.test/covers/cover-n.jpg\n' \
  | python3 "$PYDOM" dups 'href')
[[ -z "$dups_out" ]] || { echo "FAIL: dups 'href' must keep variant tokens distinct, got: $dups_out"; exit 1; }

# 13. '-' placeholder in the poster column still counts as absent (no collision) —
#     poster_path('-') must stay empty like url_path('-')
dups_out=$(printf 'v1\t-\nv2\t-\n' | python3 "$PYDOM" dups 'poster')
[[ -z "$dups_out" ]] || { echo "FAIL: dups 'poster' placeholder rows must count as absent, got: $dups_out"; exit 1; }

# 14. cross-video COLSPEC shape (href,title,poster): two videos with variant posters of the
#     same artwork collide on the normalized poster value; hrefs/titles stay clean
dups_out=$(printf 'v1\t/a\ttitle one\thttps://c.test/covers/cover-t.jpg\nv2\t/b\ttitle two\thttps://c.test/covers/cover-n.jpg\n' \
  | python3 "$PYDOM" dups 'href,title,poster')
[[ "$dups_out" == "$(printf 'poster\t/covers/cover.jpg\tv1,v2')" ]] \
  || { echo "FAIL: cross-video COLSPEC must report the normalized poster collision, got: $dups_out"; exit 1; }

# 15. check-5 norm dash canonicalization (#781): WP surfaces disagree on en-dash vs hyphen
#     for the same video's title (card '&#8211;' vs og:title '-'). The norm function lives
#     in the bash wrapper (not PYDOM), so this block extracts it from verify.sh and asserts
#     the canonicalization + whitespace/lower semantics directly.
NORMF=/tmp/verify_selfcheck_norm.sh
{ sed -n '/^norm() {/p' "$DIR/verify.sh"; echo 'norm'; } > "$NORMF"
bash -n "$NORMF"
[[ "$(printf 'Clit Licking \xe2\x80\x93 Extreme Close Up' | bash "$NORMF")" \
  == "$(printf 'Clit Licking - Extreme Close Up' | bash "$NORMF")" ]] \
  || { echo "FAIL: norm must canonicalize en-dash titles to hyphen for check-5 agreement"; exit 1; }
[[ "$(printf "  MiXed  CASE\ttitles " | bash "$NORMF")" == 'mixed case titles' ]] \
  || { echo "FAIL: norm whitespace/lower semantics regressed"; exit 1; }

# 7. Stream scheme gate (#1353): a non-http source can never open on the app's HTTP-only
#    datasource, so the gate must reject it outright. Asserted end-to-end through verify.sh
#    (below) and, for the predicates that classify a page-extracted source, directly — the
#    app-local loopback shape is not asserted end-to-end here because verifying it needs the
#    device, so its predicate is exercised instead.
GATE_OUT=$(mktemp /tmp/verify_gate_XXXX)
PREDF=$(mktemp /tmp/verify_preds_XXXX.sh)
# One trap owns every temp path this script creates (including the two fixed-name helpers),
# so a failing case never leaves debris in /tmp for the next run to trip over.
trap 'rm -f "$FIX" "$FIX2" "$GATE_OUT" "$PREDF" "$NORMF" "$PYDOM" "${TSVA:-}" "${TSVAD:-}" "${TSVB:-}" "${TSVBD:-}" "${TSVC:-}" "${TSVCD:-}"' EXIT
grep -E '^is_(http|app_local)\(\) \{' "$DIR/verify.sh" > "$PREDF"
bash -n "$PREDF" && . "$PREDF"
is_http 'https://example.com/a.m3u8' || { echo "FAIL: is_http rejected https"; exit 1; }
is_http 'data:application/vnd.apple.mpegurl;base64,AA' && { echo "FAIL: is_http accepted a data: URL"; exit 1; }
is_app_local 'http://127.0.0.1:8765/p.m3u8' || { echo "FAIL: app-local loopback not recognised"; exit 1; }
is_app_local 'https://example.com/a.m3u8' && { echo "FAIL: app-local matched a public host"; exit 1; }
if "$DIR/verify.sh" --search-url https://example.com --video-url https://example.com/x \
     --stream-url 'data:application/vnd.apple.mpegurl;base64,I0VYVE0zVQ' > "$GATE_OUT" 2>&1; then
  echo "FAIL: scheme gate accepted a data: source"; exit 1
fi
grep -q "not http(s)" "$GATE_OUT" || { echo "FAIL: scheme gate did not explain the rejection"; exit 1; }
grep -q "HTTP-only" "$GATE_OUT" || { echo "FAIL: scheme gate did not name the reason (HTTP-only datasource)"; exit 1; }

echo "RESULT: PASS"
