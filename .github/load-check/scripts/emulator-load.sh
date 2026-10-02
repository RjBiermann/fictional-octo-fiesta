#!/usr/bin/env bash
# ADR-0012 step 3 — KVM emulator load check, zero AI.
# 1. Install the LIVE UNPINNED pre-release CloudStream APK.
# 2. Push every built .cs3 into /storage/emulated/0/Cloudstream3/plugins/
#    (upstream's own deployWithAdb mechanism — recloudstream/gradle
#    DeployWithAdbTask.kt), chmod -w each (Android 14 dynamic-code rule).
# 3. Boot the app, fire upstream's local-plugin reload intent
#    ("cloudstreamapp:"), assert registration at logcat level only:
#      success  -> PluginManager: Loaded plugin <name> successfully
#      failure  -> PluginManager: Failed to load <file>: <stack>
#                  PluginManager: Failed to load plugin  X: <reason>
# Emulator results are never Geo-gated evidence (same runner IP, ADR-0012).
#
# Usage: emulator-load.sh <cs3-dir>
# Inputs: running emulator (workflow starts it), ANDROID_HOME, GH_TOKEN.
# Exit 1 with loadcheck-evidence/failing-plugins.txt populated on failure.
set -euo pipefail

CS3_DIR=$1
source "$(dirname "$0")/parse-log.sh"
EVIDENCE=loadcheck-evidence
mkdir -p "$EVIDENCE"
PKG=com.lagradost.cloudstream3

# ---- wait for boot --------------------------------------------------------
deadline=$((SECONDS + 900))
until [[ $(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; do
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
adb install -r -t "$EVIDENCE/app-prerelease.apk"

# ---- logcat capture starts BEFORE the app touches anything ----------------
adb logcat -c
adb logcat -v time > "$EVIDENCE/logcat.log" &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null || true' EXIT

# ---- push every plugin, then upstream's own reload intent -----------------
adb shell mkdir -p /storage/emulated/0/Cloudstream3/plugins
for f in "$CS3_DIR"/*.cs3; do
  adb push "$f" /storage/emulated/0/Cloudstream3/plugins/ >/dev/null
done
adb shell "for f in /storage/emulated/0/Cloudstream3/plugins/*.cs3; do chmod -w \"\$f\"; done" || true

adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null || true
sleep 8
# Exactly upstream DeployWithAdbTask's args: `start -a android.intent.action.VIEW
# -d cloudstreamapp:` — no trailing package argument.
adb shell am start -a android.intent.action.VIEW -d 'cloudstreamapp:' >/dev/null || true

# ---- wait until every plugin reports or the deadline hits -----------------
mapfile -t expected < <(cd "$CS3_DIR" && ls *.cs3 | sed 's/\.cs3$//' | sort)
printf '%s\n' "${expected[@]:-}" > "$EVIDENCE/expected-plugins.txt"
echo "expected ${#expected[@]} plugins"

# ponytail: substring match tolerates version suffixes in logged plugin names
# ("EPorner (4)" vs expected "EPorner"); distinct .cs3 stems make false
# positives unlikely.
all_loaded() {
  local l="$1" e
  for e in "${expected[@]}"; do grep -qF "$e" <<<"$l" || return 1; done
  return 0
}

timed_out=0
deadline=$((SECONDS + 300))
while :; do
  loaded=$(extract_loaded "$EVIDENCE/logcat.log" || true)
  if all_loaded "$loaded" && ! has_failed "$EVIDENCE/logcat.log"; then
    echo "ALL ${#expected[@]} plugins loaded"
    break
  fi
  if [[ $SECONDS -ge $deadline ]]; then timed_out=1; break; fi
  sleep 10
done

# ---- failing set: expected minus successfully-loaded (best-effort names) --
printf '%s\n' "$loaded" > "$EVIDENCE/loaded-plugins.txt"
for e in "${expected[@]}"; do
  grep -qF "$e" <<<"$loaded" || echo "$e" >> "$EVIDENCE/failing-plugins.txt"
done
# plus any plugin names the failure logs name directly
extract_failed "$EVIDENCE/logcat.log" >> "$EVIDENCE/failing-plugins.txt" || true
sort -u -o "$EVIDENCE/failing-plugins.txt" "$EVIDENCE/failing-plugins.txt"

if [[ $timed_out -eq 1 ]]; then
  echo "TIMED OUT waiting for plugin registration — check logcat.log" >&2
  [[ -s $EVIDENCE/failing-plugins.txt ]] || { \
    echo "no per-plugin failures logged; plugin set unchanged (${#expected[@]} expected)" \
       > "$EVIDENCE/failing-plugins.txt"; }
fi

if [[ -s $EVIDENCE/failing-plugins.txt ]]; then
  echo "LOAD FAILURES:"; cat "$EVIDENCE/failing-plugins.txt"
  exit 1
fi
[[ $timed_out -eq 0 ]] || exit 1
echo "load check PASSED — all ${#expected[@]} plugins registered in the app"
