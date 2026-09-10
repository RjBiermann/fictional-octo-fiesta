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

## Probe for #237 (library reload crash) — evidence
Symptom: saving a video to the CloudStream library, then opening it from the library,
crashes with `Index:1, size:1` at `Hqporner.kt:125`.

Root cause (code, not site drift): `load()` did `url.split("kraptor")[1]` unconditionally.
Search-result urls embed the poster as `href + "kraptor" + posterUrl`, but a library-saved
item carries only the `LoadResponse` url (`currentUrl`, from `newMovieLoadResponse`) — no
separator → `split()` yields 1 element → hard crash.

Fix: guard the split (`getOrNull(1)`); a library-saved item (no separator) gets a null
poster. The video page's static HTML does not embed the current video's image (iframe
player, no og:image / JSON-LD / own `<img>`), so no truthful fallback exists. `img[src*=imgs]`
first match on a video page is a **related** card's cover (`cover_<id>`, doc order line ~476),
e.g. `/hdporn/127769-…` → first img `cover_127782` ("i know your problem, stepmom") — dropping
it as a fallback would show another video's poster. Verified on 124908/127787/127769/124921.
(Search↔load keeps the embedded poster; only library reloads take the null path.)

## Live verify (re-run for #237, desktop UA)
- search `?q=milf|stepsis&p=1/2` → 200, 50 cards each, page 2 new items (verify.sh
  flags one "duplicate": `/hdporn/124516-…` appears on p1 and p2 — it lives in a
  `div.3u` top-rated **sidebar** on both pages, outside the `div.row` card grid the
  provider's `div.row section.box.feature:has(a.image)` selector filters to; not
  provider-visible. verify.sh's plain `a.image` selector can't express the parent filter.)
- search↔load agreement (manual, script ceiling: no `content` metas to read):
  card `img alt="pleasure overloaded"` ↔ load-page `h1` = "pleasure overloaded" ✓; card
  poster `_main.jpg` and fallback `_1.jpg` are the same video gallery ✓.
- video pages ×5 → 200; `iframe[src*=mydaddy]` ×1; tags/actors/duration present on all 5;
  related `div.4u section` 33–47 matches.
- title/poster/plot verify FAILs are the script's `meta content`-only limit: video pages
  expose only `meta name="description"` (no og: tags) and h1/alt titles — provider reads
  h1/alt, values confirmed live above.
- streams: script's stream check doesn't resolve the mydaddy two-hop (its fallback is
  filmcdm-only). Manual end-to-end: mydaddy iframe `//mydaddy.cc/video/0fa7a6046f45fddfcb/`
  → `//s13.bigcdn.cc/pubs/6aa209bfb398a2.02348091/1080.mp4` → `206 video/mp4` ✓.
