# FINDINGS — issue #456 (mangoporn: search shows no results)

## Root cause: two compounding problems

1. **Multi-word / special-character queries crashed search.**
   `Mangoporn.search()` interpolated the raw query into the URL
   (`"${mainUrl}/?s=$query"`). A literal space (the common case for a search
   phrase) makes OkHttp/NiceHttp's URL construction throw, so `search()`
   propagated the exception and the app displayed no results — for exactly the
   queries users type. Even where OkHttp tolerated it, the Dooplay theme token
   search also misbehaves on unencoded "%22 etc.
   Fix: `MangopornParse.searchUrl(baseUrl, query, page)` with
   `URLEncoder.encode(query, "UTF-8")` (+ space → `%20` for OkHttp), TDD
   red→green (`MangopornParseTest.searchUrlEncodesQuery`). The search loop now
   also catches a failed page fetch instead of throwing away everything.

2. **Site outage (confirmed, blocks live verification).**
   `https://mangoporn.net` serves Cloudflare **522 (origin unreachable)** for
   every path — home, search, category — probed repeatedly 2026-09-23 over
   ~20 min (curl, UA Firefox/144, plus Android-Chrome UA and Googlebot UA; all
   522, ~16-byte "error code: 522" body). Third-party check earlier in the
   run (attempt 1, issue comments) saw the same. DNS still resolves to
   Cloudflare (104.21.96.89 / 172.67.176.67). `mangoporn.cc` (207.57.7.16,
   non-CF) hangs; `mangoporn.com/.to` unrelated. So even the fixed provider
   would return zero results right now — the outage alone reproduces the
   reported symptom. Upstream Cs-GizliKeyif has no domain change or search
   fix to port.

This matches attempt 1's conclusion (branch `devloop/issue-456` had no commit —
delivery failed on a GitHub 503, so the work was redone here).

## Out-of-scope but touched

- `bootstrapCloudstream`: upstream re-published the `pre-release` tag
  (digest bbd246ed… → e984bf17…). Extracted class trees are **byte-identical**
  (`diff -rq` of the two unpacked jars: zero differences) — re-zip metadata
  only. `expectedSha` bumped in root `build.gradle.kts` per the pin policy.

## Build gates

- `./gradlew Mangoporn:test` — **green** (incl. new `searchUrlEncodesQuery`;
  105 tests total through the shared splice)
- `./gradlew Mangoporn:make` — **green**; `Mangoporn/build/Mangoporn.cs3`
- Live-site verification (per `verify.sh` pattern): **blocked by the 522
  outage** — recorded above; re-check when the site returns. Search markup
  selectors (`article` / `div.image a` / `div.details a`) unchanged from the
  #443 verification (`Mangoporn/VERIFICATION.md`) and not touched.

`Mangoporn/version` bumped 1 → 2.
