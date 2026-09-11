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
