# FINDINGS — neporn.com (2026-09 audit)

## Verdict: OK

## Search
- `/search/red/` returns empty (no 'red' content — legit), `/search/anal/` fine: `div.list-videos div.item` ✓; async page-2 URL works.

## Stream
- video page flashvars `video_url: 'https://neporn.com/get_file/7/.../39025_720p.mp4/?v-acctoken=...'` → 302 → `https://data003.neporn.com/remote_control.php?...` → **206 video/mp4**.

## Update — issue #130 Data-completeness probe (video/39025/wright-for-anal)
- plot: `meta[property=og:description]` ✓ (transcript: content="The adorable Whitney Wright joins Hard...")
- year: schema.org ld+json `"uploadDate": "2026-03-18T17:35:00Z"` ✓ (regex on script[type=application/ld+json])
- actors: `div.info-content a[href*=/models/]` → Whitney Wright, Ramon Nomar ✓
- streams unchanged; verify.sh PASS (search 200/24 items, 2 video pages 200 + 206 video/mp4, all LoadResponse fields assigned).

## Update — issue #648 deep audit (2026-10-16)
- Full-surface re-probe, plain-curl tier, all healthy: home latest-updates p1/p2 24+24
  disjoint; search p1 24 + async p2 24 disjoint (`li.page.last` halts); quickSearch absent
  (recorded, not drift); 3+ video pages 200; flashvars `video_url` → 302 → 206 video/mp4
  (session-bound acctoken; bare get_file 403 = known session-bound class, not drift).
- verify.sh PASS; remaining NOTEs adjudicated in FINDINGS-648.md (pydom regex-DOM engine
  limits on `:contains`/descendant selectors, newest videos carry `video_models: ''` —
  model anchors verified live on older scenes via raw grep; Duration `<em>` + ld+json
  `uploadDate` likewise curl-grep verified on all 3 samples).
- Latest-upload slugs volatile (older slugs 301 to fresh slugs) — provider unaffected
  (always uses fresh hrefs). No confirmed condition; verdict ok retained; version stays 7.

## Update — issue #301 Correctness fix (video/35636)
- Model anchors inside `div.info-content` carry a `.button-info` span with the member's
  video count; jsoup `.text()` on the `<a>` yields e.g. "Marica Hase 32".
- Fix: `Parse.actors()` reads `a[href*=/models/] .name` only → ["Marica Hase"].
  TDD: `NepornParseTest` + fixture `info-content-35636.html` (old selector fails the
  equality assert; new passes).
- Full verify run 2026-09-11: search p1 200/24 cards; home p1+p2 200/24+24, 46 unique
  hrefs (zero ID overlap); both video pages 200, flashvars v-acctoken stream 302 → 206
  video/mp4; tags/actors/year/duration present on all sampled pages; related 21–22 cards.
  Remaining verify.sh FAILs are the pre-adjudicated harness artifacts (#301 PR quote):
  regex-DOM truncation of `div.item` titles (home dup + related title dups) and
  stereotyped badges ("hd"/"premium 4k") — real hrefs/IDs are unique.

## Update — issue #826 CloudStream API-contract audit (2026-10-23)
- Audited against a fresh `bootstrapCloudstream` of the `pre-release` classes.jar
  (sha256 `ac5b150f5d91fcd641955d762aaf720bd2dc1f4e3180f01e1d7261a538564cf8` — the moving
  tag has moved since the FINDINGS-804 baseline `16278fc2…`; the Neporn-relevant surface
  was re-read with `javap`). Six of the seven checklist items conform unchanged:
  `MainAPI` overrides (`getMainPage`/`search`→`SearchResponseList`/`load`/`loadLinks`),
  `newHomePageResponse` DSL, `newMovieSearchResponse`/`newMovieLoadResponse` DSL (no raw
  `ExtractorLink`/`Episode`), `registerMainAPI` + shared `registerHostExtractors`,
  build metadata (`en`/`NSFW` exact), quality as enum `Int`.
- **Finding (fixed, v11): `addDuration()` was a silent no-op.** Pinned
  `LoadResponse.Companion.addDuration(String)` delegates to `getDurationFromString`, which
  matches only word forms (`"20 min"` → 20, `"1 h 2 min"` → 2) and returns `null` for the
  site's colon form — measured against the jar: `"20:48"` → `null`, `"58:53"` → `null`,
  `"1:02:03"` → `null`. So `duration` shipped empty on every video even though the page
  provably carries it: `Duration: <em>20:48</em>`, `meta[property=video:duration]
  content="1248"`, ld+json `"duration": "PT0H20M48S"` on 3/3 sampled pages
  (44461 / 44460 / 44459).
- Fix: `Parse.durationMinutes(doc)` maps the JSON-LD ISO-8601 token with the shared
  `JsonLdParse.minutes` (the canonical source — `year` already comes from the same block).
  TDD: fixture `ldjson-duration-44461.html`, red → green — `PT0H20M48S` → **20** min,
  missing ld+json → `null`.
- Live spot-check after the fix: search `/search/anal/` 200/24 cards; home
  `/latest-updates/` p1+p2 200/24+24; 2 video pages 200 with
  `meta[property=video:duration]` on both; related `div.related-videos div.item` 22/video;
  2/2 `video_url` streams (`_720p.mp4` + `v-acctoken`, referer) → **206 video/mp4**.
  actors NOTE = newest videos carry `video_models: ''` (pre-adjudicated #648; older scenes
  expose models), not drift.
- Quality mapping: 5/5 sampled pages expose a single `video_url` (`…_720p.mp4`,
  `video_url_hd: '1'`) with **no** `video_url_text` label and no alt URLs, so filename-based
  `Qualities.P720.value` / `Qualities.Unknown.value` is conformant (enum Int passed
  directly, never a raw int or a suffix-bearing label through `getQualityFromName`).
- Completeness absences are correct, not gaps: no rating/score and no trailer anywhere on
  the video page; `addPoster` N/A (single `og:image` → `posterUrl`); actors are set through
  the direct `actors` list (equivalent to `addActors(List<String>)`).
