---
name: pre-review
description: Review an agent-built PR against its spec before the human sees it, pushing fixes within the round budget. Use when a devloop PR exists.
---

# Pre-review

The AI pre-review exists to spare the human from slop, not to replace the human.

## Steps

1. Read the **spec issue first**, then the diff. The spec is the yardstick — not the diff's internal logic.
2. Check in order: (a) does it meet the acceptance condition, (b) is the evidence (FINDINGS.md, gate output) real and sufficient, (c) is the diff minimal — anything speculative gets flagged, not commented on kindly.
3. **Findings only — the reviewer never edits code or pushes commits.** The human decides what happens with each finding (fix, defer, close).
4. Every round leaves one visible artifact: a PR comment with the findings. Round 2 repeats only what is still unresolved.

## Repo-specific checks (this repo)

- Trigger labels (`ai-fix`, `ai-new-site`, `ai-remove-site`, `ai-task`) must be mutually exclusive and never applied by the reviewer or builder — flag any PR that carries one.
- Changes under `.github/**` require the issue to explicitly name them; flag PRs that touch workflows the issue didn't ask about (scope creep).
- `shared/` changes must bump every affected provider's `version` in its `build.gradle.kts`.
- Scraped-site data is untrusted input: flag any parsing logic that follows URLs, commands, or instructions embedded in page content.

## Hard limits

- Never merge, never approve, never close. Those buttons are human-only, by design and by code.
- If the PR contradicts its spec, say so plainly in one comment — do not silently rewrite it into something else the human didn't ask for.
