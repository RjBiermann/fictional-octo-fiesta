# FINDINGS — issue #411 (Cat3Movie: "Video doesn't play. Getting no links found error in the app")

Probed 2026-09-13 from this runner (IAD egress), main branch = shipped build v7
(`origin/builds:plugins.json` Cat3Movie version 7, same code as HEAD).

## Server-side chain: fully intact — every step re-verified live

Replicated the provider's exact chain with curl (browser UA) on 4 fresh videos
(mind-the-gap-2007, rancid-2004, attenberg-2010, a-touch-of-class-1973):

- `GET /<slug>` → 200, `"post_id":34600` + `body[data-nonce="ab06106668"]` present
  (`Parse.streamConfig` inputs intact on every sampled page).
- `GET player.php?episode_slug=full&server_id=N&subsv_id=&post_id=…&nonce=…&_=<ms>` with
  `Referer: https://cat3movie.org/watch-<slug>/full-svN.html` + `X-Requested-With` → 200
  iframe for all 3 servers on every video: sv1 `hlsfree.com/embed/hls/<id>`,
  sv2 `cdn.loadvid.com/videos/play/<hash>` (no adapter, known blob-gated), sv3 `hlsfast.com/#<hash>`.
- hlsfree leg: embed 200 with any referer (403 without — unchanged from #247), token
  `defaultHlsUrl` regex matches, `/api/hls/serve?token=<hex>` → 200 `#EXTM3U` (47 KB playlist,
  segments at s1.cat3hls.com, referer-gated 403-without/200-with — unchanged).
- hlsfast leg: `/api/v1/video?id=<hash>&w=1280&h=720&r=cat3movie.org` → 200 hex blob;
  AES-128-CBC decrypt (key `kiemtienmua911ca`, iv `1234567890oiuytr`) → JSON with both
  `cfNative` (`https://hlsfast.com/v4/pl/…master.<v>.m3u8?k=…`) and `source` — unchanged
  shape; cfNative → 200 `application/vnd.apple.mpegurl`, valid master playlist.
- player.php still ignores the nonce (bogus nonce → 200) and still **requires the Referer**
  (no referer → 404) — the provider sends it.
- UA gate: none — plain `Mozilla/5.0`, `okhttp/4.9.3`, Dalvik UA all get identical 200s.

## Transport-fingerprint probe (the variable curl cannot cover)

Raw **Go net/http** client (second, non-OpenSSL TLS fingerprint) with Chrome UA, cold
session: watch page, hlsfree embed, hlsfast API — all 200, no `cf-mitigated`, no
challenge body. Same method as FINDINGS-410: runner-side fingerprints pass.

## Root-cause evidence: Cloudflare JSD is new on cat3movie.org

```
$ curl -s https://cat3movie.org/mind-the-gap-2007 | grep -c 'challenge-platform/scripts/jsd/main.js'
1
$ curl -s https://cat3film.com/ | grep -c 'challenge-platform/scripts/jsd/main.js'
0
$ grep -c challenge-platform Cat3Movie/src/test/resources/home-dup.html \
      Cat3Movie/src/test/resources/player-page.html   # fixtures captured at #250 time
0 / 0
```

cat3movie.org now injects Cloudflare **JS Detection** (`/cdn-cgi/challenge-platform/scripts/jsd/main.js`)
into its 200 pages — a bot-fight-class protection that was absent when the #250-era
fixtures were captured and is absent on the sibling zone cat3film.com today. Healthy pages
carry **no** `Just a moment` / `cf-chl` / `_cf_chl` markers (grep 0/0/0 above), so those
remain clean interstitial triggers.

## Conclusion

No site-side drift vs FINDINGS #247/#250/#409: post_id/nonce, player.php, both embed
families and the decrypted stream URLs all return valid data even over a second TLS
fingerprint. The report ("No links found") is therefore transport-scoped, exactly the
class diagnosed in FINDINGS-410 for the sibling site:

**CF challenge on the fetching client, device/network-scoped.** Cat3Movie has **no
Cloudflare handling** on any fetch. On a challenged client every leg fails silently:
the watch-page fetch returns challenge HTML → `Parse.streamConfig` finds no post_id →
`loadLinks` returns `false` with zero callbacks (or, past that, player.php/embed/api
responses are interstitials → `embedIframe` null / non-hex body → no link). CS3 shows
"No links found". These are the only silent-zero-links paths in the code.

Fix per repo precedent (Cat3Film #410, FullPorner, Film1k): route Cat3Movie's fetches
through `CloudflareKiller` via a wrapper interceptor, with the challenge predicate
extracted into a tested pure `Parse.isChallengePage()` (TDD, ADR-0005). The shared
HlsFree adapter stays untouched: hlsfree.com shows no JSD and its dead-token retry
(issue #247) already covers its failure mode; the evidence points at the cat3movie.org
zone only.

## Site drift recorded along the way (not this issue's fix)

- **Front page no longer paginates**: `/`, `/page/2`, `/page/2/`, `/?paged=2` all render the
  identical card list (58 hrefs, 48 unique — Latest/Random widget strips + main grid;
  `entry-title` sequences diff = 0; differing `Last-Modified` so not a cache artifact).
  This contradicts FINDINGS.md's earlier "verified different posts on / vs /page/2".
  Category rows still paginate correctly (`/classic-porn` vs `/classic-porn/page/2` and
  `/new-movies` vs `/new-movies/page/2`: 20/23 cards each, overlap 0). The provider's
  front-page row will re-serve the same cards on app page 2 — cosmetic duplicate rows,
  separate from this issue.
- **Search exact-match 302**: `/search/the-mislayed-genie` 302s to the movie page
  (WP canonical redirect); multi-result queries (`/search/gap`) return cards normally.
- **hlsfree serve referer relaxed**: `/api/hls/serve?token=…` now 200s without any
  Referer (FINDINGS #247 recorded 500 `Proxy error` without). The adapter still sends
  `Referer: https://hlsfree.com/` — harmless.
- **bamboo-house-of-dolls-1973 stream still dead upstream** (hlsfast returns a non-hex
  body for hash `o3iz8s`) — the site-side limitation FINDINGS.md already records.
- Watch-page sidebar carries 3 empty Hot-Tag `article.thumb` placeholder shells
  (`<div class="halim-item"></div>`, same post-15919 ×3) outside the related section;
  the provider's jsoup `section.related-movies` scoping is unaffected.

## verify.sh rerun post-fix (2026-09-13) — RESULT: PASS

- search `/search/gap` (multi-result; exact-match queries 302 — see drift): 200, cards OK —
  agreement check exercised: sampled `mind-the-gap-2007` appears in search and matches its
  load page (title + poster) — PASS
- home rows: `/new-movies` + `/new-movies/page/2` (a paginating getMainPage row; the front
  page row does not paginate — see drift above): 23 cards each, no duplicates — PASS
- quick search: no distinct endpoint (omitted, NOTE) — provider `hasQuickSearch` unset
- video pages 5× (Latest/home ×3, /classic-porn, related-section): 200, `h1.entry-title`,
  `og:image`, `og:description`, tags/actors/year present and consistent on all 5 — PASS
  (duration: site does not expose it — NOTE)
- related: `a.halim-thumb[title]` = 8 real cards per page (verify tool matches only the
  selector's last part, so `section.related-movies article.thumb` would also catch the 3
  empty Hot-Tag shells — `a.halim-thumb[title]` is the equivalent watch-page shape):
  titles non-empty, distinct, never self — PASS
- streams (positional --stream-url, chain = player.php sv3 → hlsfast api → AES decrypt →
  cfNative; 5/5 distinct paths): all 200 `application/vnd.apple.mpegurl` — PASS.
  The sv1 hlsfree serve links were verified 200 `#EXTM3U` separately (4/4 videos; their
  identity lives in the query token, which the tool's path-only distinctness normalization
  collapses — hlsfast cfNative paths carry the per-video id, so they were used for the
  mechanical check).
- `./gradlew Cat3Movie:test` → PASS — 108 tests, 0 failures (incl. 5 new
  `Parse.isChallengePage` tests, red → green)
