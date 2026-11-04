# WatchPorn — FINDINGS (issue #278, data-completeness audit fix)

Site: https://watchporn.to (KVS, session-cookie-free GETs). Probe date: 2026-09-10.

## Quick search (the audited gap)

- Site JS: `$.autocomplete.defaults.serviceurl = 'https://watchporn.to/suggest/';` (homepage HTML).
- `GET /suggest/?q=<query>` → 200, JSON: `{"suggestions":[{"value":"<name>","data":{"type":"Models"|"Videos","url":"<absolute url>"}}]}`.
  q=deep transcript: 20 Models entries (`/models/<slug>/`), 20 Videos entries (`/video/<id>/<slug>/`). Saved as
  `src/test/resources/suggest-deep.json`.
- Implemented: `hasQuickSearch = true`; `quickSearch` parses with Jackson (`SuggestParse.videos`, provider-side object),
  maps `type == "Videos"` → `newMovieSearchResponse(value, "$url|", TvType.NSFW)`, skips Models.
- TDD (ADR-0005): `WatchPornSuggestTest` — 3 tests vs the live fixture (videos-only filter, known mapping
  "Alexa Payne …" → `https://watchporn.to/video/117655/.../`, count = 20, models skipped). Red (unresolved SuggestParse) → green.

## Floors verified live this run (verify.sh PASS, same session)

- Search: `/search/?q=milf&mode=async&...&from_videos=1|2` → 200, `div.thumb.item` → 35 cards each; page 2 disjoint from page 1.
- Card shape inside the listing: `<div class="thumb item"> <a href title>…<span class="thumb__title">…` (jsoup reads
  `span.thumb__title`/`img[data-webp]` with no truncation; only the script's mini-DOM needs `a[title]` cards — see Artifacts).
- Homepage: `/top-rated/`, `/top-rated/2/` → 200, 24 cards, disjoint.
- Video pages ×5 (58740, 91109, 2914, 28483, 110700): 200, `h1.single__content-title` non-empty, og:image present,
  related-videos (`#list_videos_related_videos_items`) 15 cards each, all disjoint from self.
- Streams: flashvars `video_url:` (720p) / `video_alt_url:` (1080p) `get_file` URLs with v-acctoken → 302 → **206 video/mp4**.
- Poster note: searcher would read `img` on original HTML — cards use data-webp; empty poster field at script level (all-or-none NOTE).

## Artifacts (script-side, not provider defects; evidenced live, cf. AllClassicPorn FINDINGS)

- check 2/2a: `h1.single__content-title` extracted via text fallback; plot selector `p.single__content-description`
  reads text but the script probes `content=` first and only title gets the text fallback → "no plot" NOTE. Plot is
  populated in `load()` (unit-covered pattern; audit verified live).
- Tags row (raw HTML): page has a `>Tags:</span>` info row with ZERO anchors, so the old `:contains(Tags:) a` selector yielded an empty list on every sampled page (issue #314). Genres live in the `Categories:` row (8 anchors, "single__info-tag") and in ld+json `"genre"` (same list). Fix: tags selector now `div.single__info-row:contains(Categories:) a`.
- tags/actors/year/duration: JSON-LD/KVS-shaped, not plain DOM rows for the script's field cmd — omitted selectors
  (NOTEs). Exposure is evidenced by `load()` assignments (check 6: recommendations/tags/plot/duration/actors all ≥2)
  and the audit's live greps (ld+json duration "PT0H36M40S", uploadDate 2023-03-24, Models row → actors).
- related selector: `div.thumb.item` inner truncation + like/dislike `a[title]` noise inside card roots forces
  `span.thumb__title` as the related selector in the script; jsoup path (`div.related-videos div.thumb.item`) in the
  provider is unaffected. All 15 related titles non-empty and distinct per page; sets contain no self-reference.
- check 1b: ran without `--quick-search-url` — the suggest endpoint returns JSON, not HTML cards; the script's
  card harness cannot assert it. Verified instead by: live transcript fixture + `WatchPornSuggestTest` (green) above.

---

# WatchPorn — FINDINGS (issue #649, KVS theme redesign drift fix)

Site: https://watchporn.to. Probe date: 2026-10-16 (deep re-check per approved #631 spec,
sub-issue #649). Runner IP: US datacenter (ddos-guard sets `__ddg9_` echo 132.196.20.106);
no Cloudflare challenge anywhere on the ladder; TLS tier never required.

## Verdict turn: ok → ok-drift (confirmed drift, fixed in-run)

**CONFIRMED DRIFT — full theme redesign.** The site shipped a new KVS theme (self-built
"wp-" classes) that killed every card and video-page selector the provider had (v15,
grammar from issues #214/#278/#314):

| surface | old selector (dead) | new selector (live 2026-10-16) |
|---|---|---|
| card title | `span.thumb__title` (0 matches) | `a.wp-ctitle` |
| card link | card-root `a[href]` | `a.wp-card__link[href]` |
| card poster | `img[data-webp] ?: img[src]` — `src` is now a 1px gif placeholder on most cards | `img[data-webp] ?: img[data-original] ?: non-data src` |
| card rating | `span.thumb__meta-item` (gone) | `span.wp-r` ("90%") |
| search pagination | `li.next` (gone) | `li.wp-pag__next` |
| video title | `h1.single__content-title` (0 matches → load() returned null) | `h1.wp-vi__title` |
| tags | `div.single__info-row:contains(Categories:) a` (row gone) | `a.wp-vchip` |
| actors | `div.single__info-row:contains(Models:) a` (row gone) | `a.wp-cast span.wp-cast__n` |
| plot | `p.single__content-description` (paragraph gone) | **site no longer exposes a real synopsis** — ld+json/og:description carry tag-glue or the site-wide boilerplate ("Free full-length porn videos in HD…") |

Net effect at probe time: search/home returned **0 parsed cards** and load() returned null
for every video — complete breakage, not cosmetic. Fixed in-run, TDD-first: pure
`WatchPornParse` object (legacy-theme selectors kept as fallbacks in every lookup for a
partial rollout), red → green in `WatchPornThemeParseTest` against the new live fixtures
`search-milf-1.html` (39 cards + 1 ad card) and `video-136954.html`; legacy fixture
`video-28447.html` still green through the fallbacks. version 15 → 16.

## Intact surfaces (re-verified live, plain-curl)

- Home: `/top-rated/` + `/top-rated/2/` → 200, 19 video cards + 1 identical ad card
  (`div.thumb.item.grid-ad`) each; p1/p2 video-cards disjoint. Site rows: `/categories/<slug>/`
  now **301 → `/sites/<slug>/`** (e.g. manyvids, onlyfans → cat1 19 cards, cat2 19, 1/19
  boundary repeat on fresh fetches — site-native, within-page always unique). `most-popular`
  unchanged shape. Home overflow `/top-rated/999/` → 200 with 38 cards NOT duplicating p1
  (clamps to a deep page, harmless); search overflow `from_videos=999` → 404.
- Search: same async URL (`from_videos=1|2`) → 200, 39 wp-cards + 1 ad card each, disjoint;
  titles inside cards are the short display form (performer prefix / studio suffix dropped —
  e.g. og "Ryan Keely, Shay Sights - Both MILFs Want To Fuck - Milfed" vs card "Both MILFs
  Want To Fuck"); `q=stepmom` same shape.
- Quick search: `/suggest/?q=<q>` → 200 JSON, 20 Models + 20 Videos entries — unchanged
  `SuggestParse.videos` contract (`suggest-deep.json` fixture + green test).
- Video pages ×5 (58740, 110700, 144806, 142799, 136954 — home/search-derived): 200;
  `h1.wp-vi__title` non-empty; og:image present; related `#list_videos_related_videos_items`
  15 wp-cards each (selector `div.related-videos div.thumb.item` intact), no self-reference.
  New quality/duration tokens visible in `div.wp-vmeta` (`span.wp-hd` "1080p", "1:12:06");
  JSON-LD VideoObject still carries duration `PT1H12M6S` / uploadDate (shared
  `JsonLdParse` minutes/year unchanged and covered by fixtures).
- Streams: flashvars contract unchanged (`video_url` 720p / `video_alt_url` 1080p get_file
  URLs with v-acctoken) — the WebView extractor needs no change. Live: each token → 302 →
  `srv*.zload.cc/remote_control.php?file=…` → **206 video/mp4**, and the session-bound
  note from 2026-09-10 did NOT reproduce — fresh cookie-less requests (referer or not)
  follow to 206; the anti-leech gif for a non-session replay class did not surface.

## Instrument-limited verify.sh notes (evidence-backed, not provider grammar defects)

- home/site-rows: FAIL "duplicate home cards" — the ad card's identical CSS/JS text is
  read as the title by the script's inner-text home pass (`--home-page` title '-');
  video cards are disjoint across pages. Ad slot payload identical on every page.
- cross-video collisions: `plot '-'` — `p.single__content-description` matches nothing
  (plot dead); the script's '-' placeholder for empty plot columns collides across
  videos instead of excluding them.
- search↔load title agreement: cards carry the short title without performer prefix /
  studio suffix; og:title/h1 carry the canonical full form — site-native display
  difference, the app flows store the card title and load() re-derives its own.
- poster agreement (58740-class cards where `src` is real): card `336x189/1.jpg`
  thumbnail vs og `preview.jpg` — same directory, different render; placeholder-src
  cards skip the compare (lazy branch has only data-src fallback, no data-original).

## Remaining NOTEs (year/duration)

Year/duration selectors are omitted from the script (JSON-LD-shaped, not plain DOM
rows); exposure is evidenced by `load()` (duration/year via shared JsonLdParse,
fixture-covered) and the live greps ("PT1H12M6S", uploadDate 2025-11-02).
