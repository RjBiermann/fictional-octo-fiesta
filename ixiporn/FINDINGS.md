# FINDINGS — ixiporn.org / ixiporn.live (2026-09 audit)

## Verdict: OK

## Search
- `https://ixiporn.org/?s=red` → 200; `div.video-block` ×31; `a.infos href` → `https://ixiporn.live/<slug>` (domain moved org→live; provider's fixUrl + WP redirect handles it; both domains serve).
- Search page2 `/page/2?s=red` OK.

## Video page / stream
- Page has `div.video-player` with `meta[itemprop=contentURL] content="https://cdn2.ixifile.xyz/5/Red%20Dress%20-%20Reshmi.mp4"` — matches provider loadLinks selector.
- Stream: **206 video/mp4** (Referer not required).
