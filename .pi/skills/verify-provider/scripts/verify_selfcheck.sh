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

# 7. check_listing's poster NOTE runs the REAL 4-field row shape (id\thref\ttitle\tposter)
#    through the positional colspec 'href,title,poster' and keeps only the poster line (the
#    call-site $1 filter). Mirroring the real shape guards the colspec↔stdin alignment: a
#    bare 'poster' colspec on this shape reads r[1] = the href column — the #775-review
#    misalignment a 2-field fixture masked. The repeated title proves the filter drops
#    non-poster lines (the NOTE stays poster-only).
dups_out=$(printf 'v1\t/a/cool-video\tTitle A\thttps://c.test/covers/cover-t.jpg\nv2\t/b/other-video\tTitle A\thttps://c.test/covers/cover-n.jpg\n' \
  | python3 "$PYDOM" dups 'href,title,poster' | awk -F'\t' '$1=="poster"')
[[ "$dups_out" == "$(printf 'poster\t/covers/cover.jpg\tv1,v2')" ]] \
  || { echo "FAIL: dups poster column must collapse variant tokens to one artwork value on the real check_listing row shape, got: $dups_out"; exit 1; }

# 8. different artwork with variant tokens must NOT collide in the poster column
dups_out=$(printf 'v1\thttps://c.test/covers/aaa-t.jpg\nv2\thttps://c.test/covers/bbb-n.jpg\n' \
  | python3 "$PYDOM" dups 'poster')
[[ -z "$dups_out" ]] || { echo "FAIL: dups 'poster' must not collapse different artwork, got: $dups_out"; exit 1; }

# 9. href column keeps url_path semantics — variant tokens are real there, stay distinct
#    (S2 constraint: url_path/href untouched)
dups_out=$(printf 'v1\thttps://c.test/covers/cover-t.jpg\nv2\thttps://c.test/covers/cover-n.jpg\n' \
  | python3 "$PYDOM" dups 'href')
[[ -z "$dups_out" ]] || { echo "FAIL: dups 'href' must keep variant tokens distinct, got: $dups_out"; exit 1; }

# 10. '-' placeholder in the poster column still counts as absent (no collision) —
#     poster_path('-') must stay empty like url_path('-')
dups_out=$(printf 'v1\t-\nv2\t-\n' | python3 "$PYDOM" dups 'poster')
[[ -z "$dups_out" ]] || { echo "FAIL: dups 'poster' placeholder rows must count as absent, got: $dups_out"; exit 1; }

# 11. cross-video COLSPEC shape (href,title,poster): two videos with variant posters of the
#     same artwork collide on the normalized poster value; hrefs/titles stay clean
dups_out=$(printf 'v1\t/a\ttitle one\thttps://c.test/covers/cover-t.jpg\nv2\t/b\ttitle two\thttps://c.test/covers/cover-n.jpg\n' \
  | python3 "$PYDOM" dups 'href,title,poster')
[[ "$dups_out" == "$(printf 'poster\t/covers/cover.jpg\tv1,v2')" ]] \
  || { echo "FAIL: cross-video COLSPEC must report the normalized poster collision, got: $dups_out"; exit 1; }

echo "RESULT: PASS"
