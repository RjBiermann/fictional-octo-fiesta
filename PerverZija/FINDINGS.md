# FINDINGS — tube.perverzija.com (2026-09 audit)

## Re-probe for issue #161 fix (2026-02, builder)
- `span.runtime` → 0 matches on the live page (selector dead; issue #161 confirmed).
- JSON-LD `<script type="application/ld+json">` VideoObject exposes `"duration":"PT34M59S"` on the same page.
- Recommendations: `div.srelacionados` gone from the page — no related-videos section exposed anymore; not a data gap.
- Fix: parse ISO-8601 duration from JSON-LD (PT#H#M#S → total minutes) instead of the dead span.
- verify.sh: search 200 ×69 `div.col-md-3`; video page 200, `iframe[src*=xtremestream]` ×1; canned script still cannot do the two-hop stream (sends no Referer, xs1.php 403s) — manual chain evidence: player page 200 → `video_id` → `xs1.php?data=<id>` with player referer → 200 body starting `#EXTM3U` (854x480 variant listed).
- Gradle gate (2026-02): `./gradlew PerverZija:make` BUILD SUCCESSFUL (jitpack issue below no longer reproduces in CI).

## Verdict: BROKEN (extractor domain mismatch)

## Search — OK
- `/?s=red` → 200, `div.col-md-3` ×69, `div.title a` hrefs OK.

## Video page
- `iframe src="https://pervl1.xtremestream.xyz/player/index.php?data=a667f4e7b0c8a3babe331569d3eac6bd"` (pervl1!).

## Stream — extractor not matched
- Provider registers ONLY `PerverZijaExtractor` with `mainUrl=https://pervl2.xtremestream.xyz`. Player iframe serves from **pervl1** → loadExtractor finds no matching extractor → no stream.
- Player page: `m3u8_loader_url = https://pervl1.xtremestream.xyz/player/xs1.php?data=`; xs1.php serves m3u8 **only with referer of the player domain** (`pervl1.xtremestream.xyz/player/index.php` OK, `pervl2...` OK, `tube.perverzija.com` 403, none 403). q=480/720/1080 all 200.

## Fix
Register extractor for both subdomains (pervl1 + pervl2), keep referer on extractor mainUrl.

## Re-probe for issue #99 fix (2026-09, builder)
- Player subdomain VARIES per video: observed pervl1, pervl2, pervl3, pervl6, pervm1, j2 across search results. Registering fixed subdomains is insufficient.
- xs1.php accepts referer from ANY *.xtremestream.xyz/player/index.php (pervl6 stream with pervl1 referer → 200); tube.perverzija.com or none → 403.
- Fix applied: provider calls PerverZijaExtractor().getUrl(iframe, ...) directly in loadLinks (extractor derives link referer from the iframe URL), so any pervlN/pervmN/jN subdomain works. No loadExtractor mainUrl matching needed.

## Reviewer round-1 verification (independent, 2026-09)
- Reproduced: search ×1 (200, `div.col-md-3`), 4+ varied video pages; iframes on pervm1, pervl2, pervl1, j2 subdomains (subdomain set wider than pervlN — confirms fixed-subdomain registration is insufficient).
- Exact code-emitted referer form `https://<sub>.xtremestream.xyz/` (url.substringBefore("/player/")+"/") → xs1.php`q=720` returns 200 `application/vnd.apple.mpegurl` body; no referer → 403. Fix validated.
- Non-video pages (e.g. /2257-exemption-statement/, discord iframe) skipped by the `contains("xtremestream.xyz")` guard — correct.
- verify.sh transcript not produced: canned verify.sh cannot pass here (it sends no Referer on the stream GET, so xs1.php 403s). Manual curl evidence substituted.

## Gradle gate: BLOCKED from CI (environment)
`./gradlew PerverZija:make` fails at root-project configuration: jitpack no longer serves
`com.github.recloudstream:gradle:-SNAPSHOT` (gradle--32895aedb6-1.pom → 404; maven-metadata lists
only `-32895aedb6-1` whose pom is also 404). Failure is repo-wide (root buildscript classpath),
affects baseline master identically — not caused by this change. Needs a root build.gradle.kts
version bump (out of scope for an ai-fix per repo rules).

## Re-probe for issue #211 fix (2026-09-09, builder)
- Tags/actors selectors were case-broken: `strong:contains(tags)` / `strong:contains(stars)` matched 0 on every video page; live markup uses `<strong>Tags: </strong>` / `<strong>Stars: </strong>` (capitalized).
- Fix applied: `strong:contains(Tags)` / `strong:contains(Stars)`.
- jsoup-equivalent proof (soupsieve, same selectors) on the Vixen/Rikako Katayama page [- 0 old tags] / 23 new tags ("Asian", "Big Dick", …); 0 old actors / 2 new actors ("Alberto Blanco", "Rikako Katayama"). Same `<div class="item-tax-list">` block on both sampled pages.
- verify.sh canned run: search /page/* 200 ×69 `div.col-md-3` no dupes; home /featured-scenes/ + page/2 200 ×64 `div.col-md-3.col-xs-6` no dupes (plain `div.col-md-3` fails only on duplicated sidebar widgets — tag cloud/calendar — shared across pages; not video rows). Stream check unrunnable in canned script: stream is two-hop (iframe → extractor xs1.php needs Referer), script can't follow the chain. Manual transcript 2026-09-09:
  - vixen-rikako… → iframe `https://j2.xtremestream.xyz/player/index.php?data=7752af…` → `curl -H "Referer: https://j2.xtremestream.xyz/" …/xs1.php?…&q=720` → 200, body `#EXTM3U`.
  - elegantangel-the-red-door… → iframe `pervl4.xtremestream.xyz` → same chain → 200 `#EXTM3U`. (Subdomain variance j2/pervl4 confirmed; direct-call extractor handles it.)
  - Checker limitations recorded: verify.sh's simple selector parser cannot compile `:contains()`/`:has(...)` selectors or bare tag+digit (`h1`) — tags/actors/title asserted via the soupsieve transcript above instead.

## Re-probe for issue #212 fix (year), 2026-09-09 builder
- Dead selector confirmed: `div.extra span.C a` → 0 matches on sampled video pages (`class="C"` absent from HTML entirely).
- Year now parsed from JSON-LD `datePublished` (same `script[type=application/ld+json]` block as duration): `Parse.year(document)` — regex first 4 digits; relative "x ago" post-dates simply yield null via JSON-LD being present but a valid date always having a year.
- Live evidence (curl, 4 sampled pages): vixen-rikako…→2026, wildoncam-harper…→2022, backroommilf-violet…→2022, brazzersexxtra-abigaiil…→2026.
- Unit tests: `PerverZija/src/test/kotlin/com/kraptor/ParseTest.kt` (datePublished year, missing datePublished → null, no ld+json script → null).
- verify.sh canned run: search/home/video fetch + selectors pass; stream check unrunnable (two-hop, no Referer — same known limitation recorded by #211); source completeness check found year/plot/duration assignments. Year exposure asserted manually above.

## Re-probe for issue #273 fix (recommendations), 2026-09-10 builder
- `div.srelacionados` → 0 matches on both a scene page (1111customs-graycee-baybee…) and a full-movie page (private-milf-cafe-2009). WP theme now serves `div.xs-related-item` cards (scene page 18, movie page 14): `a[href]` wrapping `img[src]`, real title in sibling `div.xs-related-title a`.
- `img[alt]`/`[title]` on related items repeat the HOST page's title (wrong on every probed page) — must never be used for rec titles. Posters are plain `img[src]`, no `data-src`.
- Fix: `PerverZijaParse` (related/titleOf/posterOf) + `toRecommendationResult` reads href from `a`, title from `div.xs-related-title a`, poster from `a img[src]`. TDD: `PerverZijaParseTest` (4 tests, fixture `perverzija_video_related.html` cut from a live scene page) red→green; `./gradlew PerverZija:test` 4/4, `make` BUILD SUCCESSFUL, version 7→8.
- verify.sh canned run (2026-09-10): search `div.col-md-3.col-sm-3` ×45 no dupes; home /featured-scenes/ + page/2 `div.col-md-3.col-xs-6` ×64 each no dupes; all 5 video pages 200 with `iframe[src*=xtremestream]` ×1 and `div.xs-related-item` ×12–18. NOTE: no quick-search endpoint (plain WP `?s` only — same endpoint as search); score: site exposes no rating element (`dt_rating_vgs` 0 hits) — correct null.
- Canned-script limitations (same as #211/#212, evidence substituted manually):
  - Stream two-hop: script sends no Referer → xs1.php 403 (CF challenge HTML). Manual chain transcript, app-emitted request (player page fetched first, referer `<sub>.xtremestream.xyz/`): pervl2/pervl4/pervl8/j2/pervl5 xs1.php?q=720 → 200, body `#EXTM3U` (5/5 distinct CDN paths).
  - Rec titles: script's regex block parser truncates `div.xs-related-item` at the first `</div>` (before `xs-related-title`), so its card titles are always empty. Soupsieve (jsoup-equivalent) on the same 5 live pages: 12/18/18/14/18 items, titles from `div.xs-related-title a` all non-empty, pairwise distinct, never the host video, on every page; img alt confirmed wrong (host-page title) on all 5.
  - Tags/actors selectors use `:has(...):contains(...)` (uncompilable by the script) — unchanged from #211 where they passed via soupsieve; this fix touches only the recommendations mapping.
  - Search page 2 intermittently 429s under script rate (single-page run passed; in-app pagination unaffected).

## Re-probe for issue #333 fix (listing-table sidebar sweep + dead quality links), 2026-09-11 builder
- Defect 1 confirmed live: every listing page (home p1/p2 all 4 rows, search p1/p2) embeds a
  `div id="sidebar" class="col-md-3"` widget column. Old selector `div.col-md-3` consumed it →
  one bogus card per page (title from a sidebar img, href `/advanced-search/`), cross-page duplicate,
  loads "Advanced Search" page (no iframe, no stream).
- Live class census (curl 2026-09-11): search p1/p2 → 64 `class="col-md-3 col-sm-3"` + 1 bare (sidebar);
  home/featured-scenes → 64 `class="col-md-3 col-sm-6 col-xs-6 "` + 1 bare; /studio/ and /full-movie/ same.
  Sidebar is always the bare `col-md-3`. Fix selector: `div.col-md-3:not(#sidebar)` in
  PerverZijaParse.listingCards() — robust regardless of which card-class variant the theme serves.
- Unit tests red→green: `PerverZijaListingTest` (fixture `perverzija_listing_cards.html` cut from live
  search.html with 3 real cards + the sidebar): listingCards → 3 (old selector → 4), no `/advanced-search/`
  cards, card title maps to own img/link.
- verify.sh canned run 2026-09-11: search `div.col-md-3.col-sm-3` ×64 p1 (p2 hit 429 first run —
  runner rate-limit, re-fetched manually: 64 cards, sidebar present, only the sidebar carries
  `/advanced-search/`); home `div.col-md-3.col-sm-6.col-xs-6` ×64 on all 4 rows, duplicate check PASS
  (sidebar excluded). Script cannot compile `:not(#sidebar)` (credited selectors used; `:not` itself
  proven by the unit test + manual census).
- Defect 2 confirmed live on 4 varied videos (pervl2/pervl3/pervl5/moviekh referers all HTTP 200):
  master xs1.php WITHOUT q → `#EXTM3U` + #EXT-X-STREAM-INF variants on every sampled video;
  q loop emits dead links whenever the video caps lower (pervl3 sample: 480/720 ok, 1080/2160 →
  plain-text "Could not find video playlist for quality [N] or Np"; pervl2 sample: only 1080 ok).
  Fix: emit single master-playlist link (no q), lib app picks adaptive variant. quality=Unknown.
  End-to-end: master variant (q=1080 entry) → media playlist → segment
  `https://pervl2.xspcdn01.click/cdn/down/4e0db76ef57112b337ab492a727a6069/1080/10800.html` → 200,
  1,309,420 bytes, first byte 0x47 (MPEG-TS).
- Re-verified unchanged data surface: recommendations `div.xs-related-item` ×12–18/page (script
  "empty recommendation title" FAILs are the known canned rec-title parser limitation; fixtures +
  PerverZijaParseTest cover real titles via `div.xs-related-title a`), tag cloud `<strong>Tags: ` and
  star list `<strong>Stars: ` present on sampled videos, og:description present, JSON-LD
  `datePublished`/`duration` present. No quick-search endpoint — hasQuickSearch stays false; no
  rating element — score stays null.
- Gate: `./gradlew PerverZija:make` BUILD SUCCESSFUL; `./gradlew PerverZija:test` BUILD SUCCESSFUL.
  version 10 → 11.
