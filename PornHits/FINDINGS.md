# FINDINGS — pornhits.com (2026-09 audit)

## Verdict: CHANGED — old engine gone; provider needs rewrite

## Evidence
- `videos.php?p=1&q=anal` / `q=red` → 200 but is a generic landing page; **zero `article.item`**. No `videos_list_search_result`.
- Search UI: form `action="/video/search" method="get" name="search"`; `searchUrlVideo="/video/search?search="`.
- Page now Pornhub-style: `pcVideoListItem js-pop videoblock videoBox`, `data-video-id`, thumbnails on ei.phncdn.com, video block id `v489280675`.

## Implication
Provider (KVS videos.php + window.initPlayer + base64 videos array) is fully superseded. Either rewrite for new engine or remove.
