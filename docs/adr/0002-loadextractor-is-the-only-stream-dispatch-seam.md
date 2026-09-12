# loadExtractor is the only stream dispatch seam

Embed hosts are never handled inline inside a provider's `loadLinks`. Every embed host
family gets an `ExtractorApi` adapter in `shared/` and is dispatched by the framework's
`loadExtractor`, which matches registered adapters by `mainUrl`. The **Host registry**
(`registerHostExtractors()`) is the single registration point, called by every provider's
plugin, so host coverage never varies by provider — a drift fix ("embeds moved to a host
the plugin does not register") becomes one adapter + one line in the registry.

## Shared splice coupling

`shared/` is not a Gradle subproject: the root `build.gradle.kts` splices
`shared/src/main/kotlin` into every subproject's `main` source set, and
`shared/src/test/kotlin` (plus `shared/src/test/resources`) into every
test source set. Two consequences follow from that design:

1. **Shared edits have repo-wide blast radius.** A syntax error in any single
   file under `shared/src/main/kotlin` fails `gradlew <Provider>:make` for every
   provider, and a shared test or fixture failure fails every provider's
   `gradlew <Provider>:test` — including CI's root `gradlew test make`.
   There is no provider-scoped failure mode for `shared/` changes.
2. **Shared tests run N× per CI pass.** Because spliced sources compile into
   each subproject, shared unit tests execute once per provider test task
   (once per `gradlew <Provider>:test`), multiplying CI wall time by the
   number of providers. Accepted: correctness of shared code must be proven
   against every consuming provider anyway, and the count is small.

Documented consequence of the single-registration-point design; see also
ADR-0005 (TDD splice of shared test sources).

## Rejected alternative: a custom `dispatchEmbed()` layer that tries special handlers before
falling back to `loadExtractor`. It duplicates a seam the framework already provides and
widens every provider's interface; page-derived streams (e.g. xHamster's `window.initials`)
are the honest exception and keep their custom `loadLinks` — the rule is "embed hosts use
the seam", not "everything is an embed".
