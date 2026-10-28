#!/usr/bin/env bash
# Emulator CLI over the scenario primitives (ADR-0012) — for an agent or human that has adb,
# e.g. an agent running on the runner or in the DevPod. Deliberately terse: `dump --compact`
# prints only actionable nodes (text / id / bounds), because a full hierarchy is 20-45 KB.
#
#   emulator.sh dump [--compact] [--file <path>]
#   emulator.sh find <pattern>          # pattern: text | =exact | id=resource-id  ('|' = or)
#   emulator.sh wait <pattern> [timeout]
#   emulator.sh tap  <pattern> [timeout]
#   emulator.sh shot <path>
#   emulator.sh start <avd>
#   emulator.sh restart <avd>
#   emulator.sh scenario <name> [arg]   # runs scenarios/<name>.sh with the evidence dir
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=ui-lib.sh
source "$HERE/ui-lib.sh"
EVIDENCE=${EVIDENCE:-loadcheck-evidence}
export EVIDENCE
mkdir -p "$EVIDENCE"

compact() {
  python3 - "$1" <<'PY'
import re, sys
xml = open(sys.argv[1]).read()
seen = set()
for m in re.finditer(r'<node[^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n)
    r = re.search(r'resource-id="([^"]*)"', n)
    b = re.search(r' bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    text = t.group(1) if t else ''
    rid = (r.group(1) if r else '').split('/')[-1]
    if not (text or rid) or not b:
        continue
    key = (text, rid)
    if key in seen:
        continue
    seen.add(key)
    cx, cy = (int(b.group(1)) + int(b.group(3))) // 2, (int(b.group(2)) + int(b.group(4))) // 2
    print(f"{cx:>5},{cy:<5} {rid or '-':<28} {text[:60]}")
PY
}

cmd=${1:-}; shift || true
case "$cmd" in
  dump)
    if [[ ${1:-} == --compact ]]; then
      f="$EVIDENCE/ui.xml"; dump > "$f"; compact "$f"
    elif [[ ${1:-} == --file ]]; then
      dump > "$2"; echo "saved $2"
    else
      dump
    fi ;;
  find)   find_node "$(dump)" "$1" ;;
  wait)   wait_node "$1" "${2:-60}" "$EVIDENCE/ui-timeout.xml" ;;
  tap)    tap_node "$1" "${2:-60}" "$EVIDENCE/ui-timeout.xml" > /dev/null && echo "tapped '$1'" ;;
  shot)   screenshot "$1"; echo "saved $1" ;;
  start)
    local avd=${1:?usage: emulator.sh start <avd>}
    if command -v emul-start > /dev/null; then
      emul-start "$avd" > /dev/null 2>&1 &
    else
      "${ANDROID_HOME:?ANDROID_HOME is required}/emulator/emulator" -avd "$avd" -no-window \
        -no-audio -no-boot-anim -gpu "${GPU_MODE:-auto}" -no-snapshot > /dev/null 2>&1 &
    fi
    sleep 5
    timeout 300 adb wait-for-device
    echo started ;;
  restart) "$HERE/emulator-restart.sh" "$1" "$EVIDENCE" ;;
  scenario)
    name=$1; arg=${2:-all}
    "$HERE/../scenarios/$name.sh" "$EVIDENCE" "$arg" ;;
  *) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
