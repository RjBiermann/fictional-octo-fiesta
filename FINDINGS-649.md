# FINDINGS-649 — WatchPorn deep pass (2026-10-16)

One self-contained run of the approved #631 spec: audit, probe, and fix WatchPorn
(watchporn.to). Everything below re-probed live this run; registry entry
(`audits/findings.json` → `providers.WatchPorn`) updated only for this provider.

## Phase 0 — canary calibration: 3/3 MATCH

| Canary | Expected | Observed | Result |
| --- | --- | --- | --- |
| film1k-challenge-pair | plain 403 / TLS 200 | plain 403 / TLS 200 (curl_cffi chrome) | match |
| eporner-healthy | plain 200 | plain 200 | match |
| pandamovies-redirect-origin | plain 302 | plain 302 (→ 200 @ pandamovies.org) | match |

Instrument healthy. Every WatchPorn surface below probed at **plain-curl tier**; the
TLS/browser escalation steps were never reached — no blocked surface anywhere. Runner
IP is a US datacenter; ddos-guard serves the site with no challenge for the desktop
UA the provider uses.

## CONFIRMED DRIFT — full theme redesign (fix condition of this run)

The site shipped a new KVS theme with self-built "wp-" classes that killed every card and
video-page selector the v15 provider carried (grammar from the #214/#278/#314 era):

| surface | old selector (dead — 0 matches live) | new selector (live) |
| --- | --- | --- |
| card title | `span.thumb__title` | `a.wp-ctitle` |
| card link | card-root `a[href]` | `a.wp-card__link[href]` |
| card poster | `img[data-webp] ?: img[src]` (src now a 1px gif placeholder on most cards) | `data-webp ?: data-original ?: non-data src` |
| card rating | `span.thumb__meta-item` | `span.wp-r` ("90%") |
| search pagination sentinel | `li.next` | `li.wp-pag__next` |
| video title | `h1.single__content-title` (→ load() returned null) | `h1.wp-vi__title` |
| tags | `div.single__info-row:contains(Categories:) a` | `a.wp-vchip` |
| actors | `div.single__info-row:contains(Models:) a` | `a.wp-cast span.wp-cast__n` |
| plot | `p.single__content-description` (paragraph removed) | **not exposed** — ld+json/og:description carry tag-glue or site boilerplate |

Net effect at probe time: search and homepage parsed **0 cards** and `load()` returned
null for every video — complete breakage. Drift condition count 0 → 1 (single root cause:
theme migration; all dead selectors belong to it). Listed surfaces otherwise healthy.

**Fix (TDD-first, ADR-0005):** pure `WatchPornParse` object inside the provider directory
(no shared/ change — the "wp-" grammar is WatchPorn-specific). Every lookup keeps its
legacy-theme selector as a fallback for a partial rollout. `WatchPornThemeParseTest`
red → green against new live fixtures `WatchPorn/src/test/resources/search-milf-1.html`
(39 wp-cards + 1 ad card) and `video-136954.html`; the legacy fixture `video-28447.html`
stays green through the fallbacks (partial-rollout guard asserted). Plot assignment
removed (site does not expose a synopsis; the old og `p.single__content-description` now
matches nothing). version 15 → 16. `gradlew WatchPorn:test` 150/150 green; clean
`gradlew WatchPorn:make` green.

## Probe (full surfaces)

- **Home**: `/top-rated/` + `/top-rated/2/` → 200, 19 video cards + 1 identical ad card
  (`div.thumb.item.grid-ad`) each, video cards disjoint. Site rows: `/categories/<slug>/`
  now **301-redirects to `/sites/<slug>/`** (manyvids, onlyfans — 19 cards each on p1/p2,
  1/19 boundary repeat across pages on fresh fetches, within-page always unique).
  Overflow: `/top-rated/999/` → 200 clamped to a deep page (0 overlap with p1 — harmless
  for incremental getMainPage); search overflow `from_videos=999` → 404.
- **Search**: unchanged async URL (`from_videos=1|2`) → 200, 39 wp-cards + 1 ad card per
  page, p1/p2 disjoint (0 overlap). Card titles are a site-native short display form:
  performer prefix and studio suffix dropped ("Both MILFs Want To Fuck" vs og-title
  "Ryan Keely, Shay Sights - Both MILFs Want To Fuck - Milfed").
- **Quick search**: `/suggest/?q=<q>` → 200 JSON, 20 Models + 20 Videos — unchanged
  SuggestParse contract (fixture `suggest-deep.json`, WatchPornSuggestTest green). The
  verify.sh card harness cannot assert a JSON surface; live transcript above.
- **Video pages ×5** (58740, 110700, 144806, 142799 from search p1; 136954 from home p1):
  200; `h1.wp-vi__title` non-empty; og:image present; related section
  `div.related-videos div.thumb.item` intact (15 cards/page, no self-reference); JSON-LD
  VideoObject still carries duration/uploadDate (shared `JsonLdParse` minutes/year
  unchanged); `div.wp-vmeta` additionally renders "1:12:06" and `span.wp-hd` "1080p".
- **Streams**: flashvars contract unchanged (`video_url` 720p / `video_alt_url` 1080p
  get_file + v-acctoken) — the WebView extractor is untouched by the redesign. Live:
  token → 302 → `srv*.zload.cc/remote_control.php` → **206 video/mp4**; **no cookies or
  referer needed** — the 2026-09-10 "get_file 37-byte anti-leech gif for non-session
  replay" session-bound note did not reproduce (fresh cookie-less requests served 206).

## Verification

`verify.sh` (live, plain-curl tier): search p1/p2 40+40 and top-rated 20+20 and sites
rows 20+20 all fetch, ≥1 card, video cards disjoint; 5 video pages parse (title, poster,
related 15); tags/actors selectors present on all sampled pages; streams 5/5 serve
video (206 video/mp4) with distinct paths within and across pages; LoadResponse fields
recommendations/tags/duration/year/actors/score/posters all assigned in code. Four
**instrument-limited FAIL lines** (all evidence-backed, none a provider-grammar defect):

1. home/site-rows "duplicate home cards" — the ad card's identical CSS/JS text is the
   payload the script's home inner-text pass reads; video cards are disjoint. Every page
   carries the same exolabs ad slot.
2. cross-video `plot '-'` collision — `p.single__content-description` matches nothing
   (plot dead) and the script keeps an empty column as literal placeholder `-`.
3. search↔load title agreement — site-native short card title vs canonical og/h1.
4. poster agreement (58740-class: real `src` card) — card `336x189/1.jpg` vs og
   `preview.jpg`, same directory different render; placeholder-src cards skip the compare.

## Registry / lifecycle

- `providers.WatchPorn`: verdict `ok` → `ok-drift` (in-run fix awaiting merge), tier
  `plain-curl`, surfaces re-recorded, drift_confirmed 0 → 1, last_deep 2026-10-16,
  chronic false (0 prior drift issues), no standing issue, no finding issue filed — the
  condition is fixed in-run; README verdict column updated to match.
- Dedup: standing WatchPorn issues checked (open + recently closed: #36, #133, #162,
  #214, #278, #314 — all closed, none about the theme grammar). No false positives.
- Gates: `check-findings.sh` PASS · `gradlew WatchPorn:test` green · clean build green.

## Backlog (not this diff)

None. All observed conditions were either fixed in-run or are instrument notes whose
correction (verify.sh `data-original` poster support and empty-plot placeholder
exclusion) is shared-script surface — proposed, not actioned, under the #631 epic.
