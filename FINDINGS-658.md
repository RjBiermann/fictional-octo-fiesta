# FINDINGS-658 — Javtiful (javtiful.com) deep audit, 2026-10-17

Self-contained run of the approved #631 spec (sub-issue of #631). Probe → verdict → findings-lifecycle → delivery.

## Verdict: **ok** (retained) — no confirmed condition, no code change, version stays 13

Tier **plain-curl on every surface** — the escalation ladder (plain curl → TLS-impersonated curl → browser diagnosis) never advanced past step 1: no surface was blocked or challenged.

Canaries 3/3 match the standing pattern: film1k plain-curl 403 (TLS tier serves 200), eporner 200, pandamovies 302→200 @ pandamovies.org. Probe conditions healthy.

## Probe record (instrument tier per surface)

| Surface | URLs | Result |
|---|---|---|
| Home ×5 rows p1+p2 | `/videos`, `?sort=most_viewed`, `?sort=top_rated`, `/uncensored`, `/category/milf` | all 200 ×23 non-partner cards; **0 href overlap p1↔p2 every row**; live `a.pagination__link` Next (Previous `is-disabled` on p1) |
| Search p1+p2 | `/search?q=kana`, `/search?page=2&q=kana` | 200, 23+23, **0 overlap** |
| quickSearch | `search/suggest`, `/suggest`, `/quick-search`, `/api/search` | **absent** — all 404; search input `autocomplete=""`; no suggest JS — recorded absent (spec §1: absent ≠ drift) |
| Video ×9 | 4× newest, 2× most_viewed, 2× search-hits, 1× related-hop, 1× category-adjacent | all 200; full load fields (title h1 / og:image / og:description / JSON-LD duration / Added-on year / chips / actor cards); related 12–21/page |
| Stream chain | `script#watch-config` `playerSources` → `fast-stream.jav.si/p/<token>` ×3 fresh tokens | **3/3 → 206 `video/mp4`**, no referer/cookies, bare `okhttp/4.9.2` UA also 206 |
| SPA root | `/` → 301 → `/main` | SPA-root distinction stands (not a provider row — never file dead-pagination against it) |
| Search↔load | IESP-763 card title vs `div.watch-title h1` | **exact match** |

## Site-shape observations (recorded, not drift)

1. **`top_rated` renamed `most_liked` site-wide.** The old key is a live alias: `/videos?sort=top_rated` 200 serving the identical `most_liked` listing (23/23 href overlap); the page's own pagination anchors canonicalize to `sort=most_liked`. The provider's Top Rated row functions fully via the alias (p1/p2 disjoint). **Resilience watch**: if the alias drops, the row breaks — remediation is a one-line `mainPage` data switch to `most_liked`. Not filed (no confirmed condition today).
2. Commented-out `top_favorites` / `being_watched` keys now alias to the default newest listing (23/23 overlap with `/videos`). Stay commented.
3. Same-page same-title variant rows: censored + reducing-mosaic releases of one code, different hrefs (e.g. `114223/iesp-763-reducing-mosaic` + `113965/iesp-763`), 3 pairs per search page — the pre-documented quirk the provider dedupes by title (`distinctBy { it.name }`, test-covered).
4. Some releases are actor-less (NCYF-055, TIKB-229 carry no `a.watch-actor-card`) — optional-actor handling in code is correct.
5. `top_rated` p1↔p2 overlap 1/23 — boundary churn from live-listing insertion between fetches; all other rows 0. Not duplication.

## Findings lifecycle

- Dedup: no open standing issue for javtiful; closed record **#534** (site redesign, fixed by #548/v13) — the #548 fix holds on every surface, so per the non-refile rule **#534 stays closed, nothing refiled**. No new finding issue (no confirmed condition). 0 false positives, 0 dupes.
- Registry: `audits/findings.json` Javtiful entry updated only (verdict ok; tier plain-curl; surfaces incl. quickSearch-absent; `last_deep` 2026-10-14 → 2026-10-17; `drift_confirmed` stays 1) + one `last_runs` entry (run25). No fleet-wide rewrite.

## Gates

- `gradlew Javtiful:test` — **PASS** (fixtures unchanged: live shape identical to the 2026-10-09 saves).
- Clean `gradlew Javtiful:make` — **BUILD SUCCESSFUL**.
- `verify.sh` ×6 live invocations (main: search p1/p2 + 4 videos + stream overrides + related + tags + year + LoadResponse 7/7; 5 home rows p1/p2 per-row): **PASS on every mechanically-expressible surface**. One instrument-limited FAIL line adjudicated: the mandatory search listing's 3 same-page variant-title pairs trip the title-dedup — different hrefs, identical title, site-native; the provider dedupes them by design. NOTEs: quickSearch absent; actors optional (variance); duration JSON-LD-only (out of pydom reach). Full record in `Javtiful/FINDINGS.md`.
- `check-findings.sh` — **PASS**.

## Changed files

- `audits/findings.json` — Javtiful entry + run25 last_runs entry only
- `Javtiful/FINDINGS.md` — 2026-10-17 audit section prepended
- `FINDINGS-658.md` — this report
