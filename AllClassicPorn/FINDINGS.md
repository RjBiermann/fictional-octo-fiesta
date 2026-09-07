# FINDINGS — allclassic.porn

## Engine fingerprint
KVS / Kernel Video Sharing. Evidence: KVS container ids (`list_videos_most_popular_videos_items`,
`list_videos_videos_list_search_result_items`, `list_videos_years_videos_list_pagination`),
`flashvars` object on video pages, asset paths under `/contents/videos_screenshots/`,
CDN `remote_control.php` redirect for streams. In-repo reference: Porntrex, PornHits.

## Search
`/search/{query}/` works (HTML `.th.item` blocks). Tested 2 patterns:
- `https://allclassic.porn/search/milf/` → HTTP 200, `list_videos_videos_list_search_result_items`,
  ≥50 results (`grep -c 'th item'` = 106 incl. duplicates). ✅
- Alternative `/{search}/{query}/` style not needed; also verified `/search/milf/2/` page 2 → 200,
  different items.

## Video pages
Item structure (home/search/listings): `a.th.item` with `href=/videos/{id}/{slug}/`,
title in `img alt` (and `div.th-description`), poster `img src`, `span.th-duration`.

Probed pages (all HTTP 200, slug required — `/videos/{id}/` without slug 404s):
- /videos/2252/zazel/ (homepage) — og:title "Zazel", itemprop duration PT125M24S
- /videos/6161/mature-milfs-part-three-homemade-vhs-1998/ (search "milf") — PT17M00S
- /videos/1573/casanova-2/ (homepage)
- /videos/2208/the-golden-age-of-danish-pornography/ (homepage)
- /videos/2118/worst-porno-ever-made-with-the-best-sex/ (homepage)

og meta available: og:title, og:image (preview.jpg), og:description, og:url, og:video (embed/2252 iframe — not used, direct mp4 available).

## Related videos
`#list_videos_related_videos_items` present on every probed video page (grep hit on
/tmp/v1.html and /tmp/vv_6161b.html). Selector: `#list_videos_related_videos_items a.th.item`.

## Stream sources (per video page)
Direct mp4 in inline JS flashvars — no m3u8, no external embeds needed:
- /videos/2252/zazel/: `video_url: 'https://allclassic.porn/get_file/1/fbfab.../2000/2252/2252_480p.mp4/?v-acctoken=...'` with `flashvars['video_url_text'] = '480p'`. Verified: GET with UA+referer → 302 →
  `https://cdn1.allclassic.porn/remote_control.php?file=...mp4&acctoken=...` → 206 `video/mp4`, body starts `ftypisom...avc1` ✅
- /videos/6161/...: `video_url: '.../6000/6161/6161_480p.mp4/?v-acctoken=...'` → 206 `video/mp4`, ftyp header ✅
- `video_alt_url` on 6161 is `https://allclassic.porn/?login` (720p behind login) — ignore alt URLs
  unless they end in `.mp4`.

Regex: `video_url:\s*'([^']+)'` and quality `video_url_text: '([0-9]+p)'`.

## Headers / referer
`get_file` request requires browser UA and the video-page referer (302 redirect issued regardless of
refer being absent? no-referer test also 302'd, but referer supplied throughout is the safe combo);
CDN request with same referer returns 206 video/mp4. No cookies/Cloudflare observed.

## Pagination
Plain HTML, `{url}/{page}/` suffix:
- Home: `https://allclassic.porn/page/2/`
- Decade (e.g. /90s/): `/90s/2/`, `/90s/3/` … (ul#list_videos_years_videos_list_pagination)
- Search: `/search/milf/2/` → 200, different items

## Risks / blockers
None. No Cloudflare, no age wall. Curl with plain Mozilla UA gets 200 everywhere.
