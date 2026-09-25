---
name: remove-provider
description: Delete an existing provider from this repo after an `ai-remove-site` issue — probe the live site for evidence, remove the provider directory, open the removal PR. Use when a maintainer applied the ai-remove-site trigger label.
---

# Remove Provider

You are deleting an existing provider. The maintainer's `ai-remove-site` label IS
the decision — do not second-guess it, do not stop because the site still works.
Your probe is for the audit trail, not for a verdict.

## Steps

1. **Identify the provider directory** named in the issue. It must exactly match
   a top-level directory with a `build.gradle.kts`. If the issue names no
   recognizable directory, stop and comment the closest candidates — do not
   guess.
2. **Evidence probe** (site-probe skill, reduced — no FINDINGS.md): homepage +
   one search + one video page. Record in the PR body, one line each: reachable?
   search works? video page + stream works? This is documentation of *why* the
   maintainer removed it, whatever the answers.
3. **Check for dependents** before deleting:
   - `grep -ri "<Provider>" --include="*.kt" --include="*.kts" -l` across the
     repo — other providers reusing this one's extractors (e.g. MyDaddyExtractor).
     If another live provider references files inside this directory, STOP and
     comment what you found — deleting shared code breaks someone else.
4. **Delete**: `git rm -r <Provider>/` — nothing else. Do not touch
   `build.yml`, root `build.gradle.kts`, or other providers; `settings.gradle.kts`
   auto-includes directories, so no root edits are needed. Do not edit
   `plugins.json` — CI regenerates it.
5. **PR**: branch `ai/issue-<n>`, commit message `Remove <Provider> (issue #<n>)`,
   PR body starting `Fixes #<n>` followed by: the evidence-probe lines, the
   dependents check result, and the list of provider files deleted.
6. **Never**: close the issue, apply labels, touch issues other than commenting
   evidence into the PR body.

## PR rubric (Reviewer will check)

- Directory fully removed, zero dangling references repo-wide
- Nothing outside the removed directory changed
- Evidence probe recorded in the PR body
