# FINDINGS — issue #348: Codeberg token interpolated into push URL in `build.yml`

Task: probe reality first, then minimal fix for REVIEW.md P0-1. No provider code is touched;
the subject is CI plumbing (`.github/workflows/build.yml`), which the issue spec explicitly
names, so the `.github/workflows/` agent rule is satisfied.

## Evidence probe (reality, before the fix)

**1. The vulnerable line exists exactly as reported** — `.github/workflows/build.yml`
(the "Mirror builds branch to Codeberg" step, line 68-71):

```yaml
      - name: Mirror builds branch to Codeberg
        run: |
          cd "$GITHUB_WORKSPACE/builds"
          git push --force "https://RjBiermann:${{ secrets.CODEBERG_TOKEN }}@codeberg.org/RjBiermann/fictional-octo-fiesta.git" HEAD:builds
```

`${{ secrets.CODEBERG_TOKEN }}` is expanded by GitHub **before** the shell runs, so the
literal token ends up:

- in the `git push` **process argv** (`/proc/<pid>/cmdline`, visible to any sibling process
  or crash dump on the runner);
- in the remote URL stored transiently in git's runtime state — and printed verbatim by
  any failure trace, `GIT_TRACE=1`/`GIT_CURL_VERBOSE=1` run, or `set -x`. GitHub's secret
  masking only rewrites occurrences of the secret *in log output that GH knows about*;
  a URL-embedded secret in an error path is exactly the leak class masking can miss.

**2. The builds repo setup (step context)** — the step operates in `$GITHUB_WORKSPACE/builds`
(a `actions/checkout` of the `builds` branch). Its `origin` points at github.com with the
checkout-persisted GITHUB_TOKEN credential; the Codeberg push is a one-shot explicit-URL
push. Nothing else in `build.yml` touches Codeberg.

**3. The repo already has the correct pattern in-tree** — `.github/workflows/devloop.yml:54-62`
("PAT credentials (ADR-0004)"):

```yaml
        env:
          AGENT_PAT: ${{ secrets.AGENT_PAT }}
        run: |
          B64=$(printf 'x-access-token:%s' "$AGENT_PAT" | base64 -w0)
          git config http.https://github.com/.extraheader "AUTHORIZATION: basic $B64"
```

Token travels via step `env:` (not shell interpolation), then into a **host-scoped git
config value** (a file, not argv) as a Basic `Authorization` header. This is the pattern
GitHub recommends (credentials via env, never inline `${{ }}` into a run block) and it is
the in-repo precedent — the minimal fix should reuse it, not invent a second mechanism.

**4. Codeberg auth surface** — Codeberg runs Forgejo; token-as-password over HTTP Basic
works, and the `Authorization` header is honored for token auth (same mechanism devloop.yml
relies on for github.com). Scoping the config to `http.https://codeberg.org/` leaves the
github.com credentials from `actions/checkout` untouched (an unscoped `http.<url>.extraheader`
would override them — the reason devloop.yml scopes its header and deliver-pr unsets it,
see `.github/actions/deliver-pr/action.yml:22-24`).

**5. No other occurrence** — `grep -rn "CODEBERG" .github/` shows only `build.yml:68,71`;
no other workflow or action embeds the Codeberg token. (`deliver-pr`'s URL-embedded
GH_TOKEN is a separate finding, P0-2 / issue #349 — out of scope here.)

## Fix chosen (minimal)

- Add step-level `env: CODEBERG_TOKEN: ${{ secrets.CODEBERG_TOKEN }}` (env, not inline).
- Replace the URL-embedded credential with
  `git config http.https://codeberg.org/.extraheader "AUTHORIZATION: basic $B64"` built
  from the env var, mirroring devloop.yml — secret never appears in argv, URL, or shell text.
- Push to the plain `https://codeberg.org/...` URL.

Alternatives considered and rejected:
- `x-access-token:${CODEBERG_TOKEN}` inline in the URL from env — keeps the secret out of
  the YAML but still puts it in argv/process list (half the finding). Rejected.
- credential-helper shim echoing the env var — works, but introduces a shell-in-config
  mechanism the repo doesn't already use; extraheader matches the existing precedent.

## Verification performed

- `actionlint .github/workflows/build.yml` — clean (required by AGENTS.md for any
  `.github/**` change; CI `lint.yml` runs it post-push as well).
- `ruby -e require yaml` parse — file is valid YAML after the edit.
- Static check that the secret no longer appears inline: `grep '${{ secrets'` in the mirror
  step shows only the `env:` mapping.
- No runtime verification is possible from this environment (pushing requires the real
  secret and the merge-time `build.yml` run is the actual gate; behavior of
  `http.<url>.extraheader` is already proven in this repo by devloop.yml).

## Risks / notes

- `git config` writes into the runner-local `builds` checkout only; nothing persists.
- The Basic header value (base64 of `x-access-token:<token>`) is also a secret-shaped
  string; it lives in a git config file, not in logs or argv — same exposure as
  devloop.yml, accepted there.
- Behavior change: none intended. Same push target, same `--force`, same branch.

---

# FINDINGS — issue #357: PackedJs.unpack silently defaults radix to 36 on parse failure

Task: probe reality, minimal fix for REVIEW.md P0-13 (issue #347). Subject is the shared
Parse function `shared/src/main/kotlin/com/kraptor/PackedJs.kt` — root-cause fix in `shared/`
per AGENTS.md, followed by bumping every affected provider.

## Evidence probe (reality, before the fix)

**1. The vulnerable line exists exactly as reported** — `PackedJs.kt:20`:

```kotlin
val radix = m.groupValues[2].toIntOrNull() ?: 36
```

The radix group only matches `\d+` in the grammar regex, so `toIntOrNull()` fails only on
Int-overflow-sized numerals — but the more common corruption matter is a **valid integer
radix of 37..62** (real Packer instances use `a=62` with full base62 keys). Right after the
fallback line, `i.toString(radix)` is called to build the key map; for radix > 36 that
**throws** `IllegalArgumentException: radix 62 was not in valid range 2..36` — so today a
radix-62 pack not only can silently decode as garbage (fallback 36), it crashes the
extractor outright (observed: `radix above 36` test threw this before the fix). Either way
the unpack does not "fail to nothing safely": LULUBASE/Javclan might emit wrong links
(garbage path) or the exception propagates out of `getUrl()`.

**2. Callers in this repo** (issue names VidHidePro + Javclan — both confirmed):
- `VidHidePro` (Extractorlar.kt:627, line 647): unpack → null falls back to the page's raw
  `sources:` script — a graceful fallback already exists; safer contract makes it trigger.
- `Javclan` (Extractorlar.kt:703, line 712): unpack → null returns no links (correct).
- `LULUBASE` (Extractorlar.kt:861, line 888): unpack → null returns early (correct).
- `Sexfilm.kt:113`: unpack → null falls back to raw html (documented caller behavior).
- Via shared `HostRegistry.kt`, all of these adapters are available to every provider
  (global `registerHostExtractors()`), so every provider's playback path is a consumer.

**3. No other fallback/default radix exists anywhere in the repo** (`grep -rn "?: 36"`).

## Fix (minimal)

```kotlin
val radix = m.groupValues[2].toIntOrNull()?.takeIf { it in 2..36 } ?: return null
```

Contract: unparseable or non-representable radix ⇒ null (no decode attempt), matching the
function's documented "null when nothing unpackable" vocabulary.

## TDD evidence (red → green)

- Fixture added: `shared/src/test/resources/packed_radix62_embed.js` — a real-shaped
  Dean-Edwards `eval(function(p,a,c,k,e,d){...})` pack with `a=62` and 43 base62-encoded
  keys (indices into letters A..Z exercise the >36 encoding range).
- Tests added at the existing seam (`shared/src/test/kotlin/com/kraptor/PackedJsTest.kt`):
  - `radix above 36 yields null instead of garbage or crash` — RED: threw
    `IllegalArgumentException: radix 62 was not in valid range 2..36`; GREEN after fix.
  - `unparseable radix yields null instead of falling back to 36` — RED: returned the
    decoded payload (`w0`, the silent-garbage path); GREEN after fix.
  - Existing fixture test (`unpacks a packed eval call`) still green — radix-36 packs
    unaffected.

## Verification performed

- `./gradlew Javtiful:test` (runs shared `src/test/kotlin` with every provider's test task):
  red as above, then fully green after the fix.
- `./gradlew Javtiful:assembleDebug` clean.
- Live-site `verify.sh` pipeline verification not applicable: the subject is a shared Parse
  contract, not a provider's selectors/streams; no site changed and no fixture site URLs are
  in scope. The verification burden here is the Parse-function unit red → green (ADR-0005)
  plus the compile gate, both run.

## Risks / notes

- All 24 providers version-bumped +1: PackedJs serves the global extractor registry, so
  every provider's release must be refreshed for users to pick the fix up.
- No provider Kotlin source edited — behavior change is strictly the safer failure mode in
  `shared/`.
- Work committed for human review only; no merge performed.
