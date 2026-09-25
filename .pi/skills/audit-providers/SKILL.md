---
name: audit-providers
description: Fleet-wide audit of all CloudStream providers in this repo — canary-calibrated instrument, sweep + rotating deep tier, machine-readable findings registry, dedup-aware findings lifecycle. Use on an ai-fix-labeled audit issue (e.g. #460).
---

# Provider Fleet Audit

Audit every provider's live site and leave three artifacts on the run branch:
`FINDINGS-<issue>.md` (evidence), `audits/findings.json` (registry update),
and updated finding issues. No provider code changes — artifacts only.

This skill composes [site-probe](../site-probe/SKILL.md) per provider. All
site-probe rules carry over: escalation ladder (plain curl → TLS-impersonated
curl via `scripts/impersonate.sh` → browser diagnosis), Leads-are-not-evidence,
FINDINGS-only ground truth. What's added here is calibration, lifecycle, and
the machine-readable registry.

## Phase 0 — calibrate (never skip)

Read `audits/canaries.json`. For each canary, probe with each tier listed in
`expected` and compare:

- **match** → instrument is healthy, continue.
- **mismatch with `on_mismatch: environment-degraded`** → the probe
  environment is unreliable. Every Blocked verdict this run is auto-downgraded
  to `Suspected` and **no finding issue may be filed from challenge evidence
  alone**. Record the degradation at the top of FINDINGS.
- **mismatch with `on_mismatch: expectation-stale`** → the canary site
  changed. If TLS-impersonated disagrees with the recorded expectation,
  propose an expectation update in the run PR; maintainer confirms in review.

## Phase 1 — sweep (every provider, every run)

Per provider, one pass: homepage → search → one video page → one stream check
(follow the site-probe transcript discipline; record instrument tier used per
surface). Verdict per provider into findings.json: `ok`, `ok-drift`, `ok-suspected`, `blocked`, `suspected`.

## Phase 2 — deep tier (rotating)

Deep-audit the next N providers (N from the audit issue spec; default 4):
choose the providers with the oldest `last_deep` stamp in findings.json.
Deep = full surfaces: every homepage row's pagination (page 1 vs 2 disjoint),
quickSearch endpoint, ≥3 video pages, stream URL resolution. Stamp
`last_deep` for the ones covered.

## Phase 3 — findings lifecycle (this is where previous runs failed)

Before filing a new finding issue for a provider:

1. `gh issue list --search "<provider>"` — open **and** recently closed
   findings for that provider.
2. **Standing issue exists for the same condition** → update that issue with
   this run's evidence (comment). Never file a duplicate.
3. **Closed as false positive (suspected_excluded in findings.json)** → do not
   re-open, do not count it in drift history. If you believe the closure was
   wrong, raise it in the run PR — human decides.
4. **Genuinely new condition** → file one issue: evidence (URL, failing step,
   error snippet, instrument tier), drift history counted from
   findings.json's confirmed records only. Leave it unlabeled.
5. A superseded issue gets closed as duplicate-of the standing one; the
   standing issue gets the new evidence.

## Verdict vocabulary (CONTEXT.md)

- `Blocked` requires the site-probe escalation ladder to have run **and** the
  Phase 0 calibration to be healthy.
- A CF challenge from the CI probe IP is `Suspected (challenge from
  datacenter probe IP)` — never a finding on its own.
- If `scripts/differential.sh` can run (residential proxy secret set, or a
  human pastes results), record the differential as the deciding tier.

## Delivery

- Update `audits/findings.json` (providers, standing_issue links, drift
  counts, `last_runs` entry with `false_positives` list — start it empty,
  fill it from review).
- Write `FINDINGS-<issue>.md`; cross-check it against findings.json.
- Run `scripts/check-findings.sh` — it must pass; it is also the delivery
  gate (`pipeline.verify`).
- PR body: per-provider verdict table, findings created/updated, canary
  outcome, false-positive-rate note (false positives this run / findings filed).
