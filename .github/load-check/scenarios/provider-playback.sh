#!/usr/bin/env bash
# Scenario: does a provider's video actually play in the app? (ADR-0012 amended)
#
# The load check proves a plugin registers; the player probe proves the app can play a
# known-good local stream. This opens a *provider's* video through the app's own
# `csshare:` deep link (no settings/search/card navigation to drift), presses Play, and
# asserts a rendered frame from that provider's own source — the evidence #1353 needed.
#
# Usage: scenarios/provider-playback.sh <evidence-dir> [provider|all]
#        rows: ../scripts/provider-smoke.tsv
set -euo pipefail

EVIDENCE=${1:-loadcheck-evidence}
WANT=${2:-all}
HERE=$(dirname "$0")
# shellcheck source=../scripts/cloudstream-lib.sh
source "$HERE/../scripts/cloudstream-lib.sh"
TABLE="$HERE/../scripts/provider-smoke.tsv"
mkdir -p "$EVIDENCE"

[[ -f $TABLE ]] || { echo "missing table: $TABLE" >&2; exit 1; }

# The load check's own logcat capture ends with that script, so the scenario keeps its own:
# a red run must be diagnosable without guessing (screen XML + adb-visible app + logcat).
adb logcat -c
adb logcat -v time > "$EVIDENCE/scenario-logcat.log" 2>&1 &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null || true' EXIT

rows=0
while IFS=$'\t' read -r provider url title; do
  if [[ -z ${provider:-} || ${provider:0:1} == "#" ]]; then continue; fi
  if [[ $WANT != all && $WANT != "$provider" ]]; then continue; fi
  echo "=== $provider — expect '$title' from $url"
  require_emulator "before $provider"
  ensure_renderer
  require_emulator "after renderer check"
  cs_launch
  cs_enable_nsfw
  cs_open_video "$provider" "$url"
  if ! wait_node 'id=result_play_movie|id=result_title' 60 "${EVIDENCE}/cs-result.xml" > /dev/null; then
    screenshot "$EVIDENCE/fail-$provider.png"
    echo "FAIL $provider — the video page did not open (see fail-$provider.png)" >&2
    exit 1
  fi
  if ! find_node "$(dump)" "$title" > /dev/null; then
    echo "FAIL $provider — result page does not show '$title'" >&2
    exit 1
  fi
  cs_press_play "$provider"
  cs_assert_playback "$provider" 60
  require_emulator "after $provider"
  screenshot "$EVIDENCE/smoke-$provider.png"
  echo "PASS $provider — the app rendered '$title'"
  rows=$((rows + 1))
  adb shell am force-stop "$(cs_package)" > /dev/null 2>&1 || true
done < "$TABLE"

(( rows > 0 )) || { echo "no rows matched '$WANT' in $TABLE" >&2; exit 1; }
echo "provider playback PASSED — $rows provider(s)"
