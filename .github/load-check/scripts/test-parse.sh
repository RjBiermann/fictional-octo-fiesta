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

# reported_count counts verdict lines only — `Loading plugin: …` is printed by every plugin
# *before* it reports and is not a verdict, so counting it would read a stalled round as
# progress forever (issue #1500).
cat > "$tmp/progress.log" <<'EOF'
12-25 04:41:06.000 I/PluginManager( 4355): Loading plugin: PluginData(internalName=EPorner.cs3, url=null, isOnline=false, filePath=/x/EPorner.cs3, version=1)
12-25 04:41:07.123 I/PluginManager( 4355): Loaded plugin EPorner.cs3 successfully
12-25 04:41:07.456 E/PluginManager( 4355): Failed to load plugin MissAV.cs3: something broke
EOF
got=$(reported_count "$tmp/progress.log")
[[ $got -eq 2 ]] || { echo "FAIL: reported_count must count verdict lines only, got '$got'"; exit 1; }
got=$(reported_count "$tmp/empty.log")
[[ $got -eq 0 ]] || { echo "FAIL: reported_count on an empty log must be 0, got '$got'"; exit 1; }

# no_verdicts is the seam emulator-load.sh's timed-out path decides on (issue #1500). The
# interesting input is the one no other fixture provides: a log with plugin-manager activity
# (the folder listing, a `Loading plugin: …` line) but no verdict yet. Nothing was exercised
# there, so naming all 27 expected plugins would be a claim about the runner, not the plugins.
cat > "$tmp/round-started.log" <<'EOF'
12-25 04:41:05.900 I/PluginManager( 4355): Files in '/storage/emulated/0/Cloudstream3/plugins' folder: 27
12-25 04:41:06.000 I/PluginManager( 4355): Loading plugin: PluginData(internalName=EPorner.cs3, url=null, isOnline=false, filePath=/x/EPorner.cs3, version=1)
EOF
no_verdicts "$tmp/round-started.log" || { echo "FAIL: a started round with no verdict must read as no_verdicts"; exit 1; }
no_verdicts "$tmp/empty.log" || { echo "FAIL: no_verdicts must be true for an empty log"; exit 1; }
no_verdicts "$tmp/progress.log" && { echo "FAIL: no_verdicts must be false once a verdict is present"; exit 1; }
no_verdicts "$tmp/sample.log" && { echo "FAIL: no_verdicts must be false for loaded+failed verdicts"; exit 1; }

echo "parse-log tests PASSED"
