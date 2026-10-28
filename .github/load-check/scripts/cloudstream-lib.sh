#!/usr/bin/env bash
# CloudStream-app helpers on top of ui-lib.sh (ADR-0012 amended).
#
# Encodes the app's navigation the way a user does it, so any CI scenario can reach a
# provider's video without a local emulator: dismiss post-install prompts, enable NSFW,
# search, open a result, press Play. Sourced, never executed.

# shellcheck source=ui-lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/ui-lib.sh"

# PKG comes from the load check (app-package.txt); fall back to the prerelease package.
cs_package() { cat "${EVIDENCE:-loadcheck-evidence}/app-package.txt" 2>/dev/null || echo com.lagradost.cloudstream3.prerelease; }

# cs_launch — cold start to the home screen (dismisses onboarding + system prompts).
cs_launch() {
  local pkg attempt
  pkg=$(cs_package)
  # Launch can silently no-op (app not installed yet, OEM/app-op quirk, monkey budget); the
  # screen then sits on the launcher and every later wait times out. Explicit component first,
  # launcher intent second, and retry the whole start.
  for attempt in 1 2 3; do
    adb shell am force-stop "$pkg" > /dev/null 2>&1 || true
    sleep 2
    adb shell am start -n "$pkg/com.lagradost.cloudstream3.MainActivity" > /dev/null 2>&1 \
      || adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1 || true
    sleep 5
    dismiss_system_prompts
    if ! wait_node 'id=next_btt|id=home_api_fab|id=navigation_home|Skip setup' 45 "${EVIDENCE}/cs-launch.xml" > /dev/null; then
      echo "app did not come up (attempt $attempt) — retrying" >&2
      continue
    fi
    # First launch walks a setup wizard: language ("Skip setup" / "Next") then the
    # Extensions screen ("Done"). Walk whatever it presents until the home screen exists.
    for _ in 1 2 3 4; do
      if has_node 'Skip setup'; then
        tap_node 'Skip setup' 20 "${EVIDENCE}/cs-launch.xml" > /dev/null || true
      elif has_node 'id=next_btt'; then
        tap_node 'id=next_btt' 20 "${EVIDENCE}/cs-launch.xml" > /dev/null || true
      else
        break
      fi
      sleep 3
      dismiss_system_prompts
    done
    if wait_node 'id=home_api_fab|id=navigation_home' 60 "${EVIDENCE}/cs-home.xml" > /dev/null; then
      return 0
    fi
    echo "home screen not reached (attempt $attempt) — retrying" >&2
  done
  echo "FAIL app did not launch (no home screen after 3 starts) — harness, not the provider" >&2
  return 1
}

# cs_enable_nsfw — NSFW providers stay hidden until the media-type multi-select has NSFW.
# The current state is read from the dialog, so an already-enabled run is a no-op.
cs_enable_nsfw() {
  local xml
  tap_node 'id=navigation_settings' 60 "${EVIDENCE}/cs-settings.xml" > /dev/null
  tap_node '=General' 60 "${EVIDENCE}/cs-settings.xml" > /dev/null
  tap_node 'Preferred media' 60 "${EVIDENCE}/cs-general.xml" > /dev/null
  xml=$(dump)
  if grep -q 'text="NSFW"[^>]*checked="true"' <<< "$xml"; then
    tap_node '=Cancel' 30 "${EVIDENCE}/cs-media-types.xml" > /dev/null || adb shell input keyevent KEYCODE_BACK
  else
    echo "enabling NSFW in preferred media types"
    tap_node '=NSFW' 30 "${EVIDENCE}/cs-media-types.xml" > /dev/null
    tap_node '=Ok' 30 "${EVIDENCE}/cs-media-types.xml" > /dev/null
  fi
  tap_node 'id=navigation_home' 60 "${EVIDENCE}/cs-general.xml" > /dev/null || true
}

# cs_assert_playback <provider> <timeout-s> — PASS when a frame renders, FAIL on a
# datasource error (#1353 shape) or when nothing renders in time.
cs_assert_playback() {
  local provider=$1 deadline=$((SECONDS + ${2:-60})) log
  while :; do
    log=$(timeout 30 adb logcat -d 2>/dev/null || true)
    if grep -q "Rendered first frame" <<< "$log"; then
      # a render proves the player works, not that THIS provider produced the source
      if ! grep -qE "ExtractorLink\(name=$provider|kraptor_$provider" <<< "$log"; then
        echo "FAIL $provider — a frame rendered, but not from this provider's source" >&2
        grep -E "ExtractorLink\(name=|Rendered first frame" <<< "$log" | tail -4 >&2
        return 1
      fi
      grep -E "source q=|Rendered first frame|ExtractorLink\(name=" <<< "$log" | tail -5
      return 0
    fi
    if grep -qE "ERR_UNKNOWN_URL_SCHEME|ERROR_CODE_IO_|COMPACT : Source error" <<< "$log"; then
      echo "FAIL $provider — the app rejected the emitted source:" >&2
      grep -E "ERR_UNKNOWN_URL_SCHEME|ERROR_CODE_IO_|Caused by" <<< "$log" | tail -6 >&2
      return 1
    fi
    if [[ $SECONDS -ge $deadline ]]; then
      echo "FAIL $provider — no rendered frame within ${2:-60}s" >&2
      grep -E "source q=|kraptor_|Using .*DataSource" <<< "$log" | tail -8 >&2
      printf '%s\n' "$log" > "${EVIDENCE}/smoke-$provider.logcat.log"
      return 1
    fi
    sleep 3
  done
}

# cs_open_video <api-name> <video-url> — open a provider's video page through the app's own
# share deep link (`csshare:<b64 apiName>?<b64 url>` → MainActivity/AppContextUtils.loadResult).
# No settings screen, no search, no result-list tap: the only UI left is the Play button, so
# an app UI change cannot silently reroute the test.
cs_open_video() {
  local api=$1 url=$2 b_api b_url pkg
  pkg=$(cs_package)
  b_api=$(printf '%s' "$api" | base64 -w0)
  b_url=$(printf '%s' "$url" | base64 -w0)
  adb shell am force-stop "$pkg" > /dev/null 2>&1 || true
  sleep 2
  adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1 || true
  sleep 5
  dismiss_system_prompts
  wait_node 'id=next_btt|id=home_api_fab|id=navigation_home' 60 "${EVIDENCE}/cs-launch.xml" > /dev/null
  if has_node 'Skip setup'; then tap_node 'Skip setup' 20 "${EVIDENCE}/cs-launch.xml" > /dev/null || true; fi
  if has_node 'id=next_btt'; then tap_node 'id=next_btt' 20 "${EVIDENCE}/cs-launch.xml" > /dev/null || true; fi
  dismiss_system_prompts
  adb logcat -c
  # The first intent after a cold start is sometimes swallowed (observed ~half the runs on a
  # hosted runner: no result page, no error). Confirm, then re-send once on the warm app.
  adb shell am start -a android.intent.action.VIEW -d "csshare:$b_api?$b_url" > /dev/null
  if ! wait_node 'id=result_play_movie|id=result_title' 45 "${EVIDENCE}/cs-result.xml" > /dev/null; then
    echo "deep link did not land — re-sending on the warm app" >&2
    adb shell am start -a android.intent.action.VIEW -d "csshare:$b_api?$b_url" > /dev/null
  fi
}

# cs_press_play <provider> — wait for the result page's Play button and press it.
cs_press_play() {
  local provider=$1 coords
  # On a shorter screen (1080x1920 runners vs 1080x2340 locally) the Play button sits
  # below the fold, so poll, then scroll for it.
  coords=$(wait_node 'id=result_play_movie' 20 "${EVIDENCE}/cs-result.xml") \
    || coords=$(wait_node_scroll 'id=result_play_movie' 60 6) \
    || {
      echo "FAIL $provider — Play button not reachable on the result page" >&2
      return 1
    }
  # shellcheck disable=SC2086 # two coordinates
  adb shell input tap $coords
}
