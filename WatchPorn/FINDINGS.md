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
- tags/actors/year/duration: JSON-LD/KVS-shaped, not plain DOM rows for the script's field cmd — omitted selectors
  (NOTEs). Exposure is evidenced by `load()` assignments (check 6: recommendations/tags/plot/duration/actors all ≥2)
  and the audit's live greps (ld+json duration "PT0H36M40S", uploadDate 2023-03-24, Models row → actors).
- related selector: `div.thumb.item` inner truncation + like/dislike `a[title]` noise inside card roots forces
  `span.thumb__title` as the related selector in the script; jsoup path (`div.related-videos div.thumb.item`) in the
  provider is unaffected. All 15 related titles non-empty and distinct per page; sets contain no self-reference.
- check 1b: ran without `--quick-search-url` — the suggest endpoint returns JSON, not HTML cards; the script's
  card harness cannot assert it. Verified instead by: live transcript fixture + `WatchPornSuggestTest` (green) above.
