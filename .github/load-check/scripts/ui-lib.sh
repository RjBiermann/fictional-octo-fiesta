#!/usr/bin/env bash
# Generic emulator UI primitives for CI scenarios (ADR-0012 amended).
#
# Sourced by every script under ../scenarios/. Everything reads the view hierarchy
# (`uiautomator dump`) instead of screenshots, so assertions are machine-parseable
# (workspace rule: XML dumps over screenshots). Patterns are `|`-separated alternatives:
#   text      — substring match on the node's text
#   =text     — exact match on the node's text
#   id=xyz    — substring match on the resource-id
#
# Requires: a running emulator on adb, python3.

# dump — view hierarchy XML. uiautomator refuses while the window animates, so retry.
dump() {
  local i out
  for i in 1 2 3; do
    out=$(timeout 60 adb exec-out uiautomator dump /dev/tty 2>/dev/null || true)
    if [[ -n $out ]]; then printf '%s' "$out"; return 0; fi
    sleep 1
  done
}

# find_node <xml> <pattern> — prints "x y" (node centre) for the first match.
find_node() {
  python3 - "$1" "$2" <<'PY'
import html, re, sys
xml, pattern = sys.argv[1], sys.argv[2]
alts = []
for a in pattern.split('|'):
    if a.startswith('id='):
        alts.append((a[3:], 'id'))
    elif a.startswith('='):
        alts.append((a[1:], 'eq'))
    else:
        alts.append((a, 'has'))
for m in re.finditer(r'<node[^>]*>', xml):
    n = m.group(0)
    b = re.search(r' bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if not b:
        continue
    t = re.search(r' text="([^"]*)"', n)
    r = re.search(r'resource-id="([^"]*)"', n)
    text = html.unescape(t.group(1)) if t else ''
    rid = r.group(1) if r else ''
    for value, kind in alts:
        hit = value in rid if kind == 'id' else (text == value if kind == 'eq' else value in text)
        if hit:
            print((int(b.group(1)) + int(b.group(3))) // 2, (int(b.group(2)) + int(b.group(4))) // 2)
            sys.exit(0)
sys.exit(1)
PY
}

# has_node <pattern> — 0 when present on the current screen.
has_node() { find_node "$(dump)" "$1" > /dev/null; }

# wait_node <pattern> <timeout-s> [evidence-file] — prints "x y" once the node exists.
wait_node() {
  local pat=$1 deadline=$((SECONDS + $2)) xml
  while :; do
    xml=$(dump)
    if find_node "$xml" "$pat"; then return 0; fi
    if [[ $SECONDS -ge $deadline ]]; then
      printf '%s\n' "$xml" > "${3:-/dev/null}"
      echo "TIMEOUT waiting for '$pat' (screen saved)" >&2
      return 1
    fi
    sleep 2
  done
}

# tap_node <pattern> <timeout-s> [evidence-file]
tap_node() {
  local coords
  coords=$(wait_node "$1" "$2" "${3:-/dev/null}") || return 1
  # shellcheck disable=SC2086 # coords is intentionally split into two arguments
  adb shell input tap $coords
}

# dismiss_system_prompts — post-install prompts (notifications, etc.) sit above the app.
dismiss_system_prompts() {
  local i
  for i in 1 2 3; do
    if has_node '=Allow|=While using the app|=OK'; then
      tap_node '=Allow|=While using the app|=OK' 15 > /dev/null || true
      sleep 1
    else
      return 0
    fi
  done
}

# screen_size — "W H" of the current display (swipe coordinates are derived from it).
screen_size() {
  local w h
  read -r w h <<< "$(adb shell wm size 2>/dev/null | sed 's/.*: //' | tr 'x' ' ')"
  echo "${w:-1080} ${h:-1920}"
}

# scroll_down — one flick towards the end of the list.
scroll_down() {
  local w h
  read -r w h <<< "$(screen_size)"
  # mid-screen start so the flick also scrolls content inside a centred dialog
  adb shell input swipe "$((w / 2))" "$((h * 6 / 10))" "$((w / 2))" "$((h * 3 / 10))" 250 > /dev/null 2>&1 || true
}

# wait_node_scroll <pattern> <timeout-s> [swipes] — like wait_node, for content that is
# off-screen (long provider lists, deep result pages). Scrolls down before each retry.
wait_node_scroll() {
  local pat=$1 timeout=$2 swipes=${3:-8} i xml coords
  local deadline=$((SECONDS + timeout))
  while :; do
    for ((i = 0; i <= swipes; i++)); do
      xml=$(dump)
      if coords=$(find_node "$xml" "$pat"); then printf '%s\n' "$coords"; return 0; fi
      scroll_down
      sleep 2   # let a fling settle; a moving list makes the next tap land elsewhere
    done
    if [[ $SECONDS -ge $deadline ]]; then
      printf '%s\n' "$xml" > "${EVIDENCE:-/tmp}/smoke-last-screen.xml"
      echo "TIMEOUT waiting for '$pat' after scrolling (screen saved)" >&2
      return 1
    fi
  done
}

# renderer_alive — the software GPU (swiftshader) on a hosted runner can wedge; both
# `uiautomator dump` and `screencap` then return zero bytes, which looks like a selector
# bug. A non-empty frame is the cheap health signal for that failure mode.
renderer_alive() {
  local bytes
  bytes=$(timeout 30 adb exec-out screencap -p 2>/dev/null | wc -c)
  (( bytes > 1000 ))
}

# emulator_alive — the device answered and finished booting. A dead emulator is an
# infrastructure fault: it must never be reported as a provider or scenario failure.
emulator_alive() {
  [[ $(timeout 20 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]
}

# require_emulator <label> — abort with the infrastructure signal (exit 3) when the emulator is
# gone. The scenario writes a marker so the lane digest can say so instead of blaming a provider.
require_emulator() {
  emulator_alive && return 0
  echo "INFRASTRUCTURE: emulator is not running ($1) — not a provider fault" >&2
  : > "${EVIDENCE:-loadcheck-evidence}/emulator-died"
  exit 3
}

# ensure_renderer — the hosted runner's emulator can die outright (the next adb call then
# hangs). Restart it in place once; a second failure is reported, not retried.
ensure_renderer() {
  if renderer_alive && [[ $(timeout 20 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then
    return 0
  fi
  echo "emulator looks dead (no frame buffer / no boot flag) — restarting it" >&2
  "$(dirname "${BASH_SOURCE[0]}")/emulator-restart.sh" "${AVD_NAME:-loadcheck}" "${EVIDENCE:-loadcheck-evidence}" || return 1
  renderer_alive
}

# screenshot <path> — for evidence only; assertions must use the hierarchy.
screenshot() { timeout 60 adb exec-out screencap -p > "$1" 2>/dev/null || true; }
