# FINDINGS-460 — Fleet audit evidence

Run: devloop/issue-460 · Audit-only per issue spec: no provider code changes; artifacts are
this file, `audits/findings.json` (registry), and new finding issues (unlabeled).

## Phase 0 — calibration (2026-09-25T10:47Z — all pass on plain-curl tier)

| canary | expected | observed | outcome |
|---|---|---|---|
| film1k-challenge-pair | 403 / TLS 200 | 403 / TLS 200 | match |
| eporner-healthy | 200 | 200 | match |
| pandamovies-dead-origin | 522 / 522 | 522 / 522 | match |

Instrument healthy. No `environment-degraded`; every Blocked verdict below is evidence-backed
after escalation. curl_cffi was installed on demand (impersonate.sh dependency, not
pre-provisioned).

## Per-provider sweep

Status codes: `plain / tls` when they differ. Search used each provider's own `search()`
URL, read out of its Kotlin (code carries the exact string; probe = reality-check).

| Provider | home | search | issue | note |
|---|---|---|---|---|
| AllClassicPorn | 200 | 200 `/search/lesbian/` (video cards: `a.th.item`, `/videos/{id}/{slug}/`) | — |
| Cat3Film | 200 | 200 `_ajax/search?q=anal` JSON | — |
| Cat3Movie | 200 | 200 `/?s=anal` (34 keyword hits) | — |
| EPorner | 200 | 200 `/search/anal/` | — |
| Eroticmv | 200 | 200 `/?s=anal` | — |
| Film1k | 403/200 | | — |
| FreePornVideos | 403/200 | | — |
| FullPorner | 403/200 | `/search?q=…` | — |
| HQPorner | 200 | 200 `/?q=anal` | — |
| JavGuru | 200 | 200 `/?s=anal` | — |
| Javbangers | 200 | `/search/anal/1/` **404 vs `/search/anal/` 200** | NEW #517 |
| Javmost | 200 | 200 `/search/anal` (238 hits) | — |
| Javseen | 200 | 200 AJAX search (1 kw; JS-blob endpoint — see Risks) | — |
| Javtiful | 200 | 200 `/search?q=anal` | — |
| Mangoporn | 522 | — | #464 standing |
| MissAV | 200 | 200 `/en/search/anal` (20 hits) | — |
| Neporn | 200 | 200 async search (45 hits) | — |
| PandaMovies | 522 | — | #463 standing / Chronic |
| PerverZija | 200 | 200 `/?s=anal` (214 hits) | — |
| PornXP | 200 | `/search?query=` — 200 but **zero cards** | NEW #518 |
| Porntrex | 200 | 200 `/search/anal/` | — |
| Sexfilm | 200 | 200 DLE search (31 hits) | — |
| WatchPorn | 200 | 200 async search (410 hits) | — |
| XMoviesForYou | 200 | 403 datacenter → TLS-impersonated 200 | suspected, standing |
| Xhamster | 200 | 200 search (19 hits) | — |
| ixiporn | 200 | 200; `?s=` still serves the right 30-card page (see Risks) | — |

## Deep checks — video pages & streams (sample, per-provider exact code paths)

| Provider | video page | stream chain | verdict |
|---|---|---|---|
| AllClassicPorn | `/videos/3912/teenage-twins/` 200 | `video_url:` direct get_file mp4 URLs (2 qualities) in page | OK (direct, no extractor hop) |
| Cat3Film | `/watch/behind-closed-doors?sv=1&part=1` 200 | `.wserver` `data-ep=447` → `/api/v1/episodes/447/sources` 200 → abyssplayer embed 200 → `datas` b64 payload matches repo SoTrym flow → enc-dec.app dec-abyss POST 200 → source URL → 302 → final **200 video/mp4, 211 766 175 B, real mp4 (ftyp header)** | OK end-to-end |
| Cat3Movie | `/watch-american-rampage-1989/full-sv1.html` 200 | halim player.php 200 → iframe `hlsfree.com/embed/hls/1080` 200 → `defaultHlsUrl` token → `/api/hls/serve?token=` 200 → **#EXTM3U playlist, 40 KB, real segments** (cat3hls.com `.png`-named fMP4 segments) | OK end-to-end |

| HQPorner | video page 200 | mydaddy.cc embed 200 → bigcdn mp4 **200 (ftyp verified)** | OK end-to-end |
| PerverZija | video page 200 | xtremestream xs1.php master 200 → `q=480` playlist → segment **1.3 MB MPEG-TS 200** (fixed adaptive q behavior matches code comment for issue #333) | OK end-to-end |
| JavGuru | video page 200 | base64 `iframe_url` → searcho iframe 200 → cfg data-attr token reversed → searcho/?ur= 302 → emturbovid 301 → turbovidhls → **m3u8 → googleusercontent segments 200** | OK end-to-end |
| WatchPorn | video page 200 | VOD-engine get_file mp4 200 (direct) | OK |
| Javbangers | video page 200 | VOD-engine get_file mp4 200 (direct) | OK (search surface broken — Finding-1) |
| Neporn | video page 200 | VOD-engine get_file mp4 200 (direct) | OK |
| Javtiful | video page 200 | `og:video` preview mp4 200 | OK |
| Sexfilm | video page 200 | embedUrl itemprop `morencius.com/embed/<id>` 200 → packed Dean-Edwards script unpacks to acek-cdn `hls2` master.m3u8 (token params) → 200 → variant playlist → **segment 200 MPEG-TS** | OK end-to-end |
| Porntrex | `/video/2065119/…` 200 | flashvars `video_url` direct get_file mp4s (360/720/1080) in page | OK (direct, no extractor hop) |
| MissAV | `/en/luxu-1037-uncensored-leak` 200 | packed script yields 12-hex surrit id (`/89157ab28196/playlist.m3u8` → **404 NoSuchKey**), but the dashed UUID form on the same page (`4db290e7-057b-44ad-a448-89157ab28196`) → playlist **200** → `842x480/video.m3u8` → segment **200 MPEG-TS**. Provider regex `[a-f0-9\-]{36}` matches only the dashed form → correct URL produced. Second title (jul-112) re-verified identical chain, segment **200** | OK end-to-end |
| Xhamster | `/videos/5421228` 200 | `window.initials` m3u8 URL → master 200 → 480p playlist 200 → **seg-1 200 MPEG-TS 66 552 B (0x47 sync)** | OK end-to-end |
| Eroticmv | `/at-home-with-pene-1996/` 200 | page carries b64 of `vidcdn2.eroticmv.com/dat1/…m3u8` → playlist 200 → **segment 200 MPEG-TS 277 112 B (0x47 sync)** | OK end-to-end |
| FullPorner | `/watch/5e6077d136ebf415681cba87` 200 (TLS tier, CF challenge on plain curl — known standing) | iframe `xiaoshenke.net/video/<id>/13` 200 → `vid/<reversed>/720/i` poster 200; `/720.mp4` 302 → v5.xiaoshenke.net token URL → **follow-through constant 404 even with fresh token in same second**; `/480/…` variant 302→ full follow-through pulled **~6.2 MB of 279 MB mp4 mid-download (200 stream)** | OK (redirect chain is one-shot; verified via follow-through mid-download) |
| Film1k | `/all-about-anna-2005.html` + `/taboo-1980.html` 200 | abyssplayer embed 200 → `const datas` → enc-dec.app dec-abyss 200 (sources listed) → sora URL via plain curl `-L -r 0-65535` → **206 video/mp4** (python curl_cffi 404/403s the tunnel hop; plain curl with Referer + `-L` succeeds per FINDINGS-110 note) | OK end-to-end (curl tier) |
| Javmost | `/FC2PPV-4981668/` 200 | `select_part` params → POST `/ri3123o235r/` **200** `status:success` → `dooplayer.com/embed/…` → **204 empty** (JS-only, known-unusable) — one-server page, matches documented FINDINGS blocker | OK per documented blocker |
| Javseen | `/286618/juan-028-…/` 200 → `/embed/286618/` 200 | embed page `data-embed` b64 → **mycloudz.cc/v/<id> 200** → packed script → dramiyos-cdn master.m3u8 **200** → variant → **segment 200 MPEG-TS 493 688 B (0x47 sync)** | OK end-to-end (search surface suspect — Finding-3) |
| PornXP | `/videos/46602002266` 200 | `<video><source>` direct `sd.pornxp.sh/…/720.mp4` → **206 video/mp4, ftypisom header** | OK (search surface zero-cards — Finding-2) |
| XMoviesForYou (TLS) | site redesigned: video pages now modal-driven, `fetch('/api/stream/<id>?target=dedi_1')` → `{"url": node m3u8}`; page still carries a `streamtape.com/v/<id>` anchor (provider's only scraped surface) → streamtape embed 200 | `robotlink` `get_video?…` → **status 500 "error on our side"** this run; node m3u8 **403 Forbidden** server-side either way. | ok-suspected: extractor path intact; stream endpoints unresolvable server-side this run (Streamtape 500/flake + nginx 403) |

Male/Unclear-URL notes: two sweep probes first landed on listing pages (`?p=34665`, bare
`/affected-slug`) — corrected to real video URLs above before judging; no provider impact.

## New findings (issues to file, unlabeled)

### Finding-1 — Javbangers search pagination 404 · issue #517 · `ok-drift`
- Evidence: curl https://www.javbangers.com/search/anal/1/ → HTTP 404, 395 B body
  (same result through the provider's exact code path: application code calls
  `$mainUrl/search/<q>-dashed/<page>`; the numeric page suffix now 404s).
  Page-1 without the trailing `/<n>` → 200, 217 KB, tiled results.
- Reproduction: `curl -s -A "$UA" https://www.javbangers.com/search/anal/1/ | head` → 404
  page; replace `1/` with `""` → 200 + cards.
- Instrument tier: plain-curl × both patterns (deterministic, no escalation needed).
- Drift: 1st confirmed occurrence (registry `drift_confirmed` rises 0→1).
- Impact: search page ≥2 comes back empty to users. Probe page-2 alternatives
  (`?page=2`, `page/2/`) via follow-up if review wants more; 404 is the mechanism.

### Finding-2 — PornXP search returns zero results · issue #518 · `ok-drift`
- Evidence: homepage 200 with credible grid; search `https://pxp.news/search?query=anal`
  → 200 but body carries zero video-card markers (0 keyword hits, 0 of the site's
  card classes), while the homepage cards use distinct `<article …>` markup.
- Reproduction: `curl -s -A "$UA" 'https://pxp.news/search?query=anal'` → 200, no cards.
- Instrument tier: plain-curl (both sides, so no challenge confound).
- Drift: 1st confirmed occurrence.
- Impact: search surface degrades to no-op; a follow-up probe of the
  correct query-string shape (`/search?q=` vs `?query=`) belongs in the fix issue.

### Finding-3 — Javseen search runs through an AJAX/JS plumbing endpoint · issue #519 · `ok-suspected`
- Evidence: search returns 200 with a body of **exactly 1 fuzzy keyword hit**, the classic
  shape of an endpoint where actual results ride a second AJAX call the plain HTML body
  can't show. Provider search URL:
  `/search/video/?ajax=search_results&s=<q>&o=recent`.
- Reproduction: `curl -s -A "$UA" 'https://javseen.tv/search/video/?ajax=search_results&s=anal&o=recent'`
  → 200 + one-line page shell, no result list.
- Instrument tier: plain-curl. Not verifiable further without a browser,
  which is Diagnose tier — outside FINDINGS.
- Drift: 1st confirmed occurrence.
- Impact: unclear; needs a browser-diagnose pass to tell real drift from an
  overly-thin curl view of an AJAX-served surface.

## Standing / unchanged conditions

- **PandaMovies** — 522 unchanged (matching canary). Standing issue #463. `drift_confirmed`: 4 → **Chronic**.
- **Mangoporn** — 522 unchanged. Standing issue #464.
- **XMoviesForYou** — plain-curl 403 on search (datacenter IP), TLS-impersonated 200 —
  same known probe-IP artifact as standing issues #450/#467/#473 (all
  `suspected_excluded`). No new issue filed. Tier that invalidated it before:
  residential-differential; re-confirming that would need a residential proxy secret
  (not set), so `ok-suspected` stands.

## Risks / blockers

- Datacenter-IP CF challenge on 3 sites (Film1k, FreePornVideos, FullPorner) —
  all already in `suspected_excluded` with clean TLS-impersonated 200s; probe-tier only.
- `RESIDENTIAL_PROXY_URL` not set in this environment; no residential differential tier
  this run → anything verdicted `ok-suspected` stays suspect per skill, not Blocked.
