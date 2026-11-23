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
# Stems, not raw log names: the version suffix and `.cs3` are display detail, and every
# comparison (`unsettled`, `failing_stems`) has to speak the same vocabulary as the expected
# plugin list. A raw `EPorner (4)` can never equal the expected stem `EPorner` exactly.
grep -qx 'EPorner' <<<"$loaded" || { echo "FAIL: versioned name not normalised to its stem: $loaded"; exit 1; }
grep -qx 'HQPorner' <<<"$loaded" || { echo "FAIL: plain name not captured"; exit 1; }
grep -qx 'Eroticmv' <<<"$loaded" || { echo "FAIL: tag-form .cs3 name not normalised: $loaded"; exit 1; }

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

# Upstream reports a `load()` that throws through the catch-all
# `Failed to load <absolute path>: <stack>` — a *different* shape from the manifest branch's
# `Failed to load plugin  X.cs3: <reason>`. Parsing only the manifest shape left a real
# provider failure visible solely as "expected minus loaded", i.e. inferred from silence (#1545).
cat > "$tmp/catchall.log" <<'EOF'
12-25 04:41:07.100 I/PluginManager( 4355): Loaded plugin EPorner.cs3 successfully
12-25 04:41:07.456 E/PluginManager( 4355): Failed to load /storage/emulated/0/Android/data/com.lagradost.cloudstream3.prerelease/files/plugins/Javbangers.cs3: java.lang.ExceptionInInitializerError
	at com.rjbiermann.JavbangersPlugin.load(Javbangers.kt:183)
12-25 04:41:08.001 E/PluginManager( 4355): Failed to load plugin  MissAV.cs3: No manifest found
EOF
failed=$(extract_failed "$tmp/catchall.log")
[[ $failed == $'Javbangers\nMissAV' ]] || { echo "FAIL: named failures must yield plugin stems, got '$failed'"; exit 1; }

# Silence is not a verdict (#1545). The 2026-11-23 run reported three plugins as a failing set
# whose `load()` never returned: 24/27 printed a verdict, those three printed neither `Loaded`
# nor `Failed`, and the app's next round skipped them ("Plugin with name X already exists"),
# so the silence could not resolve inside the wait window. Anything the log never decided on
# must be separable from a plugin the log explicitly failed, or the tier invents a provider verdict.
printf 'EPorner\nJavbangers\nMissAV\nixiporn\n' > "$tmp/expected.txt"
unsettled "$tmp/catchall.log" "$tmp/expected.txt" > "$tmp/unsettled.txt"
[[ $(cat "$tmp/unsettled.txt") == ixiporn ]] || { echo "FAIL: only the never-decided plugin is unsettled, got '$(cat "$tmp/unsettled.txt")'"; exit 1; }
unsettled "$tmp/sample.log" "$tmp/expected.txt" | grep -qx 'ixiporn' \
  || { echo "FAIL: manifest-shape failure must settle its plugin"; exit 1; }
: > "$tmp/none-expected.txt"
[[ -z $(unsettled "$tmp/sample.log" "$tmp/none-expected.txt") ]] || { echo "FAIL: no expected plugins means nothing unsettled"; exit 1; }

# Whole-name matching, not substring (review finding): `MissAV` is not settled by a verdict
# naming `MissAVX`, and a versioned loaded name still settles its own stem. Substring matching
# attributes silence to the wrong plugin, which is how a provider gets named for a stall.
cat > "$tmp/overlap.log" <<'EOF'
12-25 04:41:07.100 I/PluginManager( 4355): Loaded plugin MissAVX successfully
12-25 04:41:08.100 I/PluginManager( 4355): Loaded plugin EPorner (4) successfully
EOF
printf 'MissAV\nMissAVX\nEPorner\n' > "$tmp/overlap-expected.txt"
overlap=$(unsettled "$tmp/overlap.log" "$tmp/overlap-expected.txt")
[[ $overlap == MissAV ]] || { echo "FAIL: exact matching must leave only MissAV unsettled, got '$overlap'"; exit 1; }

# Success wins over a failure line for the same plugin (review finding on final-buffer
# recovery): a retried first attempt, or the same line seen by both the live reader and the
# final dump, must not keep a plugin in the failing set after it loaded.
cat > "$tmp/transient.log" <<'EOF'
12-25 04:41:07.000 E/PluginManager( 4355): Failed to load /x/EPorner.cs3: java.lang.IllegalStateException
12-25 04:41:07.100 I/PluginManager( 4355): Loaded plugin EPorner.cs3 successfully
12-25 04:41:08.000 E/PluginManager( 4355): Failed to load plugin  MissAV.cs3: No manifest found
EOF
printf 'EPorner\nMissAV\n' > "$tmp/transient-expected.txt"
failing=$(failing_stems "$tmp/transient.log")
[[ $failing == MissAV ]] || { echo "FAIL: a loaded plugin must not stay failed, got '$failing'"; exit 1; }
[[ $(verdict_outcome "$tmp/transient.log" "$tmp/transient-expected.txt") == failed ]] \
  || { echo "FAIL: a remaining named failure must read as failed"; exit 1; }

# The round-level outcome ends the ambiguity between a lagging live reader and a bad round.
# A timed-out live stream whose final buffer dump recovered every verdict is a PASS; a stall
# (no verdict for an expected plugin) is `stalled`, never `failed`; a named failure is `failed`.
cat > "$tmp/all-loaded.log" <<'EOF'
12-25 04:41:07.100 I/PluginManager( 4355): Loaded plugin EPorner successfully
12-25 04:41:07.200 I/PluginManager( 4355): Loaded plugin MissAV successfully
EOF
printf 'EPorner\nMissAV\n' > "$tmp/all-expected.txt"
[[ $(verdict_outcome "$tmp/all-loaded.log" "$tmp/all-expected.txt") == pass ]] \
  || { echo "FAIL: all verdicts recovered must be a pass regardless of reader latency"; exit 1; }
[[ $(verdict_outcome "$tmp/all-loaded.log" "$tmp/expected.txt") == stalled ]] \
  || { echo "FAIL: a silent expected plugin must read as stalled, not failed"; exit 1; }
[[ $(verdict_outcome "$tmp/transient.log" "$tmp/transient-expected.txt") == failed ]] \
  || { echo "FAIL: a named failure must read as failed"; exit 1; }

# Union of live stream and final buffer: a verdict missing from the live reader but present in
# the buffer is recovered, so the round still passes (the second silent-loss path #1545 closes).
cat > "$tmp/live.log" <<'EOF'
12-25 04:41:07.100 I/PluginManager( 4355): Loaded plugin EPorner successfully
EOF
cat > "$tmp/buffers.log" <<'EOF'
12-25 04:41:07.200 I/PluginManager( 4355): Loaded plugin MissAV successfully
EOF
cat "$tmp/live.log" "$tmp/buffers.log" > "$tmp/union.log"
[[ $(verdict_outcome "$tmp/union.log" "$tmp/all-expected.txt") == pass ]] \
  || { echo "FAIL: buffer recovery must settle a verdict the live reader missed"; exit 1; }

# A round that ends in a crash explains its own silence; the unattributable note says so instead
# of implying the emulator wedged. `Process: <pkg>, PID: …` is the header line ART prints with
# FATAL EXCEPTION (and in ANR traces), so matching the package identifies *this* app's death.
cat > "$tmp/crash.log" <<'EOF'
12-25 04:41:09.000 E/AndroidRuntime( 4355): FATAL EXCEPTION: main
12-25 04:41:09.001 E/AndroidRuntime( 4355): Process: com.lagradost.cloudstream3.prerelease, PID: 4355
12-25 04:41:09.020 E/AndroidRuntime( 4355): java.lang.ExceptionInInitializerError
EOF
crashed "$tmp/crash.log" com.lagradost.cloudstream3.prerelease || { echo "FAIL: app crash not detected"; exit 1; }
crashed "$tmp/crash.log" com.example.other && { echo "FAIL: another process's crash is not this app's"; exit 1; }
crashed "$tmp/progress.log" com.lagradost.cloudstream3.prerelease && { echo "FAIL: clean log flagged as crashed"; exit 1; }

# A `Process: <pkg>, PID:` line on its own is how a process listing reads, not a death
# (review finding): require the crash/ANR marker before claiming the app died. An ANR line
# names the package directly and is its own evidence.
cat > "$tmp/process-list.log" <<'EOF'
12-25 04:41:09.000 I/ActivityManager(  519): Process: com.lagradost.cloudstream3.prerelease, PID: 4355
EOF
crashed "$tmp/process-list.log" com.lagradost.cloudstream3.prerelease \
  && { echo "FAIL: a process-list entry is not a crash"; exit 1; }
cat > "$tmp/anr.log" <<'EOF'
12-25 04:41:09.000 E/ActivityManager(  519): ANR in com.lagradost.cloudstream3.prerelease (com.lagradost.cloudstream3.prerelease/.MainActivity)
EOF
crashed "$tmp/anr.log" com.lagradost.cloudstream3.prerelease || { echo "FAIL: ANR not detected as a crash"; exit 1; }
crashed "$tmp/anr.log" com.example.other && { echo "FAIL: another package's ANR is not this app's"; exit 1; }

# SIGQUIT thread-dump detection (review finding on #1550). ART writes the dump to /data/anr
# with a `----- pid <n> at <ts> -----` header and `Cmd line: <pkg>` — capital C, with a space —
# not the Dalvik-era `DALVIK THREADS` / `cmdline:` spellings the old predicate required. That
# predicate therefore read every dump a modern image wrote as "missing" and deleted it, so the
# diagnostic the ADR and #1545 promise for the next stall could never appear.
cat > "$tmp/art-dump.log" <<'EOF'
----- pid 4355 at 2026-11-23 16:00:00.123 -----
Cmd line: com.lagradost.cloudstream3.prerelease
Build fingerprint: 'google/sdk_gphone64_x86_64/google_apis_x86_64:14/UE1A/1234:userdebug/test-keys'
"main" prio=5 tid=1 Native
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x12c00000 self=0x7f8a1c0000
  | sysTid=4355 nice=-10 cgrp=default sched=0/0 handle=0x7f8b2c0d50
  | state=S schedstat=( 123456789 987654321 1234 ) utm=12 stm=3 core=0 HZ=100
  kernel: __switch_to+0x0/0x0
EOF
has_thread_dump "$tmp/art-dump.log" || { echo "FAIL: a real ART dump ('Cmd line:') must be recognised"; exit 1; }
# The legacy spellings and a logcat mirror of the same header must stay recognised: images
# differ, and the fallback path copies a mirrored dump in.
printf 'DALVIK THREADS:\n"main" prio=5 tid=1 NATIVE\n' > "$tmp/dalvik-dump.log"
has_thread_dump "$tmp/dalvik-dump.log" || { echo "FAIL: Dalvik-era dump must stay recognised"; exit 1; }
printf 'cmdline: com.lagradost.cloudstream3.prerelease\n' > "$tmp/old-dump.log"
has_thread_dump "$tmp/old-dump.log" || { echo "FAIL: lowercase 'cmdline:' dump must stay recognised"; exit 1; }
# The notice ART prints in logcat is not the dump itself: a run that only captured
# "Wrote stack traces to ..." has no trace to attach, and must not claim one.
printf '%s\n' "12-25 04:41:09.000 W/art( 4355): Wrote stack traces to '/data/anr/traces.txt'" \
  > "$tmp/dump-notice.log"
has_thread_dump "$tmp/dump-notice.log" && { echo "FAIL: the 'Wrote stack traces' notice is not a dump"; exit 1; }
has_thread_dump "$tmp/does-not-exist.log" && { echo "FAIL: a missing file is not a dump"; exit 1; }
: > "$tmp/empty-dump.log"
has_thread_dump "$tmp/empty-dump.log" && { echo "FAIL: an empty file is not a dump"; exit 1; }

# The digest is the human-facing half of the same contract: a stalled plugin must never be
# folded into "failing" (that restates the #1545 misattribution) or hidden (a red step reading
# clean). Its own self-check lives beside this one.
bash "$(dirname "$0")/test-lane-digest.sh"

echo "parse-log tests PASSED (incl. lane-digest self-check)"
