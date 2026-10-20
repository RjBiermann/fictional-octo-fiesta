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

echo "RESULT: PASS"
