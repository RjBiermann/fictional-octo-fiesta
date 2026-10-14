# FINDINGS-597 — Findings lifecycle: standing-record dedup + filing decision (S4 of the #593 spec, audit round 14)

Run date: 2026-10-14. Input evidence: FINDINGS-596.md (S1 uniform sweep, PR #602),
FINDINGS-598.md (S2 deep pass on the 8 ok-drift providers, PR #606), S3 registry
write-up (PR #608, run14 `last_runs` entry), `audits/findings.json` (top-level
`updated: 2026-10-14`), and the full issue tracker (open + closed).

## Lifecycle rules applied (per S4 scope + audit-providers/site-probe conventions)

1. No filing without a standing-record dedup pass against `audits/findings.json`
   and existing open/closed issues (dupes are closed on review, never re-filed;
   drift history counts from confirmed records only).
2. One issue per provider for genuinely new drift/broken-provider conditions.
3. New issues ship unlabeled — trigger labels (`ai-*`) and `status/*` are
   maintainer-applied on review.
4. Evidence-first bodies: origin evidence cited, tier + surface named,
   escalation cited for anything `blocked`.

## Dedup sweep result

Cross-checked each candidate condition from run14's S1/S2 evidence against the
registry and the tracker:

| Candidate condition (S1/S2 evidence) | Registry record | Issue | Lifecycle decision |
| --- | --- | --- | --- |
| PandaMovies `.pw`→`.org` healthy since fix | ok-drift → **ok** (fix #563 merged) | #555 closed | cleared on live evidence — no filing |
| Mangoporn markup healthy since fix | ok-drift → **ok** (fix #566 merged) | #564 closed | cleared — no filing |
| XMoviesForYou selectors healthy since fix | ok-drift → **ok** (fix #547 merged) | #535 closed | cleared — no filing |
| Javtiful markup healthy since fix | ok-drift → **ok** (fix #548 merged) | #534 closed | cleared — no filing |
| Javbangers search p2 404 (re-confirmed) | ok-drift → **ok** | #475 closed standing | unchanged condition — not re-filed (no duplicate) |
| Film1k turbovid branch gone since fix | ok-drift → **ok** (fixes #500/#501 merged) | #496 closed | cleared — no filing |
| EPorner search pagination healthy since fix | ok-drift → **ok** (fix #543 merged) | #537 closed | cleared — no filing |
| ixiporn double-redirect `.org`→`.live`, all faces healthy on that origin | **ok-drift (stays)** | #468 closed standing | unchanged standing condition — **not a new post-fix condition → no new issue** (same verdict as FINDINGS-598.md) |
| Eroticmv header CF challenge from probe IP (plain 403 / TLS 200) | S1 spec-run transition class | never an issue (never a finding on its own) | no filing |
| Javmost pending bucket null-meta | S1 spec-run transition class | #332 (outside this audit's history) | no filing |
| #538 Eroticmv articleSection healed | ok (issue closed) | #538 closed | healed — no re-open |
| #539 Javseen og:video:duration healed | ok (issue closed) | #539 closed | healed — no re-open |
| #525 Mangoporn standing | ok (fix #566 merged) | #525 closed | no action |
| #449/#466 suspected-exclusion class | excluded from confirmed history | closed | no action |
| #463 PandaMovies origin-down standing | cleared by live `.org` evidence (FINDINGS-598) | #463 closed | no action |

Tracker search: no open drift/broken-provider carrying issue remains, and no
closed issue describes a condition that re-confirmed in a state needing a
re-open.

## Filing decision

**0 new issues filed.** Run14 recorded 0 new findings and 0 false positives
(`last_runs` run14 entry, `audits/findings.json`); `false_positives` stays
seeded empty. Every standing condition is already carried by a closed issue
with its condition recorded, and the one non-ok verdict (ixiporn ok-drift on
standing #468) is an unchanged pre-existing condition, not a new one — filing
it would violate the dedup rule.

## Registry / repo deltas

- None beyond this file: no `audits/` schema or record change is warranted by a
  zero-filing outcome (run14's entry — including its standing `pr: null` — was
  landed by S3 in PR #608 and remains accurate).
- No Kotlin/runtime change — constraint honoured.
