#!/usr/bin/env bash
# ADR-0012 failure-issue writer. ONE issue per failing plugin set; the dedup
# key is the issue title = SORTED failing-plugin list. Identical set → append
# daily evidence. Set change → new issue; stale-set issues get closed with a
# pointer comment. Applies `ai-fix` mechanically (ADR-0006) as display state
# under GITHUB_TOKEN (ADR-0019: label events from GITHUB_TOKEN fire nothing —
# the caller relays 05-implement via workflow_dispatch; ADR-0020).
# Fixes ride the gh-aw budget ledger (sweeper-enforced caps) — no new knobs.
#
# Usage: failure-issue.sh --plugins <file|FIFO, one failing plugin per line> \
#            --log <compile.log|logcat.log> [--version <app-version file>]
# Env: GH_TOKEN (GITHUB_TOKEN), GITHUB_OUTPUT (issue/created outputs),
# KIND (compile|load), GITHUB_REPOSITORY, JAR_SHA.
# Requires: gh, jq.
set -euo pipefail

PLUGINS_FILE=
LOG=
VERSION_FILE=
UNATTRIBUTABLE=
while [[ $# -gt 0 ]]; do
  case "$1" in
    --plugins) PLUGINS_FILE=$2; shift 2 ;;
    --log)     LOG=$2; shift 2 ;;
    --version) VERSION_FILE=$2; shift 2 ;;
    # No plugin was ever exercised (boot/SDK/install failed): report it, but as a human
    # decision, never as `ai-fix` — that label is what dispatches an implementation run,
    # and an implementation run cannot fix an emulator (#1357).
    --unattributable) UNATTRIBUTABLE=1; shift ;;
    *)         echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[[ -n $PLUGINS_FILE ]] || { echo "missing --plugins" >&2; exit 2; }
[[ -n $LOG ]] || { echo "missing --log" >&2; exit 2; }

failures=()
while read -r p; do
  [[ -n $p ]] && failures+=("$p")
done < <(cat "$PLUGINS_FILE")

mapfile -t sorted < <(printf '%s\n' "${failures[@]}" | sort -u)
# empty plugin set → nothing a fix run could act on
[[ ${#failures[@]} -gt 0 ]] || { echo "no failing plugins — nothing to report"; exit 0; }

TITLE="Load check (${KIND}): [${sorted[*]}]"
LABEL=ai-fix
[[ -n $UNATTRIBUTABLE ]] && LABEL=triage/from-agent
RUN_URL="https://github.com/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
APP_VERSION="n/a (compile failure — app never launched)"
[[ -n $VERSION_FILE && -f $VERSION_FILE ]] && APP_VERSION=$(cat "$VERSION_FILE")

declare -A open_since=() # title -> number, open issues tagged load-check
while IFS=$'\t' read -r num title; do
  [[ $title == "Load check ("*"]]" ]] && open_since[$title]=$num
done < <(gh issue list --state open --limit 200 --json number,title \
  --jq '.[] | [.number,.title] | @tsv')

if [[ -n ${open_since[$TITLE]:-} ]]; then
  TARGET=${open_since[$TITLE]}
  echo "existing issue for this plugin set: #$TARGET"
else
  BODY="## $TITLE — first failure
$([[ -n $UNATTRIBUTABLE ]] && echo '
**Classification: unattributable (infrastructure).** No plugin was exercised, so this is
not a provider verdict and it is deliberately *not* `ai-fix`: no implementation run is
dispatched for an emulator/SDK fault (#1357). A human decides — close this when the lane
is green again.
')
**Classification: Correctness.** Never Drift, never Chronic-counted — this check is
an instrument, not the provider's fault pattern (ADR-0012). Emulator evidence is
never **Geo-gated** evidence: the runner and app share one IP.
Origin: scheduled load check — $RUN_URL
Upstream jar SHA: \`${JAR_SHA:-unknown}\` (live, **unpinned** \`pre-release\`).

Failing plugin set:
$(for s in "${sorted[@]}"; do echo "- \`$s\`"; done)

Daily runs append evidence below under \`### Run\` headings."
  URL=$(gh issue create --title "$TITLE" --label "$LABEL" --body "$BODY")
  TARGET=${URL##*/}
  echo "created issue #$TARGET"
  # ADR-0020: the workflow's Fire step stamps status/planned + dispatches
  # 05-implement for FRESH sets only (the retired AGENT_PAT label-event
  # fire had the same create-only semantics); a recurring set just
  # appends evidence. GITHUB_OUTPUT is absent outside Actions.
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    {
      # created=true is the Fire Implement trigger (ADR-0020), so it is set for an
      # attributable set only; an unattributable issue is created and left for a human.
      [[ $LABEL == ai-fix ]] && echo "created=true" || echo "created=false"
      echo "issue=$TARGET"
    } >> "$GITHUB_OUTPUT"
  fi
fi

# Pointers: other open load-check issues of the SAME KIND (their plugin set
# changed) are superseded by the fresh one. Different KINDs keep their issues —
# compile healing while load breaks must not fragment the load evidence.
for s in "${!open_since[@]}"; do
  [[ -z $UNATTRIBUTABLE ]] || break   # the infra lane never supersedes plugin evidence
  [[ $s == "Load check (${KIND}): "* ]] || continue
  old=${open_since[$s]}
  [[ $s == "$TITLE" ]] && continue
  [[ $s == *"[unattributable]"* ]] && continue  # human-owned, never closed by a plugin run
  gh issue comment "$old" \
    --body "Failing plugin set changed → superseded by #${TARGET}. Evidence history preserved here." \
    >/dev/null
  gh issue close "$old" >/dev/null
done

n=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
body="### Run — $n
$(for s in "${sorted[@]}"; do echo "- \`$s\`"; done)
- Kind: \`${KIND}\`
- Run: $RUN_URL
- App version: $APP_VERSION
- Upstream jar SHA: \`${JAR_SHA:-unknown}\`
- jar source: live unpinned \`pre-release\` (not a cache)

<details><summary>App version detail</summary>

\`app-version\` = CloudStream versionName of the APK installed this run.
</details>

<details><summary>Log tail (last 120 lines)</summary>

\`\`\`
$(tail -n 120 "$LOG" 2>/dev/null || echo '(log missing — see the linked run)')
\`\`\`
</details>"

gh issue comment "$TARGET" --body "$body" >/dev/null
echo "evidence → #$TARGET"
