# FINDINGS — xmoviesforyou.com (deep pass, issue #653 — 2026-10-16)

Probe of record for this run. Instrument tier: **TLS-impersonated** (tier 2 — plain curl is
CF-challenged on every URL, 403 "Just a moment…", canary-consistent; browser tier never
reached, no blocked surface). Canaries 3/3 match: film1k plain 403 / TLS 200, eporner 200,
pandamovies 302→200@.org.

## Engine fingerprint
Custom Next.js-style SSR app (same engine as the FINDINGS-535-era probes): static HTML
serves root-anchor cards; `const postId = "…"` inline constant; `/api/related/<postId>`
JSON sidecar for recommendations; filehost links are plain `<a target="_blank">` rows.

## Search
`GET /search?q=<q>` (302 → `/new-search?q=<q>`), page N via `&page=N`.
Card shape: `a.card` (rebuilt search grid) — title in the card's `h3` (carries the
`[Studio]` bracket prefix; the provider strips it), poster in the first `img`, link in
the root anchor's `href` (absolute paths).
Transcript (tier 2): `faketaxi` p1 24 cards / p2 24 cards, **0 href overlap**; `milf` p1 24 /
p2 24, 0 overlap. verify.sh check 1: 24 ×4 pages, no dupes, PASS.

## Quick search
**No distinct quick-search endpoint** (unchanged): `/api/search?q=` and `/api/suggest?q=`
answer 404 on the TLS tier; `/search/suggest?q=` serves the full unfiltered search page
(24k HTML), not a suggest API. Provider keeps `hasQuickSearch = false`. Recorded absent,
not drift.

## Homepage
Two live page-1 card shapes (both verified parseable):
- hero carousel `a[data-video-card]` — 30 anchors ×~15 cards, repeats items (34 unique hrefs)
- category grid `a.group.flex.flex-col` — 24 cards, page 2 = 24 cards, **0 href overlap** p1↔p2

Page 2: `/?page=2` (query param). verify.sh check 1a (grid row): 24 ×2 pages, no dupes,
PASS. Carousel is page-1-only and repeats — covered by the fixture tests
(`XMoviesForYouParseTest`, href dedupe in `Parse.cards`), instrument cannot express
per-page dedupe.

## Video pages
Sampled 4 (spec floor ≥3) from different listings:
| video | listing | h1 | fields |
|---|---|---|---|
| /realityjunkies-river-lynn-that-thick-booty | home p1 | `[RealityJunkies] River Lynn (That Thick Booty / 10.02.2026)` | og:image, postId 810555212479519, JSON-LD ×1, cats 1, stars 1, prose |
| /milfy-hime-marie-milf-seductress-needs-bbc | home p1 | `[MILFY] Hime Marie (… / 10.14.2026)` | og:image, postId 278735277954524, JSON-LD ×1, cats 3, stars 1, prose |
| /milfty-lindsay-lee-… | search q=milf p1 | `[Milfty] Lindsay Lee (… / 05.30.2026)` | og:image, postId 325316773424370, JSON-LD ×1, cats 1, stars 1, prose |
| /milfaf-justine-jakobs-the-missing-towel | search q=milf p2 | `[MilfAF] Justine Jakobs (… / 06.17.2025)` | og:image, postId 723603, JSON-LD ×1, cats 1, stars 1, prose |

Exposure inventory: title h1/og:title (og:title carries a ` - XMoviesForYou` suffix — the
provider correctly reads `h1`); poster og:image; plot `div.prose p`; tags
`a[href*='/category/']` (1–3); actors `a[href*='/pornstar/']` (1); year
`span.material-symbols-outlined:contains(calendar_month)` sibling chip present on all 4
(provider regex-extracts `\d{4}`); **duration: site does not expose** (unchanged);
JSON-LD `VideoObject` present (one block/page).

## Related videos
**API-served, no DOM section** (unchanged): `GET /api/related/<postId>` → 200 on all 4
sampled pages, JSON `{data:[{title,slug,thumbnail_url,…}]}`. The provider populates
`recommendations` from that API — no `--related-selector` exists to assert; covered by
check 6.

## Stream sources (per video page)
Filehost anchors are plain `<a>` rows; families observed:
- streamtape.com/v/<id> — on 3/4 sampled pages. **Chain fully serves from the runner IP,
  healed** vs the FINDINGS-525-era `{status:500}` host-side caveat: `/v/<id>` 200 →
  expires/ip/token → `get_video` 302 → `tapecontent.net/radosgw/...` **206 video/mp4**
  (Range-verified). All 3 fresh tokens resolved live (shared StreamTAPE adapter flow).
- mixdrop.ag/f/<id> → 200 (serves from mxdrop.click) → `/e/<id>` packer → MDCore.wurl →
  `*.mxcontent.net/v2/<id>.mp4` **206** (Range-verified) on the 4th page. Chain works;
  mixdrop.ag has a registered framework row (`MixDropAg`).
- **dood.to/d/<id>** on the 3rd page: embed 200 but the page from this IP is a Turnstile
  **captcha shell** (no `/pass_md5`, `/e/` identical) — probe-IP class, recorded
  suspected, not a provider-grammar finding.
- **bigwarp.io/** on the 4th page: **403 CF challenge** (redirects to bigwarp.pro, "Just a
  moment…") — probe-IP class, recorded suspected, not a provider-grammar finding.

## Headers / referer
No age gate, no cookie unlock. Site: plain UA/HTTP passes only with browser TLS
impersonation — the provider's in-app TLS stack (NiceHttp/okhttp) already passed site
SSR in-app (FINDINGS-535-era in-app confirmation). Filehosts do their own referer
checks inside the registered adapters.

## Pagination
Search: `&page=N` query param, fresh `/new-search…` URL served per page (transcript
above), both sampled queries disjoint p1/p2. Past last page behavior not re-probed
(fresh URL per N; p2 ≥ last page clamps to later-page cards observed far out).
Home: `/?page=2`, disjoint (above). Related: API rows, page 1 only.

## Risks / blockers
Cloudflare managed challenge on the apex for datacenter IPs at plain tier (TLS tier
passes; in-app TLS passes). dood.to Turnstile shell + bigwarp.io CF 403 from the runner
IP = suspected probe-IP class (residential differential is the decider; never a
standalone finding). US runner (canary-consistent).

## Confirmed condition fixed in-run (TDD)
The provider's `loadLinks` selects `a[href*='streamtape.com'], a[href*='mixdrop'],
a[href*='dood'], a[href*='bigwarp']`, but the shared HostRegistry had **no row for
dood.to or bigwarp.io** — loadExtractor dispatches by registered-mainUrl prefix, so
anchors of those two families were silently dropped (a page carrying only one of them
would emit **zero links**; the 4th sampled page would have emitted only its mixdrop
link before this fix, and the 1/29-class pages carrying only bigwarp/dood would be
dead to the app on any network). Root cause lives in `shared/` (ADR-0002) — fix =
register the framework's own `DoodToExtractor()` and `BigwarpIO()` rows (upstream-
maintained; row *selection* for dood.to is not live-verifiable from this IP — its page
shape here is a captcha shell — so the framework's own Dood-family row is the correct
choice, same as a default app gets). Red → green: `HostRegistryTest`
`xmfy-served filehost families have registered rows` (66 tests green after fix + row
allowlist DoodStream 9→10). Provider version 14 → 15.
