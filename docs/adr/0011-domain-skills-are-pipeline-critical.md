# Domain skills are pipeline-critical: restored, and deletion is a breaking change

The Sep-16 devloop cleanup commit (`ebdf7c7`, "remove committed skill symlinks")
deleted more than symlinks: the five repo-original domain skills under
`.pi/skills/` (`site-probe`, `fix-provider`, `new-provider`, `remove-provider`,
`verify-provider`) were caught in the same sweep — 97 files, 1899 lines. Every
agent run since then executed provider work skill-less. First observed cost
(issue #460 audit, Sep 24): the agent reinvented a plain-curl instrument,
stopped at the first Cloudflare challenge, and hard-verdicted five providers
"Blocked" that a residential-IP differential probe showed healthy — the exact
failure mode the deleted `site-probe` skill documented by name (film1k,
FINDINGS-408). AGENTS.md also kept referencing `verify-provider`'s
`verify.sh` as the validation step, so the loss went unnoticed for eight days.

## Decision

Restore the five domain skills from git history as committed `.pi/skills/`
directories (this ADR accompanies that commit). ADR-0009 stands unamended: its
vendoring rejection targets the upstream prompt packages (ponytail, caveman),
not repo-original skills; and the silent-disable defect it describes is fixed
by `--approve`, which the same ADR added to the pipeline argv — so committed
project skills now actually load in CI.

## Verdict vocabulary (glossary companion, CONTEXT.md)

A Cloudflare challenge received from the CI probe IP is **`Suspected:
challenge from datacenter probe IP`** — never a finding on its own. A
**`Blocked: challenge, no unlock`** verdict requires the site-probe escalation
ladder (plain curl → TLS-impersonated curl → browser diagnosis) to have run
first. ADR-0008's instrument asymmetry rule is unchanged: FINDINGS may escalate,
verify-provider may not.

## Consequences

- Deletion of any `.pi/skills/<domain-skill>` file is a breaking pipeline
  change and requires an ADR, not a chore commit.
- AGENTS.md references to skills are load-bearing documentation: a skill file
  and its AGENTS.md reference are removed or restored together.
- The correction for the false #460 findings (close #461/#462/#465/#466/#467
  with differential evidence; keep #463/#464/#468) is maintainer action.
