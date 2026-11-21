#!/usr/bin/env bash
# Round-trip check for the vendored wizard template's write_env/_existing
# (.agents/skills/wizard/template.sh, issue #1479).
#
# The template lives in a vendored skill tree whose hash is pinned in
# skills-lock.json, so this check lives OUTSIDE it: a sync may legitimately
# rewrite the template, and this script fails loudly when that breaks the
# guarantees we patched in (0600 before the first secret, atomic replace,
# symlink written through, dotenv round-trip).
#
# usage: scripts/wizard-template-check.sh [--template PATH]
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
template="$root/.agents/skills/wizard/template.sh"

[[ -f "$template" ]] || { echo "FAIL: no template at $template"; exit 1; }
bash -n "$template" || { echo "FAIL: bash -n"; exit 1; }

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT

# Load only the two functions under test (the template runs a wizard on source).
awk '/^_existing\(\) \{/,/^\}/; /^write_env\(\) \{/,/^\}/' "$template" > "$tmpdir/fns.sh"
grep -q '^write_env()' "$tmpdir/fns.sh" || { echo "FAIL: write_env not found"; exit 1; }
grep -q '^_existing()' "$tmpdir/fns.sh" || { echo "FAIL: _existing not found"; exit 1; }
# shellcheck disable=SC1090,SC1091
. "$tmpdir/fns.sh"

GREEN=""; RESET=""; DIM=""; BOLD=""; s=""; e=""; WRITTEN_ENV=()
fail() { echo "FAIL: $*"; exit 1; }

# 1) an existing 0644 file is tightened before the secret lands, and the value
#    round-trips through _existing (apostrophe, backslash, $, double quote).
ENV_FILE="$tmpdir/.env"; : > "$ENV_FILE"; chmod 0644 "$ENV_FILE"
secret="p'ass\\word\$x\"q"
write_env API_KEY "$secret" >/dev/null
[[ "$(stat -c '%a' "$ENV_FILE")" == "600" ]] || fail "mode is $(stat -c '%a' "$ENV_FILE"), not 600"
[[ "$(_existing API_KEY)" == "$secret" ]] || fail "round-trip: [$(_existing API_KEY)] != [$secret]"

# 2) a symlink is written THROUGH, never replaced; its mode is tightened too.
mv "$ENV_FILE" "$tmpdir/real.env"; ln -s "$tmpdir/real.env" "$ENV_FILE"
write_env OTHER "v" >/dev/null
[[ -L "$ENV_FILE" ]] || fail "symlink replaced by a regular file"
grep -q '^OTHER=' "$tmpdir/real.env" || fail "not written through the symlink"
[[ "$(stat -c '%a' "$tmpdir/real.env")" == "600" ]] || fail "symlinked target mode not 600"

# 3) a symlink cycle must fail fast, not spin.
ln -s cycle.env "$tmpdir/cycle.env"
ENV_FILE="$tmpdir/cycle.env"
if timeout 5 bash -c "$(declare -f write_env _existing); ENV_FILE='$ENV_FILE'; WRITTEN_ENV=(); write_env K V" 2>/dev/null; then
  fail "symlink cycle did not fail"
fi

echo "ok: wizard template env-writer guarantees hold"
