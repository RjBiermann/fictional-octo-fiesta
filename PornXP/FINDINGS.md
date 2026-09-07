# FINDINGS — pxp.news (2026-09 audit)

## Verdict: OK

## Search
- `https://pxp.news/?s=red` → 200 with `.item_cont` (search results); home also `.item_cont`.

## Stream
- video `/videos/915675372201` → `#player source` 360/1080 `https://sd.pornxp.sh/...mp4` → **206 video/mp4**.

## Actors (2026 fix probe, issue #131)
- No dedicated actor/models element: `grep actor` on video page → 0 HTML matches ("models" only in ad-script prose); `/models/`, `/actors/`, `/pornstars/` → 404.
- Performers are multi-word tags inside `div.tags a` after the leading studio tag(s):
  `curl -s .../videos/915675372201 | grep '<div class="tags">'` → `/tags/AlexLegend` `/tags/Maya%20Lynn` `/tags/MILF` ... ; same shape on `/videos/520526742759` (Sona Bella), `/videos/987403301222` (Angelica Amore), `/videos/869511701562` (Andrea Frank), `/videos/31203545288` (Elizabeth Skylar).
- Multi-word genre tags exist (Big Ass, Big Tits, Cum In Mouth) → small stoplist heuristic.
