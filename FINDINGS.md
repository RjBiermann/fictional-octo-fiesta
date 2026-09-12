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

---

# FINDINGS — Issue #370 (P2-2: trigger-label list omits ai-task; exclusivity)

## Evidence

- `AGENTS.md:62-63` listed only `ai-fix`, `ai-new-site`, `ai-remove-site` as trigger labels.
- `config.toml:14` defines `task = "ai-task"`; `.github/workflows/devloop.yml:23` fires on
  `ai-task`; `.github/workflows/stale.yml:32` exempts it; `skills/pre-review/SKILL.md:19`
  already lists all four. So `ai-task` is a live trigger label missing from AGENTS.md.
- Exclusivity claim checked against devloop itself (installed
  `/opt/hostedtoolcache/.../site-packages/devloop`, v0.1.0 locally; CI pins v0.2.6):
  - `config.py` `Labels.triggers` = `[fix, new, remove, task]` (all four).
  - `config.py` `Config.kind_for()` **raises `ConfigError`** ("issue carries multiple
    trigger labels … — mutually exclusive") when an issue carries >1 trigger; `core.py:130`
    calls it in the pre-flight path ("raises if triggers are not exclusive").
  → Exclusivity IS enforced by devloop's pre-flight; the doc claim was accurate. Only the
  label list was incomplete. No code/workflow change needed (and `.github/workflows/` is
  off-limits unless the issue spec explicitly requires it — it offers doc-fix OR
  pre-flight enforcement; pre-flight already exists).

## Fix

- `AGENTS.md` trigger-label bullet: added `ai-task`, kept "mutually exclusive" now that it
  is confirmed enforced, noted the enforcing pre-flight and what `ai-task` covers.
- Same drift fixed in `docs/agents/triage-labels.md` (trigger-label sentence) and
  `CONTEXT.md` (Trigger label definition) — both listed only three labels.

## Verify

- Docs-only change: no Gradle/test impact. `grep -n "ai-task" AGENTS.md
  docs/agents/triage-labels.md CONTEXT.md` now shows the label in all three;
  all four trigger labels consistently listed across AGENTS.md, config.toml,
  devloop.yml, stale.yml, triage-labels.md, CONTEXT.md, pre-review SKILL.md.
