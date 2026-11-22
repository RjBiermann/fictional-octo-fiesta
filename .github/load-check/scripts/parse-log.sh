#!/usr/bin/env bash
# Logcat → plugin-name parsing. Sourced by emulator-load.sh and test-parse.sh.
# arg 1 = logcat file.
# NOTE: exact upstream log line format is verified by the first manual live run
# (ADR-0012 review finding: push dir + log format need live confirmation).
# `adb logcat -v time` renders the tag as `I/PluginManager( 4355):`, so the tag pattern
# tolerates both that and the plain `I PluginManager:` form.
extract_loaded() {
  grep -oP 'PluginManager(?:\(\s*\d+\))?: Loaded plugin \K.*(?= successfully)' "$1" 2>/dev/null | sort -u
}

extract_failed() {
  grep -oP 'PluginManager(?:\(\s*\d+\))?: Failed to load plugin \K.*?(?=:)' "$1" 2>/dev/null | sed 's/\.cs3$//' | sort -u
}

# has_failed <log> — any explicit plugin-load failure line present?
has_failed() {
  grep -qP 'PluginManager(?:\(\s*\d+\))?: Failed to load ' "$1" 2>/dev/null
}

# reported_count <log> — how many plugin verdict lines (loaded + failed) the log contains.
# Non-zero means the app's plugin load has started reporting; it counts *lines*, not distinct
# plugins, so it answers "has anything been decided yet", not "has the round settled".
# A bare `Loading plugin: …` line is deliberately not a verdict — every plugin prints one
# before it reports, so counting them would call a stalled round "progressing" forever.
reported_count() {
  grep -cP 'PluginManager(?:\(\s*\d+\))?: (?:Loaded plugin .* successfully|Failed to load )' "$1" 2>/dev/null || true
}

# no_verdicts <log> — true when the app has reported no plugin verdict at all, i.e. nothing
# was exercised: the launch never took, a first-run dialog is up, the process is wedged, or a
# round is still inside its first load(). emulator-load.sh's timed-out path reads this as
# infrastructure (#1357), never as a plugin verdict — a round that never spoke names no
# provider, so naming all expected plugins would be a claim about the runner (#1500).
no_verdicts() {
  [[ $(reported_count "$1") -eq 0 ]]
}
