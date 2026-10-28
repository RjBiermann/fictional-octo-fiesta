#!/usr/bin/env bash
# Bash-level self-check for parse-log.sh — sample logcat fixtures vs the
# extracted plugin names. Exit 0 = pass.
set -euo pipefail
source "$(dirname "$0")/parse-log.sh"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Two log shapes must both parse: the historical wrapped form (`I/SystemOut( pid): PluginManager: …`)
# and the tag form `adb logcat -v time` actually produces (`I/PluginManager( pid): …`), which is
# what the load check captured on the runner (#1361/#1363).
cat > "$tmp/sample.log" <<'EOF'
12-25 04:41:07.123 I/SystemOut( 1234): PluginManager: Loaded plugin EPorner (4) successfully
12-25 04:41:07.456 E/PluginManager( 1234): PluginManager: Failed to load plugin MissAV.cs3: something broke
12-25 04:41:08.789 I/SystemOut( 1234): PluginManager: Loaded plugin HQPorner successfully
10-27 20:30:15.864 I/PluginManager( 4355): Loaded plugin Eroticmv.cs3 successfully
10-27 20:30:16.001 E/PluginManager( 4355): Failed to load plugin Javseen.cs3: no method registerMainAPI
EOF

loaded=$(extract_loaded "$tmp/sample.log")
[[ $(grep -c . <<<"$loaded") -eq 3 ]] || { echo "FAIL: expected 3 loaded, got: $loaded"; exit 1; }
grep -q 'EPorner (4)' <<<"$loaded" || { echo "FAIL: versioned name not captured"; exit 1; }
grep -q 'HQPorner' <<<"$loaded" || { echo "FAIL: plain name not captured"; exit 1; }
grep -q 'Eroticmv.cs3' <<<"$loaded" || { echo "FAIL: tag-form line not captured"; exit 1; }

failed=$(extract_failed "$tmp/sample.log")
[[ $failed == $'Javseen\nMissAV' ]] || { echo "FAIL: failed names '$failed' != Javseen+MissAV"; exit 1; }

has_failed "$tmp/sample.log" || { echo "FAIL: has_failed should be true"; exit 1; }

echo 'PluginManager: Loaded plugin X successfully' > "$tmp/clean.log"
if has_failed "$tmp/clean.log"; then echo "FAIL: clean log flagged"; exit 1; fi
[[ $(extract_loaded "$tmp/clean.log") == X ]] || { echo "FAIL: clean loaded"; exit 1; }

: > "$tmp/empty.log"
[[ -z $(extract_loaded "$tmp/empty.log") ]] || { echo "FAIL: empty log loaded"; exit 1; }
has_failed "$tmp/empty.log" && { echo "FAIL: empty log failed"; exit 1; }

echo "parse-log tests PASSED"
