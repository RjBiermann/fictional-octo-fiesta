# FINDINGS — HQPorner (fix probe for issue #158)

## Engine fingerprint
Custom theme (same as prior probes; `section.box.feature` card markup family).

## Root cause of the drift (#158)
The site now **302s mobile User-Agents to `m.hqporner.com`**:

```
$ curl -A "Mozilla/5.0 (Linux; Android 13; Pixel 7) …Chrome/120 Mobile" -D - "https://hqporner.com/?q=milf&p=1"
→ location: https://m.hqporner.com/?q=milf&p=1
```

The mobile page's cards are `<div class="img-container"><a href="/hdporn/…" class="atfib n8hu6s"><img …></a>`
— **no `section.box.feature`, no `a.image`** → 0 results, exactly the monitor's symptom
("tag landing page", 0 video cards; h1 = "Milf Porn HD Videos"). CloudStream/NiceHttp's
default UA is a mobile Chrome UA, so the provider's requests were being redirected.

## Search (desktop UA — the one that works)
```
$ curl -sA "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:130.0) … Firefox/130.0" \
    "https://hqporner.com/?q=milf&p=1"
→ HTTP 200; 50 cards `<section class="box feature"><a href="/hdporn/…" class="image featured non-overlay atfib …">
   <img src="//fastporndelivery.hqporner.com/imgs/…/…_main.jpg" alt="…">`
   selector `div.row section.box.feature:has(a.image)` matches 50 (1 extra non-card
   search-summary section has no a.image and is filtered out).
```
`?q=` + `&p=N` pagination unchanged; page 2 returns different items.

## Video pages
`/hdporn/<id>-<slug>.html`, HTTP 200 with desktop UA. Probed 5 varied:
from search, top, and category/asian listings (see PR verify transcript).
- iframe player: `<iframe … src="//mydaddy.cc/video/<hash>/"` (plain `src`, selector `iframe[src*=mydaddy]` unchanged)
- title `h1`, meta description, rating, duration `li.icon.fa-clock-o`, actors `li.icon.fa-star-o a` all present.

## Related videos
```
selector `div.\34 u section` on video pages → 47 matches, cards carry `a.image`
```
(verify.sh reports 47 for `div.4u section`.)

## Stream sources (per video page)
Two-hop: hqporner → mydaddy.cc iframe → `s*.bigcdn.cc/pubs/<key>/<res>.mp4` (360p/720p/1080p).
Checked 5 videos, all `HTTP 206, Content-Type: video/mp4` on `1080.mp4`:

| video | stream check |
|---|---|
| /hdporn/124516-… | 206 video/mp4 (s53.bigcdn.cc) |
| /hdporn/101302-… | 206 video/mp4 (s24.bigcdn.cc) |
| /hdporn/127769-… | 206 video/mp4 (s72.bigcdn.cc) |
| /hdporn/124908-… | 206 video/mp4 (s29.bigcdn.cc) |
| /hdporn/124505-… | 206 video/mp4 (s63.bigcdn.cc) |

## Headers / referer
Desktop UA required (mobile UA = redirect to m.hqporner.com). mydaddy.cc fetch works with
hqporner referer; mp4 CDN works with mydaddy referer.

## Pagination
`?p=N` on search; `/<page>` suffix on category/top listings — unchanged.

## Risks / blockers
- Any client using a mobile UA is silently redirected to m.hqporner.com → card markup
  mismatch. Fix pins the desktop UA on every provider request (getMainPage/search/load;
  loadLinks already had one).
- Card thumbnail classes include randomized tokens (`atfib n8hu6s`) that vary per
  response — selectors must not depend on them.
