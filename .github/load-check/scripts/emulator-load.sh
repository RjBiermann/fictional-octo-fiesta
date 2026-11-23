#!/usr/bin/env bash
# ADR-0012 step 3 — KVM emulator load check, zero AI.
# 1. Install the LIVE UNPINNED pre-release CloudStream APK.
# 2. Push every built .cs3 into /storage/emulated/0/Cloudstream3/plugins/
#    (upstream's own deployWithAdb mechanism — recloudstream/gradle
#    DeployWithAdbTask.kt), chmod -w each (Android 14 dynamic-code rule).
# 3. Boot the app and let its own startup load register the pushed plugins;
#    upstream's hot-reload intent ("cloudstreamapp:") is never fired here (see the
#    note above the registration wait). Assert registration at logcat level only:
#      success  -> PluginManager: Loaded plugin <name> successfully
#      failure  -> PluginManager: Failed to load <file>: <stack>
#                  PluginManager: Failed to load plugin  X: <reason>
# Emulator results are never Geo-gated evidence (same runner IP, ADR-0012).
#
# Usage: emulator-load.sh <cs3-dir>
# Inputs: running emulator (workflow starts it), ANDROID_HOME, GH_TOKEN.
# Exit 1 with loadcheck-evidence/failing-plugins.txt populated on failure, or with
# loadcheck-evidence/unattributable.txt written when no plugin could be attributed (the
# issue writer files those for a human instead of firing an implementation run).
set -euo pipefail

CS3_DIR=$1
source "$(dirname "$0")/parse-log.sh"
EVIDENCE=loadcheck-evidence
mkdir -p "$EVIDENCE"

# ---- wait for boot --------------------------------------------------------
deadline=$((SECONDS + 900))
until [[ $(timeout 30 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; do
  [[ $SECONDS -lt $deadline ]] || { echo "emulator did not boot within 15 min" >&2; exit 1; }
  sleep 10
done
echo "boot completed"

# ---- install latest unpinned pre-release APK ------------------------------
APK_URL=$(gh api repos/recloudstream/cloudstream/releases \
  --jq '[.[] | select(.prerelease)][0] | .assets[] | select(.name=="app-prerelease-release.apk") | .browser_download_url')
echo "APK: $APK_URL"
curl -sL "$APK_URL" -o "$EVIDENCE/app-prerelease.apk"
AAPT=$(ls "$ANDROID_HOME"/build-tools/*/aapt 2>/dev/null | sort | tail -1 || true)
VERSION=$("$AAPT" dump badging "$EVIDENCE/app-prerelease.apk" 2>/dev/null \
  | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1) || true
[[ -n ${VERSION:-} ]] || VERSION="latest-prerelease (versionName unparsable)"
printf '%s\n' "$VERSION" > "$EVIDENCE/app-version.txt"
# The prerelease APK installs as com.lagradost.cloudstream3.prerelease, not the stable
# package — a hardcoded name launches nothing and every plugin then "fails" to load.
PKG=$("$AAPT" dump badging "$EVIDENCE/app-prerelease.apk" 2>/dev/null \
  | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1) || true
[[ -n ${PKG:-} ]] || PKG=$(adb shell pm list packages 2>/dev/null \
  | sed -n "s/^package:\(com\.lagradost\.cloudstream3.*\)$/\1/p" | head -1 | tr -d '\r')
[[ -n ${PKG:-} ]] || { echo "cannot determine the CloudStream package name" >&2; exit 1; }
printf '%s\n' "$PKG" > "$EVIDENCE/app-package.txt"
echo "package: $PKG"

# ---- stall diagnostics (best effort) --------------------------------------
# A round that stalls silently leaves no stack in logcat, which is why the class recurred twice
# (#1500, #1545) without a root cause. `kill -3` makes ART dump every thread of the app into the
# buffer, so the next occurrence shows *where* `load()` sits. Signalling another uid needs root,
# which only the userdebug system image this lane runs (`google_apis`) allows, so every step is
# best-effort: the run's verdict never depends on it.
dump_threads() {
  local pid listing trace
  pid=$(timeout 20 adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}') || true
  [[ -n ${pid:-} ]] || { echo "thread dump skipped: no pid for $PKG" >&2; return 0; }
  timeout 20 adb root >/dev/null 2>&1 || true
  timeout 60 adb wait-for-device >/dev/null 2>&1 || true
  timeout 20 adb shell kill -3 "$pid" >/dev/null 2>&1 || true
  sleep 5
  # SIGQUIT makes ART write the stack to /data/anr (`traces.txt` on older images, `anr_<ts>`
  # on newer ones) — logcat only carries the "Wrote stack traces to …" notice, so the old
  # logcat-only read usually produced no dump at all. Read the trace file itself (newest
  # first, `ls -t`), with logcat as the fallback for images that mirror the dump.
  listing=$(timeout 20 adb shell 'ls -t /data/anr 2>/dev/null' | tr -d '\r') || true
  trace=$(awk 'NF{print; exit}' <<<"${listing:-}") || true
  [[ -n ${trace:-} ]] || trace=traces.txt
  timeout 60 adb shell "cat /data/anr/$trace" > "$EVIDENCE/thread-dump.log" 2>/dev/null || true
  # `adb root` restarted adbd, which ended the live logcat stream above — re-read the whole
  # buffer (`-d`) so a mirrored dump is captured too before the device state is torn down.
  timeout 60 adb logcat -d -v time > "$EVIDENCE/logcat-after-dump.log" 2>/dev/null || true
  if ! grep -q 'DALVIK THREADS\|cmdline:' "$EVIDENCE/thread-dump.log" 2>/dev/null \
    && grep -q 'DALVIK THREADS\|cmdline:' "$EVIDENCE/logcat-after-dump.log" 2>/dev/null; then
    cp "$EVIDENCE/logcat-after-dump.log" "$EVIDENCE/thread-dump.log"
  fi
  if grep -q 'DALVIK THREADS\|cmdline:' "$EVIDENCE/thread-dump.log" 2>/dev/null; then
    printf '\n===== thread dump (kill -3, after the stall) — why load() never returned =====\n' >> "$EVIDENCE/logcat.log"
    cat "$EVIDENCE/thread-dump.log" >> "$EVIDENCE/logcat.log"
    echo "thread dump captured → $EVIDENCE/thread-dump.log"
  else
    rm -f "$EVIDENCE/thread-dump.log"
    echo "thread dump unavailable (adb root, kill -3 or /data/anr read refused) — verdict unaffected" >&2
  fi
}

adb install -r -t "$EVIDENCE/app-prerelease.apk"

# ---- logcat capture starts BEFORE the app touches anything ----------------
# The plugin round prints ~4 000 lines in bursts of a few hundred ms (one `Adding … ExtractorApi`
# per registry row per plugin), and `adb logcat` streams through the emulator's adb bridge, which
# lags whenever the guest is busy. logd's default ring is 256 KiB per buffer — smaller than one
# such burst — so a lagging reader would drop verdict lines *silently*, and a dropped
# `Loaded plugin X successfully` is indistinguishable from a plugin that never loaded. That is
# not what produced #1545's failing set (the round's per-row counts step down in exact
# three-stop arithmetic there, i.e. three stalled registrations, no lost lines), but it is a
# second silent way to lose a verdict and costs emulator RAM, not run time.
adb logcat -G 64M >/dev/null 2>&1 || echo "warning: could not enlarge the logcat ring buffers" >&2
adb logcat -g > "$EVIDENCE/logcat-buffer-sizes.txt" 2>&1 || true
adb logcat -c
adb logcat -v time > "$EVIDENCE/logcat.log" &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null || true' EXIT

# ---- all-files access: without it the app lists 0 files in its plugin folder ----
# A fresh install has no storage grant, so the external plugin dir reads as empty and
# every plugin "fails" to load (measured: "Files in '/storage/emulated/0/Cloudstream3/
# plugins' folder: 0"). The install-time dialog is not tap-able in CI, so apply the
# app-op directly.
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant "$PKG" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true

# ---- push every plugin ----------------------------------------------------
adb shell mkdir -p /storage/emulated/0/Cloudstream3/plugins
for f in "$CS3_DIR"/*.cs3; do
  adb push "$f" /storage/emulated/0/Cloudstream3/plugins/ >/dev/null
done
adb shell "for f in /storage/emulated/0/Cloudstream3/plugins/*.cs3; do chmod -w \"\$f\"; done" || true

timeout 60 adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null || true
sleep 8

# ---- the app's own startup load is the only load round ---------------------
# The app loads every local plugin by itself when it starts, and the .cs3 files were pushed
# above, *before* the launch — so that startup round is the round this check asserts.
# Upstream's hot-reload intent (exactly DeployWithAdbTask's args: `start -a
# android.intent.action.VIEW -d cloudstreamapp:`, no trailing package argument) exists for a
# *running* app whose plugin folder changes; on a freshly installed APK launched by this
# harness there is no such app, so there is nothing for it to reload. Firing it into a
# startup load that is still in flight instead unloads plugin instances whose load() has not
# returned and then re-enters load() for the same .cs3: on run 45024201161 that overlap lost
# exactly the two plugins that were mid-load (`PerverZija`, `WatchPorn`) — neither ever
# printed `Loaded plugin … successfully` *or* `Failed to load …`, so the check reported a
# failure no provider code explains (FINDINGS-1500, ADR-0012). No timeout makes that safe —
# "no verdict yet" is not "no round in flight" — so the intent is never fired here, and a
# startup round that leaves a plugin without a verdict is reported as stalled (below), not
# reloaded.

# ---- wait until every plugin has a verdict or the deadline hits ------------
mapfile -t expected < <(cd "$CS3_DIR" && ls *.cs3 | sed 's/\.cs3$//' | sort)
printf '%s\n' "${expected[@]:-}" > "$EVIDENCE/expected-plugins.txt"
echo "expected ${#expected[@]} plugins"

# The round has settled when every expected plugin has a verdict: `Loaded plugin …
# successfully` or a named `Failed to load …`. Waiting for "all loaded and no failure" instead
# burned the full 300 s whenever one plugin genuinely failed, and it could not distinguish a
# plugin the app refused from one the app never decided on (#1545).
timed_out=0
deadline=$((SECONDS + 300))
while :; do
  unsettled_now=$(unsettled "$EVIDENCE/logcat.log" "$EVIDENCE/expected-plugins.txt" || true)
  if [[ -z $unsettled_now ]]; then
    echo "ALL ${#expected[@]} plugins reported"
    break
  fi
  if [[ $SECONDS -ge $deadline ]]; then timed_out=1; break; fi
  sleep 10
done

# ---- verdicts come from the union of the live stream and logd's buffers ----
# The live `adb logcat` reader can lag behind the guest, so a line can be dropped before this
# process sees it; re-reading the buffers with `-d` at the end recovers everything still held.
# Together with the enlarged buffers set before `logcat -c`, that makes every verdict line from
# the round available, so "no verdict" means the app never printed one, not that this reader
# missed it. The *final* state is what decides the run — a live reader that timed out but whose
# buffers held every verdict is a pass with a warning, never a red run (review finding).
timeout 120 adb logcat -d -v time > "$EVIDENCE/logcat-buffers.log" 2>/dev/null || true
cat "$EVIDENCE/logcat.log" "$EVIDENCE/logcat-buffers.log" > "$EVIDENCE/verdicts.log" 2>/dev/null || true
VERDICTS=$EVIDENCE/verdicts.log
unsettled_now=$(unsettled "$VERDICTS" "$EVIDENCE/expected-plugins.txt" || true)
loaded=$(extract_loaded "$VERDICTS" || true)
printf '%s\n' "$loaded" > "$EVIDENCE/loaded-plugins.txt"

# ---- attribution: verdict lines only, never silence -----------------------
# A plugin the log *names* as failed is a provider verdict. A plugin with no verdict at all is
# not one, however many of its neighbours reported: on 2026-11-23 that difference *was* the
# whole failing set — `Javbangers`, `MissAV` and `ixiporn` reported nothing while 24/27 did,
# nothing threw, and the app's own next round skipped them (`Plugin with name X already exists`:
# PluginManager.plugins is keyed by path and filled *before* `load()` runs), so the silence could
# never resolve inside the window (#1545, docs/archive/findings/FINDINGS-1545.md). Reading
# "expected minus loaded" as a failing set is what turned that into a provider issue (#1500
# treated a whole-round silence this way; a partial one is the same class).
# `failing_stems` drops a plugin that also has a loaded verdict: a failure line from an attempt
# the app retried (or from the union) must not outvote the successful one.
failing_stems "$VERDICTS" > "$EVIDENCE/failing-plugins.txt" || true
sort -u -o "$EVIDENCE/failing-plugins.txt" "$EVIDENCE/failing-plugins.txt"
silent=$unsettled_now
printf '%s\n' "${silent:-}" > "$EVIDENCE/stalled-plugins.txt"
[[ -z $silent ]] || echo "::warning::no plugin verdict (not a provider verdict): $(paste -sd' ' "$EVIDENCE/stalled-plugins.txt")"

# The final attribution — computed from verdict lines only — is what ends the run. A live
# reader that did not settle in time is a *latency* warning when the final buffer dump recovered
# every verdict; it must not fail a round whose every plugin reported (review finding).
outcome=$(verdict_outcome "$VERDICTS" "$EVIDENCE/expected-plugins.txt" || true)
if [[ $outcome == pass ]]; then
  [[ $timed_out -eq 0 ]] || echo "::warning::live logcat did not settle within the window; the final buffer dump recovered every verdict"
fi

if [[ -n $silent ]]; then
  # A stall is the only way the loop reaches its deadline, so the thread dump belongs here.
  dump_threads
  {
    echo "no plugin verdict for $(grep -c . "$EVIDENCE/stalled-plugins.txt") of ${#expected[@]} expected plugins — the app never decided on them:"
    sed 's/^/- /' "$EVIDENCE/stalled-plugins.txt"
    if crashed "$VERDICTS" "$PKG"; then
      echo "the app crashed during the round (FATAL EXCEPTION — stack in the logcat tail)"
    fi
    echo "not a provider verdict: these printed neither 'Loaded plugin … successfully' nor 'Failed to load …' (#1357/#1500/#1545)"
    echo "a dropped log line looks the same — see logcat-buffer-sizes.txt and logcat-buffers.log; thread-dump.log names a parked frame when one could be taken"
  } > "$EVIDENCE/stall-note.txt"
fi

# A stalled round with no named failure has nothing attributable: the infrastructure/human
# class (#1357). The writer reads `unattributable.txt`, labels the issue for a human and never
# dispatches an implementation run for it.
if [[ ! -s $EVIDENCE/failing-plugins.txt && -n $silent ]]; then
  cp "$EVIDENCE/stall-note.txt" "$EVIDENCE/unattributable.txt"
fi

if [[ -s $EVIDENCE/failing-plugins.txt ]]; then
  echo "LOAD FAILURES:"; cat "$EVIDENCE/failing-plugins.txt"
  # A named failure plus a stalled neighbour is inconclusive about the *stall* only; the named
  # failure is real evidence, so it keeps the attributable path and the stall stays a warning.
  [[ -z $silent ]] || echo "note: $(grep -c . "$EVIDENCE/stalled-plugins.txt") plugin(s) additionally never reported — see stalled-plugins.txt" >&2
  exit 1
fi
if [[ -n $silent ]]; then
  echo "STALLED: no verdict for $(grep -c . "$EVIDENCE/stalled-plugins.txt") plugin(s) — unattributable, not a provider failure" >&2
  exit 1
fi
if [[ $outcome != pass ]]; then
  echo "::error::could not classify the round (outcome '${outcome:-empty}') — see verdicts.log" >&2
  exit 1
fi
echo "load check PASSED — all ${#expected[@]} plugins reported"
