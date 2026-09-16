# FINDINGS-425 — PandaMovies: "search not giving same result seen in site"

Issue: the user cites the site search for `Rocco's intimacy`
(`https://pandamovies.pw/?s=Rocco%27s+intimacy`) and reports the provider's search
shows different results than the site.

## Audit (live, 2026-09-16) — search output matches the site exactly

Three independent reproductions of the exact query, all identical to the site:

1. **Plain curl** of `/?s=` (the site's own search form) and of the provider's URL
   `/search/Rocco's+intimacy` (space→`+`, apostrophe→`%27`, exactly what
   `URLEncoder.encode` produces) — **both return the same 8 cards, same order**:
   Rocco's Intimacy, Rocco's Intimacy 2, Rocco's 4 Cams POV (2/4/10), Rocco One On
   One 2/11. Space-vs-%20 encoding identical too. All parse cleanly with the
   provider's `Parse.cards` selectors (`div.ml-item > a.ml-mask[oldtitle]`).
2. **Real runtime path** — scratch NiceHttp test executing the provider's exact
   `app.get(url)` + `Parse.cards(...)` chain: 8 cards, byte-identical titles/hrefs/
   posters to the site page. Page 2 of this query returns HTTP 404 (no cards).
3. **Browser render** (headless Chromium): the site renders the same 8 cards after
   JS; no client-side reordering/additions. The site's own Site Kit pixel confirms
   the WP query internals: `arch_results=8`,
   `arch_filters=posts_per_page%3D40%26paged%3D1`.

Also verified against the deployed artifact: `PandaMovies.cs3` on the `builds`
branch is built from current master (commit `30d4b5e`), so app users run the
audited code — no stale-plugin explanation.

Hypotheses eliminated during the audit: app quick-search differences (app
`hasQuickSearch` defaults false; provider fine either way — site live-search
endpoint exists but only powers the site's own suggestion box), UA/header variants,
`+`/`%27`/`%20` encodings, WP caching/redirects, Cloudflare (no challenge on this
site from the runner), client-side rendering, and app-side NSFW search filters.

**Conclusion: for the cited query the provider's search output is indistinguishable
from the site's (count, order, titles, posters). Not reproducible as reported.**

## What the audit *did* surface: WP pagination semantics

- WP config exposes `posts_per_page=40`: search and listing pages carry **40 cards
  per page** while results exist; the site's own analytics pixel archives
  `posts_per_page=40`.
- Pages past the real end return a card-less 200 body (home/genre) or a 404
  (search), e.g. `/search/Rocco%27s+intimacy/page/2` → 404, `/movies/page/41` → 200
  with 0 cards. Verified pages 41–100 all card-less; pages ≤40 real and sequential.
- The theme renders **no pagination block** on `/search/`, `/movies/` or `/genre/`
  pages (the `ul.pagination` markup the PsyPlay theme ships is absent, so
  "pagination href" parsing is not an available signal — looked for it).
- The provider previously set `hasNext = true` unconditionally (documented
  tradeoff). Consequence: on short searches the app fired one (or a few) pointless
  trailing requests (404/empty) when scrolling past the end of results.

## Fix

**No code fix for the reported symptom** — for the cited query the provider
already matches the site exactly (see Conclusion above). There is nothing to
fix for #425 as reported.

What this PR ships is a separate, optional improvement the audit surfaced along
the way: `Parse.hasNextPage(document)` — a page with fewer than 40 cards is the
last real page: `hasNext = cards >= 40` now used by both `getMainPage` and
`search`. This is the WP rule the site follows (full 40-card pages may continue;
anything shorter is terminal), preserves the earlier overflow behavior for
genuinely long lists and stops short searches at their real end instead of
walking into 404 territory. It shortens the trailing-request tail but does not
eliminate it: result sets of exactly 40/80/… cards still fire one trailing
404/card-less request. If the maintainers want the reported behavior fixed
instead, the right PR is a reproduction; if they decide this pagination change
isn't wanted, it should move to its own issue.

### Per-surface page-size evidence (40-card threshold)

- search: 40 cards/page; terminal page short (cited query = 8).
- /movies: pages 1–40 real and sequential, all card-full; 41+ card-less.
- /genre/anal (deepest archive probed): pages 1–595 all serve 40 cards; the
  last real page 596 serves **39 cards**; 597+ → 404. A terminal short genre
  page is real, not asserted — the threshold holds there too (39 < 40 stops
  correctly; the old unconditional `hasNext` kept paging past it).

Verification: unit tests (page test + existing parse tests) green; full verify
session (this issue): search ✓ (8/40-card pages, page-2-404 semantics documented),
home ✓ (pages 1–40 real, 41+ card-less), genre ✓ (40/page, terminal page 596
= 39 cards → 404), video page ✓ (title/poster/
actors/plot/tags/duration/year all live-confirmed), related ✓ (18 cards), embed
anchors ✓ (per-stream: playmogo/mixdrop/voe registered in the shared HostRegistry;
stream body itself is 302+SPA — out of script scope as documented in FINDINGS.md).
