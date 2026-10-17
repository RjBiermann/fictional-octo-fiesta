# FINDINGS-653 — XMoviesForYou deep audit (self-contained run of approved #631 spec)

Verdict: **ok** (healed state from #535/#547 confirmed live again; no drift since the
2026-10-14 deep pass). Tier: **tls-impersonated** on every surface — plain curl is
Cloudflare-challenged (home 403; bare `/search/<q>` 302 into a query-dropping
`/new-search` shell), so the ladder escalated per rung 2. Canaries 3/3 this run
(film1k plain 403 / TLS 200 — ladder healthy, eporner 200, pandamovies 302→200@.org).

- **Home** `/` + `/?page=2` — 200 both. Page 1: hero carousel `a[data-video-card]` ×30
  (10 unique hrefs — carousel repeats items, provider dedupes by href) + grid
  `a.group.flex.flex-col` ×24; page 2: 0 carousel anchors, grid ×24. `a.card` ×0 on
  both (home still serves no `a.card`; that shape stays search-only). 0 href overlap
  p1↔p2, 0 within-page dupes.
- **Search** `/search?q=red` → `/new-search?q=red` 200 ×24 `a.card`; `&page=2` 200 ×24
  `a.card`; disjoint p1↔p2; card anatomy unchanged (root `a.card[href]`, thumb
  `img[src]` real CDN webp + `alt` studio bracket title, `h3.title` text, runtime chip).
  Instrument note: bare path-form `/search/red` (no `q=`) 302-redirects to a bare
  `/new-search` that serves the "Enter a search query" shell — the provider has always
  used the `?q=` form, so no drift; recorded so a future probe doesn't chase the dead form.
- **quickSearch — absent (recorded, not drift).** `/api/search?q=…` and
  `/api/suggest?q=…` both 404; provider has no `quickSearch` override.
- **Video pages ×5** (home-carousel ×2, home-grid, search, category/anal listings) — all
  200 with h1, og:image, `const postId = "…"`, JSON-LD block, runtime/calendar chip,
  `div.prose` plot, category + pornstar anchors intact.
- **Related** `/api/related/<postId>` — 200 JSON `{"data":[{"title","slug","thumbnail_url"}]}`
  on 5/5 sampled postIds. API-served (no page DOM section), as recorded.
- **Stream (streamtape chain, shared StreamTAPE adapter path)** — `/v/<id>` page serves
  `expires=<n>&ip=<obfuscated-non-numeric-token>&token=<alnum-dash-underscore>`; adapter's
  regexes (`expires=([^&"']+)&ip=([^&"']+)`, last `token=([a-zA-Z0-9\-_]+)`) match the live
  shape — `ip` being an obfuscated string is already tolerated (no digit assumption).
  `get_video` → **302** → `tapecontent.net/radosgw/…` → **206 `video/mp4`** end-to-end.
  Note: the Oct-14 host-side `get_video {status:500}` exclusion (dc-IP, fresh tokens) did
  **not** reproduce this run — chain fully green from the same datacenter IP class
  (ok-suspected exclusion class, per-token transient; not provider grammar; not refiled).

## Gates

- `./gradlew bootstrapCloudstream` — pre-release classes.jar pulled (this sandbox had no
  seeded mavenLocal; CI seeds it automatically).
- `./gradlew :XMoviesForYou:test` — green, 8/8 in `XMoviesForYouParseTest` (plus shared
  splice tests green in the same run).
- `./gradlew :XMoviesForYou:make` — BUILD SUCCESSFUL, `.cs3` produced.
- `.pi/skills/audit-providers/scripts/check-findings.sh` — PASS.
- **0 confirmed conditions ⇒ no code change, version stays 14.** Standing-issue dedup:
  provider history #37/#103/#450/#467/#473/#535 all closed (three are
  `suspected_excluded`), `standing_issue` stays null, nothing refiled, nothing reopened.
