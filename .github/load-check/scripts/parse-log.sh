#!/usr/bin/env bash
# Logcat → plugin-name parsing. Sourced by emulator-load.sh and test-parse.sh.
# arg 1 = logcat file.
# NOTE: exact upstream log line format is verified by the first manual live run
# (ADR-0012 review finding: push dir + log format need live confirmation).
# `adb logcat -v time` renders the tag as `I/PluginManager( 4355):`, so the tag pattern
# tolerates both that and the plain `I PluginManager:` form.
extract_loaded() {
  # `|| true`: grep exits 1 on "no match", and every caller runs under `set -e`/pipefail where
  # that would abort the run on a perfectly readable log that simply has no verdict yet.
  grep -oP 'PluginManager(?:\(\s*\d+\))?: Loaded plugin \K.*(?= successfully)' "$1" 2>/dev/null | sort -u || true
}

extract_failed() {
  {
    # Manifest branch: `Failed to load plugin  <file>: <reason>` (two spaces before the file).
    grep -oP 'PluginManager(?:\(\s*\d+\))?: Failed to load plugin\s+\K[^:]+' "$1" 2>/dev/null || true
    # Catch-all branch: `Failed to load <absolute path>: <stack>` — what a `load()` that throws
    # actually prints (PluginManager.loadPlugin). Missing it left a real provider failure
    # visible only as "expected minus loaded", i.e. a verdict inferred from silence (#1545).
    # The negative lookahead keeps the manifest line from also yielding `plugin  <file>`.
    grep -oP 'PluginManager(?:\(\s*\d+\))?: Failed to load (?!plugin)\K[^:]+(?=:)' "$1" 2>/dev/null || true
  } | sed -e 's#.*/##' -e 's/\.cs3$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
    | { grep -v '^$' || true; } | sort -u
}

# unsettled <log> <expected-file> — expected plugin stems the log never decided on: no
# `Loaded plugin … successfully` and no named `Failed to load …`. Those are not failures and
# must never become a failing set (#1545): on 2026-11-23 three such plugins (`Javbangers`,
# `MissAV`, `ixiporn`) were reported as the failing set while 24/27 had verdicts — their
# `load()` never returned, nothing threw, and the app's next round skipped them as
# `Plugin with name X already exists` (the map is keyed by path, filled before `load()` runs),
# so the silence could not resolve. Attribution comes from a verdict line, never from silence.
unsettled() {
  local log=$1 expected_file=$2 e
  local verdicts
  verdicts=$({ extract_loaded "$log"; extract_failed "$log"; } || true)
  while read -r e; do
    [[ -n $e ]] || continue
    # substring, so a versioned `Loaded plugin EPorner (4) successfully` still settles EPorner
    grep -qF "$e" <<<"$verdicts" || echo "$e"
  done < "$expected_file"
}

# crashed <log> <package> — the app died instead of reporting. ART prints
# `Process: <package>, PID: …` with FATAL EXCEPTION (and in ANR traces), so matching the
# installed package identifies this app's death and not, say, a GMS crash in the same buffer.
# A crash explains a round's silence; it never names a plugin.
crashed() {
  local log=$1 pkg=${2:-}
  [[ -n $pkg ]] || return 1
  grep -qF "Process: $pkg," "$log" 2>/dev/null
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
