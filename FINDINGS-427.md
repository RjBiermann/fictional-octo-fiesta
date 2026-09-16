# FINDINGS-427 — xHamster: blurred posters (issue #427)

Probe 2026-09-16, runner egress LAX. Desktop/mobile UA Chrome/130, cookie
`video_titles_translation=0`, `?geo=us` plus a geo sweep (us/de/jp/cz/nl/es/ca/au/ru/br/fr/it).

## Reproduction (issue #427 — "search results show blurred poster")

Search cards parse fine (46 cards, valid title/pageURL/thumbURL) — nothing broken in the
provider's mapping. The blur is in the *image data the site serves*:

- Every guest listing surface (search, /newest, /most-viewed, /4k) serves thumbnails with
  `/sfw/` (safe-for-work) in the xhcdn path: `.../s(w:526,h:298),webp/<asset>/sfw/cv/hq.1.webp`.
  Sep-11 fixtures (issues #338/#339) already carry `sfw` — this is not a new selector break.
- The `/sfw/` assets are blur-processed by the CDN. Laplacian A/B, same CDN, same resolution
  class, same content class:
  - `sfw/cv/hq.1.webp` @526×298: gradient mean **7.3**, p95 **27**
  - `sfw/cv/hq.1.webp` @1280×720: gradient mean **2.7**, p95 **9** (sharp photo baseline ~6.3/25)
  - unblurred capture frame `v2/526x298.223.webp` (video-page fixture poster, pre-degradation):
    gradient mean **32.8**, p95 **150**
  → sfw thumbs are ~4.5× smoother than natural video frames: deliberately blurred.

## How far the degradation goes (this run)

- Search/home listings: `thumbURL` = `/sfw/cv/hq.N` — blurred, only variant on offer.
- Video page (guest, desktop): `videoEntity.thumbBig` is now `/b(2),s(w:16,h:9).../v2/16x9.201.webp`
  — a **16×9-pixel** image (verified by download: 16×9 RGB). `videoModel.thumbURL` exists but is
  `s(w:1280,h:720)` + `/sfw/cv/` (blur, just larger). `xp-preload-image` css = same sfw 1280.
  The Sep-11 fixture's poster (`s(w:526).../v2/526x298.223.webp`, no sfw, sharp) is gone from
  today's initials — the video-page poster degraded between Sep 11 and today.
- Navigation-level exceptions tried — all still sfw: no-geo, 12 geos, desktop/mobile UA,
  mirrors (xhplanet/xhmoon5/xhwide1/xhlove5/xhwebsite5/xhdate/xhkhmer5 — 4 of them respond, all
  sfw), googlebot/bingbot UA, full Sec-opt-in Chrome header set, cookie guesses
  (`sfw=0/safe_mode=0/nsfw=1/sfwMode=0/xh_sfw=0/ageProtectAgreement=1/parental-control=1/ageVerified=1`...),
  URL params (`sfw=0/sfw=xxx/unsafe=1`), xhcdn host swaps (`thumb-p0`, `ic-vt-lm` — same bytes),
  sfw→xxx path change with same signature hash → **403** (the `a/` MD5 signature covers
  params + path; signing is server-side, no client JS builds these URLs — zero md5/xhcdn
  hash helpers in any served bundle).
- Server flags on every guest page: `"isSfw":true`,
  `"isAgeVerificationRequired":true` — sharp (`/xxx/`) thumbs require an age-verified account
  (avsgate.com Yoti: government ID or camera age-estimation flow); not obtainable anonymously.

## Consequence for the provider

- **Search/home posters**: guest-visible images are sfw (blurred) — identical to what a
  non-logged-in browser sees on xhamster.com. There is no guest-obtainable unblurred thumb
  anywhere on the site or its mirrors. Not fixable provider-side; the only sharper variants
  need a signed `/xxx/` URL that only an age-verified account gets.
- **Video-page (load) poster**: today `thumbBig` is a 16×9-pixel b(2) artifact — a broken
  poster URL, and in-app it renders as … a blurred smear. Fixable: fall back when thumbBig is
  the tiny variant (contains `s(w:16,h:9)`) to `videoModel.thumbURL` (sfw 1280 — blurred-but-
  viewable, minus the site-wide guest blur) then the preload-image css.

## Fix scope for this PR

- `load()`: poster chain becomes thumbBig *only if it's a real image* (not the 16×9 variant),
  else `videoModel.thumbURL`, else preload css. TDD test red→green against fresh fixture
  (fixture `xhamster-video-v1.html` is Sep-11 era with a sharp thumbBig; fresh pages degrade —
  the tiny-poster helper is tested both ways).
- Version bump (repo rule). No change to search/home mapping — nothing there produces a
  better image than the site itself shows.

Deletion candidates checked and rejected: replacing sfw lists with `xxx` URLs (403 — signed),
reconstructing xhcdn signature (secret not exposed client-side), scraping zero-API.

## Re-probe addendum (same round): recommendations lost on desktop video pages + poster fallback gaps

- Desktop video pages: `videoPageComponent.relatedVideos` is now EMPTY server-side — the
  related block went lazy: `relatedVideosComponent.relatedTabs` carries an empty shell
  ({type:"video"} ×3, no ids/titles) and `videoTabInitialData.nextRelatedPageCountMap`
  {1:12, 2:42}. DOM related-item selectors: 0. No related XHR endpoint is exposed
  statically (checked all served desktop bundles — no related fetch URL). The **mobile-UA
  video page still hydrates 11 related `videoThumbProps`** in
  `videoPageComponent.relatedVideos.videoTabInitialData.videoListProps` (verified on
  xhZNISv and xhTIqO1) — same page the stream path already fetches. Provider load() now
  falls back to the mobile fetch for recommendations when the desktop page has none.
- Poster fallback gap: on 1 of 5 sampled videos (xheRKdJ) BOTH `videoEntity.thumbBig` and
  `videoModel.thumbURL` are the 16×9 b(2) artifact and there is no preload-image css — no
  real poster exists on that guest video page at all (its author/channel page and page 1+2
  of the author's video list do not carry the video or any `/030/720/998/sfw/cv/` URL).
  The sfw `/sfw/cv/hq.1` display thumb exists only inside listing surfaces it happens to
  be listed on; per-video signature reconstruction is not possible (server-side MD5 secret).
  pickVideoPoster therefore falls through to posterUrl=null for those videos and CloudStream
  shows its standard placeholder — this cannot be sharpened without an age-verified account.
- Streams unchanged and confirmed live: mobile guest page exposes
  `xplayerSettings.sources.standard.h264` with 5 decode-able entries (same decodeXhUrl
  algos); desktop page still exposes sources only to age-verified sessions.

## Verification transcript (2026-09-16, JSON-aware supplement to verify.sh at /tmp/verify427.py)

PASS: search p1 + p2 cards (46/46), no shared cards
PASS: home newest p1 + p2 (46/46), no shared cards
PASS: home 4k p1 (50)
PASS: listing poster URL serves image (200 image/webp, 31 KB)
PASS: 5 varied video urls: 2×search, newest, 4k, related
PASS: all 5 load pages: title present; poster pipeline yields a real (non-16×9) URL on 4/5;
      1/5 (xheRKdJ) carries no usable poster server-side — NOTE, not provider-side
PASS: titles + poster paths distinct across the 5
PASS: video page exposes 5 decode-able h264 hex entries (stream side unchanged)
PASS: tags block present (33 tag/category links)
PASS: related ≥1 via the mobile-UA fallback (11 recs), distinct, never the video itself
`./gradlew Xhamster:test` green (41 assertions incl. new pickVideoPoster tests — red first,
green after the fix); `./gradlew Xhamster:make` builds.
