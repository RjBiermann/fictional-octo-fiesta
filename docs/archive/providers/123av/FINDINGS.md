# FINDINGS — 123AV (123av.com)

Evidence-only run (S1 of #1423, gate story #1653). No provider code ships with this file.
All probes were plain `curl` — no browser automation, no captcha solving, no challenge evasion.

**Verdict: gate is GREEN.** Both required studios (`1pondo`, `heyzo`) resolve to a plain-HTTP
HLS stream that returns video bytes with a documented header set, and a third (`tokyo-hot`)
does too. No wontfix recommendation.

**Network class proven:** GitHub Actions runner, egress Cloudflare colo `MSP` (read from the
`cf-ray` suffix on every response). The runner WAF deny reported by the plan round-1 probe
(2026-11-26, `403 Attention Required`) **did not reproduce in this run** — see
[Risks / blockers](#risks--blockers) for the full status and Ray IDs.

Fixtures for every probe below are committed under `123av/src/test/resources/`.

---

## Engine fingerprint

Custom Laravel + Alpine.js app (server-rendered Blade templates, Alpine components), delivered
behind Cloudflare. Not KVS, not WordPress.

Evidence (`https://123av.com/en`, 200, `text/html; charset=utf-8`, 103,937 B):

```
<script type="module" src="/assets/t4/dist/app-dev-CMTr_MWV.js" defer track>
...
<iframe class="player__frame" :src="currentEpisode?.url" allow="autoplay; fullscreen; encrypted-media" ...>
...
<div class="errpage__code">404</div><p class="errpage__msg">No query results for model [App\Models\Video].</p>
```

- `x-data="header({...})"`, `x-data="player(JSON.parse('...'))"`, `x-show`, `x-cloak`,
  `@click` — Alpine.js directives, not Vue (the parent issue's "Vue iframe" note is the
  *shape* of the binding, not the framework).
- `No query results for model [App\Models\Video]` is a Laravel Eloquent message.
- Client bundle: `/assets/t4/dist/app-dev-<hash>.js` (hash changes per deploy — always read it
  from the `script type="module"` tag in the served page, never hardcode).
- `server: cloudflare` on every response; `/cdn-cgi/challenge-platform/scripts/jsd/main.js` is
  present on all pages (normal CF bot-management injection, not an interstitial).

---

## Episode API reversal (the buildability gate)

There is **no separate episode JSON endpoint**. The episode list is inlined into the video page
as the `x-data` payload of the `player(...)` Alpine component, and the player URL is an **embed
host page**, not a media file. Chain, reversed end-to-end:

1. **Video page HTML** carries the episode list inline, HTML-entity-escaped:

   ```bash
   curl -s 'https://123av.com/en/v/1pondo-110326_001' | grep -o "player(JSON.parse('[^']*')"
   ```

   ```
   player(JSON.parse('[{\u0022number\u0022:1,\u0022name\u0022:\u00221\u0022,\u0022url\u0022:\u0022https:\\\/\\\/sadie-shop.site\\\/e\\\/D0E7W3?poster=https%3A%2F%2Ficdn.123av.me%2Fimg2%2Fs500%2Fc4%2F1pondo-110326_001%2Fcover.jpg%3F6b083139\u0022}]')
   ```

   Full call shape (the four extra args are `titleId`, `code`, rec-API base, rec-API key):

   ```
   player(JSON.parse('<episodes JSON>'), 1167587, '1pondo-110326_001', 'https://rec2.123av.com', 'e8bcc533-bc38-4a33-af0e-678d38237754')
   ```

   Decoded episodes JSON (1pondo-110326_001):

   ```json
   [{"number":1,"name":"1","url":"https://sadie-shop.site/e/D0E7W3?poster=https%3A%2F%2Ficdn.123av.me%2Fimg2%2Fs500%2Fc4%2F1pondo-110326_001%2Fcover.jpg%3F6b083139"}]
   ```

   So `currentEpisode?.url` is the **iframe src**: `https://sadie-shop.site/e/<hash>?poster=…`.
   Every sampled video (5 pages across 4 studios) had exactly one episode; the array is
   `number/name/url` so N episodes are representable.

2. **The embed page** (`https://sadie-shop.site/e/<hash>`) is a 1,814-byte plyr + hls.js shell;
   it loads `/assets/t4/embed/embed-dev-24grjPsq.js`. That script:

   ```js
   function getHashId() { /* /e/([a-z0-9_]+) */ }
   function buildStreamUrl(hashId) {
       const url = new URL("/stream", location.origin);
       new URLSearchParams(location.search).forEach((v,k) => url.searchParams.set(k,v));
       url.searchParams.set("id", hashId);
       return url.toString();
   }
   fetch(buildStreamUrl(hashId)).then(r => r.json()).then(r => setupPlayer(r.media));
   ```

   => `GET https://sadie-shop.site/stream?id=<hash>` returns the media JSON the player binds to.

3. **Stream API response** (200, `application/json`, 247 B):

   ```bash
   curl -s 'https://sadie-shop.site/stream?id=D0E7W3'
   ```

   ```json
   {"status":"ok","media":{"stream":"https://ek2n.cleartruevision.site/oo5/XEn0ezQAjwPK3U_oUqJFMNi91Ew8hZcJGuOU_R+ZhRoUy91lxtA/video.m3u8","vtt":"https://ek2n.cleartruevision.site/oo5/XEn0ezQAjwPK3U_oUqJFMNi91Ew8hZcJGuOU_R+ZhRoUy91lxtA/preview.vtt"}}
   ```

   - `?id=` alone is sufficient — the `poster` query param is cosmetic (verified with and
     without it: byte-identical body apart from the rotating CDN host).
   - An unknown id returns **404** with the site's HTML error page
     (`No query results for model [App\Models\Video].`), not JSON.
   - `media.stream` is a signed HLS master playlist; `media.vtt` is the scrub-preview track.
   - **The media host rotates per request** — observed across this run:
     `hannah-store.site`, `highpurepath.site`, `calmtruepath.site`, `cleartruevision.site`,
     `colton-studio.site`, `softpureheart.site`, all on `<edge-id>.<domain>/oo5/<token>/`.
     Never cache or pin the host; always call `/stream` fresh per `loadLinks`.

Fixtures: `stream-api-1pondo.json`, `stream-api-heyzo.json`, `stream-api-tokyo-hot.json` —
each holds **this `/stream` media response** (`{"status":"ok","media":{…}}`), *not* an episode
list; the episode array lives inline in `video-page-*.html` (step 1 above), so S2 has two
separate parse seams (`parseEpisodes` over page HTML, `parseStream` over this JSON). Plus
`embed-page-1pondo.html` and `embed-player.js` (the reversing source).

---

## Stream sources (per video page)

Every stream is **HLS** (`application/vnd.apple.mpegurl`) — decided by the response
content-type, never by the `.m3u8` suffix. Segment files are deliberately named
`.css` / `.svg` / `.webp` and are served as `text/css` etc., but the bytes are MPEG-TS
(`0x47` sync byte). A player must treat them as opaque HLS segments (ExoPlayer does).

Proof ladder per studio — `/stream` JSON → master playlist → child playlist → one segment:

```bash
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36'
curl -s 'https://sadie-shop.site/stream?id=<hash>'                       # 200 application/json
curl -s -A "$UA" -e 'https://sadie-shop.site/' -H 'Origin: https://sadie-shop.site' '<master>'
curl -s -A "$UA" -e 'https://sadie-shop.site/' -H 'Origin: https://sadie-shop.site' '<master minus video.m3u8>qc/v.m3u8'
curl -s -A "$UA" -e 'https://sadie-shop.site/' -H 'Origin: https://sadie-shop.site' '<...>/qc/<first segment>'
```

| Studio | Video page | embed id | `/stream` | master m3u8 | child playlist | first segment |
| --- | --- | --- | --- | --- | --- | --- |
| **1pondo** | `/en/v/1pondo-110326_001` | `D0E7W3` | 200 json 247 B | 200 `application/vnd.apple.mpegurl` 74 B | 200 `application/vnd.apple.mpegurl` 42,774 B | 200 `text/css` 134,796 B, magic `47 40 11 10` (TS) |
| **heyzo** | `/en/v/heyzo-3968` | `XKZLVQ` | 200 json 251 B | 200 `application/vnd.apple.mpegurl` 74 B | 200 `application/vnd.apple.mpegurl` 47,451 B | 200 `text/css` 132,916 B (TS) |
| tokyo-hot | `/en/v/tokyo-hot-ka055` | `P86JWYYK` | 200 json 251 B | 200 `application/vnd.apple.mpegurl` 87 B | 200 `application/vnd.apple.mpegurl` 27,912 B | 200 `text/css` 592,200 B (TS) |

Master playlist body (single 720p rendition in every sample):

```
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=2816000,RESOLUTION=1280x720
qc/v.m3u8
```

Child playlist head (segments are the obfuscated-name VOD list):

```
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:3
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-PLAYLIST-TYPE:VOD
#EXTINF:3.000000,
MDUwLXYw.css
...
```

### Header matrix (what the media CDN accepts)

Same master-URL request, varying only headers (`hero` = `heyzo-3968` master):

| Sent | Result |
| --- | --- |
| browser UA only | **403** `text/html` 4,552 B — Cloudflare `Attention Required!`, `cf-ray …-MSP` |
| UA + `Referer: https://sadie-shop.site/` | **200** `application/vnd.apple.mpegurl` |
| UA + `Origin: https://sadie-shop.site` | **200** `application/vnd.apple.mpegurl` |
| `curl/8` UA + `Referer` + `Origin` | **200** `application/vnd.apple.mpegurl` |
| UA + `Referer: https://123av.com/` | **403** `text/html` 4,552 B (CF deny) |
| UA + `Referer: https://123av.com/en/v/heyzo-3968` | **403** `text/html` 4,552 B (CF deny) |
| UA + `Referer: https://sadie-shop.site/e/XKZLVQ` (with path) | **200** `application/vnd.apple.mpegurl` |

Conclusion: the media CDN gates on **`Referer` (or `Origin`) whose host is the embed host**
(`sadie-shop.site`); the UA string is irrelevant; a `123av.com` referer is actively rejected.
`/stream` itself needs no referer (`200` with none, with `123av.com`, and with the embed URL).

> Provider implication: set `Referer: https://sadie-shop.site/` and `Origin: https://sadie-shop.site`
> on the HLS link (derive the origin from the episode URL on the page, so a changed embed host
> cannot silently break playback). Do **not** send `123av.com` as referer to the media host.

Fixtures: `video-page-1.html`, `video-page-2.html`, `video-page-3.html`,
`stream-api-{1pondo,heyzo,tokyo-hot}.json`.

---

## Headers / referer (site pages)

`https://123av.com/*` needs nothing beyond a normal browser UA: no cookie, no age gate, no
consent wall, no query unlock. 12/12 probes of `/en`, `/en/makers`, `/en/search` returned 200
across three rounds (see Ray IDs in Risks/blockers). The iframe embed host
(`sadie-shop.site`) also serves its shell to plain curl.

No age/cookie unlock exists or is needed — nothing to ship in `load()`.

---

## Search

Form on every page: `<form class="search__field" method="GET" action="/en/search">` with
`<input type="text" name="keyword">`. Query param is `keyword`, page param is `page`.

```bash
curl -s 'https://123av.com/en/search?keyword=1pondo'          # 200, 66,761 B
curl -s 'https://123av.com/en/search?keyword=1pondo&page=2'   # 200, 66,531 B
```

- Page 1 cards (12): `1pondo-122618_001`, `1pondo-122718_001`, `1pondo-011222_001`, …
- Page 2 cards (12): `1pondo-042819_840`, `1pondo-073016_350`, `1pondo-020221_001`, …
- **Set overlap page1 ∩ page2 = 0 of 12** (pipeline duplicate bar satisfied).
- Pagination links are absolute and HTML-escaped:
  `href="https://123av.com/en/search?keyword=1pondo&amp;page=2"` … up to `page=83` for this query.
- A query that matches the sampled video pages: `keyword=1pondo` (matches `1pondo-110326_001`
  reachable directly), and `keyword=heyzo` returns real cards (200, probed 3×).

**Card shape** (identical for search, maker rows, and home grids):

```html
<div class="card" x-data="save({ type: 'title', id: 1170450, saved: false })">
  <div class="card__poster" x-data="preview" data-preview="https://icdn.123av.me/preview/c3/same-713/preview.png?…">
    <a class="card__cover" href="/en/v/same-713">
      <img class="card__img" src="https://icdn.123av.me/img2/s360/c3/same-713/cover.jpg?…" alt="" loading="lazy">
      <div class="card__shade"></div>
      <span class="card__dur">2:12:44</span>
    </a>
    …
  </div>
  <div class="card__body">
    <h3 class="card__title"><a class="card__link" href="/en/v/same-713">SAME-713 — Having missed the last train, …</a></h3>
    <div class="card__meta"><span>31m 12s ago</span><span class="dot"></span><span class="card__views" …>250</span></div>
  </div>
</div>
```

- link: `a.card__cover[href]` (equivalently `a.card__link[href]`)
- title: `h3.card__title` text (the code prefix is part of the site's title string — keep it)
- poster: `img.card__img[src]` (real `icdn.123av.me` URL, not a placeholder)
- duration badge: `span.card__dur` (`H:MM:SS`)
- views / relative age: `div.card__meta` spans

Fixtures: `search-page-1.html`, `search-page-2.html`.

## Quick search

**No distinct quick-search endpoint.** The only search surface is the GET form above; the
client bundle contains no `/suggest`/`autocomplete` route (`grep -oi 'suggest' app.js` → 0
matches) and the input has no `@input`/debounce handler. `hasQuickSearch = false`.

---

## Homepage

`https://123av.com/en` (103,937 B) renders three server-side sections + one client-side rec row:

| # | Section | Cards | "See all" |
| --- | --- | --- | --- |
| 0 | featured swiper (`div.featured-swiper`, no `<h2>`) | 20 slides | — |
| 1 | **New Releases** (`section__title`) | 12 | `/en/new` |
| 2 | **Recently Added** | 12 | `/en/recent` |
| 3 | `rec section` `x-data="recSections(…)"` | client-fetched | — |

- **Cards, not anchors.** A row is 12 `div.card`; every card emits **two** `/en/v/` anchors
  (cover + title), so an anchor count reads as 24 and double-counts the row. `home-page-1.html`
  New Releases cards: `same-713`, `mimk-302`, `idpc-028`, `ipzz-994`, `waaa-710`, `adn-823`,
  `mida-887`, `mngs-091`, `mida-854`, `same-711`, `adn-821`, `cawb-082`; Recently Added:
  `same-713`, `mimk-302`, `ienf-158-uncensored-leaked`, … — 12 cards / 24 anchors each.
- Homepage itself does **not** paginate; each row's "See all" opens the paginated listing
  (`/en/new`, `/en/recent`, `/en/hot`, `/en/all?sort=today|week|month`), which take `?page=N`.
- **A listing page is not disjoint from the home row — the boundary card repeats.**
  `home-page-2.html` = `https://123av.com/en/new?page=2` (200, 68,340 B, 12 cards: `cawb-082`,
  `mida-881`, `adn-796`, …) starts with `cawb-082`, which is the **last** card of the home New
  Releases row: **1/12 overlap**, not 0. Paging is offset-based over a list that keeps growing,
  so a card can sit on both sides of a boundary. Consequences for S2:
  - **dedupe by `code`** when merging row → listing or page → page; an offset shift of one
    re-emits the boundary card;
  - do **not** build page 1 from `/en` and pages 2..N from `/en/new` on the assumption the two
    are disjoint (no `/en/new?page=1` capture is committed, so the "home row *is* page 1" claim
    is withdrawn — only the shape and the 1-card boundary repeat are evidenced here);
  - re-capture `/en/new?page=1` when S2 starts if it needs page 1's exact ordering.

Fixtures: `home-page-1.html` (`/en`), `home-page-2.html` (`/en/new?page=2`).

## Makers index (and the 11 ura rows)

`https://123av.com/en/makers` (200, 72,142 B) is an `a.mcard` grid, 32 pages (`?page=`):

```html
<a class="mcard" href="/en/makers/1pondo">
  <span class="mcard__logo">1P</span>
  <span class="mcard__body">
    <span class="mcard__name">1Pondo</span>
    <span class="mcard__meta">2,748 videos</span>
  </span>
  …follow button…
</a>
```

All eleven planned rows probed on page 1 (`GET /en/makers/<slug>`, plain curl, 200 each):

| slug | bytes | last page |
| --- | --- | --- |
| `1pondo` | 68,060 | 229 |
| `caribbeancom` | 68,583 | 321 |
| `heyzo` | 68,306 | 278 |
| `takara-video` | 68,203 | 259 |
| `10musume` | 68,067 | 217 |
| `tokyo-hot` | 68,128 | 195 |
| `pacopacomama` | 68,788 | 169 |
| `h4610` | 67,410 | 162 |
| `c0930` | 67,473 | 142 |
| `h0930` | 67,460 | 140 |
| `xxx-av` | 68,286 | 31 |

Maker pages use the shared `div.card` shape, 12 cards/page, pagination
`https://123av.com/en/makers/1pondo?page=N`.

- `maker-1pondo-page-1.html` cards: `1pondo-112626_001`, `1pondo-112326_001`, `1pondo-112226_001`, …
- `maker-1pondo-page-2.html` cards: `1pondo-103126_001`, `1pondo-102926_001`, `1pondo-102726_001`, …
- **Set overlap page1 ∩ page2 = 0 of 12.**

Fixtures: `makers-index.html`, `maker-1pondo-page-1.html`, `maker-1pondo-page-2.html`.

---

## Video pages

Three probed pages, all 200, all fully server-rendered (metadata, description, related list):

| # | URL | code | title | release | duration | maker | cast |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `/en/v/1pondo-110326_001` | `1Pondo-110326_001` | `1Pondo-110326_001 — elcome To Luxury Spa: Rinka Natsume` | 2026-11-03 | 1:01:52 | 1Pondo | Natsume Rinka |
| 2 | `/en/v/heyzo-3968` | `HEYZO-3968` | `HEYZO-3968 — A frustrated, busty housewife who was cheated on through a dating app - Momoha Sakurada` | 2026-10-24 | 1:01:31 | HEYZO | Sakurada Momoha |
| 3 | `/en/v/tokyo-hot-ka055` | `Tokyo-Hot-ka055` | `Tokyo-Hot-ka055 — Uncensored Deep Sex File No.055 Juri 3P Edition` | 2020-08-08 | 54:56 | Tokyo Hot | *(not exposed)* |

Each fixture differs in title/poster/description/maker, satisfying the distinctness check.
Titles are already English on `/en` (the site serves a single localized title, `— ` separator;
the `elcome` typo is the site's own data — do not "fix" it, it is the literal title text).

### Field exposure inventory (per S4's inventory)

| Field | Exposed? | Selector / evidence |
| --- | --- | --- |
| title | yes | `h1.watch__title` (text includes the code prefix) |
| poster | yes | `div.player[style="background-image:url('<icdn cover>')"]` — e.g. `https://icdn.123av.me/img2/s500/c4/1pondo-110326_001/cover.jpg?6b083139` |
| description / plot | yes (2 of 3; empty for ka055) | `p.watch__desc-text` (HTML-escaped text, 2,071 B / 868 B / 0 B) |
| tags / categories | yes | `dt` = `Genres` → `dd.chips a.chip[href^=/en/genres/]` (11 chips on page 1) |
| duration | yes | `dt` = `Duration` → `dd` `H:MM:SS` |
| release date | yes | `dt` = `Release date` → `dd` `YYYY-MM-DD` |
| maker → studio | yes | `dt` = `Maker` → `dd.chips a.chip[href^=/en/makers/]` |
| cast / actors | yes when present | `dt` = `Cast` → `dd.chips a.chip[href^=/en/actresses/]` (absent on ka055) |
| series | yes when present | `dt` = `Series` → `dd.chips a.chip[href^=/en/series/]` (absent on heyzo-3968) |
| code | yes | `dt` = `Code` → `dd` |
| censoring type | yes | `dt` = `Type` → `dd a[href^=/en/uncensored]` (`Uncensored` on all three) |
| views / relative age | yes | `div.watch__meta--stats span.watch__metaitem` |
| related videos | yes (SSR) | `aside.watch-side ul.watch-side__list li a.vside` |
| multiple episodes | yes (array) | inline `player(JSON.parse(...))` episodes array |

Full `<dl class="watch__info">` sample (video-page-1):

```html
<div class="watch__info-row"><dt>Code</dt><dd>1Pondo-110326_001</dd></div>
<div class="watch__info-row"><dt>Type</dt><dd><a href="/en/uncensored">Uncensored</a></dd></div>
<div class="watch__info-row"><dt>Release date</dt><dd>2026-11-03</dd></div>
<div class="watch__info-row"><dt>Duration</dt><dd>1:01:52</dd></div>
<div class="watch__info-row"><dt>Cast</dt><dd class="chips"><a class="chip" href="/en/actresses/7e2ff94d03">Natsume Rinka</a></dd></div>
<div class="watch__info-row"><dt>Maker</dt><dd class="chips"><a class="chip" href="/en/makers/1pondo">1Pondo</a></dd></div>
<div class="watch__info-row"><dt>Series</dt><dd class="chips"><a class="chip" href="/en/series/f22cf8278f">Luxury soap</a></dd></div>
<div class="watch__info-row"><dt>Genres</dt><dd class="chips"><a class="chip" href="/en/genres/creampie">Creampie</a> …</dd></div>
```

Note the ordering contract: `dt` text is the only stable key (`Code`, `Type`, `Release date`,
`Duration`, `Cast`, `Maker`, `Series`, `Genres`) — several rows are optional per video, so a
pure `dd:nth-of-type()` parse will drift. Match on the `dt` label.

Fixtures: `video-page-1.html`, `video-page-2.html`, `video-page-3.html`.

## Related videos

**Server-rendered** sidebar — no API needed (the client-side `You may like` grid is a separate,
optional rec2 feature; see below).

```html
<aside class="watch-side">
  <h2 class="watch-side__title">Related videos</h2>
  <ul class="watch-side__list">
    <li><a class="vside" href="/en/v/pacopacomama-091226_100">
      <span class="vside__thumb" style="background-image:url('https://icdn.123av.me/…/cover.jpg?…')"></span>
      <span class="vside__body">
        <span class="vside__title">Pacopacomama-091226_100 — Make a Call to Husband …</span>
        <span class="vside__meta"><span>2mos 2w ago</span><span class="dot"></span><span>17.9K views</span></span>
      </span>
    </a></li>
    …
  </ul>
</aside>
```

12 related items per sampled page (12 `a.vside` in all three video fixtures), links
`a.vside[href]`, title `span.vside__title`, thumb in `span.vside__thumb[style]`.

### (Optional) rec2 "You may like" API — reversed but not required

`section.rec` carries `x-data="recommendation({ base:'https://rec2.123av.com', key:'e8bcc533-bc38-4a33-af0e-678d38237754', …, scenario:'watch-similar', code:'<code>', count:12 })"`.
The client signs each request (from `app-dev-*.js`):

```js
function simpleHash(input) { let hash = 0;
  for (let i = 0; i < input.length; i++) { hash = (hash << 5) - hash + input.charCodeAt(i); hash |= 0; }
  return (hash >>> 0).toString(16); }
function signedUrl(base, key, path) {
  const timestamp = Math.floor(Date.now()/1e3).toString();
  const signature = simpleHash(key + timestamp + navigator.userAgent);
  return `${base}${path}?timestamp=${timestamp}&signature=${signature}`; }
```

Working request (32-bit signed accumulate, then `>>> 0`, then hex — note the key is *not* in the
path; only in the signature input; `target_user` must be a non-empty UUID):

```bash
POST 'https://rec2.123av.com/items/1pondo-110326_001/items?timestamp=<unix_s>&signature=<hash>'
Content-Type: application/json
{"count":12,"target_user":"<uuid4>","included_properties":["code","title_en","title_ja","duration","release_date"]}
# 200 application/json, 2,833 B
# {"recomm_id":"…","recomms":[{"code":"1Pondo-102026_001","title_en":"…","title_ja":"…","duration":"3715","release_date":"2026-10-20"}, …]}
```

Because the signature is mixed with `navigator.userAgent`, a provider must send the **same UA
string** it hashed. Not needed for S4 (SSR sidebar exists) — recorded so nobody re-reverses it.
Fixture: `related-api-1pondo.json`.

---

## Pagination

| Surface | Pattern | Page 2 | Overlap p1∩p2 |
| --- | --- | --- | --- |
| search | `/en/search?keyword=<q>&page=N` | yes | 0/12 |
| maker rows | `/en/makers/<slug>?page=N` | yes | 0/12 |
| New Releases row | `/en/new?page=N` | yes | **1/12** (boundary card `cawb-082` on both sides) |
| Recently Added row | `/en/recent?page=N` | yes | not sampled (same template as `/en/new`) |
| homepage `/en` | does not paginate (rows link to the listings above) | n/a | n/a |
| makers index | `/en/makers?page=N` | yes (32 pages) | not sampled |
| related videos | static sidebar, top 12, no pagination | no | n/a |

Past-the-end behaviour not sampled (no empty-page probe needed — every listing clamps at a
known last page in its pager, e.g. `makers/1pondo?page=229`).

---

## Risks / blockers

### Cloudflare on `123av.com` — the plan-run WAF deny is **not reproducing**

- Plan round 1 (automated probe, 2026-11-26 ~18:38Z) recorded `403`,
  `text/html`, 5,657–6,024 B, body = `Attention Required! | Cloudflare`
  (`cf-error-details`, Ray ID field) on three URLs — i.e. a firewall/ASN deny, not a JS
  interstitial.
- **This run (2026-11-26 22:52–22:56Z) got 200 on all 12 site-page probes** (three rounds of
  `/en`, `/en/makers`, `/en/search?keyword=heyzo`), content-length matching normal pages:

  ```
  200 cf-ray a60c7aad6c5f5114-MSP   https://123av.com/en#1
  200 cf-ray a60c7ab5f983a1d9-MSP   https://123av.com/en#2
  200 cf-ray a60c7abe78f54c99-MSP   https://123av.com/en#3
  200 cf-ray a60c7ac6cdbb5113-MSP   /en/makers#1
  200 cf-ray a60c7ace3a98fb43-MSP   /en/makers#2
  200 cf-ray a60c7ad5ae2bad1e-MSP   /en/makers#3
  200 cf-ray a60c7add49d74c91-MSP   /en/search?keyword=heyzo#1
  200 cf-ray a60c7ae708ff7816-MSP   /en/search?keyword=heyzo#2
  200 cf-ray a60c7aee995523db-MSP   /en/search?keyword=heyzo#3
  ```

  plus 200 on every fixture URL (home, makers index, maker p1/p2, search p1/p2, three video
  pages, three `/stream` calls).
- Conclusion: the deny is **intermittent** (or was path/timing-specific to that run), not a
  durable ASN block. Record it as a *watch* item, not a Blocked verification entry: the runner
  is currently in the allowed set, so pipeline Verification will exercise the live site.

### Cloudflare on the media CDN — real, but fully unlocked by the referer

- A media request with no `Referer`/`Origin` (or with a `123av.com` referer) returns
  `403 Attention Required!` (`cf-ray a60c77a568e7acff-MSP`, 4,552 B).
- With `Referer: https://sadie-shop.site/` (or `Origin:` the same) it returns 200. This is a
  hotlink rule keyed on the embed origin, not a challenge — plain HTTP, no cookie, no JS.
  `CloudflareKiller`/`cfChallenge` is **not** needed and must not be used here.

### Other observations

- The embed JS injects a pop-under (`attacheatableoverlabor.com`) when its referrer is not
  `123av.com`. Irrelevant for a datasource (never executes the JS), recorded so a future
  `WebView` extractor is not surprised.
- Media hosts rotate per request (six distinct `*.site` domains seen). Any caching layer that
  reuses a resolved m3u8 across episodes or sessions will 403/404; resolve per `loadLinks`.
- Two slugs taken from the rec2 API (`1pondo-082126_001`, `pacopacomama-112026_001`) returned
  **404** on `/en/v/…` — rec API entries can point at titles without a watch page. `load()` must
  return `null` on a 404 (matches the spec's wall rule).
- Region: all probes ran from the GitHub runner egress through Cloudflare colo `MSP`
  (Minneapolis). No geo-wall was observed; the catalog shown matches the maker counts in #1423.

---

## Gate decision (S1 exit)

- **1pondo** — `https://123av.com/en/v/1pondo-110326_001` → `sadie-shop.site/e/D0E7W3` →
  `/stream?id=D0E7W3` → HLS master 200 `application/vnd.apple.mpegurl` → child playlist 200 →
  segment 200, 134,796 B of MPEG-TS. Header set: `Referer`/`Origin` = `https://sadie-shop.site`.
- **heyzo** — `https://123av.com/en/v/heyzo-3968` → `sadie-shop.site/e/XKZLVQ` →
  `/stream?id=XKZLVQ` → HLS master 200 → child playlist 200 → segment 200, 132,916 B TS.
- Bonus: **tokyo-hot** (`tokyo-hot-ka055`) proves identically, so S5's three-studio matrix has
  evidence up front.

**Exit criterion (Green) met.** S2–S5 are buildable; no wontfix recommendation. No provider
code was written in this story, and no `shared/` change is needed — the stream is a direct HLS
URL with a referer, so no `HostRegistry` adapter and no embed-host extractor.
