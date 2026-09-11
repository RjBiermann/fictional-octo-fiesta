# FINDINGS — eroticmv.com

## Engine fingerprint
WordPress + **VidoRev** (wp-content/themes/vidorev) video theme, Yoast SEO.
Evidence: `<link rel="canonical">`, `wp-content/themes/vidorev/img/placeholder.png`,
`class="post-item site__col ... format-video"` on all listing cards.

## Search
- Tried `/?s=peaches` → 200, returns WP archive of `article.post-item` cards. **Works.**
  ```
  curl -s 'https://eroticmv.com/?s=peaches' | grep -o 'href="https://eroticmv.com/pretty-peaches[^"]*"'
  → https://eroticmv.com/pretty-peaches-3-the-quest-1989/  (many results)
  ```
- Search pagination `/page/2/?s=peaches` → **404** (search is single-page, hasNext=false).

## Video pages
Cards (home, search, related all identical): `article.post-item` →
`a.blog-img[href]` (video URL), `title` attr, `img[data-src]` (lazy poster; `src` is a placeholder).

Probed ≥5 from different listings (all 200):
- https://eroticmv.com/pretty-peaches-2-1987/ — search result (issue example)
- https://eroticmv.com/sextet-1964/ — home /page/1
- https://eroticmv.com/repligator-1996/ — home
- https://eroticmv.com/silk-satin-sex-1983/ — home (listed)
- https://eroticmv.com/creampie-2026/ — home (recent)
- https://eroticmv.com/my-anal-intern-2016/ — related section of peaches-2

Meta per page: `og:title` ("Watch Pretty Peaches 2 (1987) - Erotic Movies"),
`og:image` (poster jpg), `og:description`, `og:video:url`.

## Related videos
`.single-related-posts` block, header "Related videos", cards are the same
`article.post-item` / `a.blog-img` structure.
Evidence: `curl -s <video> | grep 'single-related-posts'` → present on probed pages.

## Stream sources (per video page)
Single source per page: `og:video:url` content is `http://<base64>.m3u8` — strip the literal
`.m3u8` suffix, base64-decode the token → direct HLS URL on `vidcdn2.eroticmv.com`.

```
og:video:url" content="http://aHR0cHM6Ly92aWRjZG4yLmVyb3RpY212LmNvbS9kYXQxL3ByZXR0eXBlYWNoZXMyMTk4Ny9wcmV0dHlwZWFjaGVzMjE5ODcubTN1OA==.m3u8"
→ https://vidcdn2.eroticmv.com/dat1/prettypeaches21987/prettypeaches21987.m3u8
```
Verified per video (HTTP 200, body starts `#EXTM3U`, valid HLS playlist of .ts segments):
- prettypeaches21987.m3u8 → 200, `#EXTM3U` + `#EXTINF` ts list
- sextet1964.m3u8 → 200 application/octet-stream
- silksatinsex1983.m3u8 → 200 application/octet-stream
No other embeds (site iframes are only ad spots `js.eroticmv.com/api/spots/*`), no mp4.

## Headers / referer
Stream fetch works **without** referer (200 both with and without `Referer: https://eroticmv.com/`).
UA required (normal browser UA). Send referer anyway for safety.

## Pagination
Home: `/{page}/` suffix (`https://eroticmv.com/page/2/` → 200, "Page 2 of 67", different items).
Categories under `/category/...` (not used in provider).
Search: no pagination (see above).

## Risks / blockers
- Stream CDN behind **ddos-guard** but serves 200 to plain curl with browser UA — no challenge observed.
- None blocking.

## Metadata (2026-09-09 probe, issue #175)
- JSON-LD `script[type="application/ld+json"]` on every video page exposes
  `articleSection` genres, e.g. `["1960s","Classic Erotica","Comedy","USA"]`.
- `datePublished` is the **WP posting date** (e.g. 2026-08-20 for a 1987 film), NOT the
  release year. Release year comes from og:title: `Watch Pretty Peaches 2 (1987) - ...`.

## Actors / Stars block (2026-09-09 probe, issue #202)
- Every video page carries a Stars block: `div.actor-element.single-element`, one card per cast
  member: `h6.post-title > a[href="<mainUrl>/actor/<slug>/"][title="Name"]` (name in both
  `title` attr and inner text). Image is lazy (`data-src`, `src` is a placeholder) — titles only.
- Count varies per film (peaches-2-1987 has 11; actress/director cards share the same structure
  inside the block, links are `/actor/` slugs). Verified present on all 6 probed video pages.
- Provider (v4+) populates `actors` via `Eroticmv.parseActors(doc)`:
  `.actor-element.single-element a[href*='/actor/']` → `title` attr, distinct.
- Fixture: `src/test/resources/actor-block.html` (raw Stars block of pretty-peaches-2-1987).
- No true duration exposed on video pages (`duration-text` span on cards carries the rating,
  e.g. "4.2 ★") and `datePublished` is the WP posting date — neither invented.

## Update — 2026-09-11 fix run (issue #290)

- Live re-probe: search `?s=sex` → 200, 24 cards; home → 200; video page og:video:url → base64 → vidcdn2 m3u8 intact.
- No quick-search/suggest endpoint in HTML. Defect A: `hasQuickSearch` missing → declared `override val hasQuickSearch = false`.
- Version bumped to 5.
