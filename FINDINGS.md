# FINDINGS — Issue #371 (P2-3: AGENTS.md verify.sh path)

## Evidence

- `AGENTS.md:36` said: `Validation is ... pipeline Verification (`verify.sh`) against the live site.`
  → implied repo-root `./verify.sh`.
- Repo root contains no `verify.sh` (`ls verify.sh` → No such file or directory).
- Actual location: `.pi/skills/verify-provider/scripts/verify.sh` (exists, confirmed; it is the
  mechanical live-site checker of the `verify-provider` pi skill per its header comment and
  `.pi/skills/verify-provider/SKILL.md`).

## Fix

Updated `AGENTS.md:36` to reference `.pi/skills/verify-provider/scripts/verify.sh` and note it
is a pi-skill asset.

## Verify

- No test/build impact: docs-only change.
- `grep -n "verify.sh" AGENTS.md` now resolves to the real file path; file exists at that path.
