#!/usr/bin/env bash
# The repo's own `verify` gate (required check on main).
# Delegates to the audit-provider findings lint — the exact command the
# previous devloop config wired as [pipeline].verify (config.toml) — then to the
# human-only wall matcher check, which nothing else exercises.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
bash .pi/skills/audit-providers/scripts/check-findings.sh "$@"
if command -v node >/dev/null 2>&1; then
  echo "verify: node $(node --version) (matcher check needs >=22.18 for TS type-stripping)" >&2
  node .pi/ci/deny-human-only.check.mjs
else
  echo "verify: node missing — skipped the human-only matcher check" >&2
fi
