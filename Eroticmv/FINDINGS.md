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
- fields: tags (JSON-LD articleSection), actors (`.actor-element.single-element`), plot (og:description), year (og:title "(YYYY)") all populated
- Note: search for `?s=milf` returns 1 post (was 2 in a prior run) — >0, no pagination, not a defect.

## verify.sh run 2026-09-11 (final config) — RESULT: PASS
Config: search `/?s=amateur` `article.post-item` (+h3.entry-title), home `/page/3/` `/page/4/`, 2 video URLs (Shape A creampie-2026, Shape B gorgeous-curvy-milf-cuckold), stream selector `meta[property=og:video:url]`, stream-url overrides = provider-resolved m3u8s, header Referer eroticmv.com, load-response recommendations,tags,plot,year,actors,posters.
- check 1 search 200 / 1 card PASS; check 1a home 24+24 PASS; check 2 both videos 200 + og:video:url 1 match; streams 206/206 with m3u8 body PASS; check 6 all fields populated PASS.

### Documented script artifacts (raw HTML evidence, adjudicated same as issue's prior run)
- check 4 related: pydom chained selectors count the last part GLOBALLY over the page (23 "matches") and regex `<article...>(.*?)</article>` truncation inflates blocks → false self-hit + false duplicates. Raw extraction of `.single-related-posts` section: 6 distinct recs, no self (Foreplay (1982), Little Shop of Erotica (2001), Teenage Bride (1975), The Mount of Venus (1975), Angel Above and the Devil Below (1975), Naughty Seduction Of Wife (2019)).
- check 5 poster: search card serves `.../creampie-165x248.jpg` (data-src lazy crop) while video page og:image is full `.../creampie.jpg` — same image, size-crop variant; video page has no 165x248 variant so path equality is impossible. Search markup matches provider code exactly. Title agreement passes with `h3.entry-title` (inner-text titles).
- actors exposure is inconsistent on-site (`.actor-element.single-element` present on creampie-2026, absent on gorgeous-curvy-milf-cuckold) — site shape variance, not parser drift; selector omitted from 2a, FINDINGS notes it.
