# Javseen — FINDINGS (fix run, 2026-09-09, issue #208)

Axis: CORRECTNESS+DRIFT. Site live (HTTP 200, no block). Single defect: category/listing
pagination past page 1.

## Engine / surfaces
- List surfaces (search, homepage rows, categories) are **AJAX-JSON only**:
  `{"status":1,"html":"…","pagination":"…","total":30,"next_url":"…"}`
  The plain-HTML search page (`/search/video/?s=milf`) renders "No results" client-side —
  verify.sh's HTML-based list checks cannot consume these endpoints (checker limitation,
  noted in issue #208). Manual curl transcripts stand in.
- Video pages are plain HTML. Stream sources: `button.button_choice_server[data-embed]` —
  both buttons: data-embed URL "https://cloudwish.xyz/e/75l5twephiq6", "https://mycloudz.cc/v/xq11fwjqo7lt" (base64-decoded). No `ul.videos.related` on sampled pages ("No related videos found!" commented out) — provider's related selector returns empty harmlessly.
- Card markup unchanged (selector bar from issue A-D): `li[id^=video-] > div.video > a.thumbnail`,
  title `span.video-title`, poster `img`, 30 cards/page. Search card selectors match.

## Pagination (the defect)
- code-built `{cat}/{page}/` → **404** (e.g. `/big-tits/2/?ajax=category_videos` → 404; retried 2026-09-09).
- Site's real pattern: `{cat}/{sort}/{page}/` — `/big-tits/recent/2/?ajax=category_videos` → 200,
  30 cards (`video-285562`, `video-285561`, …). `mature-woman/recent/2/?ajax=category_videos` → 200,
  next_url `/mature-woman/recent/3/`.
- Sort segment discoverable from a page-1 AJAX response's **`pagination`** field
  (`<a href="/big-tits/recent/2/">2</a>` … `/big-tits/recent/2664/`), also `next_url`.
- Recent row unchanged: `/recent/2/?ajax=browse_videos` → 200, 30 cards (`video-285620`, `video-285619`).
- Search unchanged: `/search/video/?ajax=search_results&s=milf&o=recent` → 200; `&page=2` → new items.
- Homepage page 1: `/?ajax=browse_videos` → 200 JSON, 30 `video-title` cards.

## Fields
poster/plot/title/actors/tags/year/duration/recommendations all populated in provider Kotlin;
video pages stick to og-meta + detail-box markup (unchanged from existing code).

## Checker limitations (not defects)
- List endpoints are JSON-wrapped HTML → not checkable by verify.sh HTML selectors; curl transcripts used.
- Stream URLs are base64 in `data-embed` → verify.sh can't extract; decode + live checks done manually:
  10 embed URLs across 4 videos, all HTTP 200 (mycloudz.cc / cloudwish.xyz embed players, page-level HTML —
  final m3u8 extraction is shared-extractor behavior, unchanged by this fix).
