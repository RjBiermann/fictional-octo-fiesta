# Pipeline Verification — XMoviesForYou (issue #535)

Live checks against https://xmoviesforyou.com (2026-10-09), run via
`verify-provider/scripts/verify.sh`. Full transcript saved to the PR description.

**Instrument tier: TLS-impersonated.** Plain curl is Cloudflare-challenged (403, canary-
consistent with FINDINGS-524 run 8 and the Film1k precedent) on every URL. verify.sh was
driven through the same chrome-TLS impersonation the audit uses (a `curl` PATH shim backed
by `curl_cffi impersonate=chrome` — the `impersonate.sh` tier: same plain HTTP, browser TLS).
No cookies/age gate involved; NiceHttp's in-app TLS stack passes the site (confirmed by the
issue's own report that load() flows work in-app).

## One instrument touch (same run, reviewer-visible)

- `verify.sh` video-page plot extraction fell back to text like title already did
  (`[[ -z "$vplot" ]] && vplot=$(py field "$VIDEO_PLOT_SEL" text "$F")`): the site's plot
  lives in `div.prose p` text — there is **no content attribute carrying the plot**
  (`meta[property=og:description]` is empty on 4/5 sampled watch pages), and every plot
  extraction before this fallback returned '-' → collapsed into a fake cross-video plot
  collision FAIL. `verify_selfcheck.sh` PASS after the change.

## Result: PASS (all checks)

| check | result |
|---|---|
| Search `/search?q=faketaxi` + `&page=2` (→302 `/new-search…`) | 200; `a.card` 24/24; no dupes across pages ✓ |
| Home `/` + `/?page=2` (`a.group.flex.flex-col` grid row) | 200; 24/24; no dupes across pages ✓ |
| Quick search | FINDINGS records **no distinct endpoint** (404 on `/api/search`, `/api/suggest`) — NOTE expected |
| Video page ×5 (home-carousel, home-grid, search, category, related listings) | 200; `a[href*=streamtape]` ≥1 each; h1 title each ✓ |
| Streams ×5 (resolved via the shared `StreamTAPE` adapter's chain shape: `/v/<id>` page → `expires`/`ip`/`token` → `get_video` 302) | 206 `video/mp4`, 5 distinct `tapecontent.net/radosgw/…` paths ✓ |
| Field exposure | tags, actors, year present on all 5 (all-or-none met); duration NOTE — FINDINGS records **site does not expose duration** ✓ |
| Search ↔ load agreement | `/faketaxi-mackenzie-page-do-you-know-who-i-am` title (h1 == card h3) and poster path (card img == og:image) agree ✓ |
| LoadResponse completeness | recommendations, tags, plot, year populated (actors via `addActors`) ✓ |

Related videos: the related row is **API-served** (`GET /api/related/<postId>` → JSON with
`title`/`slug`/`thumbnail_url`; probed live in FINDINGS-535) — page DOM has no related
section, so there is no `--related-selector` to pass; the provider already populates
`recommendations` from that API (unchanged code path, check 6 assertion above).

**Hero-carousel addendum:** the `a[data-video-card]` carousel cards (page-1-only, repeated
items) are covered by live probe transcripts + the fixture unit tests
(`XMoviesForYouParseTest`) because verify.sh asserts raw selector counts and cannot express
the provider's per-page href dedupe (30 anchors → 10 unique live, deduped in `Parse.cards`).
Homepage pagination check above uses the paginating grid row, as FINDINGS-535 records.
