---
name: new-provider
description: Scaffold and implement a CloudStream provider in this repo from a FINDINGS document — correct file structure, real API signatures (never invented), sibling-provider shapes, Gradle build loop. Use when building a new provider.
---

# New Provider

Build a provider from `FINDINGS.md` (see the site-probe skill — no code without it). Follow the
repo `AGENTS.md` conventions: Kotlin, JVM 1.8, NiceHttp (`app.get`/`app.post`), jsoup, Jackson.

## Scaffold

Copy the files from [assets/](assets/) into `<ProviderName>/`:
- `build.gradle.kts` — set `authors` (repo owner), `language`, `description`, `iconUrl`
  (favicon of the site domain), `version = 1` for a new provider
- `src/main/AndroidManifest.xml` — as-is
- `<Provider>.kt` — the `MainAPI` implementation **plus** the `@CloudstreamPlugin`
  plugin class (`registerMainAPI` in `load()`) at the bottom — one Kotlin file per
  provider; package `com.rjbiermann`

Package: `com.rjbiermann`. Class name = the provider directory name.

## Choose a shape from a sibling provider

Read a working provider with the same shape before writing yours — the repo is the reference:
- **Direct-source tube (KVS/custom, `<source>` tags or direct mp4)** → `FreePornVideos.kt`
- **Embed/extractor site (streams behind an embed domain)** → `HQPorner.kt` +
  `MyDaddyExtractor.kt` (reuse extractors before writing new ones)
- API signatures: use only what's in [references/cloudstream-api.md](references/cloudstream-api.md),
  distilled from the real `recloudstream/cloudstream` source. **Never invent API signatures.**

## Implementation rules

- Only use selectors/URLs that exist in FINDINGS. A selector you can't show evidence for is a bug.
- `search` returns `newSearchResponseList(list, hasNext = true)` and maps page 2+ per the
  FINDINGS Pagination section (path-based vs query param differs per site); `getMainPage`
  builds `HomePageList` + `newHomePageResponse` from the FINDINGS Homepage section (rows,
  selectors, pagination). Implement `quickSearch` only when FINDINGS records a distinct
  quick-search endpoint — otherwise leave `hasQuickSearch` at its `false` default; a faked
  endpoint that just duplicates `search` is noise the app would call twice.
- **Distinct bar (glossary: Distinct)** — identity fields are per-video. Never return a constant
  title, plot, poster, or stream URL for every video; placeholders ("Watch more at …", a shared
  fallback poster) are exactly the bug class the bar exists to kill. Tags, actors, year,
  duration, and score may legitimately repeat — populate them, but never fabricate.
- `load`: populate the full `LoadResponse` — `recommendations` from the related-videos selector
  in FINDINGS (most sites expose one; if FINDINGS says none exists, say so in the PR), plus
  tags/plot/duration/year per FINDINGS. Parse durations to **minutes** (Int); ISO-8601
  `PT#H#M#S` → hours*60+minutes.
- `loadLinks`: emit **one `ExtractorLink` per source recorded in FINDINGS** — different videos
  on the same site can carry different sources, so handle each kind you probed. Resolve stream
  URLs fresh every call (signed/expiring URLs are the norm). ExtractorLink floor:
  - `quality` from the page's quality attr when exposed (`getQualityFromName`), never a guessed
    constant — and never `Qualities.Unknown` when the site states a quality
  - `type` accurate: let `INFER_TYPE` infer from the URL; pass it explicitly when the URL
    doesn't reveal the container (an m3u8 claiming `VIDEO` fails verification)
  - `referer` set when FINDINGS says the host requires it
- Embed extractor ladder — for each embed domain in FINDINGS, in order: (1) reuse a repo
  extractor (grep the provider directories for that domain); (2) fall through to CloudStream's
  built-in `loadExtractor(...)`; (3) only if neither handles it, write a new extractor inside
  the provider's directory.
- Wrap per-item parsing in `try/catch` returning null (one broken card must not kill the list).

## Red → green (TDD-first, ADR-0005)

New parsing logic ships test-first: extract a Parse function (`parseXxx(html): List<…>`), write
the failing JUnit4 test against a Fixture — saved HTML/JSON from FINDINGS, in
`src/test/resources/` — then implement until green. No HTTP mocking: `MainAPI` flows stay
covered by Verification, not unit tests. Fixture tests also enforce the Distinct bar at the
fixture level: pipe the parsed identities through the shared `DistinctBar.assertDistinctVideos`
helper (`com.kraptor`, in `shared/src/test/kotlin`) — a fixture whose parsed titles/posters/URLs
collide fails red before any live run.

## Build loop

```bash
./gradlew <ProviderName>:make
./gradlew <ProviderName>:test
```
Fix failures, repeat until both are clean. Then hand off to the verify-provider skill — a clean
build (and green tests) prove nothing about live selectors. No PR without a multi-video verify
transcript. Verify with the flags FINDINGS earns: homepage page 1+2 (`--home-url`), the distinct
quick-search endpoint (`--quick-search-url`) when one exists, and a field selector for every
LoadResponse field the site exposes.
