# FINDINGS — Film1k

## Engine fingerprint
WordPress video theme (Genesis-loop markup: `article.loop-post`, `entry-header`, `post_format-post-format-video`,
`/?s=` search, `/page/N` pagination, widget sidebar). Probing machine gets **HTTP 403 + `cf-mitigated: challenge`
(Cloudflare managed challenge)** on every film1k.com URL; evidence below. Page structure was recovered through
a rendering proxy (r.jina.ai, `X-Return-Format: html`) — markup matches server-rendered WP (lazyload `data-src`
attributes, `noscript` fallbacks are server-side).

```
$ curl -sI https://www.film1k.com/ | grep -iE 'server|cf-mitigated|content-type'
server: cloudflare
cf-mitigated: challenge
content-type: text/html; charset=UTF-8
```

## Video host
Streams are NOT on film1k.com. Each video page embeds one of:
- **film1k.xyz** (Byse file-host, SPA "Byse Frontend") — the player path implemented in the provider.
  Page markup (server-side): `<video id="my-video" poster="..."><source src="https://film1k.xyz/e/{code}/{slug}.mp4" type="video/mp4">`
  plus `<iframe data-lazyloaded="1" src="about:blank" data-src="https://film1k.xyz/e/{code}/{slug}" ...>`.
  Only the `{code}` segment matters.
- **abyssplayer.com** (`<iframe data-src="https://abyssplayer.com/?v=XXX">`) — SoTrym/jwplayer with encrypted
  `datas` blob; implemented via the shared AbyssPlayer adapter (see Stream sources section).

## Search
`GET https://www.film1k.com/?s={query}` — standard WP search. Page 2: `GET /page/2/?s={query}` (verified returns
different items than page 1). `/?s=q&page=2` also works.

```
$ curl -s -H "X-Return-Format: html" "https://r.jina.ai/https://www.film1k.com/?s=taboo" | grep -oE 'film1k\.com/[a-z0-9-]+\.html' | sort -u | head
film1k.com/all-about-anna-2005.html
film1k.com/beyond-taboo-1984.html
film1k.com/cannibal-taboo-2006.html
...
$ curl -s -H "X-Return-Format: html" "https://r.jina.ai/https://www.film1k.com/page/2/?s=taboo" | grep -oE 'film1k\.com/[a-z0-9-]+\.html' | sort -u | head -3
film1k.com/a-rock-and-a-hard-place-1999.html
film1k.com/abnormal-family-1984.html
film1k.com/blonde-heat-1985.html
```

Listing item selector (same on home, categories, search):
`article.loop-post` → `a[href]` (video page URL, `{slug}-{year}.html`), `h2.entry-title` (title),
`figure img[data-src|src]` (poster, `img.film1k.com/video-thumbs/*` or imgur fallback).

## Video pages (5 probed, varied listings)
| URL | listing | host |
|---|---|---|
| film1k.com/tick-tock-2000.html | home latest | film1k.xyz/e/4wsa1vlemk0e |
| film1k.com/9-to-5-days-in-porn-2008.html | home page/2 | film1k.xyz/e/840zkgf9c7kn |
| film1k.com/emmanuelle-1974-just-jaeckin.html | search (?s=emmanuelle) | film1k.xyz/e/v9isf6ljsoq9 |
| film1k.com/taboo-1980.html | top-10 popular | abyssplayer.com/?v=LTjOBeDQK |
| film1k.com/all-about-anna-2005.html | top-10 popular | abyssplayer.com |

Meta available: `og:title`, `og:description`, `og:image`, `h1.title`; body info block:
`<strong>Runtime</strong>: 93 mins.` / `<strong>Genres</strong>: ...` / `<strong>Director</strong>` /
`<strong>Actors</strong>` / `<strong>Countries</strong>` / `<strong>Language</strong>`.

```
$ grep -oE 'og:[a-z]+" content="[^"]{0,80}' v1.html | head
og:title" content="Tick Tock (2000) - Watch Free Online | Film1k
og:description" content="Watch Tick Tock (2000) free on Film1k...
og:image" content="https://img.film1k.com/video-thumbs/tick-tock-2000-video.jpg
$ grep -o '<strong>Runtime</strong>[^<]*<[^>]*>[^<]*' → "<strong>Runtime</strong>: 93 mins."
```

## Related videos
Every video page exposes a "Related Videos" block inside <main> (the committed 2026-09-09 FINDINGS said
"none" — that grep was case-sensitive and missed `<h3 class="page-header">Related Videos</h3>`).
Scope to <main>, take the section after `h3:containsOwn(Related Videos)`, cards are
`article.loop-post` — identical markup to listings (header.entry-header>a href, h2.entry-title,
figure img data-src). 20 cards per page, sampled 3/3 video pages fresh 2026-09-10:

```
$ grep -oE 'https://www.film1k.com/[a-z0-9-]+\.html' tick-tock-section | head -4
/coffin-full-of-dollars-1971.html | Coffin Full of Dollars (1971)
/diary-of-a-teenage-hitchhiker-1979.html | Diary of a Teenage Hitchhiker (1979)
```
NOTE: the sidebar also renders loop-post cards — the <main> scope is what separates related from nav.

## Stream sources (per video page)
film1k.xyz (Byse) chain, verified end-to-end from this runner for two codes:

1. `POST https://film1k.xyz/api/videos/{code}/embed/captcha/` (body `{}`)
   → `{"pow_nonce","pow_difficulty":16,"pow_token","expires_in":1800,"algorithm":"sha256-leading-zero-bits"}`
2. Solve PoW: smallest `s` with ≥16 leading zero bits of Byse `gr`-hash over `pow_nonce + ":" + s`
   (custom 32-bit hash from `assets/pow-*.js`, ported to Kotlin — digests byte-verified against original JS).
3. `POST .../embed/captcha/verify/` `{"pow_token","solution"}` → `{"status":"ok","token",...}`
   (wrong solution → `{"reason":"pow_failed"}`; input is pow_nonce, NOT pow_token)
4. `POST .../embed/playback/` with header `X-Captcha-Token` and body `{"fingerprint":{}}`
   (empty body `{}` → HTTP 405) → `{"playback":{"algorithm":"AES-256-GCM","iv","payload","key_parts":[30],"version"}}`
5. Key = concat(b64urldecode(key_parts[version]), b64urldecode(key_parts[30-version])) (2×16B = 32B);
   AES-256-GCM decrypt payload (tag = last 16B) → `{"sources":[{"url":"...master.m3u8?...","label":"1080p",...}]}`
6. HLS verified:

```
$ curl -s "https://edge1-madrid-sprintcdn.r66nv9ed.com/hls2/07/11919/4wsa1vlemk0e_x/master.m3u8?..."
#EXTM3U
#EXT-X-STREAM-INF:...RESOLUTION=1280x720...
https://edge1-madrid-sprintcdn.r66nv9ed.com/hls2/07/11919/4wsa1vlemk0e_x/index-v1-a1.m3u8?...
$ curl -s -o /dev/null -w "%{http_code} %{content_type}\n" -r 0-100000 ".../seg-1-v1-a1.ts?..."
206 video/MP2T
(code 840zkgf9c7kn → edge1-waw-sprintcdn..., same result: master.m3u8 → index-v1-a1.m3u8 → seg HTTP 206 video/MP2T)
```

abyssplayer videos (implemented 2026-09-10 via the shared AbyssPlayer adapter): embed page carries
`const datas = "<base64>"` = base64 JSON {slug, md5_id, user_id, media}. media is AES-256-CTR encrypted
(key = ASCII bytes of md5hex("user_id:slug:md5_id"), IV/counter = key[0..16]); decrypting yields
{"mp4":{"sources":[{label,res_id,size,codec,status,sub}],"domains":[...],"fristDatas":[... {url, partSize}]}}.
The playable link is `https://{sub}.sssrr.org/sora/{size}/{token}` where token = double-b64(AES-CTR(
"/mp4/{md5_id}/{res_id}/{size}?v={slug}", key = ASCII-hex-of-md5(size))) — generated in the obfuscated
core.bundle.js. This repo does NOT re-implement the token: the shared adapter POSTs the datas blob to
https://enc-dec.app/api/dec-abyss (public decrypt service, same pattern as Cs-Karma's AbyssExtractor)
and maps result.sources (status:true) to links. ponytail: third-party decrypt dependency; if enc-dec.app
dies, the local reversal plan is documented here (media decrypt above; token plaintext confirmed in
core.deob2.js `/mp4/{md5_id}/{res_id}/{size}?v={slug}`).

Fresh verification 2026-09-10 (this runner):
- taboo-1980.html → abyssplayer.com/?v=LTjOBeDQK → 360p/720p/1080p (1080p 206 video/mp4 via redirect)
- all-about-anna-2005.html → ?v=PTNjwWoLv → 360p (206 video/mp4)
- sora links 302 → rotating trycloudflare tunnel; Referer required (403 without). Retrying on 403/timeout picks a live tunnel.

## Headers / referer
- film1k.xyz API: no cookies, no referer needed (verified with plain curl, empty cookie jar).
- CDN HLS: no referer needed, browser UA suffices.
- film1k.com itself: Cloudflare challenge from datacenter IPs (see Risks).

## Pagination
- Home: `/page/{N}` (page 2 verified, different items, site footer shows up to page 359).
- Category: `/category/{slug}/page/{N}` (verified: /category/horror/page/2 → different titles).
- Search: `/page/{N}/?s={query}` (verified).

## Year
`og:title` = "Tick Tock (2000) - Watch Free Online | Film1k" — the year is in every og:title and every
slug (`{slug}-{year}.html`). Provider maps `this.year` from it (issue #233 gap 3). Fresh 2026-09-10:
`Tick Tock (2000) → year=2000`, `Taboo (1980) → 1980`.

## Risks / blockers
- **film1k.com is behind a Cloudflare managed challenge for datacenter runners** (`cf-mitigated: challenge`, HTTP 403 on every URL). verify.sh only reaches the site from a non-blocked runner; this PR's PASS (2026-09-10) was recorded from an unblocked runner. In-app behavior (NiceHttp from a device) may differ.
- Videos are ~50/50 split between film1k.xyz and abyssplayer.com — both implemented (abyss via the shared AbyssPlayer adapter in this PR, see Stream sources).
- Byse rotates edge CDNs (madrid/waw/...) — handled since playback returns fresh signed m3u8 URLs per request.
