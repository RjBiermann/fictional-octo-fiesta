# FINDINGS #647 — JavGuru (jav.guru) audit / probe / fix, spec #631 round 2

**Run:** attempt 2, 2026-10-17 (UTC). Attempt 1 (run 38957959480) completed the identical
audit and posted its report on the issue, but its `create_pull_request` was rejected — it set
an explicit `base=master`, which the safe-output layer refuses (the default branch, `main`, is
filled when `base` is omitted). The rejected push discarded all artifacts, so this attempt
re-probed every surface fresh and delivers.

**Engine lane note (provenance):** this run executed on the gh-aw sandbox where stated.

## Provenance

- **Workflow run:** https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/39002334220
- **Trigger issue:** #647 (spec: #631 round 2)
- **Budget stamp:** `gh-aw budget: attempt=2 kind=fix run=https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/39002334220 credits=1`

## Verdict: `ok` — tier `plain-curl` on every surface

No surface needed escalation (browser step never reached); no Cloudflare challenge anywhere;
no confirmed condition → **no code change**. Provider `version` stays 28.

## Calibration

| Canary | Expected (plain-curl) | Observed | Match |
|---|---|---|---|
| film1k.com | 403 | 403 | ✓ |
| eporner.com | 200 | 200 | ✓ |
| pandamovies.pw | 302 → 200 @ pandamovies.org | 302 → 200 @ pandamovies.org | ✓ |

3/3 — instrument healthy, all verdicts at plain-curl tier are trustworthy.

## Surfaces

### Home (provider `getMainPage` Row 1)

- `https://jav.guru/` → 200; `https://jav.guru/page/2/` → 200.
- 24 × `div.inside-article` cards per page (plus CSS-text occurrences), **0 href overlap**
  between p1 and p2 (p1[0]=/1065162/dldss-556-…, p2[0]=/1065147/mfyd-219-…).
- Most-watched-rank row (`https://jav.guru/most-watched-rank/` → 200) uses
  `<article class="rank-item">` cards — caught by the provider's `article` selector
  (`title`/`href` from `a.rank-thumb-link`, poster from `img[alt]`).

### Search

- `/page/1/?s=teacher` → 200 (24 cards), `/page/2/?s=teacher` → 200 (24), **0 overlap**.
- `/search/{q}/` returns a card list **identical** to `/page/1/?s={q}` (24/24 hrefs equal).

### Quick search — ABSENT (recorded, not drift)

- The only search control is a static GET `<form action="https://jav.guru/" name="s">`
  (resolves to `/page/N/?s=q`).
- `admin-ajax.php` appears once, as the `wp-ulike-pro` vote endpoint config
  (`UlikeProCommonConfig.AjaxUrl`); POSTing a search-style action → **400**.
- `wp-json/wordpress-popular-posts` is the popular-posts API (200), not a suggest endpoint.
- Provider keeps `hasQuickSearch = false` (default). No drift.

### Video pages ×3 (varied listings: home / category-amateur / most-watched-rank)

All → 200. Per-page evidence:

| Field | Selector (provider) | Evidence |
|---|---|---|
| title | `h1.titl` | `[DLDSS-556] I'll impregnate…` / `[DNPS-005]…` / `[PPPE-447]…` |
| poster | `div.large-screenshot img` | `https://cdn.javmiku.com/wp-content/uploads/2026/10/1dldss556pl.jpg` — **cdn host still 403s; jav.guru rewrite serves 200** (issue #424 behavior intact; the provider's `parsePosterUrl` rewrite is required, not vestigial) |
| year | `div.infometa li:contains(Release Date)` ownText | `2026-10-20` → 2026 |
| tags/actors | `li.w1` rows | Tags:, Series:, Actor:, Actress: rows present (PPPE-447 has no Series row — page-shape variance, parser handles) |
| plot | `div.wp-content` | **empty on all 3 sampled posts** — the site ships no plot text; provider fallback stands |
| related | `div.woo-sc-related-posts li` | present on 2/3 posts; **the site natively omits the block on some posts** (YMDS-322 precedent) — not drift |

Exposure inventory: title ✓, poster ✓ (rewritten host), year ✓, tags ✓, actors ✓, duration ✗
(site does not expose), plot ✗ (site does not expose), score ✗.

## Streams (spec deep-pass focus: javmiku base64-iframe chain re-resolution)

`loadLinks` chain replicated end-to-end (python mirror of the Kotlin, all hops) on all 3
videos:

1. `"iframe_url":"<base64>"` → decodes to `https://jav.guru/searcho/?{xd,td,ed,cd,gd,hd,od}=…&bg=<poster>`
   (4–7 tiles per post; the mirror set **rotates per run** — see Watch items).
2. Loader page → `window.cfg = {base, rtype, cid, keys:['data-*']}` + `div#<cid>` data-attrs.
3. Concat attrs → reverse → `?xr=<token>` → **302** → per-mirror embed host.
4. **javclan packed-eval is BACK** — the 2026-09 hg-function.js-only window (FINDINGS re-probe
   section) has ended: `javclan.com/e/<hash>` again packs its jwplayer config in
   `eval(p,a,c,k,e,d)`; the shared `Javclan` extractor matches it (mirror verified with a
   python replica of `PackedJs.unpack` + the extractor's hls2/hls4 pairing). Resulting
   premilkyway `hls2/.../master.m3u8` → **200 / 200 / 206 `application/vnd.apple.mpegurl`**
   on the 3 sampled videos, referer-free (tested with no Referer, javclan referer, and
   jav.guru referer — all 200).
5. **Vidara still live**: `cd` tile on all 3 posts → `POST /api/stream` with
   `{filecode, device:"web"}` → `streaming_url` m3u8 (97bf1.com) → 200 (same content-type).
   Shared `Vidara`/`VidaraSo` rows cover it.

Every sampled video carries ≥2 independent working mirrors at probe time.

## verify.sh (mechanical, live)

- **Listing run — RESULT: PASS.** search p1/p2 (24+24, disjoint), home p1/p2 (24+24,
  disjoint), 1 video page 200 + fresh chain-resolved stream → 200 m3u8.
- **3-video run** — all 3 video pages 200 with `div.large-screenshot`; fresh chain-resolved
  stream overrides → **200/200/206 application/vnd.apple.mpegurl, distinct paths** (no stream
  dup); tags + year present on all 3; **search↔load title agreement clean** (the 3 sampled
  codes each found their own post, titles match after normalization); check 6 fields all
  populated in the provider Kotlin.
- **FAIL line adjudicated (instrument limitation, FINDINGS-635 disposition precedent):**
  `plot - video0,video1,video2` — the site exposes no plot text, so `dups` sees the `-`
  absent-column placeholder three times and calls it a collision. The check cannot express
  absent; the live evidence (empty `div.wp-content` on all sampled posts) is above.
- Related selector kept out of the mechanical bar for the same site-shape reason (block is
  natively absent on some posts; a per-video ≥1 bar would fake-FAIL healthy pages).
- **429 note:** jav.guru began serving transient 429s under the probe burst (verify.sh's
  retry only covers 5xx/ERR). A spaced re-run was clean — rate-limit, not drift; recorded as
  a Risks entry.

## Gates

- ✓ `bash .pi/skills/audit-providers/scripts/check-findings.sh` → PASS.
- ✓ verify.sh — PASS (listing run) / PASS-with-one-adjudicated-FAIL (3-video run, above).
- ✗ **`gradlew JavGuru:test` / `JavGuru:make` — not executable in this sandbox (honest
  deviation).** The egress policy 404s all of Maven Central (both `repo.maven.apache.org`
  and `repo1.maven.org` return 404 for known-good artifacts — jsoup 1.23.2 and junit 4.13.2
  poms verified 404 from this runner), the pre-provisioned Gradle cache is a 3.8 MB partial
  (no kotlin-stdlib, no netty-codec-http), and `bootstrapCloudstream`'s buildscript
  resolution therefore needs Central. The firewall is a policy boundary and was not bypassed.
  Materially: the tree carries **zero code changes** — the built surface is byte-identical to
  the last green CI build of `52b8166`; `build.yml` validates compilation at merge time, and
  unit tests remain covered (fixtures unchanged, no Parse function touched).

## Findings lifecycle

- 0 finding issues filed (no confirmed condition).
- Dedup: no standing, open, or recently-closed issues for JavGuru → no update targets.
- Registry: only the `JavGuru` entry updated (verdict `ok`, tier `plain-curl`, surfaces
  `+stream: ok`, `+quicksearch: absent`, `last_deep` 2026-10-17) plus one `last_runs` entry
  (`run26`). No fleet-wide rewrites.

## Watch items (not drift, no issue filed)

- Mirror tiles per post rotate between runs (attempt 1: td→megankiddifference,
  ed→streamhihi, hd→playmogo, od→maxstream, no `ud` tile; this attempt: vo/lu/dd/jk tiles
  with no registry adapter, no `ud` tube, one extra `gd` tile on PPPE-447). Every sampled
  post keeps the `xd` (javclan) and `cd` (vidara) tiles, both live. If javclan and vidara
  ever die together, the unhandled-tile set grows — that is the upgrade path (javclan
  hg-function.js deobfuscation remains the documented fallback from the 2026-09 episode).
- emturbovid's sandbox trap for non-NiceHttp clients (documented #267-era) did not apply —
  no `ud` tile was present in this sample.
- jav.guru transient 429s under request bursts (see verify.sh note).

## Risks / blockers

- Probe region: datacenter runner IP (US, ASN 8075 visible in premilkyway tokens). No
  Cloudflare challenge, no age gate, no geo-wall observed on jav.guru / vidara.to /
  javclan.com. vide0.net 403s the runner (unchanged, known).
- Site serves transient 429s to fast request bursts — in-app users are single-request
  paced; not a user-facing risk.
