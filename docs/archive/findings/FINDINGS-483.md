# FINDINGS-483 — audit all sites (issue #483)

Run date: 2026-09-26 (attempt 2 — attempt 1 delivered PR #485 with an empty
diff/ghost and was closed without merge; its claimed changes were never
committed and are redone here from fresh evidence). Probe IP: CI datacenter.
Instrument tier used for the deep provider: plain curl.

## Phase 0 — canary calibration

| canary | plain-curl | tls-impersonated | expected | result |
|---|---|---|---|---|
| film1k-challenge-pair | 403 | 200 (`impersonate.sh`) | 403 / 200 | match |
| eporner-healthy | 200 | — | 200 | match |
| pandamovies-dead-origin | 522 | 522 (TLS) | 522 / 522 | match |

Instrument healthy; no environment-degradation downgrades this run.

## REPO BUILD REPAIR (blocking everything — fixed first)

**Condition**: every `./gradlew` invocation on current main fails configuration:

```
Could not find com.github.recloudstream.gradle:gradle:-SNAPSHOT.
  Searched: https://jitpack.io/com/github/recloudstream/gradle/gradle/-SNAPSHOT/gradle--32895aedb6-1.pom
```

Reproduced locally 2026-09-26 (fresh runner, empty `~/.m2`, no
`com.github.recloudstream` in `~/.gradle/caches`). CI Build red on main since
run 36220684523 (2026-09-26T05:24) — same failure line.

**Root cause (curl evidence, re-verified twice)**:

1. `GET https://jitpack.io/com/github/recloudstream/gradle/gradle/-SNAPSHOT/maven-metadata.xml`
   → **200**, advertises `<version>-32895aedb6-1</version>`.
2. `GET .../gradle/-SNAPSHOT/gradle--32895aedb6-1.pom` → **404** (empty body),
   same for the `.jar` and for the same path repeated minutes later.
3. jitpack's build API lists `master-master-32895aedb6-1` = **ok** and that
   version's pom/jar **are** 200 — the metadata points at a dangling timestamp
   while the real artifact lives under a different version string. Stale
   jitpack -SNAPSHOT metadata; nothing in this repo can pin around a metadata
   entry that 404s.

Note: the fine print in commit 00512f5 ("the earlier vendoring existed only
for the then-broken jitpack -SNAPSHOT path") was right for 2026-09-12 — the
break is re-current. A metadata-driven `-SNAPSHOT` coordinate is fragile by
construction; this is the second failure in two weeks.

**Fix (minimal, restores the reviewed shape)**: re-vendor the plugin jar
verbatim from git history — `vendor/com/github/recloudstream/gradle/gradle/-SNAPSHOT/{gradle--SNAPSHOT.jar,gradle--SNAPSHOT.pom}`
(restored from f0ec7c7, byte-identical digests recorded in
`gradlelibs/INTEGRITY.txt`) — and put `maven("$rootDir/vendor")` FIRST in the
buildscript repositories so `com.github.recloudstream.gradle:gradle:-SNAPSHOT`
resolves locally and never reaches jitpack's broken metadata. The P0-15
`verifyVendoredJars` gate (dropped in 00512f5) is restored and wired back into
make/test/check. No other file touched: the classpath coordinate, deps, and
`bootstrapCloudstream` (classes.jar) are unchanged.

**Verified**: cold environment (`rm -rf ~/.gradle/caches ~/.m2/repository/com`)
→ `./gradlew bootstrapCloudstream` → BUILD SUCCESSFUL; `AllClassicPorn:test`,
`AllClassicPorn:make` → BUILD SUCCESSFUL, `.cs3` produced.

**Review decision point (maintainer call — carried here, not settled):** this
repair restores the previously reviewed vendoring shape (P0-15), but a smaller
alternative exists: jitpack serves the pinned timestamped coordinate
`com.github.recloudstream.gradle:gradle:-32895aedb6-1` (pom/jar verified 200)
with a byte-identical pom, so pinning the buildscript classpath to that
coordinate would fix resolution with no committed binary. Vendored binary vs.
pinned snapshot coordinate vs. waiting for jitpack to heal is a merge
decision, and this build repair arguably deserves its own issue rather than
riding under #483 — flagged for review, not silently bundled.

## Phase 1 — sweep (deep tier this run: AllClassicPorn, issue's surfaces)

### AllClassicPorn — deep audit (home, load, loadLinks, search, quicksearch, pagination, dedup)

All plain curl, UA `Mozilla/5.0 (X11; Linux x86_64; rv:130.0)`:

| surface | URL | result |
|---|---|---|
| home p1 | `/page/1/` | 200, 60 `a.th.item` cards, 0 in-page dups |
| home p2 | `/page/2/` | 200, 60 cards, **0 shared with p1** |
| search p1 | `/search/milf/` | 200, 60 cards, 0 dups |
| search p2 | `/search/milf/2/` | 200, 60 cards, **0 shared with p1** |
| decade row | `/90s/` | 200, 60 cards |
| sort row | `/best/` | 200, 60 cards |
| video (old) | `/videos/1573/casanova-2/` | 200, caption `480p` |
| video (old) | `/videos/2208/the-golden-age-of-danish-pornography/` | 200, caption `480p` |
| video (old) | `/videos/2118/worst-porno-ever-made-with-the-best-sex/` | 200, caption `480p` |
| video (new) | `/videos/2252/zazel/` | 200, caption `480p` (bracket form) |
| video (new) | `/videos/6161/mature-milfs-part-three-homemade-vhs-1998/` | 200, caption `480p` (colon form) |
| stream | 2252 + 6161 `video_url` with UA+referer | **206 video/mp4**, `ftypisom…avc1` header |

Quicksearch: site has no distinct quick-search endpoint (explicit FINDINGS
note, audit-#204). No provider edit is involved — `hasQuickSearch = false` is
the MainAPI default, applied by omission as the scaffold requires.

### Engineered fix — loadLinks quality pass-through (issue requirement)

The KVS caption (`flashvars['video_url_text']` / `video_url_text: '…'`, both
forms, parseQuality already unit-tested red→green in #322) was only embedded
in the link **name** — `ExtractorLink.quality` stayed `Qualities.Unknown`, so
CloudStream could not see the exact resolution. Fix (one line + comment):
`this.quality = getQualityFromName(quality)` in
`AllClassicPorn.loadLinks`. TDD: Parse-level extraction remains covered by
fixture tests (2252 → `480p`); the wiring is the only untestable line
(ADR-0005: no HTTP mocking). Version 13 → 14.

### verify-provider mechanical run (2026-09-26, transcript trimmed)

```
── check 1: search pages (2) — 60 + 60 cards, no duplicate cards
── check 1a: homepage pages (2) — 60 + 60 cards, no duplicate cards
── check 1b: quick search (0) — NOTE: FINDINGS records no endpoint
── check 2: video page 6161 → 200; stream 206 video/mp4
── check 4 (related): 'a.th.item' 30 matches, no self/dup FAIL
── check 5: title agreement PASS (div.th-description card title ↔ h1[itemprop=name])
── check 6: recommendations/plot/duration/year/actors/tags all assigned
RESULT: FAIL (2 standing script-side artifacts below)
```

Re-run during PR repair (2026-09-26, same flags): reproduces exactly this
state — all surface / no-dup / stream / agreement / field checks pass on the
current diff; the only FAILs are the two standing script artifacts below.

**Standing mechanical FAILs — ok-suspected site design, not provider defects**
(all previously classified in AllClassicPorn/FINDINGS.md audits #204/#266;
re-verified live this run):

1. `FAIL stream content-type …/embed/6161` — verify.sh's stream extractor also
   grabs `og:video` (the embed iframe, text/html). The provider emits ONLY the
   flashvars `video_url` mp4 → 206 video/mp4. No code change can or should
   remove the meta tag from the site.
2. `FAIL poster mismatch` — search-card poster is a random screenshot slice
   (`…/320x240/22.jpg`, different N per render) while the load page's only
   stable poster is og:image `preview.jpg`. Same video, same screenshot tree;
   site exposes different thumbnail variants per surface.

### Remaining Unknown-quality emitters (no-fix rationale — per-provider evidence provenance)

**Screened for an exposed caption — evidence cited per run:**

- **Cat3Film** — deep-tier provider of run 3 (2026-09-26, issue #479): watch
  page player-obfuscated (atob/eval, season panes); no quality caption on a
  `loadLinks`-visible surface. Registry row unchanged this run, no new
  condition.
- **Javmost** — deep-tier provider of run 3 (issue #479): `/ri3123o235r/`
  AJAX → emturbovid m3u8 (master playlist); HLS master quality is resolved by
  the player, no discrete caption on the page. No new condition this run.
- **ixiporn** — direct mp4 but no quality attribute/caption anywhere on the
  video page (grep-verified across sweeps #479/#483 evidence).
- **Cat3Movie** — **absence of evidence only**: registry rows (runs 2–4)
  record home/search/video 200 with stream via obfuscated player (extractor
  path). No probe has ever looked specifically for a quality caption there;
  the "no caption" claim is not caching of a caption check — treat as
  unscreened until probed.

Forcing a quality guess on any of the above would be fiction;
`Qualities.Unknown` is the honest value. Any new provider whose site exposes a
caption must follow the AllClassicPorn pattern (`getQualityFromName`), per the
scaffold.

**Not screened this run:** the remaining ~21 providers' `loadLinks` emitters
were not probed for an exposed quality caption; their `Qualities.Unknown`
rests only on absence of evidence from earlier (shallower) probes that did not
look for captions. Caption-screening the rest of the fleet is open work under
issue #483 — an exposed caption found later gets the AllClassicPorn pattern,
not a locked-in "no caption".

## Phase 2/3 — registry + lifecycle

- `audits/findings.json`: `run4` appended (`false_positives: []` — no finding
  issues filed this run); AllClassicPorn stamped `last_deep: 2026-09-26`,
  verdict `ok`.
- The two standing mechanical FAILs are recorded as **still-open script
  artifacts, not closed lifecycle items** (see `run4` note in
  `audits/findings.json`); their standing classification and re-classification
  history live in `AllClassicPorn/FINDINGS.md` audits #204 (artifact 1:
  og:video embed grab; artifact 2: poster mismatch) and #266 (poster mismatch
  extended). A reviewer needing the justification should read those, not
  re-derive it.
- `check-findings.sh`: **PASS**.
- Probe-IP challenge artifacts (Film1k / FreePornVideos / FullPorner /
  XMoviesForYou search) not re-filed per `suspected_excluded` family
  [450, 461, 465, 466, 467, 473] — run 3 verified all via TLS impersonation.

## Issue #483 checklist disposition

- **loadlinks quality → CloudStream**: fixed for AllClassicPorn (the one
  emitter whose site exposes a caption); others documented no-fix above.
- **homepage rows / paginations / duplicates**: AllClassicPorn deep-verified —
  13 rows, all `{base}/{page}/`-paginated; p1↔p2 disjoint on home and search;
  in-page site duplicates deduped provider-side (`distinctByHref`, #266).
- **old + new videos, categories/tags**: 3 old + 2 new video pages probed,
  decade + sort rows 200.
- **all fields utilized**: check-6 all LoadResponse fields assigned (tags,
  actors, year, duration, plot, recommendations, poster).
- **TDD**: Parse tests green (fixtures); build repair verified from cold env.
- **Fleet scope of this run: AllClassicPorn only.** The checklist items above
  are demonstrated for AllClassicPorn, not the fleet. Remaining providers:
  registry verdicts unchanged from run 3 (2026-09-26, PR #481 — deep tier
  Cat3Film/Eroticmv/Javmost/Xhamster), no new conditions observed this run;
  caption-screening the remaining `loadLinks` emitters and fleet-wide
  hardening of rows/pagination/dedup remain open. Issue #483 stays open
  accordingly.
