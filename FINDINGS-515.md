# FINDINGS-515 — vintage/classic homepage rows across movie providers

Issue: *"For all movie providers, add vintage porn and/or classic porn row in homepage."*

Every URL below was probed live 2026-10-03/04 (curl UA Firefox/130 Linux x86_64; CF-challenged
sites re-fetched via `impersonate.sh` TLS-impersonation, curl_cffi 0.16.3 — tier marked per
provider; matches each provider's existing tier in `audits/findings.json`).

## Verdict table

**9 additions, 4 already satisfied, 13 recorded absences.**

| Provider | Verdict | Row |
|---|---|---|
| EPorner | add | `"$mainUrl/cat/vintage/" to "Vintage"` |
| Porntrex | add | `"$mainUrl/categories/vintage/" to "Vintage"` |
| HQPorner | add | `"${mainUrl}/category/vintage" to "Vintage"` (fully commented row existed at file bottom) |
| PerverZija | add | `"${mainUrl}/tag/classic/" to "Classic"` |
| Film1k | add | `"$mainUrl/category/classic-porn" to "Classic Porn"` |
| FreePornVideos | add | `"$mainUrl/categories/vintage" to "Vintage"` |
| FullPorner | add | `"${mainUrl}/category/vintage/" to "Vintage"` |
| PornXP | add | `"$mainUrl/tags/Vintage" to "Vintage"` |
| Xhamster | add | `"${mainUrl}/categories/retro" to "Retro"` **+ parser extension** |
| Sexfilm | already | `"$mainUrl/movies/vintage/" to "Vintage"` exists |
| Cat3Movie | already | "Classic Porn" + "Classic Erotica" rows exist |
| Eroticmv | already | "Classic Games" + "Classic erotica" genre rows exist |
| AllClassicPorn | already | entire site is vintage (home *is* the vintage grid) |
| JavGuru | absent | `/tag/classic/` + `/tag/vintage/` 404 |
| Javbangers | absent | tag sidebar lacks classic/vintage; `/tag/vintage/` 404 |
| Javtiful | absent | `/tags` has no classic/vintage entry |
| MissAV | absent | `/en/classic` 404; type filter has no vintage |
| Javseen | absent | `/tag/classic` exists but `?ajax=browse_videos` total=0 (empty tag) |
| Javmost | absent | `showlist2` AJAX groups: `vintage`/`retro` total=0; `classic` total=3 — not a row |
| Mangoporn | absent | audit verdict `blocked residential-differential`; settings-driven rows list has no classic category; genre page `/genre/classic/` exists (49 post-ids p1/p2, 0 overlap) but the provider's `div.items > article` home parser matches 0 cards on fresh home HTML (0 `class="items"` matches); grid moved to `video-block` markup |
| Neporn | absent | categories Amateur…Toys, no classic/vintage |
| WatchPorn | absent | `/tag/classic` empty grid |
| ixiporn | absent | no classic/vintage in its fixed category rows |
| XMoviesForYou | absent | no classic/vintage in `/categories` or `/tags` (43 categories) |
| Cat3Film | absent | has a year filter, but no classic/vintage category surface |
| PandaMovies | absent | settings category list has no classic/vintage |

## Per-provider probe transcripts (the 9 additions)

### EPorner
- `GET /cat/vintage/` → 200; 44 `div.mb.hdy` + 20 plain `div.mb` cards inside `div#vidresults`
- `GET /cat/vintage/2/` → 200, 61 cards; `EPornerParse.pageUrl` path-suffix pagination resolves (`/cat/vintage/2/`)
- verify.sh home check: 64 / 62 cards, **site-owned sticky "popular" cards repeat** inside a page and across pages (verified IDs 14069445, 13308277, 11788724, 18034847, 15344593 on both) — site behavior, present on search pages as well; not pagination failure
- tier: plain-curl

### Porntrex
- `GET /categories/vintage/` → 200; 120 `data-item-id` cards (`div.video-preview-screen.video-item`)
- page 2 (provider's AJAX endpooint): `?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&from=2` with `X-Requested-With: XMLHttpRequest` → 200, 120 cards, **0 id overlap** (re-probed fresh before commit)
- verify.sh: search 84 hits; home 120/120 matches; the script's home title check needs a `--home-title-selector` flag it doesn't have (prints resolution labels as titles → false "duplicate" lines); real title overlap computed card-by-card = 0
- tier: plain-curl

### HQPorner
- `GET /category/vintage/1` → 200, 44 `/hdporn/` links specifically in section area, 24 unique `/watch/` ids on page-1 card set; `GET /category/vintage/2` → 51 `a.image` matches, 24 unique/0-overlap real card ids
- verify.sh run: 45/51 `a.image` matches; duplicate-href hit is a placeholder ad tile (`href="#"`) present on both pages — not real cards; real `/watch/` cards 0-overlap (fresh)
- tier: plain-curl

### PerverZija
- `perverzija.com/tag/classic/` 301 → `tube.perverzija.com/tag/classic/` 200; p1 64, p2 64 titled cards **0 overlap** (provider semantics: `div.col-md-3:not(#sidebar)` + `img[title]` — probe confirmed `col-md-3` markup present; `xs-related-title` variant is a different widget)
- `/tag/classic//page/2/` (double slash the provider's `${request.data}/page/$page/` produces) → 301 to canonical → 200 with content
- verify.sh raw-dup FAIL lines were sidebar/footer tag widgets sharing `col-md-3` wrappers; provider-semantics check clean
- tier: plain-curl

### Film1k
- plain curl on every `/category/classic-porn*` URL → 403 cf-mitigated challenge — never probe evidence; fetched via `impersonate.sh` → 200 "Classic Porn Archives", 24 unique `article.loop-post > header.entry-header > a` cards; `/category/classic-porn/page/2` 200, 0 real overlap (first-pass impression of 1-overlap was the *Top 10 Popular* sidebar widget `film1k-popular`, not a listing loop card)
- also `/category/classic-erotica` 24/24 0-overlap (page 2 of 4) for evidence breadth
- tier: **tls-impersonated**

### FreePornVideos
- plain curl: `/latest-updates/` ✓ 200 but `/categories/vintage(/2/)` steadily 403 — the visibility of the site's home-grid and the vintage category differ; fetched via `impersonate.sh` → `/categories/vintage` 200, 24 `div.item` cards (`a.thumb_img href=/videos/...`); `/categories/vintage/2/` 200, 24 unique, 0 overlap
- provider pattern `${request.data}/$page/` resolves the same shape
- tier: **tls-impersonated** (matches audit `ok-suspected tls-impersonated`)

### FullPorner
- plain curl 403 challenge everywhere; `impersonate.sh` → `/category/vintage/` 200, 24 unique `/watch/<hash>` video cards in `div.video-card`; p2 = `/category/vintage/2/` (`${request.data}${page}` pattern) → 24 unique cards, **0 overlap** (verified with and without trailing slash — both 24 cards via TLS-impersonated fetch, `a∩b=0`)
- provider already ships `cloudflareKiller` + shared `cfChallenge` interceptor for challenge handling in-app
- tier: **tls-impersonated**

### PornXP
- `pxp.news/tags/Vintage` → 200; 36 `div.item_cont` cards (`data-id`), `?page=2` → 36 cards, **0 overlap** (re-probed fresh before commit)
- that mainUrl (`pxp.news`) is the live canonical; `porntubezone.com` (an old redirector target) `/tags/` route now 404s; `porn-xp.eu` (banner backup domain) `/tags/Vintage` works — record only; provider's `mainUrl` = pxp.news and is still canonical
- verify.sh full run (tag-page search equivalent — provider's own search uses the same `/tags/$q` endpoint as home): script passes search + home; `--video-title-selector '.player_details h1'` picked up the site's banner `<h1>` ("New Backup Domain: porn-xp.eu") because `verify.sh`'s simple CSS matcher only supports single-token selectors; provider semantics local check: card title ↔ `.player_details h1` agreement true on 4 sampled videos (Vintage Collection - Provocation / A Fine Vintage / Vintage Gman / Vintage Pleasure); no poster/plot/duration on the video page (excluded per script NOTEs)
- tier: plain-curl

### Xhamster — parser extension, not just a row
- `GET /categories/retro` → 200 HTML "Retro"; **`layoutPage` key is absent on category pages** — cards live in `<script id='initials-script'>window.initials=...` → `pagesCategoryComponent.trendingVideoListProps.videoThumbProps`
- The current `cardsFromInitials` (searchResult → layoutPage.videoListProps → layoutPage.trendingVideoListProps) then returns EMPTY — the row would have rendered blank
- Fix: `InitialsJson` gains `pagesCategoryComponent: PagesCategoryComponent?` (new data class with `trendingVideoListProps: VideoListProps?` — the video-list type already used by the other routes), and `cardsFromInitials` adds the fourth fallback; card shape (`title` / `pageURL` / `thumbURL`) is identical to the already-parsed `VideoThumb` — no other delta
- TDD: `XhamsterParseTest.`category page initials cards parse from pagesCategoryComponent`` — fixture `xhamster-home-retro.html` = the live `window.initials` JSON block from the same probe (red → green committed)
- Live pagination: `pagesCategoryComponent.paginationProps.pageLinkTemplate = "https://xhamster.com/categories/retro/{#}"`, `lastPageNumber=2189`; fresh p1/p2 fetches land 46/46 cards, **0 overlap** (an earlier capture showed 9 duplicates from custom widget positions — a rank shuffle artifact; not reproducible post-sweep — see § Site-behavior quirks)
- Real video pages + streams checked live via verify.sh: five sampled `/videos/...` pages from the retro row each returned 200 with m3u8 stream URLs extracted by the script's HLS fallback patterns → stream requests serve `application/vnd.apple.mpegurl` (206)
- tier: plain-curl

## verify.sh scope note

Each of the 9 changed providers' homepage-row checks (check 1a — verify.sh's homepage-row pass) used the
recorded selectors. The FAIL lines recorded above are script-matcher and site-widget artifacts, not
caused by the changed rows: (1) the verify.sh simple-CSS matcher's precision limits (PornXP: the
banner `<h1>` shares the `.player_details` scope with the real title; Porntrex: the script has no
`--home-title-selector` flag, so the title check prints resolution labels instead of card titles),
and (2) site-owned widget markup inside the listing pages (EPorner sticky "popular" cards, HQPorner
placeholder ad tile, PerverZija sidebar/footer tag widgets). Both causes are sitewide and
branch-independent — the same FAILs reproduce on master's home-page checks, which render the
identical markup; this diff only adds rows. Streams were verified through the script's m3u8
fallback patterns (see the Xhamster transcript above).
Provider stream/search behavior is unchanged by this diff; the last per-provider audit
(`audits/findings.json`) covered it and no live probes here contradicted it.

## Site-behavior quirks (recorded, not code-changed)

- **EPorner**: sticky "popular" card IDs repeat *inside* one listing page and again across pages
  (both on search and category paths) — page-1∩page-2 artifact; site-wide, not session/rank-dependent
  transient. Verified identical on `/cat/vintage/` and `/search/vintage/` probe sets.
- **Xhamster**: on category pages, repeated card IDs (channel `"Old Good Porn"` etc. featured
  landing tiles) can appear at both top-of-page widget slots and bottom — verified on stale
  capture; a fresh 2026-10-04 sweep showed 0 dup across p1/p2 (rank shuffle). The parse fix drops
  nothing: card-level collapse in the provider would discard tiles the site deliberately shows in
  multiple grid positions.
- **Film1k**, **FreePornVideos**, **FullPorner** — plain curl from datacenter egress yields cf-mitigated
  challenges on most root urls across the whole site; TLS-impersonated fetches pull the same surfaces cleanly.
  Same tier conclusion as `audits/findings.json`.
- **HQPorner** — one `a.image` element per page serves a placeholder ad tile with `href="#"`;
  the dup-check linked both pages to this non-card shape (not a real duplicate).
- **PornXP** — the video page carries a banner `<h1> "New Backup Domain: porn-xp.eu"` that shares the
  `.player_details` scope with the real title h1 in `verify.sh`'s coarse matcher only; the actual
  title parse in Kotlin (`.player_details h1` in a scoped select) gets the correct video title on every
  sampled page.

## Code deltas (this branch)

- 9 Kotlin providers: one new `mainPageOf` row each (+1 fix, see below) — no parser changes elsewhere
- Xhamster additionally:
  - `data class PagesCategoryComponent(val trendingVideoListProps: VideoListProps? = null)` + read key in `InitialsJson`
  - one new `cardsFromInitials` fallback term
  - TDD fixture `Xhamster/src/test/resources/xhamster-home-retro.html` (real `window.initials` JSON block) + new test
- 9 build.gradle.kts `version` bumps (EPorner 16→17, Porntrex 16→17, HQPorner 12→13, PerverZija 12→13, Film1k 9→10, FreePornVideos 10→11, PornXP 9→10, FullPorner 13→14, Xhamster 21→22)

## Checks run before the PR

- `gradlew test` on Xhamster: red (new test) → **green** after the parse extension; all prior fixtures still pass
- `gradlew <P>:make` for all 9 → `.cs3` built for each
- The comment above outlines all post-build verification (`verify.sh` runs + local-equivalent probes)
