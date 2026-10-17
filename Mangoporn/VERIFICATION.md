# Pipeline Verification — Mangoporn (issue #443)

Live checks against https://mangoporn.net (2026-02-14, plain curl UA Firefox/144).
Review-repair round: mirror rows + MangopornVidguard deleted, first list reduced
to CloudWish+Maxstream, Parse functions extracted behind the tests (see FINDINGS).
Fixtures: `/tmp/verify_home.html`, `/tmp/verify_movie.html`.

| check | result |
|---|---|
| Home `GET /` | 200; `div.items > article` count = **36** ✓ |
| Poster lazy-load attr `data-wpfc-original-src` present | 37 hits ✓ (provider's poster fallback needed) |
| Load `GET /movies/cum-gushers/` | 200; `#pettabs` (11 refs) + `#playeroptionsul` (2 hosts of container) ✓ |
| Category page `/movies/page/2/` | 200, 36 articles ✓ (probe earlier in run) |
| Genre page `/genre/asian/` | 200, 36 articles ✓ (probe earlier in run) |
| Search `/?s=_cum` page page-1 | 200, 36 result articles ✓ (probe earlier in run) |

Build gates:
- `./gradlew Mangoporn:test` — **green** (50 tests incl. MangopornParseTest, shared suites run through the splice)
- `./gradlew Mangoporn:make` — **green**; `Mangoporn/build/Mangoporn.cs3` produced (124 KB). D8 kotlin-metadata
  warnings are the known repo-wide non-fatal class (present for existing providers too).

---

# Pipeline Verification — Mangoporn update run (issue #657, 2026-10-17)

Deep pass + 3 confirmed-condition fixes (parser grammar, shared vidara.so row,
load-page row bindings). Fixtures under `src/test/resources/` refreshed from
live captures before each red:

| check | result |
|---|---|
| `./gradlew Mangoporn:test` | **green** — 73 tests / 0 failures (incl. shared suite: vidara.so row, registry pins) |
| full clean `./gradlew test make` (26 providers) | **green** (D8 kotlin-metadata warnings = known repo-wide non-fatal class) |
| verify.sh home p1/p2 `div.grid:not(.grid--blk) article.card` | 200/200, exact 27+27 extracted, 0 cross-page dups (widget row correctly excluded — drift A fixed live) |
| verify.sh search (1-result shape) | 200; 2 cards (query match + site-native promoted yamo-v88 — pinned) |
| verify.sh video ×4 | 200 ×4 (`section.hlm[data-servers]` matches 1/1); tags/actors/year present on all |
| verify.sh streams ×4 (vidara.so → 97bf1 m3u8) | **200 `vnd.apple.mpegurl` ×4** — chain: `POST vidara.so/api/stream` `{filecode, device:web}` → `streaming_url` |
| check 5 search↔load agreement | exercised, title+poster agree (bc__c selector = exact card title) |
| verdict | **RESULT: PASS** |

Full evidence: root `FINDINGS-657.md`.
