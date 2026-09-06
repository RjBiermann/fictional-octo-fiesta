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
- `<Provider>Plugin.kt` — `@CloudstreamPlugin` class extending `BasePlugin`, `registerMainAPI`
  in `load()`; package `com.rjbiermann`
- `<Provider>.kt` — the `MainAPI` implementation

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
- `search` returns `newSearchResponseList(list, hasNext = true)`; `getMainPage` builds
  `HomePageList` + `newHomePageResponse`.
- Map listing pages per FINDINGS pagination (path-based vs query param differs per site).
- `load`: populate the full `LoadResponse` — `recommendations` from the related-videos selector
  in FINDINGS (most sites expose one; if FINDINGS says none exists, say so in the PR), plus
  tags/plot/duration per FINDINGS.
- `loadLinks`: emit **one `ExtractorLink` per source recorded in FINDINGS** — different videos
  on the same site can carry different sources, so handle each kind you probed. Resolve stream
  URLs fresh every call (signed/expiring URLs are the norm); set `referer` when FINDINGS says
  it's required; `getQualityFromName(quality)` for qualities.
- Embed extractor ladder — for each embed domain in FINDINGS, in order: (1) reuse a repo
  extractor (grep the provider directories for that domain); (2) fall through to CloudStream's
  built-in `loadExtractor(...)`; (3) only if neither handles it, write a new extractor inside
  the provider's directory.
- Wrap per-item parsing in `try/catch` returning null (one broken card must not kill the list).
- Duration: parse to **minutes** (Int). ISO-8601 `PT#H#M#S` → hours*60+minutes.

## Build loop

```bash
./gradlew <ProviderName>:make
```
Fix compile errors, repeat until clean. Then hand off to the verify-provider skill — a clean
build proves nothing about selectors. No PR without a multi-video verify transcript.
