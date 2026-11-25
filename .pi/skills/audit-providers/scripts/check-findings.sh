#!/usr/bin/env bash
# Findings lint — the pipeline gate (config.toml pipeline.verify).
# Structural only, no network: fast and deterministic.
# Exits 0 when there is nothing to lint (non-audit runs have no registry).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

# --block FILE lints a posted <!-- findings-registry-entry --> comment body instead of
# the tree: the run no longer edits audits/findings.json (ADR-0013), so this is the only
# way the same checks see the payload before registry_sync unions it.
FINDINGS=audits/findings.json
block=""
while [ $# -gt 0 ]; do
  case "$1" in
    --block) block="${2:?--block needs a file}"; shift 2 ;;
    *) echo "usage: $0 [--block <comment-body-file>]" >&2; exit 2 ;;
  esac
done
if [ -n "$block" ]; then
  FINDINGS=$(mktemp)
  trap 'rm -f "$FINDINGS"' EXIT
  sed -n '/^```json$/,/^```$/p' "$block" | sed '1d;$d' > "$FINDINGS"
  [ -s "$FINDINGS" ] || { echo "findings-lint: no fenced json block in $block"; exit 1; }
else
  [ -f "$FINDINGS" ] || { echo "findings-lint: no registry, skipping (non-audit run)"; exit 0; }
  [ -f audits/canaries.json ] || { echo "findings-lint: FAIL audits/canaries.json missing"; exit 1; }
fi
command -v jq >/dev/null || { echo "findings-lint: jq missing"; exit 1; }

fail=0

# 1. JSON parses
jq empty "$FINDINGS" || { echo "findings-lint: findings.json invalid JSON"; exit 1; }
[ -n "$block" ] || jq empty audits/canaries.json || { echo "findings-lint: canaries.json invalid JSON"; exit 1; }

# 2. Every provider entry has verdict + tier
while IFS=$'\t' read -r name verdict tier; do
  [ -n "$name" ] || continue
  case "$verdict" in
    ok|ok-drift|ok-suspected|blocked|suspected) ;;
    *) echo "findings-lint: $name bad verdict '$verdict'"; fail=1 ;;
  esac
  [ -n "$tier" ] || { echo "findings-lint: $name missing tier"; fail=1; }
done < <(jq -r '.providers | to_entries[] | "\(.key)\t\(.value.verdict // "")\t\(.value.tier // "")"' "$FINDINGS")

# 3. Blocked: challenge-based verdicts must cite escalation (origin-down 5xx evidence is
#    valid on any tier — no instrument fixes a dead origin)
while IFS=$'\t' read -r name reason tier; do
  [ -n "$name" ] || continue
  [ -n "$reason" ] || { echo "findings-lint: $name blocked without reason"; fail=1; }
  case "$reason" in
    *challenge*|*Challenge*)
      case "$tier" in
        plain-curl) echo "findings-lint: $name challenge-blocked on plain-curl tier only — must cite escalation"; fail=1 ;;
      esac ;;
  esac
done < <(jq -r '.providers | to_entries[] | select(.value.verdict == "blocked") | "\(.key)\t\(.value.reason // "")\t\(.value.tier // "")"' "$FINDINGS")

# 4. standing_issue must be an integer or null
bad=$(jq -r '.providers | to_entries[] | select(.value.standing_issue != null and (.value.standing_issue | type != "number")) | .key' "$FINDINGS")
[ -z "$bad" ] || { echo "findings-lint: standing_issue must be number|null: $bad"; fail=1; }

# 5. last_runs entries must carry a false_positives array (empty ok)
bad=$(jq -r '.last_runs // [] | .[] | select((.false_positives // null) == null) | .id' "$FINDINGS")
[ -z "$bad" ] || { echo "findings-lint: last_runs missing false_positives array: $bad"; fail=1; }

if [ "$fail" -eq 0 ]; then echo "findings-lint: PASS"; fi
exit "$fail"
