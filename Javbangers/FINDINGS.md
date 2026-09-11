# FINDINGS — javbangers.com

## Engine fingerprint
KVS / Kernel Video Sharing (Porntrex-family — homepage carries `porntrex-box` wrapper and
`list_videos_*` block ids; video pages use inline `flashvars` + `kt_player`).
In-repo reference: `Porntrex/` — same engine, same card markup, but **different paging
behavior and selector details** (see below; the Porntrex selectors for title/desc/categories
do NOT exist on javbangers).

```
$ curl -s https://www.javbangers.com/ | grep -c list_videos   -> 37
$ curl -s <video page> | grep -oE 'kt_player' | head -> kt_player (5x)
```

## Search
`/search/{query}/` works (hyphenated slug). Tested:
- `/search/mdyd/` → 3 result cards (works).
- `/search/mdyd/2/` → 404.
- `/search/japanese/` → 75 cards.
- async POST/GET forms with `from=2`, `from_videos+from_albums=2`, `?page=2` all return
  **page 1 items again** (verified overlap p1∩p2 = 75/75). Search pages beyond 1 are not
  reachable by simple URL — implement search page 1 only.

```
$ curl -s https://www.javbangers.com/search/mdyd/ | grep -oE '/video/[0-9]+' | sort -u
/video/393873 /video/401533 /video/401534
```

Search result card == listing card: `div.video-item > a.thumb[href, title]`,
`img.cover[data-original]` (lazyload), `p.inf a` (truncated title), `div.durations`.

## Video pages (7 probed, all plain `video_url`)
| source of URL | URL |
|---|---|
| home recent | /video/236474/md200 |
| home recent | /video/210889/fc2-ppv-3157360-… |
| home recent | /video/66008/japanese-busty-milf-hardcore-gangbang |
| home recent | /video/223404/sky-angel-asian-angel-1823 |
| home recent | /video/79786/japanhdv-spoiled-teen-izumi-tachibana-scene2 |
| home recent | /video/21660/korean-seoul-lovers-013 |
| related (236474) | /video/188678/sw-854 |

Selectors (from /video/236474/md200 transcript):
- title: `h1` → "MD200引導白絲母狗騎乘" ; also `og:title`
- poster: `meta[property="og:image"]` → https://jav.cdntrex.com/contents/videos_screenshots/236000/236474/preview.jpg
  (`#tab_screenshots img` is in a `hidden` tab, useless without JS)
- description: `div.videodesc em` → mirrors title here
- categories: `div.block-details a[href*="/categories/"]` → Blowjob, Hardcore, Uncensored
- duration: `em.badge` next to "Duration:" is EMPTY on the server-rendered page (JS fills it);
  duration is only available on listing cards (`div.durations` → "193:33"). Video page
  duration = null.
- Porntrex selectors (`p.title-video`, `div.js-categories a.js-cat`, `i.fa-clock-o`, `div.videodesc em.des-link`) **return 0 matches here**.

## Related videos
Present: `div.related-videos div.video-item` → `list_videos_related_videos_items`
```
$ curl -s https://www.javbangers.com/video/236474/md200 | grep -c 'div class="video-item'  -> 10 (related list)
```
(`/related_videos_html/{id}/` needs a session cookie, returned empty to plain curl — use the on-page selector.)

## Stream sources (per video page)
Every probed page: inline `flashvars` with plain direct URLs (`video_url` + optional
`video_alt_url`, quality in `video_url_text` / `video_alt_url_text`). No m3u8, no embeds.

- 236474: video_url 480p + video_alt_url 720p
- 188678, 223404, 210889, 79786: video_url + video_alt_url
- 21660: video_url only (alt=0)

```
$ curl -sIL '...get_file/...236474_720p.mp4/?v-acctoken=...' -e https://www.javbangers.com/
HTTP/1.1 302 Found   Location: https://origin25-direct.cdntrex.com/remote_control.php?file=...mp4&acctoken=...
HTTP/1.1 200 OK
Content-Type: video/mp4
Content-Length: 208704156
```

## Headers / referer
No auth required. `get_file` → 302 → cdntrex origin; served video/mp4 with referer empty even
set already correct via response; set `referer = mainUrl` on the ExtractorLink to be safe.
Normal UA fine.

## Pagination
Listings are path-paginated: `/latest-updates/{N}/`, `/most-popular/{N}/`, `/top-rated/{N}/`,
`/categories/{slug}/{N}/` (verified `/latest-updates/2/` and `/most-popular/2/` return
different items, 0 overlap with page 1; `/latest-updates/4444/` → 404).

## Main page category slugs (verified 200 + 24 cards)
`milf`, `uncensored`, `creampie`, `cosplay`, `hentai`, `teen` — all live.
`categories/asian/` → **404** (no such slug on site) → main page uses `chinese` instead
(live, 24 cards). Full slug list: amateur, anal, babe, blowjob, bondage, bukkake, busty,
censored, chinese, cosplay, creampie, cumshot, deep-throat, fetish, hairy, handjob,
hardcore, hentai, lesbian, massage, masturbate, milf, orgy, outdoor, squirt, teen, thai,
threesome, toys, uncensored, uniform.

## Risks / blockers
- No Cloudflare/bot wall observed (plain HTTP 200, only PHPSESSID cookie).
- Search pages beyond page 1 unreachable — provider returns page 1 only for search.
- `?page=2`/async pagination silently return page-1 content; don't use them.
- Video-page duration absent server-side (JS-filled badge) — LoadResponse.duration null.

## Tags row (issue #297, verified 2026-09-11)
Video pages carry a `div.item` whose text starts `Tags:`; its anchors have **no href**, so
the old categories-only selector never captured them. Exposure: 66008 → 18 real tags,
188678 → 2 (SW-854 + long JP title), 223404 → 1 (Hardcore), 236474 → 1 (the title itself —
junk); 210889 and 21660 → no Tags row. Fix: `JavbangersParse.tagsFromDetails` merges the
categories links (`a[href*="/categories/"]` scoped to `div.block-details`) with the
href-less Tags-row anchors into one LinkedHashSet (dedupe) and drops the title-echo entry.
Unit tests: `Javbangers/src/test/kotlin/.../JavbangersParseTest.kt` (fixture
`javbangers_details.html`). verify.sh: PASS (3 videos; streams 206 video/mp4; Tags-row
anchors 18/2/1 by grep on the saved pages).
