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
grep -qx 'EPorner' <<<"$loaded" || { echo "FAIL: versioned name not reduced to its stem"; exit 1; }
grep -qx 'HQPorner' <<<"$loaded" || { echo "FAIL: plain name not captured"; exit 1; }

failed=$(extract_failed "$tmp/sample.log")
[[ $failed == MissAV ]] || { echo "FAIL: failed name '$failed' != MissAV"; exit 1; }

has_failed "$tmp/sample.log" || { echo "FAIL: has_failed should be true"; exit 1; }

# ---- the app's CURRENT logcat shape (issue #1362) --------------------------
# Upstream logs the plugin lines under the `PluginManager` *tag*, not as a
# "PluginManager: " message prefix, and the success line carries the file name
# ("Loaded plugin EPorner.cs3 successfully"):
#   I/PluginManager( 4355): Loaded plugin Eroticmv.cs3 successfully
# Requiring the message prefix made extract_loaded blind to every real line, so a
# fully-loaded fleet (measured run 40577836748: 26/26 "Loaded plugin X.cs3
# successfully") was reported as all 26 failing. Fixtures are verbatim lines.
cat > "$tmp/device.log" <<'EOF'
10-27 20:30:14.215 D/PluginManager( 4355): Files in '/storage/emulated/0/Cloudstream3/plugins' folder: 26
10-27 20:30:15.864 I/PluginManager( 4355): Loaded plugin Eroticmv.cs3 successfully
10-27 20:30:15.869 I/PluginManager( 4355): Loaded plugin Film1k.cs3 successfully
10-27 20:30:15.896 I/PluginManager( 4355): Loaded plugin PerverZija.cs3 successfully
10-27 20:30:16.010 I/PluginInstance( 4355): Adding Cat3Movie (https://cat3movie.org) MainAPI
EOF

loaded=$(extract_loaded "$tmp/device.log")
[[ $(grep -c . <<<"$loaded") -eq 3 ]] || { echo "FAIL: tag-form loaded lines, got: $loaded"; exit 1; }
grep -qx 'Eroticmv' <<<"$loaded" || { echo "FAIL: tag-form name not captured ('.cs3' not stripped)"; exit 1; }
grep -qx 'PerverZija' <<<"$loaded" || { echo "FAIL: tag-form name not captured"; exit 1; }
has_failed "$tmp/device.log" && { echo "FAIL: clean device log flagged as failed"; exit 1; }

# failure shape under the tag form (no "PluginManager: " message prefix)
cat > "$tmp/device-failed.log" <<'EOF'
10-27 20:30:15.864 I/PluginManager( 4355): Loaded plugin Eroticmv.cs3 successfully
10-27 20:30:15.900 E/PluginManager( 4355): Failed to load plugin MissAV.cs3: NoSuchMethodError boom
EOF
failed=$(extract_failed "$tmp/device-failed.log")
[[ $failed == MissAV ]] || { echo "FAIL: tag-form failure '$failed' != MissAV"; exit 1; }
has_failed "$tmp/device-failed.log" || { echo "FAIL: tag-form failure not detected"; exit 1; }

# ---- plugin-folder visibility (issue #1362) -------------------------------
# The app's own listing of the pushed plugin dir is the only evidence that the
# plugin set was examined at all. 0 files = instrument fault, never a provider
# failing set. Fixtures mirror the measured logcat of run 40575172160.
DIR='/storage/emulated/0/Cloudstream3/plugins'

cat > "$tmp/invisible.log" <<EOF
12-25 04:41:06.100 D/PluginManager( 1234): Files in '$DIR' folder: 0
12-25 04:41:11.200 D/PluginManager( 1234): Files in '$DIR' folder: 0
12-25 04:41:16.300 W/Monkey  ( 1234): args: [-p, com.lagradost.cloudstream3.prerelease]
EOF

[[ $(extract_plugin_dir_count "$tmp/invisible.log") == 0 ]] \
  || { echo "FAIL: dir count should be 0"; exit 1; }
[[ $(plugin_visibility "$tmp/invisible.log" 26) == invisible ]] \
  || { echo "FAIL: 0-file listing must be invisible, not a provider fault"; exit 1; }

# never listed the folder at all (app not launched / died early) is the same shape
printf '%s\n' '12-25 04:41:16.300 W/Monkey  ( 1234): args: [-p, x]' > "$tmp/nolisting.log"
[[ $(plugin_visibility "$tmp/nolisting.log" 26) == invisible ]] \
  || { echo "FAIL: missing folder listing must be invisible"; exit 1; }

# a later reload that does see the files wins (max over the log, not the first line)
cat > "$tmp/relisted.log" <<EOF
12-25 04:41:06.100 D/PluginManager( 1234): Files in '$DIR' folder: 0
12-25 04:41:21.400 D/PluginManager( 1234): Files in '$DIR' folder: 26
EOF
[[ $(extract_plugin_dir_count "$tmp/relisted.log") == 26 ]] \
  || { echo "FAIL: dir count should take the max (26)"; exit 1; }
[[ $(plugin_visibility "$tmp/relisted.log" 26) == listed ]] \
  || { echo "FAIL: 26-file listing must be attributable"; exit 1; }

# an explicit per-plugin load failure proves the file was read -> attributable
cat > "$tmp/read-but-failed.log" <<EOF
12-25 04:41:06.100 D/PluginManager( 1234): Files in '$DIR' folder: 26
12-25 04:41:07.456 E/PluginManager( 1234): Failed to load plugin MissAV.cs3: something broke
EOF
[[ $(plugin_visibility "$tmp/read-but-failed.log" 26) == listed ]] \
  || { echo "FAIL: explicit load failure must be attributable"; exit 1; }

# nothing pushed: the visibility question is moot, never an instrument fault
[[ $(plugin_visibility "$tmp/invisible.log" 0) == listed ]] \
  || { echo "FAIL: empty push set must not be an instrument fault"; exit 1; }

echo 'PluginManager: Loaded plugin X successfully' > "$tmp/clean.log"

# one successful registration also proves the app reached the plugin dir
[[ $(plugin_visibility "$tmp/clean.log" 1) == listed ]] \
  || { echo "FAIL: a successful load must be attributable"; exit 1; }
if has_failed "$tmp/clean.log"; then echo "FAIL: clean log flagged"; exit 1; fi
[[ $(extract_loaded "$tmp/clean.log") == X ]] || { echo "FAIL: clean loaded"; exit 1; }

: > "$tmp/empty.log"
[[ -z $(extract_loaded "$tmp/empty.log") ]] || { echo "FAIL: empty log loaded"; exit 1; }
has_failed "$tmp/empty.log" && { echo "FAIL: empty log failed"; exit 1; }

echo "parse-log tests PASSED"
