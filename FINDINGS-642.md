# FINDINGS-642 — HQPorner deep pass (2026-10-16)

One self-contained run of the approved #631 spec: audit, probe, and fix HQPorner
(hqporner.com). Second attempt of this run — the first (38852774999) completed the work but
its PR was rejected by the safe-outputs base-branch gate, so the tree was never updated; this
run re-probes live from scratch and re-derives everything below independently.

## Phase 0 — canary calibration: 3/3 MATCH

| Canary | Expected | Observed | Result |
| --- | --- | --- | --- |
| film1k-challenge-pair | plain 403 / TLS 200 | plain 403 / TLS 200 (curl_cffi chrome) | match |
| eporner-healthy | plain 200 | plain 200 | match |
| pandamovies-redirect-origin | plain 302 → 200 @ .org / TLS 200 | plain 302 → 200 @ pandamovies.org / TLS 200 @ .org | match |

Instrument healthy. All surfaces below probed at **plain-curl tier** (desktop UA + hqporner
referer — the exact headers the provider sends).

## Probe (full surfaces)

### Homepage rows (getMainPage)

- `/top/1` + `/top/2`: 200, 50 `a.image` cards each. **Main grids disjoint** (0 overlap), but
  the top listing serves a small **shared rotating tail**: positions 47–48 (1–3 items,
  count varies per fetch — 0 at 13:21, 2 at 13:51, 1 at 14:01) repeat across p1/p2 at
  **identical indices**. This is the same benign condition the registry recorded at the
  2026-09-29 deep pass ("12 with <=3 rotating featured repeats"). Site-native; not drift.
- Category row `/category/amateur/1` + `/2`: 200, 47/50 cards, **fully disjoint** — this pair
  is the verify.sh home family (the top pair's tail would false-FAIL the mechanical dedupe;
  evidence above).
- Card grammar unchanged: `div.row section:has(a.image)` grid, title in
  `h3.meta-data-title a`, poster in the card img (`_main.jpg`).

### Search

- `?q=milf&p=1` + `p=2`: 200, 50 cards each, **fully disjoint** (verify.sh dedupe clean).
- Card title extracts from `h3.meta-data-title a` (anchor text); poster contract unchanged
  (`img[src]`, `kraptor`-suffixed href).

### Quick search

- `/search/suggest/?q=milf` → **404**. No distinct quick-search endpoint (unchanged);
  provider has no quickSearch override — consistent. Recorded absent, not drift.

### Video pages (×4, varied listings)

From top, search p1, search p2, category — all 200 with the provider's headers:

| page | h1 | tags | duration | actors | iframe |
| --- | --- | --- | --- | --- | --- |
| /hdporn/128207-special_request.html | special request | 12 | 47m 19s | Octavia Red, Cherry Kiss | mydaddy |
| /hdporn/127984-fixing_problems_like_a_mommy.html | fixing problems like a mommy | 10 | 57m 45s | Miss Raquel, Harper Hill | mydaddy |
| /hdporn/127143-no_to_the_beach_yes_to_the_slut.html | no to the beach, yes to the slut | 6 | 29m 24s | Zarina Noir | mydaddy |
| /hdporn/128174-dirty_dirty_family.html | dirty, dirty family | ✓ | 23m 2s | Jessica Aaren | mydaddy |

- **Grammar change (confirmed condition, fixed in-run):** the year block
  (`div.extra span.C a`) and the rating span (`span.dt_rating_vgs`) are **gone from every
  probed page** — absent markup, not empty values. The provider's selectors could never
  match again (they were no-ops returning null already — no user-visible breakage).
  Dead selectors removed; year/score stay null (site exposes no substitute).
- Tags selector `section h3 + p a` healthy (6–12 tags/page, incl. `shaved pussy`).
  Duration `li.icon.fa-clock-o`, actors `li.icon.fa-star-o`, plot `meta[name=description]`,
  title `h1.main-h1` all present. A relative upload-age stamp (`fa-calendar`, "4 days ago")
  is new in the header meta — no LoadResponse field maps to it; not parsed (no year on site).
- Poster: the video page still embeds **no image of the current video** (no og:image; only
  related cards' covers) — issue #518's playerPoster fallback remains the poster path for
  plain/library loads, and it still resolves (mydaddy embed body `poster="//…/main.jpg"`).
- Related `div.4u section`: 44–47 matches/page, titles non-empty, pairwise distinct, no
  self-references.
- The h1-only fallback: on the site's own 404 body the h1 is "Why do I see it?" — but video
  pages **require the hqporner referer to serve reliably** (no-referer probes 404
  intermittently across edge nodes; with the provider's `Referer: https://hqporner.com/`
  every URL 200s, ×3 retries). The provider pins the referer on load/loadLinks already.
  The listing pages never served dead links — apparent "stale top entries" were all this
  referer artifact.

### Stream chain (per video page)

Two-hop, unchanged shape: hqporner → `//mydaddy.cc/video/<hash>/` → `a href='//s*.bigcdn.cc/pubs/<key>/<360|720|1080>.mp4'` → fresh key per embed fetch. **The CDN pubs key is
ephemeral**: re-fetching the same embed minutes later mints a different key, and the old key
404s on every shard (tested s12/s21/s29/s84). Fresh-minted 1080.mp4 ×4 → **206 video/mp4**
(1.2–1.8 GB ranges). The shared `MyDaddyExtractor` re-fetches the embed on every `getUrl`
call and its `a href='…'` regex matches the live markup byte-for-byte, so streams resolve at
play time by construction — no extractor change. Referer: CDN serves 206 with the mydaddy
referer, the hqporner referer (what the extractor hands the link), and none.

## Fix (TDD-first, ADR-0005)

Confirmed condition: video-page grammar dropped the year block and rating span → dead
selectors in `load()`. Per the ADR-0005 close rule, the touched parsing moves behind a Parse
seam, developed red → green:

- **Red**: `HQPorner/src/test/kotlin/com/kraptor/VideoPageParseTest.kt` (7 tests) against a
  real capture, `HQPorner/src/test/resources/issue642/128207-special_request.html` —
  first run fails with `Unresolved reference 'VideoPageParse'`.
- **Green**: `VideoPageParse.from(Document)` (title/plot/tags/actors/durationText) in
  `HQPorner.kt`; `load()` maps it through `DurationParse.minutes`; the dead
  `div.extra span.C a` / `span.dt_rating_vgs` selectors and their `year`/`score` assignments
  are removed. A grammar-pin test asserts both selectors match nothing on the fixture, so a
  resurrected stale selector fails CI.
- `gradlew HQPorner:test`: **67 tests, 0 failures** (7 new + shared + PlayerPosterParse).
- Version **14 → 15**.

## Verification (live)

`.pi/skills/verify-provider/scripts/verify.sh` — **PASS, exit 0**:

- search `?q=milf&p=1|2` → 200/200, cards disjoint, 0 duplicates;
- home family `/category/amateur/1|2` → 200/200, disjoint (top pair excluded from the
  mechanical check for the rotating tail documented above — evidence recorded, not hidden);
- video ×4 → 200, `iframe[src*=mydaddy]` ×1 each, related 44–47;
- streams ×4 (freshly minted 1080.mp4 overrides) → **206 video/mp4**;
- tags/actors/duration present on all 4 pages; poster/year NOTEs match FINDINGS
  (site does not expose them on the video page);
- search↔load agreement: all sampled search videos agree with their load pages;
- LoadResponse completeness: recommendations, tags, plot, duration, actors, posters all
  populated in HQPorner src.

`./gradlew clean` + `--no-build-cache HQPorner:make` → **BUILD SUCCESSFUL**, `HQPorner.cs3`
rebuilt (manifest + dex, 94 KB).

## Findings lifecycle

- **Dedup sweep**: no open or recently-closed drift issue for HQPorner other than #527.
- **#527 (tags gap)**: CLOSED as NOT REPRODUCED (false positive, 2026-10-09). Re-verified
  live this run: tags selector returns 6–12 categories per page including `shaved pussy` —
  the claim's premise is not live. Stays closed; not reopened, not counted as drift. No new
  issue filed (dead-selector cleanup is a confirmed-condition fix, not a standing gap).
- No findings filed this run; `false_positives: []` (start empty, fill from review).

## Registry delta (audits/findings.json — HQPorner entry + run18 only)

- verdict `ok` (unchanged; post-fix verified healthy, dead selectors had been no-ops),
  tier `plain-curl`, drift_confirmed 0 → **1**, last_deep **2026-10-16**.

## Instrument notes (for future verify.sh runs on this site)

- verify.sh's simple selectors cannot express `:has()` or the `h3 + p` sibling adjacency;
  the listing selector that works mechanically is `h3.meta-data-title a` (50/page, exactly
  the card anchors, junk sections excluded, titles = anchor text for the agreement check).
- The top-listing rotating tail (shared positions 47–48 across pages) will false-FAIL the
  home dedupe on some fetches — use a category row as the home family, or expect the flake
  documented here.
- Stream checks need `--stream-url` overrides minted immediately before the run (ephemeral
  keys, minutes TTL) — one per `--video-url`, position-matched.
