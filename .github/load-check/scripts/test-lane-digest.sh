#!/usr/bin/env bash
# Self-check for lane-digest.sh's verdict line. The digest is what a human reads when the lane
# is red, so its counts are load-bearing: a stalled plugin (no verdict at all) must appear as
# its own number, never folded into "failing" (that restates the #1545 misattribution) and
# never hidden (a red step would read "24 loaded, 0 failing" and look clean).
# Runs lane-digest.sh against a fixture evidence dir; no gh, no emulator, no network.
set -euo pipefail
DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
EVIDENCE=$tmp/evidence
mkdir -p "$EVIDENCE"

printf 'EPorner\nHQPorner\n' > "$EVIDENCE/loaded-plugins.txt"
: > "$EVIDENCE/failing-plugins.txt"
printf 'ixiporn\n' > "$EVIDENCE/stalled-plugins.txt"

# COMMENT_ON unset: the digest must not try to post anywhere in a self-check. GITHUB_STEP_SUMMARY
# is set by Actions, and appending the fixture digest there would be noise, not a test.
out=$(env -u COMMENT_ON -u GITHUB_STEP_SUMMARY LOAD=success PROBE=skipped \
  bash "$DIR/lane-digest.sh" "$EVIDENCE")

grep -q '| load check | ✅ pass — 2 loaded, 0 failing, 1 stalled (no verdict) |' <<<"$out" \
  || { echo "FAIL: digest must report stalled separately from failing, got:"; echo "$out"; exit 1; }

# The stalled count is not a pass signal: a red load step with a stall still shows the count
# next to the failure, so the digest never hides it behind a green-looking summary.
out=$(env -u COMMENT_ON -u GITHUB_STEP_SUMMARY LOAD=failure PROBE=skipped \
  bash "$DIR/lane-digest.sh" "$EVIDENCE")
grep -q '| load check | ❌ failure — 2 loaded, 0 failing, 1 stalled (no verdict) |' <<<"$out" \
  || { echo "FAIL: a failing load step must still show its stalled count, got:"; echo "$out"; exit 1; }

# No stalled file at all is the green case; it must not print a stale "0 stalled" or abort
# under pipefail (the run where the emulator never booted still has to post its digest).
rm -f "$EVIDENCE/stalled-plugins.txt"
out=$(env -u COMMENT_ON -u GITHUB_STEP_SUMMARY LOAD=success PROBE=skipped \
  bash "$DIR/lane-digest.sh" "$EVIDENCE")
grep -q '2 loaded, 0 failing |' <<<"$out" \
  || { echo "FAIL: a round with no stalled file must still report clean counts, got:"; echo "$out"; exit 1; }
grep -q 'stalled' <<<"$out" && { echo "FAIL: no stalled file must not invent a stalled count"; exit 1; }

echo 'lane-digest tests PASSED'
