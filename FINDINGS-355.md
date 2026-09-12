# FINDINGS-355 — [review #347] P0-12: solvePow AIOOBE on long server-controlled nonce

## Probe evidence (before fix)

- `shared/src/main/kotlin/com/kraptor/Extractorlar.kt` `solvePow` (`:117`): fixed
  `ByteArray(64)` buffer is pre-filled with `prefix = "$nonce:"` via
  `System.arraycopy(prefixBytes, 0, buffer, 0, prefixBytes.size)` with **no bounds
  check** on the server-controlled nonce (`captcha.optString("pow_nonce")`).
- Any nonce whose ASCII prefix is > 64 bytes → `ArrayIndexOutOfBoundsException`
  straight out of the arraycopy; a nonce of 60–61 bytes overruns later via the
  counter suffix (`buffer[pLen + i]`, buffer len 64) once `s` grows digits.
- Live reproduction (unit test, red → TDD):
  `BysePowTest.solvePow returns null for overlong server-controlled nonce`
  first run FAILED with `java.lang.ArrayIndexOutOfBoundsException: Index 64 out
  of bounds for length 64 / at com.kraptor.ExtractorlarKt.solvePow(Extractorlar.kt:140)`
  (60-char nonce, counter "1024" overwrote index 64).
- Existing `BysePowTest` covered goldens + timeout only — the AIOOBE path was
  uncovered, consistent with the review finding.
- Only consumer: Film1k (`solvePow`/`pow_nonce` used nowhere else — grep over
  `*/src`).

## Fix (minimal, shared — the root cause lives there)

`shared/src/main/kotlin/com/kraptor/Extractorlar.kt`, `solvePow`:

1. Guard `if (prefixBytes.size + 10 > 64) return null` before the arraycopy (10
   digits = Long.MAX_VALUE counter; an overlong nonce can never succeed).
2. Guard `if (pLen + sLen > 64) return null` inside the counter loop — covers
   corner nonces that fit the prefix but whose counter would overrun.

Both return null, matching the solver's existing timeout contract (caller
treats null as "PoW failed" instead of crashing `getUrl`).

## Tests

- Red (AIOOBE live-observed) → green after guard; `./gradlew MissAV:test`
  (shared tests run per-provider) passes, including untouched goldens
  ("deadbeef"/d=12, "abc123"/d=16, timeout path).

## Verification

- `./gradlew Film1k:make` — BUILD SUCCESSFUL (`Film1k:make` compiles the
  shared splice into the provider).
- Full live-site verify (verify.sh): not applicable — the added paths only
  trigger on non-conforming server responses that previously crashed the
  provider rather than degrading; extraction behavior for well-formed
  traffic is unchanged, and Film1k's selector/stream proof was established
  in #347 and is untouched by this change.

## Version bumps

- `Film1k` 6 → 7 (only provider consuming the shared `solvePow`).

## Review notes

- Change is confined to `shared/` + one provider bump; no other providers
  affected (grep-verified).
- Not merged; left for human review.
