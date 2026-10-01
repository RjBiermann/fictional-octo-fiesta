#!/usr/bin/env bash
# ADR-0012 failure-issue writer. ONE issue per failing plugin set; the dedup
# key is the issue title = SORTED failing-plugin list. Identical set → append
# daily evidence. Set change → new issue; stale-set issues get closed with a
# pointer comment. Applies `ai-fix` mechanically (ADR-0006) via AGENT_PAT
# (GITHUB_TOKEN label events never fire devloop; ADR-0006 post-merge note).
# Fixes ride devloop's existing caps — no new knobs.
#
# Usage: failure-issue.sh --plugins <file|FIFO, one failing plugin per line> \
#            --log <compile.log|logcat.log> [--version <app-version file>]
# Env: GH_TOKEN (AGENT_PAT), KIND (compile|load), GITHUB_REPOSITORY, JAR_SHA.
# Requires: gh, jq.
set -euo pipefail

PLUGINS_FILE=
LOG=
VERSION_FILE=
while [[ $# -gt 0 ]]; do
  case "$1" in
    --plugins) PLUGINS_FILE=$2; shift 2 ;;
    --log)     LOG=$2; shift 2 ;;
    --version) VERSION_FILE=$2; shift 2 ;;
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

**Classification: Correctness.** Never Drift, never Chronic-counted — this check is
an instrument, not the provider's fault pattern (ADR-0012). Emulator evidence is
never **Geo-gated** evidence: the runner and app share one IP.
Origin: scheduled load check — $RUN_URL
Upstream jar SHA: \`${JAR_SHA:-unknown}\` (live, **unpinned** \`pre-release\`).

Failing plugin set:
$(for s in "${sorted[@]}"; do echo "- \`$s\`"; done)

Daily runs append evidence below under \`### Run\` headings."
  URL=$(gh issue create --title "$TITLE" --label ai-fix --body "$BODY")
  TARGET=${URL##*/}
  echo "created issue #$TARGET"
fi

# Pointers: any other open load-check issue (its plugin set changed) is
# superseded by the fresh one.
for s in "${!open_since[@]}"; do
  old=${open_since[$s]}
  [[ $s == "$TITLE" ]] && continue
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
$(tail -n 120 "$LOG")
\`\`\`
</details>"

gh issue comment "$TARGET" --body "$body" >/dev/null
# Re-assert the mechanical label on every append too (humans may remove it to
# pause the loop; the next daily failure re-arms the fix run).
gh issue edit "$TARGET" --add-label ai-fix >/dev/null
echo "evidence → #$TARGET"
