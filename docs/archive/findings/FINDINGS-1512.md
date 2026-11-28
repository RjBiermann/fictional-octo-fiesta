# FINDINGS — #1512 (PandaMovies / Mangoporn data-servers rotation: unregistered embed hosts)

- Trigger: [#1512](https://github.com/RjBiermann/fictional-octo-fiesta/issues/1512)
  (`[from-impl]` standing issue filed by the S9 video-source audit #1447; evidence for the
  second affected provider appended from S23 #1461).
- Run: 05-implement, 2026-11-27, plain-curl tier from the CI runner.
- Resolution this run: **`vinovo.to` registered**; `peeplayer.com` and `content.st` stay
  deferred (reasons below).

## Condition

Both providers enumerate `section.hlm[data-servers]` host-agnostically
(`PandaMovies/src/main/kotlin/com/rjbiermann/PandaMovies.kt` `Parse.embeds`,
`Mangoporn/src/main/kotlin/com/kraptor/Mangoporn.kt` `MangopornParse.embedLinks`) and
dispatch every `u` through the framework's `loadExtractor`, which prefix-matches against
`shared/src/main/kotlin/com/kraptor/HostRegistry.kt`. A served host with no row is a
silently dropped mirror. Three of the eight rotation hosts had no row (FINDINGS-1447,
FINDINGS-1461).

## `vinovo.to` — fixed: framework `VinovoTo()` row

The fix is a registry row, not an adapter (`shared/HostRegistry.kt`, one line) plus the
`HostRegistryTest` pin (TDD red → green; `shared/src/test/kotlin/com/kraptor/HostRegistryTest.kt`
"vinovo to embed host has an explicit registry row").

Evidence:

1. **The pinned framework jar ships the adapter.** `javap`/string dump of
   `cloudstream3:pre-release` → `com/lagradost/cloudstream3/extractors/VinovoTo.class`:
   `mainUrl = "https://vinovo.to"`, name `VinovoTo`, `requiresReferer`,
   `/d/ → /e/` normalisation, parse targets `video` + `data-base`,
   `meta[name=token]`, `meta[name="file_code"]`, `meta[name=recaptcha]`,
   `POST /api/file/url/` with `token`/`recaptcha` and `Origin` + `X-Requested-With`
   headers, reCAPTCHA through `APIHolder.getCaptchaToken`.
2. **The live page still carries exactly that grammar** (re-probed 2026-11-27, both embed
   ids from the audit transcripts, plain curl, shared `FIREFOX_UA`):

   ```
   $ curl -sSL https://vinovo.to/e/8ggdpn80c26vd   → 200 (8.6 KB)
   <meta name="token"     content="85f2c642c7315216…">
   <meta name="recaptcha" content="6LcBdeUqAAAAAP4pgGR0…">
   <meta name="file_code" content="8ggdpn80c26vd">
   <meta name="noab"      content="1">
   <video id="video" … data-base="https://fs-31af0e.vincdn.net" …>
     (same shape for /e/488ejj6gun3vn2 → data-base="https://fs-554cd9.vincdn.net")
   ```

3. **It is deliberately not a `dood(...)` row.** `vinovo.to` does not serve the dood
   `pass_md5` grammar; the classic dood adapter's `/pass_md5/<expiry>/<token>` regex never
   matches this page and would emit a bogus URL (FINDINGS-1447). The `HostRegistryTest` pin
   asserts the row *is* `VinovoTo`, so a dood-shaped regression fails the suite.

The reCAPTCHA hop (`APIHolder.getCaptchaToken`) is app-side and cannot be exercised from
the runner — same class as the dood Turnstile rows. The row ships on "adapter present in
the pinned jar + live grammar matches verbatim", with the in-app confirm at merge.

## `peeplayer.com` — deferred: bespoke adapter, not a hostname row

The page runs a WebCrypto handshake before any playlist exists
(ECDH P-256 → `deriveBits(256)` → HKDF-SHA256 → AES-GCM `unwrapKey` → AES-CBC media key),
so there is no static URL for an `ExtractorApi` to fetch. The pinned jar has no PeeStream
adapter (`unzip -l classes.jar | grep -i peestream` → empty), so no row can be honest here.
Carried forward as its own story (see the follow-up issue referenced on #1512); #1512's own
body already said this "needs its own story, not a hostname row".

## `vinovo.si` — same family, deliberately no row today

The pinned jar also ships `VinovoSi` (`mainUrl = "https://vinovo.si"`), which inherits the
same `/d/ → /e/` normalisation and `meta[token|file_code|recaptcha]` + `video[data-base]`
grammar; the `/api/file/url/` hop on a `vinovo.to` embed `302`s there. No sampled
PandaMovies/Mangoporn rotation page serves a `.si` host directly, so no row is added — but
the next host-rotation sweep must not rediscover it: if a `.si` embed appears, the fix is
the framework `VinovoSi()` row, never a provider-side branch.

## `content.st` — not a finding

`403` on every embed while the host root answers `200`; the body is a viewer-side gate
("Playback is blocked" / "turn off your VPN or proxy"). That is the runner's datacenter IP,
never a grammar problem, and the TLS-impersonated tier is unavailable in the agent container
(#1434) — `Suspected` per the S-audit D8 rule, not a `Blocked`/drift finding. No row is
added: an unregistered row would only convert a host-side block into a silent one.

## Gates

- `./gradlew PandaMovies:test` → BUILD SUCCESSFUL (provider + shared suites; the new pin
  failed before the row and passes after).
- `./gradlew Mangoporn:test` → BUILD SUCCESSFUL (same shared suite through the second owner).
- `audits/findings.json` untouched in the diff (ADR-0013); any registry update rides an
  `<!-- findings-registry-entry -->` comment instead.

## Version bumps (repair round 1)

`shared/src/main/**` classifies as `ALL`, so every provider must republish with the row.
The implement run's fleet bump (`chore(version): bump affected providers`) lost three
providers to the later main-into-branch merges, which resolved their `build.gradle.kts`
back to main's already-published numbers: `123av` 3, `PornSlash` 6, `WatchPorn` 35.
This round restores the bump relative to what is published — `123av` 3→4,
`PornSlash` 6→**8** (main published 7 in #1680 while this branch was open), `WatchPorn`
35→36 — so merging as-is republishes all 30. Every other provider already carries its
post-merge number, so no other file is touched.

## Registry bookkeeping (repair round 1)

The implement run's #1512 delta comment did not begin with
`<!-- findings-registry-entry -->`, so it is unanchored; the orchestrator's
`_registry_candidate` self-heal re-posts an unanchored entry anchored on the issue — the
same lesson FINDINGS-1461.md recorded, and `providers.PandaMovies.standing_issue` is still
`null` in `audits/findings.json` until that sync lands. This repair round therefore posts
no registry payload: the registry is conveyor-owned (ADR-0013) and the diff must not carry it.
