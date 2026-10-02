#!/usr/bin/env bash
# Logcat → plugin-name parsing. Sourced by emulator-load.sh and test-parse.sh.
# arg 1 = logcat file.
# NOTE: exact upstream log line format is verified by the first manual live run
# (ADR-0012 review finding: push dir + log format need live confirmation).
extract_loaded() {
  grep -oP 'PluginManager: Loaded plugin \K.*(?= successfully)' "$1" 2>/dev/null | sort -u
}

extract_failed() {
  grep -oP 'PluginManager: Failed to load plugin \K.*?(?=:)' "$1" 2>/dev/null | sed 's/\.cs3$//' | sort -u
}

# has_failed <log> — any explicit plugin-load failure line present?
has_failed() {
  grep -qP 'PluginManager: Failed to load ' "$1" 2>/dev/null
}
