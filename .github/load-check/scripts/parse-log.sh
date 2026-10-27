#!/usr/bin/env bash
# Logcat → plugin-name parsing. Sourced by emulator-load.sh and test-parse.sh.
# arg 1 = logcat file.
# Two emitted shapes are supported: the tag form from current builds
# (`I/PluginManager( 4355): Loaded plugin X successfully`) and the older
# message-prefix form (`I/SystemOut( 1): PluginManager: Loaded plugin X ...`).
extract_loaded() {
  grep -oP 'PluginManager(?:\(\s*\d+\))?:\s*Loaded plugin \K.*(?= successfully)' "$1" 2>/dev/null | sort -u
}

extract_failed() {
  grep -oP 'PluginManager(?:\(\s*\d+\))?:\s*Failed to load (?:plugin\s+)?\K[^:]+' "$1" 2>/dev/null | sed 's/\.cs3$//' | sort -u
}

# has_failed <log> — any explicit plugin-load failure line present?
has_failed() {
  grep -qP 'PluginManager(?:\(\s*\d+\))?:\s*Failed to load ' "$1" 2>/dev/null
}
