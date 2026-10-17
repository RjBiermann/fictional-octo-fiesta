# FINDINGS — freepornvideos.xxx (deep pass 2026-10-17, issue #643)

## Verdict: OK — tier `tls-impersonated` (instrument recorded per surface below)

Full-surface re-audit of the approved #631 spec (sub-issue of #631 epic). The #536
details-block condition was **confirmed live and already fixed on master** — all parsing
runs through the ADR-0005-tested `FreePornVideosParse` seam. **No code change this run;
version stays 13.**

## Engine fingerprint

KVS/Kernel: canonical KVS URL grammar (`/videos/<id>/<slug>/`, `/search/<q>/<page>/`,
`/latest-updates/<page>/`, `/categories/<slug>/`, `/networks/<slug>/`), `div.item` card grid,
`list_videos_*` block ids, `get_file/<token>/<dir>/<dir>/<id>/<file>.mp4/` stream paths,
KVS meta block. Evidence: homepage + video-page transcripts below.

## Probe ladder (per surface instrument tier)

| Surface | plain curl | TLS-impersonated | Verdict |
|---|---|---|---|
| home `GET /` | **403** `Just a moment...` (CF JS challenge) | **200** text/html 220 KB | 200 TLS |
| home page 2 `GET /2/` | not expected to work (CF) | **200** | 200 TLS |
| home rows `latest-updates/1/` vs `/2/` | — | **200** — 24×24 distinct video links, `comm -12` overlap **0** | paginate ✓ |
| search `/search/red/1/` and `/2/` | **403** | **200** — 24 `div.item` cards each page, overlap **0** | 200 TLS |
| video pages (4 sampled) | — | **200** (198–204 KB each) | 200 TLS |
| stream `get_file/.../` | — | **200** `video/mp4` (576 MB stream, verified `video/mp4`) | 200 TLS |

Cloudflare (datacenter-IP canary) serves 403 `Just a moment...` to plain curl on HTML
surfaces; TLS-impersonated Chrome fingerprint passes everywhere — consistent with the
2026-10-09/10 (run8/run13) and 2026-10-16 baselines. Not a site condition.

## Search

- Pattern: `/search/<slug>/<page>/` — provider's `search()` builds exactly this (`#533`
  page-field pagination). `search/anal/` 200 (181 KB); verified collision-free query set:
  `search/casting/1..2/` 200 (`#custom_list_videos_videos_list_search_result_items > div.item`
  cards, 24/page, p1/p2 disjoint). Card grammar unchanged (`shared/` `searchCard`,
  `a.thumb_title` + `img.thumb`, iframe-ad cards carry class `ourdreamai-native` —
  pydist expressed as `div.item:not(.ourdreamai-native)`). Recognized title-text collision
  pair on the generic `red` query (two distinct uploads, `…/` and `…-1/`, identical display
  title) — data population, not dupes.
- Page 2 verified distinct (24×24, overlap 0) on both earlier (`red`) and final (`casting`) runs.

## Quick search

No distinct quick-search endpoint found on current markup (no quick-search/suggest JS
references in homepage HTML). Provider keeps `hasQuickSearch = false`. Absent surface,
recorded — not drift.

## Homepage

Provider rows (`mainPage`): `/latest-updates/`, `/most-popular/week/`, `/networks/<slug>/`,
`/categories/<slug>/`, each `+ /$page/`. Verified:

- `/latest-updates/1/` vs `/2/`: 24×24 video links, overlap **0**.
- `/` vs `/2/` (mixed rows): 33×12 raw links, overlap 1 (pinned/featured placement —
  cross-row duplication among rotating sections; per-row pagination is disjoint, which is
  what the provider serves).
- Card selector `div.item` + `div.img iframe` ad-skip per #286 — still matches.

## Video pages (4 sampled, varied listings)

| # | Listing | URL | h1 (channel - model - title / date) | og:title = card text ✓ | poster | plot | cats in details | models in details | streams |
|---|---|---|---|---|---|---|---|---|---|
| 1 | search/casting/1 | `/videos/93377024/elizabeth-mrquez-in-casting-3-men-p2/` | ✓ | ✓ | ✓ | ✓ (starring form) | ✓ | ✓ | ✓ served |
| 2 | search/casting/2 | `/videos/93496834/molly-dae-casting/` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ served |
| 3 | latest-updates | `/videos/93821356/birthday-creampie-1/` | ✓ | ✓ | ✓ | ✓ | **0** (JS toggle) | 1 | 2160/720/480 |
| 4 | categories/vintage | `/videos/93796888/everyone-loves-destiny/` | ✓ | ✓ | ✓ | ✓ | 29 | 3 | 2160/720/480 |

All four carry the **#536 grammar live**: `div.block-details > div.info` cells with
`<span>Channel:</span> / Network: / Categories: / Pornstars:` labels, Categories often
inside `div.hidden_tags`,Pornstars with `a.btn_model[href*=/models/]` — exactly what
`FreePornVideosParse.cell/tags/actors` consume (models/both-href aliases stay supported).

Exposure inventory (per field, live-verified against Parse seam + verify.sh):

- title: `div.headline > h1` ✓ (channel-star-title-slash-date format; card text = og:title, which
  is exactly the search-card title — search↔load agreement PASS)
- poster: `meta[property=og:image]` ✓ (all sampled; search↔load poster path agreement PASS)
- plot: `meta[property=og:description]` — real on most videos; **population gap:** a large
  fraction (≈50% of sampled /search/casting/ cards) carry the literal placeholder
  `Default site description.` and no real description anywhere on the page (JSON-LD description
  is just "channel - title", details block has none — cookie-wall text only). Provider degrades
  honestly ("Default site description." is what the site exposes); not a parse defect.
- tags: details-block Categories cell ✓ on most — **population notes:** (a) some videos (sample
  #3, Karups) ship the Categories cell as a `js-show-tags` "+" toggle with *no server-rendered
  links* — tags honestly degrade to empty; (b) identical title-text collisions exist
  (`…2/` and `…-1/` = two genuinely distinct videos with the same title, e.g. "From lingerie to
  her red bed, Red Bunny pleases us"). Both are site-side, not grammar drift.
- actors: Pornstars cell ✓ (`a.btn_model`); selectForm's `div.info a[href*=models]` alias form —
  verify mechanical NOTE only (regex-DOM cannot express the comma-chain; FINDINGS carries the
  transcript evidence + live counts).
- duration: `meta[property=video:duration]` (seconds) + JSON-LD `VideoObject.duration`
  cross-agree on all sampled ✓ (#536); verify duration-selector check PASS on 4/4.
- year: JSON-LD `uploadDate` (FINDINGS states source; verify NOTE only — no flat year selector).
- Dead-video population note: some old videos serve `class="player_404"` with **no**
  `<video><source>` (sampled 93403140 — a removed DDF media), i.e. a real og-metadata page
  whose video is gone site-side.
- search↔load agreement verified PASS on 4/4 (og:title = card title, poster paths agree).

## Related videos

`#list_videos_related_videos_items > div.item` present on all 4 pages (provider
`recommendations` selector still matches). Transcript: block id + card markup in each
video-page fetch.

## Stream sources (per video page)

KVS direct `<video><source src='https://www.freepornvideos.xxx/get_file/<token>/<a>/<b>/<id>/<id>_<q>m.mp4/' type='video/mp4' label="...">` (verify.sh stream checks: **10/10 distinct paths → 206 video/mp4, referer-independent**; see table for sampled sets):

| Video | Sources (distinct paths) |
|---|---|
| 93377024 | ✓ served (casting/1 sample) |
| 93496834 | ✓ served (casting/2 sample) |
| 93821356 | 2160p / 720p / 480p |
| 93796888 | 2160p / 720p / 480p |

All reachable via page HTML (no embed/kt_player hop on current markup — the old #449-era
kt_player/get_file artifacts are gone; plain markup serves).

Content-type proof (TLS-impersonated GET on 93821356_720m.mp4): `HTTP 200/206`,
`content-type: video/mp4`, `content-length: 576400553`, streamed ≥256 KB live (full-body
576 MB stream also confirmed against the bare-GET path). Works **without** Referer and
with a bogus Referer → referer-independent.

Verify result: `.pi/skills/verify-provider/scripts/verify.sh` **PASS** — 2 search pages p1/p2
distinct (24 cards each), 2 home rows p1/p2 distinct, 4 video pages, streams all 200/206
`video/mp4`, related-videos selector resolves, search↔load agreement PASS, LoadResponse
completeness PASS. The transient-403 challenge surfaces occasionally on runner bursts and
resolve on retry — recorded as Risks, not drift.

## Headers / referer

None required beyond normal browser UA-TLS: HTML surfaces are fingerprint-walled (datacenter
plain-curl 403), stream endpoints serve to any Referer. No age gate, no consent cookie. No
geo-wall observed from runner IP.

## Pagination

- search: `/search/<q>/<page>/` path-based ✓ (p2 distinct)
- home rows: `/<row>/<page>/` path-based ✓ (latest-updates p2 distinct)
- past last page: not clamped (site truncates listing content); provider `hasNext=true`
  per KVS convention, empty-grid apps render no rows.

## Risks / blockers

- **Cloudflare on datacenter IPs** for plain HTTP clients on HTML routes (stream routes
  unblocked). In-app (residential, NiceHttp TLS) is the runtime client class — same class
  that passed 2026-10-09/10/16 baselines. `Suspected`/`Blocked` from runner probes is
  canary-consistent and never a standalone finding (#449/#466 remain `suspected_excluded`).
- Probe region: runner (US datacenter).

## Findings lifecycle this run

- Standing issue **#536** (details-block rebuild) — confirmed live, **fix already on
  master** (`FreePornVideosParse.kt` + version 13). Registry pointer stays #536; no new
  issue filed. Not drift (repaired 2026-10-10 run13).
- `suspected_excluded`: #449, #466 — not reopened, not counted as drift.
- No new open or closed issues this run (0 confirmed, 0 suspected).

## Delivery-gate log (this deep pass)

- `gradlew FreePornVideos:test` — **BUILD SUCCESSFUL** (after repairing the shared
  `HostRegistryTest.kt` squash-merge splice damage; see PR body).
- `gradlew FreePornVideos:make` (clean provider artifact) — **BUILD SUCCESSFUL**.
- `.pi/skills/verify-provider/scripts/verify.sh` — **PASS** (instrument tier: TLS-impersonated
  `chrome` fingerprint via a transparent curl drop-in; the plain-HTTP class is CF-walled
  runner-side, same tier as every earlier deep pass on this provider).
- `.pi/skills/audit-providers/scripts/check-findings.sh` — **PASS** (providers entry +
  `run26` last_runs entry added surgically, fleet-wide untouched).
