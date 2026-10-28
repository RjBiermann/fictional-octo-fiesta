#!/usr/bin/env bash
# Restart the load-check emulator in place (ADR-0012 amended).
#
# On hosted runners the emulator can die mid-job: the scenario step then sees empty
# hierarchies AND empty screencaps, and the next `adb` call hangs on "- waiting for
# device -". Rather than re-running the whole job, relaunch the AVD here and wait for
# boot. Evidence of the death (dmesg / free) is captured by the workflow's diagnostic step.
#
# Usage: emulator-restart.sh <avd-name> [evidence-dir]
set -euo pipefail

AVD=$1
EVIDENCE=${2:-loadcheck-evidence}
mkdir -p "$EVIDENCE"

pkill -f "qemu-system" 2>/dev/null || true
sleep 3
# Two knobs that must match the host. GPU_MODE defaults to `auto`: hosted runners have no GPU
# and the emulator resolves `auto` to `swangle` (software ANGLE), which is the only mode that
# survived rendered video here — `swiftshader_indirect` aborts the emulator on the first frame,
# and `angle_indirect` is not a valid option for this emulator build (it silently falls back to
# `auto`). A workstation/DevPod may pass GPU_MODE=host. `emul-start` is the known-good launcher
# in the DevPod; hosted runners have neither it nor a GPU.
export GPU_MODE=${GPU_MODE:-auto}
rm -f "$HOME"/.android/avd/*.avd/*.lock 2>/dev/null || true
if command -v emul-start > /dev/null && [[ ${USE_EMUL_START:-0} == 1 ]]; then
  emul-start "$AVD" >> "$EVIDENCE/emulator.log" 2>&1 &
else
  LAUNCH=()
  if [[ ${USE_XVFB:-1} == 1 ]] && command -v xvfb-run > /dev/null; then LAUNCH=(xvfb-run -a); fi
  "${LAUNCH[@]}" "${ANDROID_HOME:?ANDROID_HOME is required}/emulator/emulator" -avd "$AVD" \
    -no-window -no-audio -no-boot-anim -gpu "$GPU_MODE" -no-snapshot \
    -memory 2048 -cores 2 >> "$EVIDENCE/emulator.log" 2>&1 &
fi
echo $! > "$EVIDENCE/emulator.pid"

deadline=$((SECONDS + 300))
until [[ $(timeout 20 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; do
  [[ $SECONDS -lt $deadline ]] || { echo "emulator did not boot after restart" >&2; exit 1; }
  sleep 5
done
sleep 10
# the app's all-files app-op does not survive a fresh emulator process
PKG=$(cat "$EVIDENCE/app-package.txt" 2>/dev/null || echo com.lagradost.cloudstream3.prerelease)
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
echo "emulator restarted and booted"
echo restart >> "$EVIDENCE/emulator-restarts"
