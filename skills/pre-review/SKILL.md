---
name: pre-review
description: Review an agent-built PR against its spec before the human sees it. Findings only — never change code. Use when a devloop PR exists.
---

# Pre-review

The AI pre-review exists to spare the human from slop, not to replace the human.
This file overrides devloop's bundled default for this repository.

## Steps

1. Read the **spec issue first**, then the diff. The spec is the yardstick — not
   the diff's internal logic.
2. Check in order: (a) does it meet the acceptance condition, (b) is the
   evidence (FINDINGS-<n>.md, gate output from
   `.pi/skills/audit-providers/scripts/check-findings.sh`) real and sufficient,
   (c) is the diff minimal — anything speculative is a finding, not something
   you cut yourself.
3. Findings only — you never change code. A separate repair phase
   (skills/repair) acts on what you find; keep each finding self-contained
   (`file:line — severity — rationale`) so the fixer can act on it without
   re-deriving your reasoning.
4. Round budget exhausted with issues remaining → say so plainly in the final
   comment; the human decides.

## Audit-only PRs (the common kind here)

A diff made only of `FINDINGS-*.md`, `CONTEXT.md`, `docs/**`, `.pi/**`, or
registry files — no provider code. Reviewing these for "correctness, convention
violations, missing tests" is the wrong task and is how a reviewer with nothing
to check drifts into repetition loops. Instead:

- Verify the report is internally consistent: provider counts vs the verdict
  table, cited issue numbers vs what the PR body and findings registry actually
  say, and that any "filed this run: N" claim matches the findings-registry diff.
- Check the report does not contradict the spec issue's scope.
- Do not re-audit sites, re-derive probe results, or judge stream bytes — that
  is the build agent's Verification work, already done.
- If the report is consistent and in scope, output `LGTM`. An audit-only PR
  with zero findings is a fast pass; there is no shame in an early LGTM.

## Output discipline (machine-parsed — follow exactly)

- Your entire output is posted verbatim as a PR comment, and its tail is parsed
  for the verdict. End your output with **either** the single word `LGTM` on its
  own line, **or** a numbered findings list, one per line:
  `file:line — severity (P0/P1/P2) — one-paragraph rationale`.
- Nothing after the verdict line. No closing prose, no tables, no `*eof*`, no
  summaries of what you considered.
- Cite only `file:line` paths that exist in the diff, and only issue numbers
  that appear in the spec, the diff, or the PR thread. Never invent or guess a
  reference; if one is unclear, say so inside the finding's rationale.
- Do not act on other bots' comments (SonarQube, CI annotations) — context, not
  findings.
- If you have nothing to report after a genuine check, output `LGTM` — an empty
  or rambling response is treated as a failed run, not as "no findings".

## Hard limits

- Never merge, never approve, never close, never change code. Those buttons —
  and the working tree — are human and repair-phase territory.
- If the PR contradicts its spec, say so plainly in one comment — do not
  silently rewrite it into something else the human didn't ask for.
