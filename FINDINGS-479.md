# FINDINGS-479 — Fleet audit (issue #479)

Run date: 2026-09-26. Probe IP: CI datacenter (Geo-gate: US). Instrument
calibration: Phase 0 canaries **all matched** — film1k plain 403 / TLS 200,
eporner 200, pandamovies 522 on both tiers. Instrument healthy; no
environment-degradation downgrades this run.

Escalation ladder per site-probe skill: plain curl → TLS-impersonated
(`scripts/impersonate.sh`) → (browser tier not needed this run).

## Phase 0 — canary calibration

| canary | plain-curl | tls-impersonated | expected | result |
|---|---|---|---|---|
| film1k-challenge-pair | 403 | 200 | 403 / 200 | match |
| eporner-healthy | 200 | — | 200 | match |
| pandamovies-dead-origin | 522 | 522 | 522 / 522 | match |

## Phase 1 — sweep (26 providers; Cat3Film's sweep row is covered by its Phase 2 deep audit below)

Verdicts recorded per `audits/findings.json`. Transcripts abbreviated;
raw captures in `/tmp/sweep479/` at probe time.

### Alive and serving (plain curl 200 on all surfaces)

| provider | home | search | video | stream |
|---|---|---|---|---|
| AllClassicPorn | 200 | `/search/anal/` 200, 163829 B, 36 `.th.item` cards | `https://allclassic.porn/videos/205/debbie-does-dallas/` 200 | `get_file/…/205.mp4/?v-acctoken=…` tokenized (session-bound; bare curl returns gif placeholder — provider fetches with cookies) |
| Cat3Movie | 200 | `/search/anal` 200, 70870 B, result links `/<slug>` | `/accidental-incest-2014` 200 | player-obfuscated (atob/eval) — provider extractor path, not a bare-HTML stream |
| EPorner | 200 | `/search/anal/` 301 → `/tag/anal/` 200, 237386 B | — | — (age-wall markup inert, per prior runs) |
| Eroticmv | 200 | `/?s=anal` 200, 255643 B | `/a-wild-party-1993/` 200 | base64 `aHR0cHM6…` → `https://vidcdn2.eroticmv.com/dat1/awildparty1993/awildparty1993.m3u8` → **200** |
| HQPorner | 200 | `/?q=anal&p=1` 200, 302148 B, `/hdporn/<id>-<slug>.html` cards | `/hdporn/127988-she_wants_to_dance_horizontally.html` 200 | player via JS lib; MyDaddyExtractor path |
| JavGuru | 200 | `/page/1/?s=anal` 200, 192304 B, `jav.guru/<id>/<slug>/` cards | `jav.guru/1052757/…` 200 | player-obfuscated (data-localize iframes) |
| Javbangers | 200 | `/search/anal/` 200, 217303 B, **75** `a.thumb` video cards | `/video/396138/…` 200 | `get_file/…/396138.mp4/?v-acctoken=…` → **200 video/mp4** |
| Javmost | 200 | `/showlist2/anal/1/search/` 200, JSON 13852 B | 3 pages probed (deep tier below) | `/ri3123o235r/` AJAX endpoint → 200 |
| Javseen | 200 | AJAX `search/video/?ajax=search_results&s=anal&o=recent` → JSON `status:1`, **30** `<li id="video-…">` results | `/286640/…` 200 | embed iframes base64: mycloudz.cc / cloudwish.xyz / streambeast.upn.one / dooood.com |
| Javtiful | 200 (301 → `/main`, locale-normal) | `/search?q=anal` 200, 104621 B, `/video/<id>/<code>` cards | `/video/113846/hsoda-133` 200 | playlist-UI/obfuscated — provider extractor path |
| MissAV | 200 (301 → `/dm265/en`) | `/en/search/anal` 200, 188691 B, `/en/<code>` cards | `/en/fc2-ppv-4980245` 200 | eval-packed surrit.com m3u8 (known provider flow) |
| Neporn | 200 | `/search/anal/` 200, 92771 B, `/video/<id>/<slug>/` cards | `/video/39025/wright-for-anal/` 200 | `get_file/…/39025_720p.mp4/` → 403 on bare curl (session-bound token; provider uses cookie session) |
| PerverZija | 200 | `/?s=anal` 200, 542654 B | `sweetheartvideo-alison-rey-…` 200 | iframe player `pervl4.xtremestream.xyz/player/…` |
| PornXP | 200 | `/tags/anal` 200, **36** `.item_cont` cards | `/videos/68452850425` 200 | `//xxx.pornxp.sh/…/360.mp4` → **200 video/mp4** |
| Porntrex | 200 | `/search/anal/` 200, 631584 B | `/video/3128305/…` 200 | `get_file/…/3128305.mp4/` → **200 video/mp4** |
| Sexfilm | 200 | GET search **requires session cookie**: bare GET → empty `searchtable`; two-step (home first, then GET) → **24** `div.short` results | `/11310-taxi-anal.html` shape confirmed | — |
| WatchPorn | 200 | KVS async search 200, 76847 B | `/video/47941/…` 200 | `get_file/…/47941.mp4/?v-acctoken=…` (session-bound; bare curl → gif) |
| Xhamster | 200 | `/search/anal` 200, 380797 B | `videos/…xhPm4m1` 200 | xhcdn HLS m3u8 → **200 application/vnd.apple.mpegurl** |
| ixiporn | 200 on `.live` | `.org/page/1?s=anal` → 301 `.live` → 200, 80726 B | `ixiporn.live/hot-sexy-nurse-…` 200 | cdn2.ixifile.xyz mp4 → **200 video/mp4** |

### Challenge-walled from datacenter IP (Suspected, not findings)

| provider | plain | TLS-impersonated | disposition |
|---|---|---|---|
| Film1k | 403 | home 200, `?s=anal` 200 (91615 B) | same as recorded; `suspected_excluded` [448, 465] |
| FreePornVideos | 403 | home 200, search 200 (175361 B, 335 video-ish) | `suspected_excluded` [449, 466] |
| FullPorner | 403 | home 200; search timed out twice at 30 s | home-only evidence this run; `suspected_excluded` [461] |
| XMoviesForYou | home 200, **search 403** | search 200 (105912 B) | 4th probe-IP-challenge artifact on search; matches excluded family [450, 467, 473] — not filed, not drift |

### Dead origin (Blocked)

| provider | evidence |
|---|---|
| PandaMovies | home **522** both tiers (19.6 s hang then CF 522). Standing #463, Chronic (4 confirmed) |
| Mangoporn | home **522** both tiers. Standing #464 |

## Phase 2 — deep tier (oldest/missing `last_deep`: Cat3Film, Eroticmv, Javmost, Xhamster)

### Cat3Film — PASS
- Home rows: `/movies`, `/tv-series`; `/movies?page=2` 200 with **30/34 items new** vs page 1 (4/34 overlap). Bare `/?page=2` is a no-op (53/53 overlap) but the provider never uses that shape.
- Quick search: `_ajax/search?q=anal` → JSON, 2 results (`slug`, `thumb`, `title`, `year`).
- 3 video pages (`/behind-closed-doors`, `/sins-of-a-nympho`, `/a-foreign-girl-in-paris`): all 200, `h1.info-title` present, `og:image`/`og:description` populated, `section#related` present.
- Watch page `/watch/behind-closed-doors?sv=1&part=1` 200: `.season-pane .epbtn[data-ep="447"]` intact — provider's episode parse still matches.

### Eroticmv — PASS
- Home paginates `/page/2/` (6/16 unique-page-1 items reappear on p2 — 17 new items).
- Quick search: none (provider `hasQuickSearch = false`, plain form GET — recorded).
- 3 video pages probed; streams resolve from base64 literal to `vidcdn2.eroticmv.com/dat1/<slug>/<slug>.m3u8` → **200** for all three.
- Exposure: og:title/og:image/og:description, JSON-LD blocks (3), duration + year present.

### Javmost — PASS with one quirk
- Home `/showlist2/all/{page}/all/` JSON, 24 entries/page, `total=328791`.
- **Page 2 returns byte-identical JSON to page 1** (md5 e95d70a6… on repeated fresh fetches); pages 3, 4, 5 are fully disjoint from page 1 and each other (0 overlaps). One-off site-side clamp, page 3+ fine.
- Quick search: not overridden by provider (no distinct suggest endpoint found).
- 3 video pages 200 (`PRED-901-…`, `START-638-…`, `RKI-765`): title, og:image present, player AJAX endpoint `/ri3123o235r/` → 200.

### Xhamster — PASS
- Home `?page=2` 200, video links disjoint (1/6 overlap, small home grid).
- Search pagination `?page=2`, `?page=3` links present on search page.
- Video page (`videos/dirty-american-whores-xhO3BBo`) 200: JSON title + description present, xhcdn HLS `…/media=hls4/…/…mp4.m3u8` stream URL in page; sibling video's m3u8 → 200 verified in sweep.

## Phase 3 — findings lifecycle

Enumerated open + closed issues per provider (see registry `last_runs`).
Dispositions:

- **#463 PandaMovies** — condition still present (522 both tiers). Evidence comment added. Chronic count unchanged (4; this run confirms but count tracks filed/confirmed occurrences).
- **#464 Mangoporn** — condition still present (522). Evidence comment added.
- **#468 ixiporn** — `.org` → `.live` redirect still present (301, 2 hops). Evidence comment added.
- **#475 Javbangers** — `/search/<q>/1/` still 404; provider stays on suffix-free page-1 URL (75 cards). Evidence comment added.
- **#476 PornXP** — condition **cleared**: `/tags/anal` returns 36 `.item_cont` results; stream mp4 200. Clearing evidence comment added.
- **#477 Javseen** — condition **cleared/resolved**: the AJAX endpoint (`?ajax=search_results&s=…`) returns 30 full results; the one-hit shell is only the non-AJAX page. Provider already uses the AJAX endpoint. Comment added.
- **NEW: Javmost home page 2 duplicates page 1** — genuinely new condition, filed as **#480** (unlabeled, per label contract).
- False positives: none filed this run (rate 0/1); excluded family respected (no re-filing of the #450/#461/#465/#466/#467/#473-line artifacts).

## Risks / blockers

- Probe IP is datacenter: Film1k / FreePornVideos / FullPorner / XMoviesForYou(search) present CF challenges to plain curl; all clear on TLS-impersonated tier. Per ADR-0011 these are `Suspected`, never findings alone.
- `scripts/differential.sh` HITL mode: no residential differential was run this round — no verdict hinged on it (both disagreed-tiers resolved via TLS impersonation, which is in-registry precedent for all four sites).
- PandaMovies / Mangoporn: origin-down 5xx — no instrument fixes a dead origin; Blocked stands.
