# FINDINGS — javmost.ws

## Engine fingerprint
Custom PHP theme (not KVS, not WP). Cards use `div.col-md-4.col-sm-6 > div.card` with lazy
`<picture><source data-srcset=...><img class="lazyload" data-src=...>`; Bootstrap 4-ish panels;
obfuscated player JS (`_0x3041` string array) posting to a fixed AJAX endpoint. curl evidence:

    $ curl -s https://www.javmost.ws/ -A "$UA" | grep -c thumbnail-container
    26

## Search
- Tested `https://www.javmost.ws/search/{query}/` → **works** (HTTP 200, 28 result cards for "avsa").
- Tested `?s={query}` — not present (no WordPress fingerprint). Kept `/search/{q}/`.
- Pagination of search: `https://www.javmost.ws/search/{q}/page/2/` → HTTP 200, 28 cards.

    $ curl -s -A "$UA" "https://www.javmost.ws/search/avsa/" | grep -c thumbnail-container
    28

Listing card structure (identical on home/search/category):

    <div class="col-md-4 col-sm-6"><div class="card ">
      <a href="https://www.javmost.ws/AVSA-457/" id="AVSA-457_tag" alt="AVSA-457">
        <div class="container2"><div class="thumbnail-container">
          <picture><source data-srcset="https://img2.javmost.ws/file_image/AVSA-457.jpg">
          <img class="card-img-top lazyload" data-src="https://img2.javmost.ws/file_image/AVSA-457.jpg">

## Video pages
URL shape: `https://www.javmost.ws/{CODE}/` (CODE = e.g. AVSA-457). Probed 5 from varied listings:
- https://www.javmost.ws/AVSA-457/  (home `category/all`)
- https://www.javmost.ws/DLDSS-529/ (home, recent)
- https://www.javmost.ws/FTHTD-192/ (home)
- https://www.javmost.ws/1PONDO-080926-001/ (`release/new`)
- https://www.javmost.ws/CPZ69-015/ (`category/uncensor/page/3`)
All HTTP 200. Metadata:
- title/code: `og:title` "Watch AVSA-457 JAV movie online free streaming. Genre: Married Woman." (genre optional in the string)
- ld+json `VideoObject`: `"name": "AVSA-457"`, `"uploadDate": "2026-03-03"`, `thumbnailUrl: https://img2.javmost.ws/file_image/CODE.jpg`
- `og:image` same poster URL. No duration exposed anywhere on the page.

## Related videos
Yes — after the player: "Relate Porn Star" and "Relate Genre" sections contain the same card
markup (`div.card > a[href="https://www.javmost.ws/CODE/"]` with `img[data-src]`), e.g.
RD1237, IENFH-13901, MIUM-254, HODV-20816 on AVSA-457. Selector: `div.card > a` filtered to
`/{CODE}/` hrefs (excluding the page's own code).

## Stream sources (per video page)
No direct `<video><source>` or og:video. Player is AJAX: each "SERVER n" tab has a
`select_part('1','<group>',this,'parent','<code>','<code2>','<code3>')` button; JS POSTs to

    https://www.javmost.ws/ri3123o235r/
    form: group, part=1, code, code2, code3, value=<page constant YWRzMQo>, sound=av
    (Referer: the video page)

Response JSON: `{"status":"success","msg":"","data":["<embed-url>"]}`.

Observed per page (group order varies; ~2 servers per video):
- `AVSA-457` g62 → `https://www.dooplayer.com/embed/e/MTEzMjky.5ef6592a1f6cbbc3` (**204 empty without JS — unusable server-side, skipped**)
- `AVSA-457` g60 → `https://emturbovid.com/t/6a9d77d386473`
- `DLDSS-529` g60 → `https://emturbovid.com/t/6a9ab91af390c`
- `FTHTD-192` g60 → `https://emturbovid.com/t/6a981dce6135e`
- `1PONDO-080926-001` g62 → dooplayer (unusable); other servers were also dooplayer
- `CPZ69-015` g60 → `https://emturbovid.com/t/6a911929a0eb4`

**emturbovid chain (works on every probed page):** GET `emturbovid.com/t/<id>` 301s to
`https://turbovidhls.com/t/<id>`; page contains the HLS master:

    <div id="video_player" data-hash="https://cdn3.turboviplay.com/data3/6a9d3b773ab2f/6a9d3b773ab2f.m3u8">
    var urlPlay = 'https://cdn3.turboviplay.com/data3/6a9d3b773ab2f/6a9d3b773ab2f.m3u8';

NOTE: turbovidhls player pages **expire** (an ID verified at probe time later returned
"Video Unavailable"), so the provider resolves the chain fresh on every `loadLinks` call —
never cache the m3u8. The m3u8 itself:

    $ curl -s -A "$UA" "https://cdn3.turboviplay.com/.../....m3u8" -e "https://turbovidhls.com/t/..."
    #EXTM3U
    #EXT-X-STREAM-INF:BANDWIDTH=52800,RESOLUTION=854x480,...
    #EXT-X-STREAM-INF:BANDWIDTH=1205600,RESOLUTION=1920x1080,...
    HTTP 200, Content-Type: application/vnd.apple.mpegurl

## Headers / referer
- AJAX POST requires `Referer: <video page URL>`; m3u8 fetch works with plain UA, no referer needed.
- Poster/img domains (img2/img3.javmost.ws) need no special headers.

## Pagination
Path-based: `/{listing}/page/N/` (search, category/all, category/uncensor, release/new all
confirmed HTTP 200 with 28 fresh cards on page 2).

## Risks / blockers
- dooplayer embeds are JS-only (204 to curl) — provider emits only emturbovid sources; pages
  whose only server is dooplayer will have no links.
- Obfuscated player JS could change its endpoint/params — POST shape recorded above.
- No Cloudflare / age wall observed; all probes plain curl + desktop UA.
