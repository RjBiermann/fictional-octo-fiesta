# FINDINGS-537 — EPorner search pagination: /search/<q>/<n>/ collapses to the tag base

Live probe: 2026-10-10 (plain curl from this runner; headers via `-sI`, bodies via `-s`).
Fix branch: `devloop/issue-537`. TDD seam: `EPornerParse.pageUrl` (`EPorner/src/main/kotlin/com/byayzen/EPorner.kt`).
Test: `EPorner/src/test/kotlin/com/byayzen/ParseTest.kt`.

## The bug

`EPorner.search()` builds `mainUrl/search/<q>/<page>/` for pages ≥ 2. But `/search/<q>/<n>/`
**301-redirects** to the **tag base** `/tag/<q>/` — a two-leg rewrite:

```
/search/sex/2/   → 301 Location: /search/sex-r5M2q9RA/    (leg 1: suffixed hash form)
/search/sex-r5M2q9RA/2/ → 301 Location: /tag/sex/          (leg 2: page number discarded)
```

`/search/sex/` itself also 301s → `/search/sex-r5M2q9RA/` → (follow) → `200 /tag/sex/`.
NiceHttp follows redirects, so every in-app search page 2..N renders **page-1 tag results**
while the provider reports `hasNext` — duplicate rows with no way forward (issue reproducer:
"pages 2+ are duplicates"). Verified: the followed body of `/search/sex-r5M2q9RA/2/` is
identical to `/tag/sex/` page 1 (55/65 same card ids, 6/6 same sampled card hrefs), not page 2.

## Ground truth — status codes (2026-10-10, repeat× 5)

| URL | status |
|---|---|
| `/search/sex/` | 301 → `/search/sex-r5M2q9RA/` → 200 (tag view) |
| `/search/sex/2/`, `/3/` | 301 → `/search/sex-r5M2q9RA/` (same, still drops page) |
| `/tag/sex/` | 200 |
| `/tag/sex/2/` | 200 |
| `/tag/sex/3/` | 200 |
| `/tag/sex/1/` | 301 → `/tag/sex/` (only "1" folds; keep provider page-1 as the literal URL) |
| `/tag/sex/most-viewed/2/` | 301 → `/tag/sex/most-viewed/` (so **paged** sub-rows are broken by the site itself) |
| `/tag/sex/most-viewed/` | 200 |

## Ground truth — tag pages serve distinct content

Card container `div#vidresults` with `div.mb` cards (65 per page), and distinct
`data-id` sets confirmed by `comm -12`:

- `/tag/sex/` (p1) ∩ `/tag/sex/2/`: **0 of 65** — fresh cards.
- `/tag/sex/2/` ∩ `/tag/sex/3/`: 7 of 65 (small rotation drift).
- `/tag/sex/most-viewed/` ∩ `/tag/sex/`: 11 of 65 (sort shuffle, same pool).
- Direct body of `/tag/sex/most-viewed/2/` (curl, no redirect follow): **0 cards, no `div#vidresults`** — no server-side ACT-style preview either.
- `/tag/sex/1/` (followed): lorem-hop page, `data-id` count 0, "We have 0 videos".

Provider's page-1 search redirect target is `/tag/sex/` (i.e., tag page 1) — the same content
the provider currently gets via redirect-following. So the fix is: **route search pages 2+
explicitly to `/tag/<q>/<page>/`** (the bare `/tag/<q>/` needs no tag/paged/ACT fallback; use
the plain `/tag/<q>/<page>/` directly — no `?page=` per audit).

## Search-vs-tag equivalence

Provider yields `pageUrl = /tag/sex/N/` for search. The site's own search results CANNOT be
addressed page-wise through `/search/` at all (redirect always drops the page). Tag pages are
live ground truth and paginate properly. Search page 1 redirect-target (`/tag/sex/`) ∩ tag
page 2 (`/tag/sex/2/`) distinct card hrefs: **0** overlap — the tag path is the real
pagination surface.

## Side records (field-audit followups, not provider bugs)

- `li.vit-pornstar.starw a` and `span.valor a` are gone from watch pages; JSON-LD `VideoObject.actor` is absent — `actors` is empty site-side (provider already falls back to og:description; no data to add).
- `og:description` on watch pages no longer carries the "Starring:"/"X. Duration" comma variant either; more data is simply not exposed.
- No `&amp` HTML-entity breakdown observed (issue #536/#534's real pattern — `&asin=` became `&asin=` — does not reproduce under the new `/search/<q>-<hash>/` → `/tag/<q>/` scheme).
- `It` was not a bot (no user-agent flag); reported quotes existed but not for provider actions.

## Side note (not from this issue's audit run, but observed while probing)

The tag ordering `/tag/sex/top-monthly/` exists (a `/tag/<q>/?include=4k-porn` style
variant does not tilt this bug; unrelated).

## Fix ledger (eporner previous issues)

- #293 — segment-swap pagination (`/2/<list>/` for most-viewed / longest).
- #533 — page-field request for tag pages (see issue). Closed as already working at that time; this issue is adjacent but about the tag-vs-search ranking, not the page field.
- #536 — category rows: `&amp` entity encoding (see repo finding; field audit only).

## Chosen fix

`EPornerParse.pageUrl` maps `/search/<q>/` → (`/tag/<q>/` for page 1, `/tag/<q>/<page>/` for
pages ≥ 2). Page 1 stays as the natural URL the site itself redirects to. All other rows keep
their existing behavior (root as-is; segment-swap rows per #293; other lists path-suffix per
site as probed above, including `/tag/sex/<page>/` and `/cat/<x>/<page>/`).

Side-recorded for the field audit (not a provider bug): those observed in `EPorner/load()` —
`li.vit-pornstar.starw`, `span.valor`, `div.vit-sub-left`, JSON-LD `VideoObject.actor` are
absent, and og:description no longer ships the "Starring:"/"X. Duration" comma variant. The
site exposes no actor data at the moment; `actors` is empty site-side.
