# Deliberate dependency pins get dependabot ignores

Two root `build.gradle.kts` pins are deliberate and non-obvious, and dependabot
re-opened both bumps until ignored (Jackson was merged to 2.22.2 in PR #94 and
had to be walked back; the Kotlin Gradle plugin 2.4.20 bump broke CodeQL in
issue #388):

- **Jackson ≤ 2.13.1** — newer versions break older Android devices (minSdk 21
  audience). Enforced by the lint.yml version gate.
- **kotlin-gradle-plugin 2.4.20** — CodeQL's Kotlin extractor cannot parse
  newer plugin output; `codeql.yml` downgrades to 2.4.10 to build. Upgrading
  would need a CodeQL-side fix first, so the pin stays until that changes.

Decided: every deliberate pin gets three artifacts in the same change — a
comment in `build.gradle.kts` naming the pin and its reason, an
`ignore` entry in `.github/dependabot.yml` with the same reason, and (for
hard ceilings like Jackson) the lint.yml gate. Bumping a pinned dependency is
a decision, not a dependency update: it requires addressing the original
constraint and updating all three artifacts.

Considered and rejected:
- Ignore nothing and rely on humans to reject bumps: at ~1 dependabot PR per
  week per pinned package, re-reviewing the same rejected bump forever wastes
  the review budget the pipeline exists to protect.
- Ignore whole dependency groups broadly: hides real security bumps for
  unpinned deps; ignores stay per-package.

Consequence: a new deliberate pin added without the ignore re-opens the
dependabot PR weekly until someone adds it. The lint gate only covers what it
regexes — new pins need the ignore, not just a comment.
