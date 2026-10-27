#!/usr/bin/env bash
# Logcat → plugin-name parsing. Sourced by emulator-load.sh and test-parse.sh.
# arg 1 = logcat file.
# NOTE: exact upstream log line format is verified by the first manual live run
# (ADR-0012 review finding: push dir + log format need live confirmation).
#
# Two shapes are live, and the app moved from the first to the second:
#   I/SystemOut( 1234): PluginManager: Loaded plugin EPorner (4) successfully
#   I/PluginManager( 4355): Loaded plugin EPorner.cs3 successfully
# So the tag carries the prefix no longer — matching a literal "PluginManager: "
# message prefix made every real line invisible and reported a fully-loaded fleet
# as failing (#1362). Both forms are accepted, and the captured name is reduced to
# the .cs3 STEM the callers compare against (file names are what upstream logs).
extract_loaded() {
  grep -oP 'PluginManager[^:]*: Loaded plugin \K.*(?= successfully)' "$1" 2>/dev/null \
    | sed -E 's/ \([0-9]+\)$//; s/\.cs3$//' | sort -u
}

extract_failed() {
  grep -oP 'PluginManager[^:]*: Failed to load plugin \K.*?(?=:)' "$1" 2>/dev/null \
    | sed -E 's/\.cs3$//' | sort -u
}

# has_failed <log> — any explicit plugin-load failure line present?
has_failed() {
  grep -qP 'PluginManager[^:]*: Failed to load ' "$1" 2>/dev/null
}

# extract_plugin_dir_count <log> — the highest plugin-folder file count the app itself
# reported ("Files in '<dir>' folder: N"). Empty when the app never listed the folder
# (never launched, or died before reloading plugins).
extract_plugin_dir_count() {
  # tag form is "D/PluginManager( 3971): Files in '<dir>' folder: N" — the
  # optional "( pid)" is why the separator cannot be a literal "PluginManager:".
  grep -oP "PluginManager[^:]*: Files in '[^']*' folder: \K[0-9]+" "$1" 2>/dev/null \
    | sort -n | tail -1
}

# plugin_visibility <log> <pushed-count> — did the app see the files this run pushed?
#
#   listed    → the app's own listing counted ≥1 file, or it registered/rejected at
#               least one plugin by name (it read the file: a provider fault).
#   invisible → it counted 0, or never listed the folder at all, while files were
#               pushed: the all-files grant or the push path, i.e. an INSTRUMENT
#               fault. ADR-0012 — a defect in the check never blames a provider;
#               measured in #1362 (app: "Files in '/storage/emulated/0/Cloudstream3/
#               plugins' folder: 0" directly after 26 successful `adb push`es) the
#               old code named all 26 providers and filed a false ai-fix issue.
plugin_visibility() {
  local log=$1 pushed=${2:-0} seen
  [[ $pushed -gt 0 ]] || { echo listed; return 0; }
  has_failed "$log" && { echo listed; return 0; }
  [[ -n $(extract_loaded "$log") ]] && { echo listed; return 0; }
  seen=$(extract_plugin_dir_count "$log")
  if [[ -n $seen && $seen -gt 0 ]]; then echo listed; else echo invisible; fi
}
