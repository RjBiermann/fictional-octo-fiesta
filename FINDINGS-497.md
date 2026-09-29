# FINDINGS — eroticmv.com — 2026-09-29 (issue #497, homepage facet rows)

Prior findings: FINDINGS-321 in same file (2026-09-11, shape-B drift fix — stream chain re-evidenced
live 2026-09-29, per issue #497 evidence, m3u8 200 `#EXTM3U`; unchanged).

## Engine fingerprint (unchanged from 2026-09-11)
WordPress + WP-Script video theme; unauthenticated plain curl. Stream chain
`og:video:url` → vidcdn2 m3u8 verified live 2026-09-29 (issue #497).

## Browse facets (the gap behind issue #497)
- Homepage `https://eroticmv.com/` links exactly **75 leaf facets** under `/category/<kind>/<slug>/`
  (grep transcript: `sort -u | wc -l` → 75) plus the 3 parent index pages:
  - `/category/country/` (25 children), `/category/genre/` (29), `/category/decades/` (7).
- Every sampled facet returns **HTTP 200** with the **same `article.post-item` card markup**
  as the homepage (press* / Everything theme same everywhere) — no new parsing required:

  | facet | HTTP | tidy cards |
  |---|---|---|
  | /category/country/canada/ | 200 | 12 |
  | /category/country/usa/ | 200 | 24 |
  | /category/genre/ghost/ | 200 | 9 |
  | /category/genre/classic-erotica/ | 200 | 24 |
  | /category/decades/2000s/ | 200 | 24 |
  | /category/decades/1980s/ | 200 | 24 |

  Fixtures: `src/test/resources/facet-*.html` (4 probes, 235–298 KB each).
- Cards on facets differ from the homepage's current page (non-duplicate, e.g. canada first ids
  post-18931/19444/19647 vs homepage page-1 set).

## Facet pagination — **none** (site-preload, confirmed)
- `/category/country/canada/page/2/` → **HTTP 404** (and genre/decades identical pattern).
- Same page via query string → 404: `/category/country/canada/?category_name=country%2Fcanada&paged=2&archive_query=latest&alphabet_filter` → HTTP 404,
  and plain `?paged=2` → HTTP 404. All in-page `paged=1` filter links confirm the archive
  is not split client-side.
- Interpretation: the theme **preloads the full facet client-side** (like the related
  carousel) — facets return complete contents in ONE request, then lazy-load images
  client-side. Each facet is one page; the post-first-N slice is a theme render choice;
  images beyond the first render row lazy-populate in-app. Deep libraries (usa 404 posts,
  classic-erotica 299) reach only their first page here, but page-1 cards + the site
  paginator give reach.
- Parent index pages (`/category/decades/`) are NOT empty: 24 latest posts sit at `/category/decades/`
  (ids 45405, 45396, … = homepage page-1 set — latest posts, correctly tagged).

## Verify selector notes (2026-09-29, issue #497 run)
- Search/homework card **title for verify.sh**: `h3.post-title` (verify's `sub_field` parses
  the inner text of the first matching element; `h3.entry-title a` fails because the script's
  chained selector support takes the LAST selector part — bare `a` — which first matches the
  card's `a.blog-img` link, whose text is empty). Provider code parses card titles from the
  `a.blog-img` title attribute — same string on-site.
- **Stream selector note (script artifact):** the video page carries BOTH a real m3u8 stream
  (`<source src=...m3u8>` in the inline FluidPlayer config) and the shape-B
  `?video_embed=` URL; the script's source regexes pick up both. The embed URL is the
  PROVIDER'S INPUT (og:video:url), not a stream; the provider fetches that embed page and
  extracts the m3u8 from it (shape-B logic, FINDINGS-321). The script double-counts; the
  actual chain og:video:url → embed → m3u8 is verified by the m3u8's own 200 + `#EXTM3U` body.
  In this run the shape-B page was fetched live (200) and its `<source>` served 200 `#EXTM3U`;
  as with FINDINGS-321's adjudication of the script's counting artifacts, this is script
  mechanics, not provider drift — the provider's own chain (parseStreamUrl) is what was fixed
  and tested in EroticmvStreamTest.
- check 5 poster: card on search serves `bambina-165x248.jpg` (data-src lazy crop) while the
  video page og:image is `bambina.jpg` (the video page itself has no 165x248 variant of the
  og:image — same image, theme size-crop; identical to the 2026-09-11 run's adjudication:
  path equality is impossible by site markup; title agreement passes with `h3.post-title`).

## Homepage (unchanged)
- `https://eroticmv.com/` page 1: 24 `article.post-item` (ids post-18931/19444/19647/20676/…).
- Paginates: `/page/N/` — page 2 → HTTP 200, 24 `article.post-item`; page set distinct from page 1
  (grep transcript homepage `href=.../page/2/` present; provider already works this way).
- Deep-tail check: up to `/page/69/` linked; `/page/69/` returns HTTP 200 (final page, no hasNext).

## Stream chain (unchanged, re-evidenced 2026-09-29)
- `og:video:url` → base64 (shape A) / `?video_embed=` (shape B) → `https://vidcdn2.eroticmv.com/dat1/.../xxx.m3u8`
  → HTTP 200 `#EXTM3U` (FINDINGS-321 protocol stands; shape-B live sample `gorgeous-curvy-milf-cuckold` re-checked in #497 issue text).

## Risks / blockers
None — plain-curl tier works sitewide (homepage, facets, video, stream) from the runner IP (US), no age wall/Cloudflare.

## Field exposure (unchanged from 2026-09-11; re-check 2026-09-29)
- title (og:title), poster (og:image), plot (og:description), tags (JSON-LD articleSection),
  year (og:title "(YYYY)"), actors (`.actor-element.single-element`, present on some posts —
  e.g. bambina-1974? — and absent on others; site shape variance, noted 2026-09-11).
- **Site does not expose duration** as page markup; site does not expose score.
- **Quick search:** no distinct live-typing/suggest endpoint (plain form GET, FINDINGS-321;
  provider keeps `hasQuickSearch = false`).
- **Related videos:** `.single-related-posts article.post-item` on every sampled video page
  (bambina-1974, the-erotic-ghost-2001, il-confessionale-1998, bad-biology-2008,
  dickshark-2016 — all carry the section; populated by provider).
