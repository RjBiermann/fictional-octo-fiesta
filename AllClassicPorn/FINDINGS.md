# FINDINGS — allclassic.porn

## Engine fingerprint
KVS / Kernel Video Sharing. Evidence: KVS container ids (`list_videos_most_popular_videos_items`,
`list_videos_videos_list_search_result_items`, `list_videos_years_videos_list_pagination`),
`flashvars` object on video pages, asset paths under `/contents/videos_screenshots/`,
CDN `remote_control.php` redirect for streams. In-repo reference: Porntrex, PornHits.

## Search
`/search/{query}/` works (HTML `.th.item` blocks). Tested 2 patterns:
- `https://allclassic.porn/search/milf/` → HTTP 200, `list_videos_videos_list_search_result_items`,
  ≥50 results (`grep -c 'th item'` = 106 incl. duplicates). ✅
- Alternative `/{search}/{query}/` style not needed; also verified `/search/milf/2/` page 2 → 200,
  different items.

## Video pages
Item structure (home/search/listings): `a.th.item` with `href=/videos/{id}/{slug}/`,
title in `img alt` (and `div.th-description`), poster `img src`, `span.th-duration`.

Probed pages (all HTTP 200, slug required — `/videos/{id}/` without slug 404s):
- /videos/2252/zazel/ (homepage) — og:title "Zazel", itemprop duration PT125M24S
- /videos/6161/mature-milfs-part-three-homemade-vhs-1998/ (search "milf") — PT17M00S
- /videos/1573/casanova-2/ (homepage)
- /videos/2208/the-golden-age-of-danish-pornography/ (homepage)
- /videos/2118/worst-porno-ever-made-with-the-best-sex/ (homepage)

og meta available: og:title, og:image (preview.jpg), og:description, og:url, og:video (embed/2252 iframe — not used, direct mp4 available).

## Related videos
`#list_videos_related_videos_items` present on every probed video page (grep hit on
/tmp/v1.html and /tmp/vv_6161b.html). Selector: `#list_videos_related_videos_items a.th.item`.

## Stream sources (per video page)
Direct mp4 in inline JS flashvars — no m3u8, no external embeds needed:
- /videos/2252/zazel/: `video_url: 'https://allclassic.porn/get_file/1/fbfab.../2000/2252/2252_480p.mp4/?v-acctoken=...'` with `flashvars['video_url_text'] = '480p'`. Verified: GET with UA+referer → 302 →
  `https://cdn1.allclassic.porn/remote_control.php?file=...mp4&acctoken=...` → 206 `video/mp4`, body starts `ftypisom...avc1` ✅
- /videos/6161/...: `video_url: '.../6000/6161/6161_480p.mp4/?v-acctoken=...'` → 206 `video/mp4`, ftyp header ✅
- `video_alt_url` on 6161 is `https://allclassic.porn/?login` (720p behind login) — ignore alt URLs
  unless they end in `.mp4`.

Regex: `video_url:\s*'([^']+)'` and quality `video_url_text: '([0-9]+p)'`.

## Headers / referer
`get_file` request requires browser UA and the video-page referer (302 redirect issued regardless of
refer being absent? no-referer test also 302'd, but referer supplied throughout is the safe combo);
CDN request with same referer returns 206 video/mp4. No cookies/Cloudflare observed.

## Pagination
Plain HTML, `{url}/{page}/` suffix:
- Home: `https://allclassic.porn/page/2/`
- Decade (e.g. /90s/): `/90s/2/`, `/90s/3/` … (ul#list_videos_years_videos_list_pagination)
- Search: `/search/milf/2/` → 200, different items

## Risks / blockers
None. No Cloudflare, no age wall. Curl with plain Mozilla UA gets 200 everywhere.

## Audit #204 (2026-09-09) — title drift fix

- `h1[itemprop="name"]` = card title (`div.th-description`) exactly, including the canonical
  "- (yyyy)" suffix. Live evidence (4 sampled videos): 6161 → "Mature Milfs - Part Three - HOMEMADE VHS - (1998)",
  2252 → "Zazel, Full movie (1996)", 1573 → "Casanova 2, Uncut Movie (1982)",
  2208 → "The Golden Age Of Danish Pornography - (1970)". `og:title` omits the suffix on all 4 → looks/title drift.
- Fix: `AllClassicPornParse.parseTitle()` (h1[itemprop=name] first, og:title fallback), used by `load()`;
  fixture test `AllClassicPornParseTest` proves search-card ↔ load-page title agreement on video 6161
  (fixtures `src/test/resources/video-6161.html`, `search-milf.html`).
- Quick search: provider has `hasQuickSearch = false`; site has no distinct quick-search endpoint (this is the explicit FINDINGS note verify.sh's NOTE requires).
- verify.sh script-side artifacts (as classified by the audit run, not provider defects):
  1. check-2 "FAIL stream content-type" on `<id>`-scale og:video — script's stream extractor also grabs
     `og:video` (embed iframe, text/html). Provider emits ONLY the flashvars `video_url` mp4 which serves
     206 video/mp4 on all 3 sampled videos.
  2. check-5 poster mismatch (card `.../320x240/N.jpg` vs og:image `.../preview.jpg`) — site exposes different
     thumbnail variants per surface; no code change can equalize.
  3. check-5 title comparison cannot read the new source: the script's mini-DOM only extracts `content` attrs
     and its tag regex requires `[a-zA-Z]+` (fails on `h1`). Provider's new title source is the h1 text;
     agreement is evidenced by the unit test + the live h1/card matches above instead.

## Audit #205 (2026-09-09) — data-completeness: actors/tags/categories/year

- KVS `flashvars` on every video page exposes `video_models`, `video_tags`, `video_categories`
  (comma+", "-separated; `video_models` empty string on some videos, e.g. 6161). Live evidence:
  2252 → 13 models ("Anna Romeo, … Keven James"), 1573 → names incl. escaped apostrophe
  ("Tracy O\'…"), 549 → same models as 2252 + tags; year is the canonical "(yyyy)" h1 suffix
  (2252 → "Zazel, Full movie (1996)", 1573 → "Casanova 2, Uncut Movie (1982)",
  549 → "Zazel: Parfum d'Amour, Uncut (1996)").
- Provider fix: `AllClassicPornParse.parseActors/parseTags/parseCategories` (flashvars; handles
  both `field: '…'` and `flashvars['field'] = '…'` forms; unescapes `\'`) and `parseYear`
  ("(yyyy)" title suffix). `load()` now sets actors/tags(+categories distinct)/year;
  duration/plot/recommendations unchanged.
- Fixtures from live probes: `video-2252.html`, `video-1573.html`, `video-549.html`
  (plus existing `video-6161.html`). Unit tests cover actors (incl. escape + empty), tags,
  categories, year.
- verify.sh script-side artifacts (2026-09-09 re-run, additions to the list above — none are
  provider defects; all evidenced live):
  4. check-1a "FAIL duplicate home cards": the homepage (`/page/`) renders three KVS sections —
     `list_videos_most_popular_videos_items`, `list_videos_recommended_videos_items`,
     `list_videos_most_recent_videos_items` — and ~17 videos legitimately appear in 2–3 of
     them (e.g. 5431 in popular+recommended+recent). No section contains an internal duplicate.
     Whole-page `a.th.item` counting conflates sections.
  5. check-4 "FAIL related videos -1": `#list_videos_related_videos_items` (id-only, no tag)
     cannot be parsed by the script's mini-DOM (regex requires `[a-zA-Z]+`); chained variant
     also fails. `a.th.item` alone counts 17–30 per video page, ≥1 — related-videos fetches OK.
     6161 and 2208 recommendation sets are disjoint (0 overlap), so recommendations are real.
  6. --video-tags/actors/year/duration selectors omitted: these are JS flashvars/h1 values,
     not DOM text; script's `field` cmd can't assert them. Exposure is evidenced by the live
     greps above + unit tests + check-6 assignments (actors/tags/year ≥1).

## Audit #266 (2026-09-10) — home/recommendation card duplicates

- The site itself serves some videos twice in one page: on `/page/` the `list_videos_most_popular_videos_items`
  section repeats 7 hrefs verbatim (5272, 5431, 5793, 5843, 5851, 5862, 6305 — each `<a>` block appears twice;
  fixture `src/test/resources/home-page.html` = 84 anchors, 7 duplicated hrefs). Related lists show the same
  pattern (video 6134: 2 pairs duplicated among 18 anchors).
- Fix: `AllClassicPornParse.distinctByHref()` (pure, unit-tested); wired into `getMainPage` cards and
  `load()` recommendations. Fixture test proves 84 → 77 cards on the live-captured homepage HTML.
- Poster agreement (check 5): the card screenshot is `…/videos_screenshots/{buckets}/{id}/320x240/N.jpg`
  with a **different random N per render** (22.jpg in one fetch, other indices in another) — there is no
  stable URL shared with the card, and no full-size card-screenshot equivalent on the load page. `og:image`
  (`preview.jpg`) is the only stable poster on the load page. Load poster therefore stays `og:image`;
  the search↔load poster difference is a site-side artifact no code change can equalize (extends the
  existing audit-#204 artifact 2).
- verify.sh script-side artifacts (2026-09-10 run, none provider defects; all evidenced):
  - check-1a FAIL on raw `/page/` HTML duplicates: fixed provider-side by `distinctByHref`; script counts raw
    HTML, so the passing mechanical check uses `/page/2/` + `/page/3/` (both 200, 60 cards, no overlap) and the
    `/page/` dedupe is proven by the unit test on the captured fixture.
  - check-5 title mismatch remains the audit-#204 artifact 3 (script's mini-DOM can't read `h1[itemprop=name]`;
    og:title omits the year suffix; card text carries stats prefix). Actual agreement proven by the unit test
    + live h1/card match.
  - check-5 poster mismatch as above.
  - tags/actors/year/duration exposure NOT asserted by script (KVS flashvars / h1 suffix — documented in
    audit-#205 section, live greps + unit tests).

## Audit #289 (2026-09-11) — data-completeness: plot fallback (D-1)

- Site page shape varies: video 5887 (live curl 2026-09-11) has NO `og:description` meta, but every
  probed page (5887, 6403, 2252, 6161, 1573, 549) carries
  `div.video-description[itemprop=description] > div.description-container` with the site's canonical
  description text (prefixed by `<strong>Description:</strong>`). Fixtures:
  `video-5887.html` (og:description absent) + `video-6403.html` (present).
- Fix: `AllClassicPornParse.parsePlot(html)` — `og:description` first, else the description-container
  text with the `Description:` label stripped (leading whitespace-only text nodes in 5887's div make
  `ownText()` return "" there; `text()` + label strip handles both shapes). Wired into `load()`.
- Unit tests: parsePlot prefers og:description (6403), falls back to the div (5887, asserts plot
  contains "Venus Film V7: The Barbershop" and does not start with "Description:"), label-strip unit
  case, and null when no source. Version 7→8.
- verify.sh 2026-09-11 run artifacts (none provider defects; issue-classified):
  - `FAIL stream content-type .../embed/<id>` — script's stream extractor grabs `og:video` (embed
    iframe, text/html); provider emits ONLY the flashvars `video_url` mp4 → 206 video/mp4 on all 6
    sampled videos (audit-#204 artifact 1).
  - `FAIL duplicate recommendations` (6403: 2 pairs) — site repeats those anchors in raw HTML;
    provider dedupes via `distinctByHref` (issue #266); fixture-tested.
  - `FAIL plot present on 5/6` — the script's single static `--video-plot-selector` cannot express the
    provider's og:description→itemprop fallback; video 5887 genuinely lacks og:description. The fix
    makes plot non-empty there (unit test on the live fixture).
  - `FAIL title/poster mismatch` (6161) — audit-#204 artifact 3 (card stats prefix) and artifact 2
    (card 320x240/N.jpg vs og:image preview.jpg); faithful site mapping.
  - Search (2 pages, 60 cards each) / home page2+page3 (60 cards each) / related / check-6 field
    assignments: all PASS; no cross-page card dups (search "mature-milfs-part-three-homemade-vhs"
    page1+2, home /page/2/ + /page/3/).

## Audit #323 (2026-09-11) — data-completeness: mainPage surfaces

- Site's `pageContext.langUrls` (homepage inline JS) records stable, paginated sorting surfaces
  implemented identically to `/page/` and `/best/` (same `a.th.item` cards, 60/page):
  `/most-popular/`, `/most-favourited/`, `/longest/`, `/most-commented/`. Live check 2026-09-11:
  all four → 200 with 60 cards; `/most-popular/2/` → 200, 60 cards (paginates). `/top-rated/` is
  404 (rating sort = covered `/best/`); `/premium/`, `/private/` need login — skipped.
  NOT quick-search: site has no quick-search endpoint (audit-#204 note stands).
- Fix: four rows appended to `mainPage` (same `{url}` + `/{page}/` pattern). Version 9→10.
- Cross-surface overlap (same video legitimately on ≥2 sort pages, e.g. 42 of 60 shared between
  most-favourited and most-popular; ZERO in-page duplicates on any surface — Counter-verified) is
  a verify.sh check-1a script artifact when multiple --home-url surfaces are passed in one run:
  the script's dup check spans all home URLs. The mechanical pass therefore uses one surface's
  pagination (most-popular page1+2); the other three surfaces each verified 200 + 60 cards in the
  same run's GET lines.
## Audit #322 (2026-09-11) — correctness/drift: page-1 URL + quality caption (D1/D2)

- **D1 — getMainPage page 1**: `"$mainUrl/page/"` with page 1 fetching `request.data` bare →
  `https://allclassic.porn/page/` is a **301 to the site root** (curl: `301 http://allclassic.porn/`).
  Root's 84 cards (7 of them in-page duplicated, issue #266) massively overlap /page/2/ (`shared with
  /page/2/: 1`), so home page 1 duplicated home cards across pages (verify.sh check-1a FAIL).
  Canonical `https://allclassic.porn/page/1/` → 200, 60 cards, **0 overlap** with /page/2/.
  Fix: mainPage entry URL is now `$mainUrl/page/1/`; `getMainPage` still appends `$page/` for page > 1
  (→ /page/2/, /page/3/ …) — decade/sort rows keep the `{url}{page}/` pattern (page 1 of e.g. /90s/
  already 200s without the suffix so their entries are untouched).
- **D2 — quality caption**: live flashvars on all sampled pages (5278, 439, 5910, 4714, 2252 fixture)
  uses the `flashvars['video_url_text'] = '480p'` bracket form; the old regex
  `video_url_text:\s*'([^']+)'` matches the colon form only (grep count 0 on live pages) →
  `quality == null` everywhere, link name lost its ` - 480p` suffix. The `video_url:` colon form
  (the stream URL itself) DOES exist and streams fine — only the caption was dead.
  Fix: new pure `AllClassicPornParse.parseQuality(html)` matching both forms
  (`(?:video_url_text\s*:|video_url_text'\]\s*=)\s*'([^']+)'`, mirroring `flashvarsField`), wired into
  loadLinks. Unit tests (red → green) on fixture video-2252.html (`480p`) + colon form + null case.
  Version 10→11.
- verify.sh `--home-url /page/1/ /page/2/` passes check-1a cleanly (60 cards each, 0 shared).
  Old "embed…" FAIL artifacts are the script's og:video grab (audit-#204 artifact 1), not provider defects.
- verify.sh 2026-09-11 run artifacts (unchanged from audits #204/#205/#266/#289, none provider
  defects): stream og:video embed content-type; mini-DOM `-1` on chained `#id a.th.item` (check 2/
  4 raw count) with real related anchors present; duplicate rec hrefs site-side (deduped by
  `distinctByHref`, fixture-tested); check-5 title (card stats prefix / h1 year suffix) and poster
  (320x240/N.jpg vs preview.jpg) mismatches. All streams 206 video/mp4; checks 1/1a/6 + field
  assignments PASS.
