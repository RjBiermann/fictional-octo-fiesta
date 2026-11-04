# FINDINGS-635 — Xhamster deep pass (probe → fix → verify)

Spec: #635 (sub-issue of epic #631, approved round 2). One self-contained run: full-surface
probe, verdict, in-run fix for the confirmed condition, registry delta, PR.

Run date: 2026-10-16 · tier **plain-curl on every surface** (no challenge seen; no escalation needed)

## Probe results (all surfaces, instrument tier recorded)

| Surface | Result | Tier | Evidence |
|---|---|---|---|
| home p1 (`/newest/1` → 301 → `/newest`) | 200, 46 JSON cards | plain-curl | provider path `layoutPage.videoListProps.videoThumbProps` resolves; **p1∩p2 = 0** (normalized) |
| home p2 (`/newest/2`) | 200, 46 JSON cards, 0 overlap | plain-curl | fresh fetch + card-by-card set compare |
| search p1 (`?page=1&x_platform_switch=desktop&geo=us`) | 200, 46 JSON cards | plain-curl | provider path `searchResult.videoThumbProps` (46) |
| search p2 | 200, 46 JSON cards | plain-curl | see § Site-behavior quirks for cross-page repeats |
| quickSearch | **absent** — no distinct suggest endpoint (`/search-suggest`, `/api/search-suggest`, `/ajax/search-suggest` all 404; type-ahead has no fetchable API) | — | recorded absent, not drift (unchanged since #275) |
| video ×5 (3 from search p1 + 2 from home p2) | 200 desktop + mobile, `script#initials-script` present | plain-curl | `/videos/xhf7GPD`, `/videos/xhmsT4f`, `/videos/xhu335C2`, `/videos/xhEkqm0`, `/videos/xh7ZmfT` |
| stream resolution | **5/5 streams 206 `application/vnd.apple.mpegurl`** | plain-curl | mobile `xplayerSettings.sources.standard` h264/av1; decode algos 1,2,3,4,5,6,7 all observed live and all resolve |

Verdict: **`ok`** — no drift. All provider parse paths resolve on the live template.

## Open question resolved — year/UploadDate (unfiled pending field-shape proof)

The 2026-10-09 re-probe (`window.initials videoModel/videoEntity carry no year key;
XMLUploadDate uploadDate absent`) is **superseded**: the video template re-expanded and
`videoModel` now carries:

- `created` — **unix-epoch upload timestamp** (vid1 `1788379812` → 2026-09-02, agrees with
  `videoEntity.dateAgo` "1 month ago"; vid3 `1711626209` → 2024-03-28 agrees with "2 years ago")
- `modified` (int year), `rating.value`, `views`, full `description`/`title`/`thumbURL` again
- `videoEntity.dateAgo` (human string)

Field-shape proof: full key dump of `videoModel`/`videoEntity` on 3 live pages (2026-10-16)
captured in the probe transcripts; fixture `xhamster-video-v4.html` is the reduced live capture.

**Decision: fix in-run** (confirmed condition — the site now exposes the upload date the
provider doesn't use). Not a drift finding (provider never parsed a year field; nothing broke).

## Fix (TDD-first, ADR-0005)

- **Red**: `XhamsterParseTest` 2 new tests — `videoModel created epoch parses from live template`
  (fixture v4) + `yearFromCreated maps epoch to utc year and stays null-safe` (incl. a
  near-year-boundary epoch). First run: 7× `Unresolved reference` (created/dateAgo/yearFromCreated).
- **Green**: `VideoModel` gains `created: Long?`, `VideoEntity` gains `dateAgo: String?`;
  pure Parse function `yearFromCreated(Long?): Int?` (UTC `Calendar` — JVM 1.8/minSdk 21, not
  `java.time`); `load()` sets `this.year = yearFromCreated(videoModel?.created)`.
- `gradlew Xhamster:test`: **16/16 green** (14 prior + 2 new; full shared suite green).
- Version **22 → 23**.

## verify.sh (mechanical gate)

5/5 streams serve, all listing surfaces 200/46-cards, tags present on all 5 video pages,
LoadResponse completeness 7/7 fields populated. Raw FAIL lines, each evidence-backed as an
instrument limitation (FINDINGS-515 § verify.sh scope note / FINDINGS-633 precedent):

1. `FAIL duplicate search cards across pages` — **site-native rank re-injection**: on the
   verify-run's fresh fetches, 7 of 46 videos appear on both pages' `searchResult.videoThumbProps`
   (the provider's own JSON source — SSR anchors are set-identical to the JSON per page). Within a
   page always 46 unique/0 dups. The provider renders the site's own lists; same quirk class as
   FINDINGS-515 "EPorner sticky cards". Not drift, nothing to fix in code.
2. `FAIL poster mismatch` (×2) — `og:image` on fresh guest pages is the 16×9-pixel artifact
   (`b(2),s(w:16,h:9)`) that the provider **deliberately skips** since issue #427 (`pickVideoPoster`);
   the search-card poster (sfw 1280 webp) is what the provider actually surfaces.
3. `FAIL title mismatch` (×2) — 2 of 46 SSR name anchors render title-only in the `title=` attr
   (text node empty); verify.sh's extractor is text-only. JSON `title` == `og:title` proven
   card-by-card (e.g. xhu335C2: JSON title == `og:title` "horny stepmom mckenzie lee…").

Instrument notes for future runs:
- Card selector that works mechanically: `div[data-video-type=video]` (46/page, set-identical to
  the JSON the provider parses); bare titles via `--search-title-selector 'a.video-thumb-info__name'`
  (fails only on attr-only cards).
- Streams are JS-built at play time: mint fresh tokens from the mobile page's
  `xplayerSettings.sources.standard` (decode per `decodeXhUrl`) and pass as `--stream-url`
  overrides; tokens are ~1h-valid (`end=` epoch in path) — re-mint immediately before the run.
- Direct MP4 tokens (`video-h.xhcdn.com/key=…,data=<ip>-mv`) are IP-bound — hls4 multi m3u8
  entries (algo 6 "auto" entries) are the verifiable overrides.
- `a[data-role=thumb-link]` on a video page is a *recommendation* widget, not the video's own thumb.

## Findings lifecycle

- Dedup sweep: standing issues for Xhamster — none open; 10 closed (25, 119, 146, 160, 216,
  338, 339, 427, 242, 134), all superseded by the initials-JSON architecture (issues #119/#146/#160
  drift era predates #338's hardening).
- Registry `drift_confirmed: 0` unchanged (the closed-drift count predates the registry era;
  this pass confirms all surfaces healthy).
- No finding issues filed this run; `false_positives: []`.
- The year/UploadDate open question is closed by the in-run fix (no issue needed — nothing broke;
  the Data-completeness gap it flagged is now filled).

## Registry delta (audits/findings.json — Xhamster entry + run19 only)

- verdict `ok` (unchanged), tier `plain-curl`, surfaces all 200, `last_deep` **2026-10-16**,
  reason updated (year gap closed via `videoModel.created`; streams re-proven 5/5).

## Checks run before the PR

- `./gradlew bootstrapCloudstream` + `Xhamster:test` red → green, `Xhamster:make` → `.cs3` built
- `clean` + build → SUCCESSFUL
- `.pi/skills/verify-provider/scripts/verify.sh` against the live site (transcript above)
- `scripts/check-findings.sh` (structural findings lint) — PASS
