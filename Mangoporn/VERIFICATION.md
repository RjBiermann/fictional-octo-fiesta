# Pipeline Verification — Mangoporn (issue #443)

Live checks against https://mangoporn.net (2026-02-14, plain curl UA Firefox/144).
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
