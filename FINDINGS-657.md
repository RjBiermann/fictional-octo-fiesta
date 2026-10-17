# FINDINGS-657 — Mangoporn (mangoporn.net) deep pass + confirmed-condition fixes

Run: 2026-10-17. Issue **#657** (sub-issue of epic #631, "Audit and probe all providers"). Self-contained run: full-surface probe with verdict vocabulary, then in-run TDD-first fixes of every confirmed condition. **Verdict: `ok-drift`, tier `plain-curl` (embeds TLS; vidara chain runner-resolvable), drift_confirmed 3, fixed in-run — version 7 → 8.**

## Phase 0 — calibration

Canaries **3/3 match** (`audits/canaries.json`, last_verified 2026-10-16 → this run):

| canary | expected | observed |
|---|---|---|
| film1k-challenge-pair | plain 403 / TLS 200 | 403 (plain) / 200 (curl_cffi chrome) ✔ |
| eporner-healthy | plain 200 | 200 ✔ |
| pandamovies-redirect-origin | plain 302 / TLS 200 @ .org | 302 (plain, no-follow) / 200 @ pandamovies.org ✔ |

Instrument healthy; no environment-degraded downgrade.

## Probe evidence (escalation ladder: plain curl → TLS-impersonated; browser never needed)

All plain-curl unless noted. Fresh captures saved to `/tmp/gh-aw/agent/probe/` (fixtures) and `/tmp/gh-aw/agent/verify/` (verify inputs).

### Home pagination (p1/p2 disjointness — the drift sentinel)

- `GET /` → 200, main listing = `section.hb--main > div.grid--mc`: **27 cards**; PLUS a rotating featured widget `section.sec > div.grid--blk`: **11 cards** (card__th markup identical to main cards)
- `GET /page/2/` → 200, 27 cards, all main-grid
- **p1(all-cards) ∩ p2 = 4** (bbc-pie-17, big-white-tits-large-black-dicks-14, blacked-raw-v110, blondes-addicted) — all 4 live in the p1 widget row and **rotate onto p2's main grid** → whole-page `article.card` parsing (the #564 grammar) recreated them on both pages = **CONFIRMED DRIFT A**
- Widget-row cards partially shadow same-page main-grid cards too (devil-in-her, desperate-anal-milfs-7, darkkos-hot-fucks-2, cock-hungry-milfs-get-their-holes-filled, cougar-seductions-3 appear in BOTH rows today) — homeCards' href-dedupe absorbs those; the across-page class is what duplicates results
- Main-grid-only sets: p1∩p2 disjoint ✔ (verify.sh dedupe PASS after the fix)

### Search

- `GET /page/1/?s=czech` + `/page/2/?s=czech` → 200/200, 27+27 cards, disjoint, `a.next` present; search cards sit directly in `div.grid--mc` (same grammar as home main list)
- **1-result search shape (NEW, grammar-binding)**: `GET /page/1/?s=boundless%20encounters` → 200, ONE card in a **bare `div.grid`** (no `grid--mc` modifier!) + **one site-native promoted card** (`yamo-v88`, structurally identical `article.card`, absent from multi-result grids and home grids). Adjudicated **site shape**: the skeleton's own relevance-grid fallback. Test pins both cards (`singleResultSearchStillParses`) so grammar changes break loudly:
  - multi-result grids: `class="grid grid--mc"`
  - 1-result grids: `class="grid"` (+ promoted card inside)
  - home widget row: `class="grid grid--blk"`
  - → binding grammar **`div.grid:not(.grid--blk) article.card`**
- `/page/2/?s=boundless%20encounters` → 404 (single result; provider search just returns the p1 rows)

### Quick search (turbosearch)

- `GET https://mangoporn.net/wp-json/turbosearch/v1/suggest?q=anal` → 200, valid posts JSON (title/url/thumb/meta fields) — unchanged from #564; `suggestPosts()` unit-tested (blank thumb → null). JSON surface: not fed to verify.sh's HTML-card harness (FINDINGS records it; unit fixture covers the contract).

### Video pages (4 fresh)

`GET /movies/{club-vxn-15, boundless-encounters-france, cougar-seductions-3, big-white-tits-large-black-dicks-14}/` → 200 ×4. Grammar:

- title: breadcrumb `span.bc__c` (e.g. `Boundless Encounters – France`), h1.vid__t fallback carries **"Now Playing: "** glue (backlog note, not fixed in-run)
- JSON-LD `VideoObject`: `duration` ISO-8601 (`PT2H48M` = 168 min) ✔; `thumbnailUrl` **array-form** (handled)
- **embeds: `video-1 > div[data-servers]`** (hlm--dark hlm--poster section) — JSON attr `data-servers` with per-server `u` iframe links + `data-default` index. Fresh page host sets:
  - club-vxn-15: **vidara.so (default)**, luluvido.com, playmogo.com, voe.sx, mixdrop.ag
  - boundless-encounters-france: playmate.to, playmate.to, playmogo, ..., vidara.so present
  - cougar-seductions-3 / big-white-tits: **vidara.so first (default)**, luluvido, playmogo
  - convincing-stepmom: playmate, playmogo, luluvido, voe, mixdrop (**no vidara** — per-page host rotation)
- **CONFIRMED DRIFT C** (load rows): `div.dp__panel[data-panel=details]` and `div.vid__txt` — **0 live hits** (whole-file searches) → old parse = plot null, actors empty. Actually live: **`div.row--stars > div.chips > a.chip.chip--star` ×12** (Agatha Vega, Julia Grandi, …) and plot paragraphs in **`div.desc p`** (`Club VXN Vol. 14 …`). Info rows render: Pornstars / Studio / Release Year / Genres / Category / Director / Released. Year OK document-wide via `/year/` anchor ("2024"); tags document-wide via `/genre/`.

### Stream / embed resolution

- **vidara.so (NEW default host — CONFIRMED DRIFT B**: shared `Vidara` registered only `vidara.to`; `loadExtractor`'s `mainUrl`-dispatch (startsWith host match) would drop the default host's links entirely):
  - chain: `POST https://vidara.so/api/stream` `{"filecode": "<fc>", "device":"web"}` (plain POST, no TLS shim) → `{"streaming_url": "https://s1-z20.97bf1.com/hls/<...>/master.m3u8?token=..."}` → `GET` master.m3u8 → **200 `application/vnd.apple.mpegurl`**
  - verified **4/4 fresh** pages (club-vxn-15, boundless-encounters-france, cougar-seductions-3, big-white-tits-large-black-dicks-14 — distinct files s1-z20/s11-t)
  - fix: `open class Vidara` + `VidaraSo : Vidara()` (vidara.so) mirror row registered in shared HostRegistry (`"Vidara" to 2` allowlist pin consciously updated)
- **luluvido.com (TLS tier)**: embed page plain OK via TLS-impersonated curl (cloud challenge on plain from datacenter); packed **Dean-Edwards** JW config unpacked → master.m3u8 on **rotating tnmr.org edge hosts**: `s3vzcp6anxcc.tnmr.org/hls2/...` → 403, `7db6af2i1kzb.tnmr.org` → 403, `cdn1016.cdn-tnmr.org` → 400 (`hls2/03/04322/wwcv90oaclo9_h/80.m3u8`). **Error pages, not challenges** → stays in the ok-suspected exclusion band (datacenter edge-reputation class; host sessions rotated cdn1009 → cdn1016 → subdomain-style hosts within one run — evidence the edge is up but filter-banning this vantage). Provider keeps luluvido factual.
- playmogo.com / playmate.to / mixdrop.ag embed pages: 200 from runner (adapter-covered in shared/framework), voe.sx: 200 interstitial (755 B shell)
- supplemental evidence (not in primary overrides): playmogo/mixdrop/playmate pages serve their own player flows — covered by shared adapters registered at `registerHostExtractors()`

## Fixes in-run (TDD-first, red→green; version 7 → 8)

1. **Parser A** — `MangopornParse.mainCards()` = `SearchCard.homeCards(doc, "div.grid:not(.grid--blk) article.card", ".card__t a", hrefSel="a.card__th", posterSel="img.card__img")`. getMainPage + search bind to it. Tests: 27-card main grid both fixtures & p1/p2 disjointness, widget exclusion (boundless/bbc-pie-17 absent from row), heading-tag variance (h3/h2/h5), old-grammar-matches-nothing guard, 1-result shape (2 cards incl. promoted yamo-v88).
2. **shared/ B** — `open class Vidara`, `VidaraSo` mirror (`mainUrl = "https://vidara.so"`), HostRegistry row + allowlist. Test: `vidara so mirror row serves the vidara family`, registry uniqueness/host-shadowing/collision pins all green.
3. **Load rows C** — `MangopornParse.actors()` (`div.row--stars a.chip--star`), `plot()` (`div.desc p`), load() rewired; dp__panel/vid__txt dead lookups removed; year `/year/`, tags `/genre/` fallbacks retained. Tests: 12 actors + exact contains, plot paragraphs (starts `"Club VXN Vol. 14."`), absent-row → empty (no throw).

Gates: `gradlew Mangoporn:test` **73/0** green (per-provider run includes the shared suite); full clean `test`+`make` across all 26 providers green; module version 7 → 8 (only Mangoporn — additive shared row).

## verify.sh (Pipeline Verification vs live site) — RESULT: PASS

Full transcript in the run log; key lines:

```
── check 1: search pages (1)
GET https://mangoporn.net/page/1/?s=boundless%20encounters → 200; 'div.grid:not(.grid--blk) article.card' matches: 2
── check 1a: homepage pages (2)
GET https://mangoporn.net/                    → 200; ... matches: 38   ← instrument lower-bound count artifact (extraction exact 27)
GET https://mangoporn.net/page/2/             → 200; ... matches: 27
── check 1b: quick search (0) NOTE: FINDINGS records turbosearch (JSON surface; unit-tested)
── check 2: video pages (4 URLs) — all 200; section.hlm[data-servers] matches 1/1
GET stream (…97bf1.com/.../master.m3u8…) → 200 application/vnd.apple.mpegurl   ← ×4 (distinct, s1-z20/s11-t + tokens)
field 'tags': present on all 4 sampled pages
field 'actors': present on all 4 sampled pages
field 'year': present on all 4 sampled pages
NOTE: no --video-duration-selector — exposure not asserted (FINDINGS states duration lives in JSON-LD VideoObject; unit-tested PT2H48M→168min)
── check 5: search ↔ load agreement   (exercised: sampled video in search rows; title+poster agree)
── check 6: LoadResponse completeness (code vs FINDINGS)
RESULT: PASS
```

Two instrument adjudications during the run (both fixed by re-invocation, no provider code impact):
1. `--search-title-selector ".card__t a"` — pydom can't compile tag-less first parts; switched to tag-qualified `h2.card__t a` (provider jsoup selector stays heading-agnostic).
2. `--video-title-selector` og:title carries the `Free - Juicy Full Porn Movie - MangoPorn` wrapper suffix — exact-agreement check 5 mismatch; switched to `span.bc__c` (breadcrumb = exact card title).
3. (recorded, informational) pydom chain count prints the lower-bound 38 for `div.grid:not(.grid--blk) article.card` on home p1 while extraction/dedupe is exact (27+27 rows, 0 dup FAILs) — pre-documented pydom limitation ("scoped counts are a lower bound").

## Backlog (not fixed in-run)

- h1.vid__t fallback title carries `"Now Playing: "` glue — breadcrumb span.bc__c is primary; strip the glue if the fallback ever becomes load-bearing.
- tnmr.org edge rotation makes the luluvido chain vantage-fragile — if it hardens into a confirmed condition, the fix lands in shared/ (rotating-edge resolution), never per-provider.

## Registry deltas

- findings.json: Mangoporn verdict ok → **ok-drift**, drift_confirmed 2 → **3**, standing_issue 525 → null, last_deep → 2026-10-17; one `last_runs` entry (**run24**); findings-lint PASS.
- Version: Mangoporn 7 → 8.
