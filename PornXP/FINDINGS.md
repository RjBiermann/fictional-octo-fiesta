# FINDINGS — pxp.news (2026-09 audit + 2026-09 fix for #151)

## Verdict: OK (fixed — item-card markup changed, provider selector stale)

## Root cause of #151 (blank homepage feed)
The card markup changed: the `<a href="/videos/...">` is now an **ancestor** wrapping
`.item_title` (title is a plain div inside the anchor). The provider matched
`.item_title a`, which matches nothing → every card maps to null in `toSearchResult()`
→ empty HomePageList → blank feed.

Evidence (home + search + related all share this shape):
```
<div class="item_cont"><div class="item preview" data-id="..."><a href="/videos/434655441290">
  <div class="item_width"><div class="item_height"><div class="item_thumb"><img class="item_img"
  src="/43465544641290.jpg" ...>
  <div class="item_title">Monica - Hardcore</div>
```
34 of 36 thumbnails carry `class="item_img lazy"` with the real poster in `data-src`
(spinner in `src`) — already handled by the provider.

## Engine fingerprint
Static server-rendered HTML (`text/html`), jquery + yall lazy-loader. No JS listing.

## Search
- `https://pxp.news/?s=red` → 200, 36 `.item_cont` cards
- `https://pxp.news/?q=red` → 200, 36 `.item_cont` cards (both params accepted; provider uses `?q=`)
- Pagination `?page=N` and `/best/?page=2` → 200, 20–36 cards each.

## Video pages
Probed ≥5 pages across home/search/paginated listings, e.g. `/videos/80377921180`,
`/videos/629192201007`, `/videos/24078228504`, `/videos/322543611680` — all 200, `h1` title,
`#player video poster`, `#desc` (date + description), `.tags a` tags, related `div.item_cont`.

## Related videos
Video pages contain 25–36 additional `.item_cont` cards → recommendations.

## Stream sources (per video page)
`#player source` (1–3 per page), e.g. issue video `/videos/80377921180`:
```
<source src="//ce.pornxp.sh/wZpMua0e0R5c9nHnh8H2gG10w/8037792185/360.mp4" title="360p" type="video/mp4">
<source src="//ce.pornxp.sh/VJj1OH06EdvJ9Ohc88cv0sz_w/80377921215/720.mp4" title="720p" type="video/mp4">
```
verify.sh (5 URLs): `GET stream → 206 video/mp4` each. Scheme-relative `//host` URLs —
fixUrl() resolves them.

## Headers / referer
No auth/ referer required; site serves 200 to any UA (no Cloudflare). `referer = mainUrl`
kept on links.

## Pagination
`?page=N` appended to listing URL; next pages return fresh cards (20–36).

## Risks / blockers
None observed: no CF, no age wall, no UA sensitivity (tested android UA + no UA).
