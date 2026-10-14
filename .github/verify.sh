#!/usr/bin/env bash
# The repo's own `verify` gate (required check on main).
# Delegates to the audit-provider findings lint — the exact command the
# previous devloop config wired as [pipeline].verify (config.toml).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
exec bash .pi/skills/audit-providers/scripts/check-findings.sh
