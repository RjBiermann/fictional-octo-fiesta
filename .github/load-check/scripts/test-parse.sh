#!/usr/bin/env bash
# Bash-level self-check for parse-log.sh — sample logcat fixtures vs the
# extracted plugin names. Exit 0 = pass.
set -euo pipefail
source "$(dirname "$0")/parse-log.sh"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/sample.log" <<'EOF'
12-25 04:41:07.123 I/SystemOut( 1234): PluginManager: Loaded plugin EPorner (4) successfully
12-25 04:41:07.456 E/PluginManager( 1234): PluginManager: Failed to load plugin MissAV.cs3: something broke
12-25 04:41:08.789 I/SystemOut( 1234): PluginManager: Loaded plugin HQPorner successfully
EOF

loaded=$(extract_loaded "$tmp/sample.log")
[[ $(grep -c . <<<"$loaded") -eq 2 ]] || { echo "FAIL: expected 2 loaded, got: $loaded"; exit 1; }
grep -q 'EPorner (4)' <<<"$loaded" || { echo "FAIL: versioned name not captured"; exit 1; }
grep -q 'HQPorner' <<<"$loaded" || { echo "FAIL: plain name not captured"; exit 1; }

failed=$(extract_failed "$tmp/sample.log")
[[ $failed == MissAV ]] || { echo "FAIL: failed name '$failed' != MissAV"; exit 1; }

has_failed "$tmp/sample.log" || { echo "FAIL: has_failed should be true"; exit 1; }

echo 'PluginManager: Loaded plugin X successfully' > "$tmp/clean.log"
if has_failed "$tmp/clean.log"; then echo "FAIL: clean log flagged"; exit 1; fi
[[ $(extract_loaded "$tmp/clean.log") == X ]] || { echo "FAIL: clean loaded"; exit 1; }

: > "$tmp/empty.log"
[[ -z $(extract_loaded "$tmp/empty.log") ]] || { echo "FAIL: empty log loaded"; exit 1; }
has_failed "$tmp/empty.log" && { echo "FAIL: empty log failed"; exit 1; }

# Real logcat -v time shape (measured on run 40577836748, app 4.8.0-PRE): the
# tag is PluginManager and carries the pid, while the message has no
# "PluginManager: " prefix. The fixture above only covered the older
# SystemOut/message-prefix form, so the parser silently extracted nothing and
# every plugin was reported as a load failure even though all 26 loaded.
cat > "$tmp/tagged.log" <<'EOF'
10-27 20:30:21.588 I/PluginManager( 4355): Loaded plugin AllClassicPorn.cs3 successfully
10-27 20:30:22.340 I/PluginManager( 4355): Loaded plugin EPorner.cs3 successfully
10-27 20:30:10.544 W/SemanticLocation( 1399): Failed to load ttl file, clearing index
EOF
tagged=$(extract_loaded "$tmp/tagged.log" || true)
[[ $(grep -c . <<<"$tagged") -eq 2 ]] || { echo "FAIL: tagged log expected 2 loaded, got: $tagged"; exit 1; }
grep -q 'AllClassicPorn' <<<"$tagged" || { echo "FAIL: tagged AllClassicPorn not captured"; exit 1; }
grep -q 'EPorner' <<<"$tagged" || { echo "FAIL: tagged EPorner not captured"; exit 1; }
if has_failed "$tmp/tagged.log"; then
  echo "FAIL: unrelated 'Failed to load ttl file' line flagged as a plugin failure"; exit 1
fi

cat > "$tmp/tagged-fail.log" <<'EOF'
10-27 20:30:22.000 E/PluginManager( 4355): Failed to load plugin  MissAV: bad dex
10-27 20:30:22.100 E/PluginManager( 4355): Failed to load JavGuru.cs3: bad zip
EOF
failed_tagged=$(extract_failed "$tmp/tagged-fail.log" || true)
grep -qx 'MissAV' <<<"$failed_tagged" || { echo "FAIL: tagged failed name MissAV not captured: $failed_tagged"; exit 1; }
grep -qx 'JavGuru' <<<"$failed_tagged" || { echo "FAIL: tagged failed name JavGuru not captured: $failed_tagged"; exit 1; }
has_failed "$tmp/tagged-fail.log" || { echo "FAIL: tagged failure should set has_failed"; exit 1; }

echo "parse-log tests PASSED"
