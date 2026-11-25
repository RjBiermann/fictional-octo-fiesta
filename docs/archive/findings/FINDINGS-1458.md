# FINDINGS-1458 — Porntrex video sources (S20 of #1354)

Video-source leg (D2) of the Porntrex spec (`www.porntrex.com`). Scope: for every D3 listing
type (`new`/`latest`, `old`, `popular`, `genres`, `categories`, `tags`) open ≥2 video pages,
sampled at both ends of the catalogue where the type is pageable, and resolve `loadLinks`
end-to-end — video page → KVS flashvars → stream URL → quality passed to CloudStream.

Provider state at run start: registry `last_deep` = 2026-10-16, `drift_confirmed` = 0,
verdict at last deep = `ok` (#633), `version` = 32.

**Result: `Blocked` — `www.porntrex.com` returned a blanket `403` to this runner on every path,
at all three escalation tiers. No video page, listing page, or stream URL was reachable this
run, so the leg could not be probed. No provider condition is confirmed, nothing was filed as a
finding, and no code change or version bump ships. The *cause* of the 403 is a leading
hypothesis (IP/region refusal), not an established fact — see "Confidence" below. The tier that
would decide it (a differential probe from a non-runner egress) was not available this run.**

Run: `05-implement` for #1458, 2026-11-24, plain-curl + TLS-impersonated + headless-Chromium
tiers, runner IP US/Virginia (Azure/GitHub Actions).
Workflow run: `https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/45444142621`.

Out of scope per spec: a full listing audit (pagination/card-field correctness was
#632–#659) and *rows* work. `drift_confirmed` = 0, so no hardening rung applies.

---

## Confidence — observed vs. inferred (read this before citing the diagnosis)

**Observed (established by this run's transcripts):** a bare nginx `403` (`Server: nginx`, no
`Set-Cookie`, no challenge page, no `Retry-After`) for `/` and every probed path. Its body is the
same minimal page but **not the same byte count at every `User-Agent`**: **548 B** with the
desktop UA (the size every quoted transcript below was measured with), a shorter **146 B** page
when no `User-Agent` is sent, and **144 B** as measured on the Chromium leg. The same `403` came
at the plain-curl, TLS-impersonated (`curl_cffi` `chrome`) and headless-Chromium tiers; over
HTTP/1.0, HTTP/1.1 and HTTP/2, with and without a `User-Agent`; on a direct
`Host: www.porntrex.com` request to `88.80.24.7`; and on the second operator edge
`www.porntrex.tv` (`88.80.24.3`). A Let's Encrypt certificate for `CN=porntrex.com` is valid
2026-10-19 → 2027-01-17.

**Inferred — hypothesis, not a conclusion:** that the refusal is scoped to this runner's
US/Virginia datacenter IP (or its region). That is the most likely reading and nothing observed
contradicts it, but **this run does not exclude an operator-wide blanket refusal** — a failed
deploy, a maintenance window, or a WAF rule applied to all clients would produce a
byte-identical 403 from here. Precisely:

- cert freshness proves only that the TLS front-end still serves that vhost. Let's Encrypt
  re-issue is automated ACME and completes even while the application behind the vhost is down;
  it is **not** evidence the catalogue is being actively maintained.
- a second edge under the same operator returning the same 403 shows the refusal is
  operator-wide across edges, **not** that it is geo-scoped.
- `RESIDENTIAL_PROXY_URL` was not set, so `scripts/differential.sh`'s residential tier — the
  documented deciding instrument for probe-IP artifacts, and the only tier available that could
  separate "this egress" from "everyone" — printed `NOT CONFIGURED` and did not run.

**Falsification test for the next run:** re-probe from a different egress (VPN / non-US
runner / residential). Healthy `200` there ⇒ the IP/region hypothesis is confirmed and this
Blocked reason stands verbatim. The **same bare nginx `403`** there (same status and the same
minimal page — match the shape, not the byte count, which tracks the `User-Agent`) ⇒ the
hypothesis is **falsified**: the refusal is site-wide, D8 does **not** exempt a site-wide
refusal, and the run must file/escalate it rather than record another Blocked.

---

## Phase 0 — canary calibration (before touching the site)

| canary | expected | observed | reading |
|---|---|---|---|
| `eporner-healthy` | plain-curl 200 | plain-curl **200** (3 590 B — the site's own server-rendered **age-verification page**, `<title>Age verification – Eporner</title>`, `<body class="agegate-page">`) | recorded expectation **matches** (200) — instrument healthy; body shape noted below |
| `film1k-challenge-pair` | plain-curl 403, tls-impersonated 200 | plain-curl **200** (253 678 B) | **expectation-stale** (#1435 open) — live pair recorded here; **no** `environment-degraded` branch taken |
| `pandamovies-redirect-origin` | plain-curl 302, tls-impersonated 200 | plain-curl **302 → https://pandamovies.org/** (143 B) | plain-tier match |

```
$ UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36'
$ curl -s -o /dev/null -A "$UA" -w '%{http_code} %{size_download} %{redirect_url}\n' https://www.eporner.com/     → 200 3590
$ curl -s -o /dev/null -A "$UA" -w '%{http_code} %{size_download} %{redirect_url}\n' https://www.film1k.com/      → 200 253678
$ curl -s -o /dev/null -A "$UA" -w '%{http_code} %{size_download} %{redirect_url}\n' https://pandamovies.pw/      → 302 143 https://pandamovies.org/
$ curl -s -o /dev/null -A "$UA" -w '%{http_code} %{size_download} %{redirect_url}\n' https://www.porntrex.com/    → 403 548
```

Reading:

- **No genuine `environment-degraded` mismatch** on any canary: every canary answered at the
  recorded tier and status. Nothing was downgraded, and `Blocked` stays available (D8: "Blocked
  requires the ladder to have run on a healthy Phase 0").
- **#1435 (open)** — `film1k-challenge-pair` is expectation-stale exactly as recorded
  (plain-curl 200 where 403 was expected). Live pair recorded above; no degradation branch.
- **#1434 (closed 2026-11-24, #1470)** — the TLS-impersonated tier **is available** this run:
  `impersonate.sh` now honours `$PYTHON`, and a run-local venv carried `curl_cffi 0.17.0`
  (`escalation_tier: available`). This is the first S-chain run able to escalate, and it did —
  see the ladder below. The browser tier (`playwright` + Chromium 159 headless shell) was also
  installed and run.
- **Canary refinement proposed, not applied** (maintainer confirms): `eporner-healthy`'s
  `plain-curl: 200` still holds, but the body for this runner is the site's age-verification
  page (3 590 B), not the ~313 KB SSR homepage recorded at `last_verified` 2026-10-16. The
  canary still proves the instrument (real SSR HTML from a large adult site), so it is not
  degradation — but "known-good SSR homepage" is now a stale *purpose* description for US
  runners. `audits/canaries.json` is conveyor-owned, so this note is the proposal.
- Region of the probe: **US / Virginia** (`eporner`'s own client config for this IP reads
  `geocc='ca'`, `geoct='US'`, `georeg='VA'`), i.e. a US datacenter IP in a state with an
  age-verification statute — relevant to the refusal below.

---

## The refusal — escalation ladder, run to completion

Every tier returned the same bare **nginx 403** (`Content-Length: 548`, no `Set-Cookie`, no
challenge page, no `Retry-After`, no Cloudflare headers). This is an origin-level refusal of
this client, not a JS challenge and not a client-rendering problem.

### Tier 1 — plain curl

```
$ curl -s -D - -o /dev/null -A "$UA" https://www.porntrex.com/
HTTP/1.1 403 Forbidden
Server: nginx
Date: Tue, 24 Nov 2026 17:10:43 GMT
Content-Type: text/html
Content-Length: 548
Connection: keep-alive
Vary: Accept-Encoding
Strict-Transport-Security: max-age=63072000; includeSubDomains; preload

<html>
<head><title>403 Forbidden</title></head>
<body>
<center><h1>403 Forbidden</h1></center>
<hr><center>nginx</center>
</body>
</html>
<!-- a padding to disable MSIE and Chrome friendly error page -->  ×5
```

Also 403 with: no `User-Agent` (146 B), a full browser header set (`Accept`,
`Accept-Language`, `Accept-Encoding`, `Sec-Fetch-*`, `Upgrade-Insecure-Requests`),
`Referer: https://www.google.com/`, HTTP/1.0, HTTP/2, `--tlsv1.2`, `HEAD`, and a direct
`https://88.80.24.7/` request with `Host: www.porntrex.com`.

### Tier 2 — TLS-impersonated (`scripts/impersonate.sh`, curl_cffi 0.17.0, `impersonate="chrome"`)

```
$ PYTHON=/tmp/gh-aw/agent/venv-audit/bin/python .pi/skills/site-probe/scripts/impersonate.sh https://www.porntrex.com/
HTTP 403 https://www.porntrex.com/
server: nginx
date: Tue, 24 Nov 2026 17:10:39 GMT
content-type: text/html
transfer-encoding: chunked
connection: keep-alive
vary: Accept-Encoding
strict-transport-security: max-age=63072000; includeSubDomains; preload
content-encoding: br
```

Body: the same bare nginx 403 page (548 B under the impersonated Chrome UA). A real Chrome TLS
fingerprint changes nothing.

### Tier 3 — browser diagnosis (headless Chromium 159, `playwright`)

`browser-probe.py` aborts on the raw `urllib` leg (`HTTPError: HTTP Error 403`), so the
navigation was replayed directly with the same instrument:

```
$ playwright chromium headless → page.goto('https://www.porntrex.com/', wait_until='networkidle')
STATUS: 403 https://www.porntrex.com/
TITLE: 403 Forbidden
BYTES: 144
LINKS: 0 VIDEOS: 0
BODY: 403 Forbidden | nginx
--- net (first 15) ---
document: https://www.porntrex.com/
```

No subresource was requested and no DOM was built: the refusal happens before any JS can run,
so this is **not** `client-rendered (JS-only)` and there is no rendered-vs-raw gap to diagnose.

### The refusal is blanket, and it has no unlock

```
/                        → 403 548     /nonexistent-xyz/        → 403 548
/robots.txt              → 403 548     /favicon.ico             → 403 548
/static/                 → 403 548     /categories/             → 403 548
/latest-updates/         → 403 548     /search/milf/            → 403 548
/video/3364971/          → 403 548     /embed/3364971/          → 403 548
```

Cookie/query unlocks tried (the site's own age-gate vocabulary, per the #171 probe):
`confirmed=true`, `age_verified=1`, `confirmed=true; age_verified=1; sid=1`,
`kt_age_verified=1; confirmed=1`, and `?age_verified=1` → **403 548 on every variant**.
`http://www.porntrex.com/` and `https://porntrex.com/` → 301 to `https://www.porntrex.com/`
(so the hostname is not the problem), then 403.

### What the refusal does and does not establish

- **Not a path/UA/client defect** (established): the 403 is path-independent, UA-independent,
  protocol-independent and unchanged by a real Chrome TLS fingerprint or a real headless browser
  — so it is an origin-level refusal, not a JS challenge and not a client-rendering problem.
- **Not one bad host** (established): `www.porntrex.tv` (a second operator edge, `88.80.24.3`)
  returns the **same** bare nginx 403 (548 B under the desktop UA); `porntrex.com` and `http://`
  variants 301 to `https://www.porntrex.com/` and then 403.
- **TLS front-end still serving** (established), **application healthy — not established**: the
  certificate on `www.porntrex.com` is `CN=porntrex.com`, issuer Let's Encrypt `YE1`, valid
  **2026-10-19 → 2027-01-17** — issued **three days after** the last healthy deep pass
  (2026-10-16) and five weeks before this run. That proves only that the vhost's TLS is
  configured: ACME re-issue is automatic and completes even while the application behind the
  vhost is down, so it says nothing about content-path health.
- **IP/region scoping — leading hypothesis only.** A fresh cert and a second refused edge are
  equally consistent with an operator-wide blanket refusal (see "Confidence" above). Do not
  read this section as proof of a geo block.
- **No in-provider change since** the 2026-10-16 deep pass (`ok`, 120/120 home cards, 85+85
  search, 5/5 streams `206 video/mp4`, 45 related items) — so the provider code is not the
  explanation for a 403 that happens before any provider request shape matters.

**Blocked reason (recorded so a future run does not re-probe the dead end):**
`Blocked: 403 no unlock — origin nginx returns the same bare 403 page to this runner on every
path (host- and path-independent; 548 B with the desktop UA, a shorter minimal page without one),
at plain-curl, TLS-impersonated and headless-Chromium tiers. Leading hypothesis: IP/region refusal of the US/Virginia datacenter runner. NOT excluded:
an operator-wide blanket refusal, which looks identical from here — re-probe from a different
egress first, and treat the same 403 there as site-wide (a finding, not Blocked). Human in-app
verification required.`

Unrelated domains checked while confirming the site had not moved (both **not** the tube site,
so neither is a provider lead): `www.porntrex.io` is a Hugo blog ("PornTrex 2026 – Best Free HD
Porn Videos & 4K Porn Blog", 36 KB, no video grid), and `www.porntrex.net` is a parked domain
behind a JS interstitial (`window.location.replace('…?ch=1&js=<jwt>&sid=<sid>')` → 302
`http://ww19.porntrex.net` → "Error. Page cannot be displayed."). The tube site's canonical
host is `www.porntrex.com`, which is what the provider ships.

---

## D3 listing-type inventory — all six types unprobed this run

The site's routes are known from the provider and the 2026-10-16 deep pass; **none of them was
reachable on 2026-11-24**. Recorded per type with the route that must be re-probed, so the next
run (different region/VPN) starts where this one stopped. Prior-run detail is labelled as such
— it is **not** evidence for this run's verdict.

| type | route to re-probe | this run | prior-run reference (2026-10-16, #633) |
|---|---|---|---|
| `new` / `latest` | `/` and `/latest-updates/`; provider rows are `/categories/<cat>/` with the `mode=async…from=N` page 2 | **blocked** (403) | home p1 120 cards + async p2 120 |
| `old` | catalogue tail of the newest-first listing (no oldest-first route recorded) | **blocked** (403) | not staked separately |
| `popular` | `/top-rated/` and `/most-popular/` (KVS standard; both 403 this run) | **blocked** (403) | not staked separately |
| `genres` | no distinct genre vocabulary recorded — the site's taxonomy is categories | **blocked** (403) — presence undetermined this run | treated as `absent` in provider design (categories only) |
| `categories` | `/categories/<name>/` (34 rows in `mainPage`) | **blocked** (403) | 120 cards/row, page 2 via async endpoint |
| `tags` | `/tags/<tag>/` (unknown whether live) | **blocked** (403) — presence undetermined this run | not staked separately |

`genres` and `tags` must **not** be recorded `absent` from this run: absence is a positive
observation and this run observed nothing but a 403. They stay `undetermined (blocked)`.

---

## Evidence rows (D4)

`type | sample URL | instrument tier | stream host | quality | verdict` — every row ends at the
same refusal, so the table is deliberately degenerate rather than padded with unprobed rows.

| # | type | sample URL | instrument tier | stream host | quality | verdict |
|---|---|---|---|---|---|---|
| 1 | new/latest | `https://www.porntrex.com/` | plain-curl / TLS-impersonated / Chromium | — | — | **blocked** (403, no unlock) |
| 2 | new/latest (p2 async) | `/categories/teen/?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&from=2` | plain-curl | — | — | **blocked** (403) |
| 3 | old (tail) | `https://www.porntrex.com/latest-updates/` | plain-curl | — | — | **blocked** (403) |
| 4 | popular | `https://www.porntrex.com/top-rated/` | plain-curl | — | — | **blocked** (403) |
| 5 | categories | `https://www.porntrex.com/categories/milf/` | plain-curl | — | — | **blocked** (403) |
| 6 | tags | `https://www.porntrex.com/tags/milf/` | plain-curl | — | — | **blocked** (403) |
| 7 | video page (direct) | `https://www.porntrex.com/video/3364971/` | plain-curl | — | — | **blocked** (403) |
| 8 | video page (embed fallback) | `https://www.porntrex.com/embed/3364971/` | plain-curl | — | — | **blocked** (403) |
| 9 | search | `https://www.porntrex.com/search/milf/` | plain-curl | — | — | **blocked** (403) |
| 10 | related endpoint | `https://www.porntrex.com/related_videos_html/3364971/` | plain-curl | — | — | **blocked** (403) |

Raw transcript backing every row (plain-curl tier, desktop UA, `X-Requested-With:
XMLHttpRequest` so the async/related endpoints are probed exactly as the provider calls them):

```
$ for u in <row URL>; do curl -s -o /dev/null -A "$UA" -H 'X-Requested-With: XMLHttpRequest' \
      -w '%{http_code} %{size_download}\n' "$u"; done

/                                             → 403 548
/categories/teen/?mode=async&function=get_block&
  block_id=list_videos_common_videos_list_norm&from=2   → 403 548
/latest-updates/                              → 403 548
/top-rated/                                   → 403 548
/most-popular/                                → 403 548
/categories/milf/                             → 403 548
/tags/milf/                                   → 403 548
/tags/                                        → 403 548
/video/3364971/                               → 403 548
/embed/3364971/                               → 403 548
/search/milf/                                 → 403 548
/related_videos_html/3364971/                 → 403 548
```

The response body is byte-identical for all of them (every row above was measured with the
desktop UA) — the single nginx-403 page quoted under "Tier 1" (`Content-Length: 548`,
`Server: nginx`).

`loadLinks` end-to-end therefore **could not be exercised**. The chain the next run must walk
(unchanged from the 2026-10-16 pass): video page → `var flashvars` (`video_url` +
`video_alt_url*`, skipping `video_<n>_redirect: '1'`) via `PorntrexParse.qualityLinks` → `get_file`
URL → 302 CDN → ranged `206 video/mp4`; quality from the `<key>_text` label via
`PorntrexParse.qualityOf` (S1 table, #767/#769). Stream host family at last pass:
`pcdn.cdntrex.com` / `www.porntrex.com/get_file/…` — an embed/`HostRegistry` adapter is **not**
involved (direct mp4, no iframe host).

---

## Confirmed conditions

**None.** No selector, endpoint, quality mapping, or `HostRegistry` adapter was observed to be
wrong — nothing could be observed at all. Per D1 the run ships no code change and no version
bump (`version` stays 32); a fix without evidence would violate the audit's fix policy and the
red → green TDD bar.

## Findings lifecycle / dedup

Searched open **and** closed issues for `Porntrex`: #633 (deep pass, `ok`), #215/#256/#275/
#309/#429/#759/#769 (historical conditions, all fixed and closed), #664 (verify.sh instrument
false-FAILs, closed), #830 (API-contract audit, closed), #1361–#1363 (fleet load-check
instrument, closed). **No standing issue describes a Porntrex refusal, geo-block, or
video-source gap.** Nothing filed: per D8 a datacenter-IP/region refusal is `Suspected`-class
evidence and **never a finding**, and no provider-side condition was confirmed. Note the D8
exemption is scoped to the datacenter-IP class: it is granted here **because** the diagnosis is
the leading hypothesis, not because a blanket refusal was observed to be geo-scoped. A
site-wide refusal is not exempt — see the falsification test under "Confidence".

---

## Verification (delivery gate)

- `.pi/skills/audit-providers/scripts/check-findings.sh` → **PASS** (registry untouched, ADR-0013)
- `./gradlew bootstrapCloudstream` → BUILD SUCCESSFUL (jar refreshed into mavenLocal)
- `./gradlew Porntrex:test` → **BUILD SUCCESSFUL** (392 tests across 56 result files, 0 failures, 0 skipped — provider + shared suites)
- `./gradlew clean Porntrex:make` → **BUILD SUCCESSFUL**, `Porntrex/build/Porntrex.cs3` 166 309 B
- `.pi/skills/verify-provider/scripts/verify.sh` (live) → **RESULT: FAIL — `403` on every
  surface**, i.e. the gate could not be satisfied from this runner. Output:

```
── check 1: search pages (2)
GET https://www.porntrex.com/search/milf/ → 403; 'div.video-preview-screen.video-item' matches: 0
FAIL search (https://www.porntrex.com/search/milf/)
GET https://www.porntrex.com/search/milf/2/ → 403; 'div.video-preview-screen.video-item' matches: 0
FAIL search (https://www.porntrex.com/search/milf/2/)
── check 1a: homepage pages (1)
GET https://www.porntrex.com/categories/teen/ → 403; 'div.video-preview-screen.video-item' matches: 0
FAIL home (https://www.porntrex.com/categories/teen/)
── check 2: video pages (2 URLs)
GET https://www.porntrex.com/video/3364971/ → 403; 'div#kt_player' matches: 0
FAIL video page / FAIL video title missing / FAIL: no absolute stream URL extracted
FAIL related videos
GET https://www.porntrex.com/video/2913997/ → 403  (same three FAILs + related)
── check 6: LoadResponse completeness (code half, still green)
field 'recommendations': 3 assignment(s) · 'tags': 3 · 'plot': 1 · 'duration': 2
field 'actors': 2 · 'posters': 6
RESULT: FAIL
```

The check-6 lines confirm the provider's `LoadResponse` field wiring is intact (code half of
Data-complete); the live half is what the origin refused. This is the documented Blocked
outcome per GLOSSARY ("A PR may open as Blocked with an explicit note; the human verifies
in-app instead"), not a silent gate skip.

Invocation used (selectors from `docs/archive/providers/Porntrex/FINDINGS.md`):

```bash
.pi/skills/verify-provider/scripts/verify.sh \
  --search-url 'https://www.porntrex.com/search/milf/' \
  --search-url 'https://www.porntrex.com/search/milf/2/' \
  --search-selector 'div.video-preview-screen.video-item' \
  --search-title-selector 'p.inf a' --search-poster-selector 'img.cover' \
  --home-url 'https://www.porntrex.com/categories/teen/' \
  --home-selector 'div.video-preview-screen.video-item' \
  --video-url 'https://www.porntrex.com/video/3364971/' \
  --video-url 'https://www.porntrex.com/video/2913997/' \
  --stream-selector 'div#kt_player' \
  --related-selector 'a.player-related-videos-item.kt-api-related-item' \
  --video-title-selector 'p.title-video' --video-plot-selector 'div.videodesc em.des-link' \
  --video-tags-selector 'div.js-categories a.js-cat' \
  --video-duration-selector 'div.block-details div.item span:has(i.fa-clock-o) em.badge' \
  --provider-src 'Porntrex' \
  --load-response 'recommendations,tags,plot,duration,actors,posters'
```

---

## Next-run guidance (the dead end is recorded, not left to be re-discovered)

1. Re-probe from a **different egress** (VPN, non-US runner, or residential via
   `RESIDENTIAL_PROXY_URL`, which unblocks `scripts/differential.sh`'s deciding tier) **before**
   concluding anything about scope. A US datacenter IP gets 403 at the operator's edge regardless
   of tier, UA, or path — but this run cannot show that a non-US/residential egress is served.
   If the second egress also gets the same bare nginx 403 (match the shape, not the byte count),
   the IP/region hypothesis is falsified: the refusal is site-wide and must be **filed**, not
   recorded Blocked again.
2. Do **not** treat `genres`/`tags` as `absent` on the strength of this run — the 403 observed
   nothing about them.
3. `last_deep` stays **2026-10-16** (that is still the last completed deep pass); this run adds
   no deep stake. The registry records `verdict: blocked`, but its `reason`/`canaries` **on main**
   are still the unqualified round-1 text — read *Registry write* below before quoting the
   machine record.
4. Provider `version` stays **32** — no code change, no fixtures changed, `PorntrexParse` unit
   tests untouched and green.

## Registry write (ADR-0013 — nothing in the PR diff)

The provider entry + one `last_runs` entry (`false_positives: []`) belong in the #1458 thread, not
in this diff. **What actually happened**, checked against the thread and against
`audits/findings.json` **on main** (read through the API, not quoted from memory):

- The run's delta comment (`2026-11-24T17:12:56Z`) was posted **unanchored** — a bare fenced json
  object with **no** `<!-- findings-registry-entry -->` line — so `registry_sync`'s
  `_registry_entry()` could never see it.
- The orchestrator's #777 self-heal (`.github/orchestrator.py`, `_registry_candidate()`) re-posted
  that delta **verbatim** under the anchor at `2026-11-24T17:28:34Z`, wrote its `registry entry
  applied` marker (`17:28:44Z`) and opened registry PR #1587. That PR went `CONFLICTING` and was
  **closed unmerged** on `2026-11-25T01:04:31Z`; the entry that *did* reach main arrived via
  registry PR **#1606** (`[registry] apply findings entries from #1457, #1458`, merge commit
  `38f0364`, merged `2026-11-24T23:59:56Z`).
- The landed copy is the **unqualified** one. On main today `providers.Porntrex.reason` ends
  "…So this is an IP/region refusal, not site death, not drift…" and
  `last_runs[run1458].canaries` ends "No degradation, so Blocked stays available (D8)" — the
  conclusion review round 1 removed from the docs is exactly what the machine record future runs
  read first.
- **Repair round 2** meant to fix that, but its "corrected anchored" comment
  (`2026-11-24T18:42:03Z`) also went out **unanchored** — a bare json fence, no anchor line — so
  `_registry_entry()` skips it and round 2's sentence "posted a corrected anchored comment" was
  false. That false claim is what review round 2 caught.
- **Repair round 3** (this PR, run
  https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/45527013372) posts the
  qualified entry **with** the `<!-- findings-registry-entry -->` anchor on #1458, carrying the
  same `reason`/`canaries` wording as the Blocked reason above (leading hypothesis / NOT
  excluded: operator-wide blanket refusal / falsification test: the same bare 403 from a
  different egress ⇒ site-wide, file it, not Blocked). `_registry_entry()` scans the thread in
  **reverse** and returns the latest anchored object, so that comment — not the self-healed
  `17:28:34Z` one — is the entry the thread now hands the next reader.

**What the anchored entry does *not* do: the file stays unqualified.** `registry_sync` dedupes by
**evidence**, not by the marker comment — it derives `applied = _registry_known_issues(merged)`
from the registry on main and skips any issue number in that set *before* reading the thread
(`if i["number"] in applied: continue`). `audits/findings.json` on main already records issue 1458
in `last_runs[].issue`, so #1458 is skipped and **no** entry from this thread, anchored or not, is
re-unioned by any later sweep. An earlier version of this section cited a `registry entry
applied` **marker** skip and pointed at #1587 as the live correction vehicle; both were wrong at
merge time — the marker no longer gates anything (it was removed with the evidence dedupe, #1583 /
#1598 reviews) and #1587 is closed.

**So the machine record is a maintainer action.** Correcting it means editing the two strings in
`audits/findings.json` on main — `providers.Porntrex.reason` and `last_runs[run1458].canaries` —
to the qualified wording above, or changing the machinery so a corrected entry can re-apply to a
story it already records. ADR-0013 keeps this lane out of the registry either way. The
`[backlog-report]` finding round 2 filed (**#1593**, "registry_sync cannot apply a corrected
anchored entry…") is built on the removed marker logic and should be **superseded** rather than
kept as the tracker of record — machinery too, so also a maintainer call.

**PR body (maintainer action).** The 05-implement body still states "so this is an IP/region
refusal, not site death" as fact and calls the registry delta "posted … anchored". This lane's
safe-output allow-list has no PR-body tool, so no repair round can rewrite it; the qualified
replacement paragraph is posted on #1458 with each repair report and must be applied to the PR
body by a human at merge.

Expected shape: `verdict: "blocked"`, `tier: "plain-curl"`,
`escalation_tier: available (curl_cffi 0.17.0 + Chromium 159) — all tiers 403`,
`drift_confirmed: 0`, `chronic: false`, `last_deep: "2026-10-16"`.
