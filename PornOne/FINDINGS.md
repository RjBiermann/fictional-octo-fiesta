# FINDINGS — PornOne

Probed 2026-09-06 per site-probe skill. Engine: **custom** (tailwind-styled server-rendered app,
`videocard` markup, JSON-LD via `data-react-helmet`). Chosen as the #5 tracer after
SpankBang (Cloudflare-Blocked) and hclips/upornia (SPA, no server HTML) were ruled out.
XVideos was verified equally workable (evidence in ticket #2) but skipped at maintainer request.

## Engine fingerprint

- Server-rendered: 53 `a.videocard` items on the homepage; no `#app` SPA mount, no `kt_player`
- Tailwind utility classes throughout; JSON-LD scripts tagged `data-react-helmet="true"`
- Not KVS (`kt_player`/`flashvars` absent), not WP (`wp-content` absent)

## Search

- **Works:** `https://pornone.com/?s={query}` → HTTP 200, 47–54 `a.videocard` cards for
  `?s=amber` (spaces as-is; URL-encode `+`/`%20` — `?s=amber+leaves` accepted)
- **Dead:** `/search/{q}/` → 301 loop (to homepage), `/searchquery/{q}/` → 404

Transcript:
```
$ curl -s "https://pornone.com/?s=amber" | grep -c videocard      → 54
$ curl -s -o /dev/null -w "%{http_code}" "https://pornone.com/search/amber/"  → 301
```

## Video page

- URL shape: `https://pornone.com/{category}/{slug}/{numeric-id}/`
- **JSON-LD `VideoObject`** carries everything (`<script type="application/ld+json"
  data-react-helmet="true">`): `name`, `description`, `thumbnailUrl` (array), `uploadDate`,
  `duration` (ISO-8601 `P0DT0H21M7S`), `contentUrl` (lowest-quality direct mp4), `videoQuality`
- Page also has: `h1` (clean title, no site suffix), `og:image` poster, direct `<source>` tags
- Sample JSON-LD (transcript):
```
"@type": "VideoObject", "name": "lesbian fetish",
"thumbnailUrl": ["https://th-eu4.pornone.com/t/67/280872467/b152.jpg", …],
"uploadDate": "2026-08-23T18:14:04+00:00", "duration": "P0DT0H21M7S",
"contentUrl": "https://s313.pornone.com/vid2/…/280872467_720x406_500k.mp4?lang=en"
```

## Stream source

- Direct MP4s as `<source src="…" res="1080">` elements in the page player (no JS decode, no
  extractor needed). Quality lives in the `res` attribute AND the filename
  (`280872467_1920x1080_4000k.mp4`)
- `contentUrl` in JSON-LD is the 500k/720x406 fallback — prefer the `<source>` set
- Verified:

```
$ GET contentUrl (Range: 0-64, plain UA, no referer)
STATUS: 206   Content-Type: video/mp4   Content-Range: bytes 0-64/95179050
```

## Headers / referer

- Stream serves **without** referer: plain browser UA + Range → 206 video/mp4
- Site pages: plain browser UA required

## Pagination

- **Path-based**: page N of the current listing = prefix `/{N}/` before the query —
  search page 2 is `https://pornone.com/2/?s=amber`, homepage page 2 is
  `https://pornone.com/2/` (homepage carries `<link rel="next" href="https://pornone.com/2/">`)
- Verified: `/2/?s=amber` → 35 items, **0 id overlap** with page 1
- Dead ends documented: `&page=2`, `&p=2`, `&paged=2`, `&start=` all return page 1 unchanged

## Listing item structure (homepage + search share it)

```
a.videocard[href=https://pornone.com/{cat}/{slug}/{id}/]
  div.thumbcont
    span.durlabel  → duration text ("21:07", next to an svg)
    img[src=https://th-eu{N}.pornone.com/t/{cat}/{id}/{d|b}{n}.jpg]  ← poster (plain src, no lazyload;
        selector img[src*="/t/"])
  div.titlecont → title text
```

Evidence: card block dump in probe log; the poster imgs are plain `src` matches for
`img[src*="/t/"]` (54 on the homepage, 0 svg false-positives after the `/t/` filter).

## Stream selector

`source[src]` (with `res` attribute for quality) — 2 matches on the sample video page; first
absolute `src` verified serving `206 video/mp4` (see Stream source).

## Risks / blockers

- Stream URLs embed a session token + timestamp (`/vid2/{token}/{ts}/{id}/…`) — assume they
  expire; resolve fresh in `loadLinks`, never persist
- Language-prefixed mirrors exist (`/de/`, `/nl/`, …) — main site is English; don't prefix
- No category/tag listing pages found for mainPage; homepage is the only general listing
