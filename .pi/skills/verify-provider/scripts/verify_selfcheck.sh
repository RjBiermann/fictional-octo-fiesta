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
trap 'rm -f "$FIX" "$FIX2"' EXIT
out=$(python3 "$PYDOM" cards 'div.card' 'a.title' - "$FIX2")
grep -q '/one/' <<<"$out" || { echo "FAIL: nested wrapper card /one/ not found"; exit 1; }
grep -q '/two/' <<<"$out" || { echo "FAIL: nested wrapper card /two/ not found"; exit 1; }

# 5. poster_path: cover-art variant normalizer (issue #680, story S1) — url_path + strip of
#    at most ONE trailing -<alnum 1-3> token from the filename stem, so per-surface cover
#    filenames (card thumb cover-t.jpg vs load-page og:image cover-n.jpg) compare equal.
pp() { python3 "$PYDOM" poster_path "$1"; }
# helper is live: real paths in, real paths out ('' only for the '-' placeholder)
[[ -n "$(pp '/images/cover-t.jpg')" ]] || { echo "FAIL: poster_path returns nothing — helper/dispatch missing"; exit 1; }
# spec case 1: same artwork, different per-surface variant → equal
[[ "$(pp '/images/cover-t.jpg')" == "$(pp '/images/cover-n.jpg')" ]] \
  || { echo "FAIL: poster_path cover-t vs cover-n should be equal, got: '$(pp '/images/cover-t.jpg')' vs '$(pp '/images/cover-n.jpg')'"; exit 1; }
# spec case 2: different base artwork → still unequal
[[ "$(pp '/images/cover-t.jpg')" != "$(pp '/images/other-n.jpg')" ]] \
  || { echo "FAIL: poster_path cover-t vs other-n should be unequal"; exit 1; }
# at most ONE token: cover-t-n.jpg → cover-t.jpg (not cover.jpg)
[[ "$(pp '/images/cover-t-n.jpg')" == '/images/cover-t.jpg' ]] \
  || { echo "FAIL: poster_path must strip at most one variant token, got: '$(pp '/images/cover-t-n.jpg')'"; exit 1; }
# generic schemes, no per-site allowlist: 2-char token stripped, 4-char token kept
[[ "$(pp '/images/cover-x2.jpg')" == '/images/cover.jpg' ]] \
  || { echo "FAIL: poster_path should strip the -x2 variant token, got: '$(pp '/images/cover-x2.jpg')'"; exit 1; }
[[ "$(pp '/images/cover-1080.jpg')" == '/images/cover-1080.jpg' ]] \
  || { echo "FAIL: poster_path must not strip a 4-char token, got: '$(pp '/images/cover-1080.jpg')'"; exit 1; }
# full URL: scheme/query stripped by url_path, then the variant collapses
[[ "$(pp 'https://fourhoi.example/t/cover-t.jpg?w=100&h=150')" == '/t/cover.jpg' ]] \
  || { echo "FAIL: poster_path full URL should normalize to /t/cover.jpg, got: '$(pp 'https://fourhoi.example/t/cover-t.jpg?w=100&h=150')'"; exit 1; }
# '-' placeholder (absent column value) stays empty, no crash
[[ -z "$(pp '-')" ]] || { echo "FAIL: poster_path('-') should stay empty"; exit 1; }

# 6. url_path/path semantics unchanged — variant tokens preserved for href/stream paths
[[ "$(python3 "$PYDOM" path 'https://img.example/images/cover-t.jpg?w=100')" == '/images/cover-t.jpg' ]] \
  || { echo "FAIL: path must keep the -t variant token (href/stream semantics unchanged)"; exit 1; }
[[ "$(python3 "$PYDOM" path '/images/cover-t.jpg')" == '/images/cover-t.jpg' ]] \
  || { echo "FAIL: plain path must pass through unchanged"; exit 1; }

echo "RESULT: PASS"
