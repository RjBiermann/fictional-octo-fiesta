# FINDINGS-640 — Javmost deep audit (www.javmost.ws) — 2026-10-20

Run for #640 (spec of epic #631, approved round 2). Probe tier per surface recorded inline.

## Phase 0 — canary calibration

| canary | expected | observed | verdict |
|---|---|---|---|
| film1k-challenge-pair (plain / tls) | 403 / 200 | 403 / 200 | MATCH |
| eporner-healthy (plain) | 200 | 200 | MATCH |
| pandamovies-redirect-origin (plain / tls) | 302 / 200 | 302 / 200 on retry (first TLS attempt stalled mid-body at 30 s; 200/60 KB at 60 s) | MATCH |

Instrument healthy; Blocked remains available (none needed).

## 1. Home pagination (plain-curl)

`GET /showlist2/{group}/{page}/{type}/` (the exact URL shape the provider builds):

| group | p1 / p2 / p3 sizes | p1∩p2 | p1∩p3 | p2∩p3 |
|---|---|---|---|---|
| all | 24 / 24 / 24 | 0 | 0 | 0 |
| uncensor | 24 / 24 / 24 | 0 | 0 | 0 |
| censor | 24 / 24 / 24 | 0 | 0 | 0 |
| new (`type=release`) | 24 / 24 / 24 | 0 | 0 | 0 |

- Standing #480 (page-2-duplicates-page-1) did **NOT** regress — zero URL/title overlap across p1/p2/p3 on every group. Stays cleared.
- All-1 page is still the null-meta pending bucket (24/24 items `release:null, star:null`) — provider already excludes the `all` group from `mainPage` (#332 fix), no change warranted. Null-meta entries in `uncensor` (e.g. HEYZO-3937, 1PONDO-091526-001) are live: HTTP 200.
- JSON contract unchanged: top-level `{result, success, total}`; item = `url/name/full_name/cover/star/release/...` (full_name carries HTML entities — provider decodes).

## 2. Search (plain-curl)

- `GET /showlist2/{query}/{page}/search/` → 200 JSON. "milf": 24 results, `total=804`, p1∩p2 = 0. "avsa": 24 results, `total=671`.
- The site presents no distinct quickSearch surface (unchanged from FINDINGS 2026-09-08; `hasQuickSearch = false` stays correct).

## 3. Video pages (plain-curl) — 3/3 probed from three different listing groups

| URL | status | og:title | og:image | card-block |
|---|---|---|---|---|
| …/CARIBBEANCOM-091326-001/ (uncensor list) | 200 | "Watch CARIBBEANCOM-091326-001 JAV movie online free streaming. Full HD Japanese porn video." | present (3) | metadata intact |
| …/MESU-100/ (censor p2) | 200 | "Watch MESU-100 JAV movie online free streaming. Genre: Creampie, Hotel." | present | metadata intact |
| …/START-647-REDUCING-MOSAIC/ (new/release list) | 200 | "Watch START-647-REDUCING-MOSAIC JAV movie online free streaming. Genre: Cumshot, Slender." | present | metadata intact |

`YWRzMQo = '<value>'` constant present on all 3; `select_part('1','<group>',…)` rows present (1 / 5 / 1).

## 4. Stream/embed resolution — AJAX chain OK; embed-host layer degraded

AJAX endpoint healthy (plain-curl): `POST /ri3123o235r/` (group/part/code/code2/code3/value/sound, Referer=page) → `{"status":"success","data":[…]}` on every server row sampled.

Per-page embed rows observed this pass:

| page | row → embed | embed-host health (instrument per row) |
|---|---|---|
| CARIBBEANCOM-091326-001 | g62 → dooplayer | 204 empty (plain) — known-dead domain |
| MESU-100 | g54 → mostplayer | 204 empty (plain) |
| MESU-100 | g61 → emturbovid | **TCP connect timeout** (plain / tls-impersonated / real Chromium all time out) — full ladder exhausted, no challenge served |
| MESU-100 | g46 → voe.sx | **404 Not found in real Chromium** — dead link ID (not a vantage artifact) |
| MESU-100 | g41 → dood.ws → 301 playmogo | playmogo 403 to datacenter IP (suspected vantage); dood.ws adapter `playmogo.com` registered in shared HostRegistry |
| MESU-100 | g50 → streamtape | timeline 404 at streamtape.com AND stape.fun mirror — dead link ID |
| START-647-REDUCING-MOSAIC | g62 → dooplayer | 204 empty — known-dead domain |

**Confirmed (vantage-independent) condition:** `turbovidhls.com` — the emturbovid m3u8 chain endpoint recorded in FINDINGS/#332 (`emturbovid.com/t/<id>` → 301 → `turbovidhls.com/t/<id>` → urlPlay m3u8) — no longer resolves **anywhere**: local resolver NXDOMAIN and dns.google/resolve returns no A record (SOA authority from registrar nameservers). DNS is not IP-filtered, so this cannot be a datacenter-vantage artifact.

**Suspected:** emturbovid.com itself — DNS still answers (132.243.175.122) but TCP is dead for plain curl, TLS-impersonated curl_cffi (chrome), and real headless Chromium. No challenge was served, so this is not the datacenter-CF case, but a full-ladder connect-timeout from a datacenter-only vantage cannot distinguish "host dead" from "datacenter range blocked." Verdict vocabulary: suspected (needs residential differential to decide).

## 5. verify.sh (mechanical gate, HTML-expressible subset) + JSON-surface transcript substitutes

**Standing script-artifact heritage (run 3, 2026-09-26 — reconfirmed by this pass):** every HTML
listing page on javmost.ws ships the **unrendered JS template grid alongside the rendered cards**
(`div.card` ×24 real with `class="card "`, ×1 template with `class="card"`, anchors `href="${url}"`,
`id="${element.name}_tag"`, alt="${element.name}"), so `div.card` card-count/dup mechanics see a
phantom card and a JS-blob title on every page; and no stream URL exists in any video-page HTML
(the AJAX two-hop chain — heritage: "verify.sh cannot express it... none of which exist here").
Both facts are unchanged. What the script could mechanically assert ran clean; the rest is
transcript-substituted above (JSON surfaces are the provider's actual listing path):

| check | result | notes |
|---|---|---|
| canaries (Phase 0 equivalent) | 3/3 match | film1k 403/200, eporner 200, pandamovies 302/200 (TLS retry at 60 s) |
| home pagination (JSON showlist2) | PASS | p1/p2/p3 × {all,uncensor,censor,new} 24 items each, 0 overlap — direct probe, table in §1 |
| search (JSON showlist2) | PASS | 24 results, `total` field, p1∩p2 = 0 (§2) |
| video pages ×3 | PASS | 200, og:title/og:image extracted by script mechanics, card-block fields present |
| stream check (script check 3) | NOT APPLICABLE | standing artifact: AJAX chain unexpressible in HTML mechanics; chain itself probed live in §4 (AJAX success on every row; embed hosts dead/blocked this pass) |
| search↔load agreement (check 5) | PASS (transcript) | search row `MESU-100-UNCENSORED-EDIT` loads 200 with matching og:title; site serves real `-UNCENSORED-EDIT` URL variants in search rows — live URLs, not drift |
| LoadResponse completeness (code half) | PASS | posters/tags/actors/plot/year/duration all assigned in Javmost.kt |

## Disposition

- **javmost.ws surfaces: all healthy.** Selectors, endpoints, and the AJAX contract in `Javmost.kt` match the live site exactly — no selector swap possible, none needed.
- **No code change.** The degraded layer is third-party embed hosts, not provider code: `loadLinks` already iterates every server row, fails fast per dead row, and dispatches live hosts (voe/dood/streamtape family) through the registered shared adapters (#376 design). Provider has 0 closed confirmed-drift issues — the hardening threshold (3+) is not met, and no in-repo change is provable against a dead emturbovid/turbovidhls chain.
- **Finding lifecycle:** regression documented on standing #332 (the issue that recorded the chain as verified-good on 2026-09-11) — dedup rule: update the standing issue instead of duplicating. Not counted as a new drift issue.
- **Verdict: `ok-suspected`, tier `plain-curl`** — site confirmed ok; the stream layer's primary chain is dead-confirmed at DNS (turbovidhls) with emturbovid connected-timeout-suspected. `drift_confirmed` stays 0.
