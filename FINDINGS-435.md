# FINDINGS-435 — node20 workflow runtimes (pre 2026-09-23 removal)

Probe: GitHub API (latest release tags) + `action.yml` `runs.using` fetched for each
pinned action in `.github/workflows/*.yml`. No actionlint binary available locally
(not installed in this environment) — accept-and-fix is by direct runtime verification.

## Runtime audit (verified against action.yml of each tag)

| Action (repo pin) | Current | runs.using | Latest major | runs.using |
|---|---|---|---|---|
| actions/checkout | v4 (build.yml:27,32; devloop.yml:63; lint.yml:27,38,56,68) | node20 | v7.0.1 | node24 |
| actions/checkout | v7 (codeql.yml:32) | node24 | — | — |
| actions/dependency-review-action | v4 (lint.yml:58) | node20 | v5.0.0 | node24 |
| actions/stale | v9 (stale.yml:20) | node20 | v11.0.0 | node24 |
| actions/setup-java | v5 (build, codeql, devloop) | node24 | v6.0.1 | node24 — no bump needed |
| actions/setup-python | v5 (devloop.yml:67) | node20 | v7.0.0 | node24 |
| gradle/actions/setup-gradle | v4 (build.yml:47) | node20 | v6.3.0 (v6.4 is rc) | node24 |
| android-actions/setup-android | v3 (build.yml:54) | node20 | v4.0.1 | node24 |
| github/codeql-action | v4 (init/analyze) | node24 | — | — |
| reviewdog/action-actionlint | v1 (lint.yml:29) | wrapper/shell, not node20 target | — | — |

## Numbers from evidence

- `actions/checkout v5.0.0` action.yml: `using: node24`; v4.x is node20.
- `actions/setup-python v5.0.0` action.yml: `using: node20`; v6.3.0/v7.0.0: node24.
- `gradle/actions v4.4.0 setup-gradle/action.yml:242`: `using: 'node20'`; v5.0.2/v6.3.0: node24.
- `android-actions/setup-android v3.2.2` action.yml: node20; v4.0.1: node24.
  v4 default `packages: 'tools platform-tools'` unchanged from v3; build.yml already
  passes `packages: ''}`, and the "tools removed from SDK repo" comment pin referenced
  runner-image preinstalled SDK — v2→v3 semantics; v4 keeps same input schema
  (all inputs carry over: packages, log-accepted-android-sdk-licenses).
- `github/codeql-action v4` analyze/action.yml:97 `using: node24` — already compliant.

## Fix applied

Bump every node20 consumer to latest stable node24 major: checkout v4→v7,
dependency-review-action v4→v5, stale v9→v11, setup-python v5→v7,
setup-gradle v4→v6, setup-android v3→v4.

Risk notes for review:
- checkout v7 / stale v11: pure runtime bump majors on those actions.
- setup-gradle v6: gradle/actions majors 5–6 changed internals (build-scan develocity
  etc.) but setup-gradle task input surface is unchanged.
- setup-android v4: input schema same; `packages: ''` skip behavior preserved.
