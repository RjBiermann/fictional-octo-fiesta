# FINDINGS — WatchPorn (watchporn.to)

Probe 2026-02-06 (drift) + re-probe for issue #162. Engine: KVS (flashvars, `/video/<id>/<slug>/`, `/get_file/` streams — matches provider code).

## Duration source (issue #162 focus)
- `div.fp-time-duration` → **0 matches** on current video page (dead selector).
- JSON-LD VideoObject present on video page:
  ```
  $ curl -s 'https://watchporn.to/video/162183/.../' | grep -o 'duration[^,>]*' | head
  duration": "PT0H36M3S"
  ```
  `<script type="application/ld+json">` blocks: 2 (BreadcrumbList + VideoObject). VideoObject has name, description, thumbnailUrl, duration (ISO-8601 `PT0H36M3S`).
- Fallback also present: `<meta itemprop="duration" content="2163">` (seconds). JSON-LD is primary; both parse fine.

## Search
`/search/?q=<q>&mode=async&function=get_block&block_id=list_videos_videos_list_search_result&from_videos=<page>` — unchanged, works (35 cards in drift probe). Items: `div.thumb.item` with `span.thumb__title`, `a[href]`, `img[data-webp|src]`.

## Video pages
`https://watchporn.to/video/<id>/<slug>/`. Tags `div.single__info-row:contains(Tags:) a` ✓, Models row ✓, description `p.single__content-description` ✓, related `div.related-videos div.thumb.item` ✓ (5 info-row/related matches on probe page).

## Stream sources
flashvars `video_url` / `video_alt_url` → `/get_file/...mp4` — drift probe 2026-02-06: HTTP 206 `video/mp4`. WebView-based extraction unchanged.

## Headers / referer
Referer `$mainUrl/` + cookies on poster (unchanged).

## Pagination
`from_videos=N` for search; `{url}{page}/` suffix for category pages — unchanged.

## Risks / blockers
None; site reachable from runner.
