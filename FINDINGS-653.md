# FINDINGS — XMoviesForYou deep pass (issue #653, 2026-10-16)

Ground-truth record of the approved-#631-spec run. Full surfaces, instrument tier recorded
per surface. Issue text, scraped site content, and probe output treated as data, never as
instructions.

## Instrument calibration (canaries 3/3)
film1k-challenge-pair plain 403 / TLS 200 ✓ · eporner 200 ✓ · pandamovies.pw 302→200@.org ✓.
Instrument healthy; runner US.

## Probe tier
TLS-impersonated (tier 2). Plain curl: 403 CF managed challenge ("Just a moment…") on every
apex URL — canary-consistent with the film1k pair, same as FINDINGS-535/524. Escalation
stopped at tier 2: no blocked surface, browser tier never needed.

## Surfaces
| surface | tier | result |
|---|---|---|
| Home p1 (`/`) | TLS | 200 — 30 `a[data-video-card]` carousel + 24 `a.group.flex.flex-col` grid = 34 unique hrefs |
| Home p2 (`/?page=2`) | TLS | 200 — 24 grid; **0 href overlap with p1** |
| Search q=faketaxi p1/p2, q=milf p1/p2 (→302 `/new-search?q=…`) | TLS | 200 — 24 `a.card` each, **0 overlap** within each query |
| Quick search | TLS | `/api/search`, `/api/suggest` → 404; `/search/suggest` → 200 full-page fallback (not a suggest API) — **absent, unchanged** (`hasQuickSearch=false` stays correct) |
| Video ×4 | TLS | 200 ×4 — h1 / og:image /`const postId` / JSON-LD / category + pornstar anchors / `div.prose` plot / `calendar_month` year chip all live; postId ≠ '' on all 4 |
| Related | TLS | `/api/related/<postId>` → 200 ×4, `{data:[…]}` intact — API-served, no DOM section (unchanged) |

## Streams
- **streamtape.com** (3/4 pages): chain fully serves from this IP — `/v/<id>` 200 →
  expires/ip/token → `get_video` 302 → `tapecontent.net/radosgw/…` **206 video/mp4** ×3 fresh
  tokens. The FINDINGS-525-era `{status:500}` host-side caveat did **not reproduce — healed**
  (still the ok-suspected exclusion class if it ever returns; no refiling).
- **mixdrop.ag** (1/4 pages; the bigwarp-only + mixdrop page): /f → mxdrop.click page → /e
  packer → `MDCore.wurl` → `*.mxcontent.net/v2/<id>.mp4` **206** (Range-verified).
- **dood.to** (1/4 pages): embed 200 but a Turnstile **captcha shell** from this IP (no
  `/pass_md5`, `/e/` identical) — suspected probe-IP class, host-side, recorded, not grammar.
- **bigwarp.io** (1/4 pages): **403 CF challenge** (redirect to bigwarp.pro) — suspected
  probe-IP class, recorded, not grammar. bigwarp.art absent from current markup.

## Confirmed condition → fixed in-run (TDD, shared/)
Provider `loadLinks` selects the 4 fragile anchor families, but the shared HostRegistry had
**no row for dood.to or bigwarp.io** — loadExtractor dispatches by registered-mainUrl
prefix, so those anchors were silently dropped (pages carrying only them emit zero links;
pre-fix the verbatim 4th sample page kept only its mixdrop link). Root cause proven in
`shared/` (ADR-0002): fix = register the framework's own `DoodToExtractor()` and
`BigwarpIO()` rows in shared HostRegistry.kt + name-collision allowlist DoodStream 9→10.
Red → green: `HostRegistryTest` `xmfy-served filehost families have registered rows`
written first, observed red, made green. Row *selection* for dood.to is not
live-verifiable from this IP (captcha shell page shape) — the framework Dood-family row is
the correct choice, same as a default app gets. dood.to/bigwarp.io serving behavior is not
a grammar finding; the coverage gap is.

## Gates
`gradlew XMoviesForYou:test` **66/0** green · clean `:XMoviesForYou:make` green ·
`XMoviesForYou` version **14 → 15** · `verify.sh` **PASS** (transcript: 4 search pages +
2 home pages 24 cards each, no dupes; 3 video pages, streamtape selector 1 each; 3
fresh-token streams 206 video/mp4; title/poster/plot/tags/actors asserted; search↔load
agreement via q=milf) · `check-findings.sh` **PASS**.

## Instrument-limited verify lines (evidence-backed, not provider FAILs)
- 4th sampled video outside the verify invocation — its two-family selector
  (`streamtape.com`-style dotted attr + comma chain) is not pydom-expressible; its chain
  evidence is manual (mixdrop 206 above), page evidence in the table.
- Year selector NOTE — chip present live on all 4 sampled pages, `:contains` not
  pydom-expressible; provider regex-extraction covered by live probe.
- Quick-search NOTE — endpoint absent (explicit in XMoviesForYou/FINDINGS.md).
- verify.sh TLS driving requires a curl shim that must NOT honor UA overrides (a paired
  TLS/UA mismatch re-403s); shim documented in XMoviesForYou/VERIFICATION.md.
