# Findings — eroticmv.com — 2026-09-11 (issue #321, Shape-B drift fix)

## Load-links shapes (og:video:url)
- **Shape A (base64, working):** `og:video:url" content="http://aHR0cHM6...==.m3u8"` → strip suffix, base64-decode → e.g. `https://vidcdn2.eroticmv.com/dat1/creampie2026/creampie2026.m3u8`. Confirmed live: creampie-2026 (206, #EXTM3U), foreplay-1982, helena-2026, liberte-sexuelle-2012.
- **Shape B (?video_embed=):** `og:video:url" content="https://eroticmv.com/<slug>/?video_embed=<id>"` → the stream lives ON that embed page, in FluidPlayer markup: `<source src="https://vidcdn2....m3u8">`. Confirmed live: gorgeous-curvy-milf-cuckold (?video_embed=32878) → `https://vidcdn2.eroticmv.com/dat1/gorgeousbustycurvypalemilfcuckold/gorgeousbustycurvypalemilfcuckold.m3u8` (HTTP 200, #EXTM3U; also serves 200 with NO referer).
- Old code did `substringAfterLast("/")` → `"32878"` → base64 decode failed → zero streams for Shape-B posts (the bug).

## Fix shipped
- `parseStreamUrl(raw, embedHtml)` pure function (same file companion object): shape-A decode untouched; shape-B fetches the embed page, regex `source src="([^"]+\.m3u8)"`.
- `shared/HostRegistry.kt` decodeBase64: try java.util.Base64 first (JVM unit tests throw "not mocked" on android.util.Base64), fallback to android.util.Base64.

## Verify checks run 2026-09-11
- search `article.post-item` (`/?s=milf` → 200, 1 card) PASS
- home `/` + `/page/2/` → 24 cards each PASS
- Shape A stream 206 m3u8 PASS; Shape B stream 200 m3u8 PASS (after fix)
- related `.single-related-posts article.post-item` → 3 distinct recs PASS
- fields: tags (`.categories-elm a.category-item` — VideoObject articleSection has left watch pages, see FINDINGS-538.md), actors (`.actor-element.single-element`), plot (og:description), year (og:title "(YYYY)") all populated
- Note: search for `?s=milf` returns 1 post (was 2 in a prior run) — >0, no pagination, not a defect.

## verify.sh run 2026-09-11 (final config) — RESULT: PASS
Config: search `/?s=amateur` `article.post-item` (+h3.entry-title), home `/page/3/` `/page/4/`, 2 video URLs (Shape A creampie-2026, Shape B gorgeous-curvy-milf-cuckold), stream selector `meta[property=og:video:url]`, stream-url overrides = provider-resolved m3u8s, header Referer eroticmv.com, load-response recommendations,tags,plot,year,actors,posters.
- check 1 search 200 / 1 card PASS; check 1a home 24+24 PASS; check 2 both videos 200 + og:video:url 1 match; streams 206/206 with m3u8 body PASS; check 6 all fields populated PASS.

### Documented script artifacts (raw HTML evidence, adjudicated same as issue's prior run)
- check 4 related: pydom chained selectors count the last part GLOBALLY over the page (23 "matches") and regex `<article...>(.*?)</article>` truncation inflates blocks → false self-hit + false duplicates. Raw extraction of `.single-related-posts` section: 6 distinct recs, no self (Foreplay (1982), Little Shop of Erotica (2001), Teenage Bride (1975), The Mount of Venus (1975), Angel Above and the Devil Below (1975), Naughty Seduction Of Wife (2019)).
- check 5 poster: search card serves `.../creampie-165x248.jpg` (data-src lazy crop) while video page og:image is full `.../creampie.jpg` — same image, size-crop variant; video page has no 165x248 variant so path equality is impossible. Search markup matches provider code exactly. Title agreement passes with `h3.entry-title` (inner-text titles).
- actors exposure is inconsistent on-site (`.actor-element.single-element` present on creampie-2026, absent on gorgeous-curvy-milf-cuckold) — site shape variance, not parser drift; selector omitted from 2a, FINDINGS notes it.

---

# FINDINGS — eroticmv.com — 2026-10-16 deep pass (issue #646, run20)

## Headlight: new Cloudflare challenge on plain curl
- Plain curl (any UA/referer) → 403 `cf-mitigated: challenge` "Just a moment..." since the
  2026-10-09 deep; consistent 3/3. Escalation tier 2 (`impersonate.sh`, curl_cffi chrome
  TLS) → 200 on every surface. Fingerprint gate (canary film1k pair matches 3/3), not IP.
  Device OkHttp effect unadjudicated (no residential differential) → `ok-suspected`.

## Full-surface evidence (tier-2)
- **Home**: `/` 24 cards, `/page/2/` 24 cards, disjoint. Card grammar unchanged
  (`a.blog-img` href+title, lazy `data-src` poster).
- **Facets**: classic-erotica p1/p2 24+24 disjoint; 1970s 24 (10 shared with home p1 =
  cross-section recency, not drift).
- **Search**: `/?s=erotica` 17 cards; `/page/2/?s=` 404 (no pagination — provider matches).
- **Quick search**: `/search/suggest/` serves the theme's full HTML search page (269 KB,
  text/html) — no distinct suggest endpoint; `hasQuickSearch=false` stays correct.
- **Video ×5** (home/facet/search listings): og:title/image/description live; tags
  `.categories-elm a.category-item` 3–6/page (1980s/Classic Erotica/Spain/Violence style
  values); actors `.actor-element.single-element a[href*='/actor/']` 1–14/page (title attr);
  related `.single-related-posts article.post-item` 12/page distinct.
- **articleSection (#538)**: standalone VideoObject carries none 5/5; yoast `@graph`
  Article node carries it 5/5. Provider parses anchors → covers both faces. Record stays
  closed (fixed).
- **Streams**: b64 `og:video:url` → `data.eroticmvs.com/dat1/<slug>/<slug>.m3u8`
  (2026-10-16, **migrated from vidcdn2.eroticmv.com** — payload-hosted, zero provider
  impact); 206 `application/vnd.apple.mpegurl` `#EXTM3U` 5/5, serves with AND without
  referer, all distinct.

## verify.sh 2026-10-16 (tier-2 instrument: curl_cffi chrome shim) — PASS
Main family (search+home p1/p2+5 videos) + facet family (classic-erotica p1/p2). Checks
1/1a/2/3/4/6 + 2a tags PASS. Instrument-limited lines (adjudicated, not site drift):
3 poster-agreement FAILs (WP crop-rendition/pipeline artifact class from 2026-09-11 —
search card `…-165x248.jpg` or re-based crop vs full og:image, both 200 image/jpeg);
actors NOTE (pydom `field text` first-anchor artifact — provider reads `title` attr).
Gates: `Eroticmv:test` 72/0, clean `Eroticmv:make`, `check-findings.sh` PASS.
No provider change (verdict `ok-suspected`, stays version 11).
