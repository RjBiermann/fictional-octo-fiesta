#!/usr/bin/env bash
# ADR-0012 extension — CloudStream's own player must open an http(s) HLS stream.
#
# Registration-only checks ("Loaded plugin X successfully") cannot see the layer that
# broke #1353: the provider chain was green end-to-end while the app rejected the emitted
# `data:` playlist at the datasource (Cronet: net::ERR_UNKNOWN_URL_SCHEME → ExoPlayer
# 2001, FINDINGS-1353.md). This probe plays a generated local fixture through the app's
# own player deep link (`cloudstreamplayer://<url>`, MainActivity APP_STRING_PLAYER) and
# asserts a rendered frame. No site, no geo, no AI: the only network leg is
# emulator → 10.0.2.2 (the runner's own loopback).
#
# Usage: player-probe.sh [evidence-dir]
# Inputs: running emulator, ffmpeg, python3. Exit 1 when the app never renders.
set -euo pipefail

EVIDENCE=${1:-loadcheck-evidence}
PORT=${PROBE_PORT:-8765}
mkdir -p "$EVIDENCE"
WORK=$(mktemp -d)
HTTP_PID=""
cleanup() { [[ -n "$HTTP_PID" ]] && kill "$HTTP_PID" 2>/dev/null || true; rm -rf "$WORK"; }
trap cleanup EXIT

command -v ffmpeg >/dev/null || { echo "ffmpeg missing — install it before the probe" >&2; exit 1; }

# 2 s H.264/TS + VOD playlist: small enough for a 60 s window, decodable by the
# emulator's software codecs (no GPU needed).
ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc=size=320x240:rate=25:duration=2 \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p -f mpegts "$WORK/seg0.ts"
cat > "$WORK/index.m3u8" <<'EOF'
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:2
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-PLAYLIST-TYPE:VOD
#EXTINF:2,
seg0.ts
#EXT-X-ENDLIST
EOF
# the emulator reaches the host loopback via 10.0.2.2, so the fixture needs no other interface
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$WORK" > "$EVIDENCE/probe-http.log" 2>&1 &
HTTP_PID=$!
sleep 2
curl -fsS -o /dev/null "http://127.0.0.1:$PORT/index.m3u8" \
  || { echo "fixture server did not answer on 127.0.0.1:$PORT" >&2; exit 1; }

# 10.0.2.2 reaches the runner's loopback from inside the emulator. The app URL-decodes
# the deep link authority once, and `am start` normalizes one level, so the URL is
# escaped twice.
URL="http://10.0.2.2:$PORT/index.m3u8"
ENC=$(python3 -c 'import sys,urllib.parse; print(urllib.parse.quote(urllib.parse.quote(sys.argv[1], safe=""), safe=""))' "$URL")

# Every adb call is bounded: a dead emulator makes adb block for minutes.
timeout 30 adb logcat -c || { echo "adb unavailable — emulator is not running" >&2; exit 1; }
timeout 60 adb shell am start -a android.intent.action.VIEW \
  -d "cloudstreamplayer://$ENC?name=load-check" >/dev/null \
  || { echo "could not open the player deep link" >&2; exit 1; }

probe_once() {
deadline=$((SECONDS + 60))
while :; do
  # NOT `-s CS3ExoPlayer`: the rejection lines #1353 lived on are tagged
  # E/ExoPlayerImplInternal and I/COMPACT, so a filtered read can only ever time out.
  log=$(timeout 30 adb logcat -d 2>/dev/null | grep -E "CS3ExoPlayer|ExoPlayerImplInternal|COMPACT|ERR_UNKNOWN_URL_SCHEME|ExtractorLink" || true)
  if grep -q "Rendered first frame" <<<"$log"; then
    echo "player probe PASSED — the app rendered $URL"
    # leave no fullscreen player behind for the next step
    adb shell am force-stop "$(cat "$EVIDENCE/app-package.txt" 2>/dev/null || echo com.lagradost.cloudstream3.prerelease)" \
      > /dev/null 2>&1 || true
    return 0
  fi
  if grep -qE "ERR_UNKNOWN_URL_SCHEME|ERROR_CODE_IO_" <<<"$log"; then
    echo "player probe FAILED — the app rejected $URL:" >&2
    grep -E "ERR_UNKNOWN_URL_SCHEME|ERROR_CODE_IO_" <<<"$log" | tail -5 >&2
    # Persist the evidence and mark it: a rejection is a class defect (#1353 shape), and must
    # never be re-labelled infrastructure by the retry logic below.
    printf '%s\n' "$log" > "$EVIDENCE/probe-logcat.log"
    : > "$EVIDENCE/probe-rejected"
    return 1
  fi
  if [[ $SECONDS -ge $deadline ]]; then
    echo "player probe TIMED OUT — no rendered frame for $URL" >&2
    printf '%s\n' "$log" > "$EVIDENCE/probe-logcat.log"
    return 1
  fi
  sleep 3
done
}

# One retry: the first attempt after the load check can race the emulator's renderer on a hosted
# runner. A second miss with the emulator still alive is the renderer, not the provider — the
# fixture is site-free, so no provider is implicated either way.
probe_once || {
  if [[ -f $EVIDENCE/probe-rejected ]]; then
    echo "player probe FAILED — the app rejected the fixture stream (class defect, see probe-logcat.log)" >&2
    exit 2
  fi
  if timeout 20 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -qx 1; then
    echo "player probe: retrying once (emulator alive, no frame yet)" >&2
    adb logcat -c || true
    timeout 60 adb shell am start -a android.intent.action.VIEW \
      -d "cloudstreamplayer://$ENC?name=load-check" > /dev/null || true
    if probe_once; then exit 0; fi
    echo "INFRASTRUCTURE: emulator alive but the player never rendered the fixture (renderer/codec, not a provider)" >&2
    : > "$EVIDENCE/probe-unrendered"
    exit 3
  fi
  echo "INFRASTRUCTURE: emulator died during the probe (not a provider)" >&2
  : > "$EVIDENCE/emulator-died"
  exit 3
}
