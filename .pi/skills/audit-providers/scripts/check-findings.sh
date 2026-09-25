#!/usr/bin/env bash
# Findings lint — the pipeline gate (config.toml pipeline.verify).
# Structural only, no network: fast and deterministic.
# Exits 0 when there is nothing to lint (non-audit runs have no registry).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

[ -f audits/findings.json ] || { echo "findings-lint: no registry, skipping (non-audit run)"; exit 0; }
[ -f audits/canaries.json ] || { echo "findings-lint: FAIL audits/canaries.json missing"; exit 1; }
command -v jq >/dev/null || { echo "findings-lint: jq missing"; exit 1; }

fail=0

# 1. JSON parses
jq empty audits/findings.json || { echo "findings-lint: findings.json invalid JSON"; exit 1; }
jq empty audits/canaries.json || { echo "findings-lint: canaries.json invalid JSON"; exit 1; }

# 2. Every provider entry has verdict + tier
while IFS=$'\t' read -r name verdict tier; do
  [ -n "$name" ] || continue
  case "$verdict" in
    ok|ok-drift|ok-suspected|blocked|suspected) ;;
    *) echo "findings-lint: $name bad verdict '$verdict'"; fail=1 ;;
  esac
  [ -n "$tier" ] || { echo "findings-lint: $name missing tier"; fail=1; }
done < <(jq -r '.providers | to_entries[] | "\(.key)\t\(.value.verdict // "")\t\(.value.tier // "")"' audits/findings.json)

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
done < <(jq -r '.providers | to_entries[] | select(.value.verdict == "blocked") | "\(.key)\t\(.value.reason // "")\t\(.value.tier // "")"' audits/findings.json)

# 4. standing_issue must be an integer or null
bad=$(jq -r '.providers | to_entries[] | select(.value.standing_issue != null and (.value.standing_issue | type != "number")) | .key' audits/findings.json)
[ -z "$bad" ] || { echo "findings-lint: standing_issue must be number|null: $bad"; fail=1; }

# 5. last_runs entries must carry a false_positives array (empty ok)
bad=$(jq -r '.last_runs // [] | .[] | select((.false_positives // null) == null) | .id' audits/findings.json)
[ -z "$bad" ] || { echo "findings-lint: last_runs missing false_positives array: $bad"; fail=1; }

if [ "$fail" -eq 0 ]; then echo "findings-lint: PASS"; fi
exit "$fail"
