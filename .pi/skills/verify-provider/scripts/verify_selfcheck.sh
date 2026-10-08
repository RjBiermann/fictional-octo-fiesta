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

echo "RESULT: PASS"
