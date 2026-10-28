#!/usr/bin/env bash
# One-screen verdict for the emulator lane (ADR-0012), printed as a step summary and — when a
# run is dispatched with `comment_on` — posted back where the requester reads it. The agent
# lane never needs the 4 MB artifact unless the digest says something failed.
#
# Usage: lane-digest.sh [evidence-dir]
# Env: LOAD / PROBE / SCENARIO (step outcomes), SCENARIO_NAME, SCENARIO_ARG, COMMENT_ON, RUN_URL
set -euo pipefail

EVIDENCE=${1:-loadcheck-evidence}
status() { case "${1:-skipped}" in success) echo "✅ pass" ;; skipped) echo "⏭️ skipped" ;; *) echo "❌ ${1:-fail}" ;; esac; }

loaded=0 failing=0
[[ -f $EVIDENCE/loaded-plugins.txt ]] && loaded=$(grep -c . "$EVIDENCE/loaded-plugins.txt" || true)
[[ -f $EVIDENCE/failing-plugins.txt ]] && failing=$(grep -c . "$EVIDENCE/failing-plugins.txt" || true)
# `|| true`: with pipefail an absent line would abort the digest, and the run where the
# emulator never booted is exactly the run whose digest must still be posted.
boot=$(grep -o "Boot completed in [0-9]* ms" "$EVIDENCE/emulator.log" 2>/dev/null | tail -1 || true)

{
  echo "## Emulator lane"
  echo
  echo "| tier | result |"
  echo "|---|---|"
  echo "| load check | $(status "${LOAD:-}") — $loaded loaded, $failing failing |"
  if [[ -f $EVIDENCE/probe-rejected ]]; then
    echo "| player probe | ❌ the app rejected the fixture stream — datasource/scheme defect (see probe-logcat.log) |"
  elif [[ -f $EVIDENCE/emulator-died || -f $EVIDENCE/probe-unrendered ]]; then
    echo "| player probe | ⚠️ infrastructure fault — emulator/renderer, not a provider |"
  else
    echo "| player probe | $(status "${PROBE:-}") |"
  fi
  if [[ -n ${SCENARIO_NAME:-} ]]; then
    echo "| playback scenario (\`${SCENARIO_NAME}\` ${SCENARIO_ARG:-all}) | $(status "${SCENARIO:-}") |"
  fi
  echo
  echo "emulator: ${boot:-boot unknown}"
  echo
  if [[ -f $EVIDENCE/scenario-logcat.log ]]; then
    source_=$(grep -E "source q=" "$EVIDENCE/scenario-logcat.log" | tail -1 || true)
    [[ -n $source_ ]] && echo '```' && echo "${source_#*: }" && echo '```'
  fi
  echo "evidence: artifact \`load-check-logcat\` · run: ${RUN_URL:-n/a}"
} > /tmp/lane-digest.md

cat /tmp/lane-digest.md
[[ -n ${GITHUB_STEP_SUMMARY:-} ]] && cat /tmp/lane-digest.md >> "$GITHUB_STEP_SUMMARY"

if [[ -n ${COMMENT_ON:-} ]]; then
  # `comment_on` is pasted by humans, so "#1370" and whitespace both arrive in practice; the
  # digest is the last step and must never turn a red lane into a confusing gh usage error.
  target=${COMMENT_ON//[^0-9]/}
  if [[ -z $target ]]; then
    echo "comment_on ignored (not an issue/PR number): ${COMMENT_ON}" >&2
  else
    gh issue comment "$target" --body-file /tmp/lane-digest.md > /dev/null
    echo "digest posted to #$target"
  fi
fi
