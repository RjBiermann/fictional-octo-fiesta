# FINDINGS — pornslash.com (PornSlash v1 close-out, S6 #1648)

Chain: #1425 (`[new-site] PornSlash`), stories S1 #1643 … S5 #1647, close-out S6 #1648.
Probe date: **2026-11-28** (fixtures under `PornSlash/src/test/resources/` are live captures
from 2026-11-26/27). Run: https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/46112906468

Provider-level report, archived per AGENTS.md ("Run reports are transient"); the live copy
never stays in `PornSlash/`.

## Verdict

v1 ships at **`version = 7`** — one number for the shipped v1, replacing the per-story
increments (1 scaffold → 2 → 3 → 4 → 5/6). `./gradlew PornSlash:test` green (47 tests in
`PornSlashParseTest`, 462 across the spliced shared+provider suite), `./gradlew
PornSlash:make` clean, and `.pi/skills/verify-provider/scripts/verify.sh` reports
**RESULT: PASS** (§6).

**One correction landed at close-out, and it is the important finding:** the
`og:image`-mirrored `/p/<encid>.mp4` that S3/S4 shipped as the play path is the cards'
**10-second hover preview**, not the movie. The play path is now the page's own HLS master
(`https://<tunnel>/master/<encid>`), decoded **per load**; the preview clip survives only as
the last-resort fallback for a page that carries no player base (§2, §3). Every other S1–S5
decision proved out live as written.

## 1. Selector traceability

Every parse function, the selector/field it reads, the fixture that pins it, and the live
check that re-asserted it this run. No selector is invented: each one was re-read off the
live page in this probe.

| Parse function | Reads | Pinned by | Live (2026-11-28) |
|---|---|---|---|
| `cards(document)` | `div.video-item` | `home-p1.html` (50), `search-milf-p1.html` (25), `cat-milf-p1.html` (25), `pornstar-riley-reid-p1.html` (25) | 50 / 25 / 25 / 25 matches (§6) |
| `card(el)` id | `div.video-info[data-id]`, else `/watch/<id>` href | `home card carries id title poster…` | — |
| `card(el)` title | `div.video-info[data-title]`, else `h2.video-title a[title]` → text | same test | card `title` == `data-title` == load `h1` (§6) |
| `card(el)` poster | `div.thumbnail img` `data-src` → `src` (a `data:` URI is never a poster) | `card identities are distinct` (all posters non-null) | search↔load poster paths equal (§6) |
| `card(el)` quality | `.quality` text, else `div.video-info[data-quality]` | `quality normalises a bare number…` | `data-quality` `480`/`720`/`1080`, no other shape |
| `card(el)` duration | `div.video-info[data-duration]` (seconds), else `.duration` clock | `duration falls back to the card clock…` | `data-duration` present on every card |
| `detail(document)` title | `h1.video-page-title` → `og:title` → `div.share-video[data-title]` | `detail page carries title poster tags description and quality` | `h1.video-page-title` 1/page on 5 samples |
| `detail` poster | `meta[property=og:image]` | same test | present on 5/5 |
| `detail` plot | `og:description` → `meta[name=description]` | same test | only `meta[name=description]` ships live (`PornSlash <title>`); 5/5 ✅ (verifier `--video-plot-selector 'meta[name=description]'`) |
| `tags(document)` | `meta[property=og:video:tag]` (deduped) | same test | 5/5 non-empty (verifier `--video-tags-selector 'a.video-tag'`, the visible twin of the metas) |
| `durationMinutes(og, ld+json)` | `og:duration` bare seconds → ISO-8601 → ld+json `VideoObject` | `duration is minutes and never invented…` | `og:duration` 3275 / 1409 / 2329 / 2191 / 26 on the 5 samples; `application/ld+json` also present 5/5 |
| `recommendations(document)` | `div.related div.video-item`, self excluded, cap 50 | `related cards are the watch page's recommendation surface` | 50 matches on 5/5; no self-link, no duplicate href (§6) |
| `detail` quality | `div.share-video[data-quality]` | `detail page carries …` | `1080`/`720` live |
| `mp4FromThumb(og:image)` | `/t/<encid>.<ext>` → `/p/<encid>.mp4` on the same host | `thumbnail path yields the mp4 on the same cdn host` | derived preview plays (10s, §2) |
| `hlsMaster(html, encid)` | rendered `https://<host>/master/<encid>` (encid-matched), else the player's own `Pt="<b64>"` base when it decodes to a bare https origin — no `encid`, no candidate (§3.1) | `detail carries the hls master…`, `a base token is decoded…`, `the tunnel base is decoded per load…`, `an ad token that decodes to a path is not a stream base`, `a page carrying several master urls yields this video's`, `a rendered master is not this video's without the page's encid`, `a quoted base64 that is not the player token is not a base` | master 200 `application/x-mpegurl` on 5/5 (§3); 1 bare-origin base per page, and it *is* the `Pt` token (§3.1) |
| `shortsPage(json)` | `videos[]` → `item_id`, `title`, `poster` → `image`, `quality`, `duration` | `shorts feed page 1 yields ten cards…`, `explicit json nulls are absent, never text` | 10 items/page (§8) |
| `modelSuggestions(json)` / `modelSlug(q, json)` | `[1][]` → `model_id`, `name`; exact-name match | `suggest fixture yields the model directory rows…`, `only an exact model name resolves…` | S5 surface, unchanged by S6 |
| `pageUrl(surface, page)` | `?p=N`, page 1 bare, sibling params preserved | `page url is a query param only…` | p2 = 25/50 fresh cards, 0 overlap (§6, §8) |
| `searchUrl` / `suggestUrl` | `%20`-encoded query, no `-` normalisation | `search url is the raw query, percent-encoded…` | `/search/milf/` 200 |
| `hasNext(count, pageSize)` | full page ⇒ maybe more, partial page ⇒ stop | `paginate while the page is full, stop on a partial page` | §4 |
| `CURATED_CATEGORIES` | `a.cat-item` on `/categories/` | `every curated category row is still a tile on the live categories page` | 92 tiles; all 8 curated slugs present with matching labels |

## 2. The CDN rule — one `*.pages.dev` project per video, read per page

Every video lives on its **own** Cloudflare Pages project (`<name>-<n>.pages.dev/<ptid>/…`),
so the host is read from the page on every load and never templated. Five sampled videos,
five different projects:

```
b4jlKRJ69mQ  quality=1080  duration=3275s  poster_host=wolfcipher56-1345.pages.dev
qnmWAyrRMxK  quality=1080  duration=1409s  poster_host=wolfcipher56-1343.pages.dev
1ZxelzPwKjy  quality=720   duration=2329s  poster_host=tz26sfmpn4-1235.pages.dev
ykm1XvbDQg0  quality=1080  duration=2191s  poster_host=ba0ze4asqz-1338.pages.dev
Vpg80K5v6gA  quality=720   duration=26s    poster_host=mxaf3bj2fs-886.pages.dev
```

(Two videos from the same `ptid` family do share a project — `13442`/`13441` both sit on
`wolfcipher56-1345` — which is exactly why the host is read, not derived.)

**The `/p/<encid>.mp4` clip is a hover preview, not the movie.** The listing card carries it
as `div.thumbnail[data-p]`, i.e. the site itself uses that URL only as the pointer-over clip:

```
<div class="video-item"><a href="/watch/kwjoN7R3Qmz">
  <div class="thumbnail" data-p="https://ba0ze4asqz-1333.pages.dev/13328/p/HxMttwQzkQuQzwkwoQow.mp4">
    <img src="https://ba0ze4asqz-1333.pages.dev/13328/t/HxMttwQzkQuQzwkwoQow.jpeg">
```

Measured `mvhd` duration of the derived clips vs the page's own `data-duration`:

```
/watch/b4jlKRJ69mQ  /p/HkkMtzwtMMuQzwkQeMQQ.mp4  209926 B  video/mp4  10.01 s   page 3275 s
/watch/oQjZO0veLx9  /p/HkkMtzwtMMuQzwkQeMeM.mp4  286726 B  video/mp4  10.01 s   page 2464 s
/watch/Vpg80K5v6gA  /p/HxztQetownuQzwketkte.mp4  187176 B  video/mp4   9.00 s   page   26 s
```

**False-green trap worth remembering:** an unknown path on a Pages project answers
`200 text/html` (the SPA fallback), so a `%{http_code}`-only stream check passes on paths
that do not exist:

```
https://wolfcipher56-1345.pages.dev/13442/v/HkkMtzwtMMuQzwkQeMQQ.mp4   200 text/html 701 B
https://wolfcipher56-1345.pages.dev/13442/f/HkkMtzwtMMuQzwkQeMQQ.mp4   200 text/html 701 B
https://wolfcipher56-1345.pages.dev/13442/s/HkkMtzwtMMuQzwkQeMQQ.mp4   200 text/html 701 B
```

Only `/p/<encid>.mp4` answers `video/mp4`. The provider therefore derives the clip from
`og:image` (`/t/<encid>.jpeg` → `/p/<encid>.mp4`, host + `<ptid>` preserved) and emits it
**only when the page carries no player base** — never as the primary source.

## 3. The HLS rule — rotating `*.trycloudflare.com` tunnel, decoded per load

The page's own player (`hls.js`, with a native-`t.src` fallback) loads
`<base>/master/<encid>`. The base is a Cloudflare quick tunnel on `*.trycloudflare.com` and it
**rotates**: the same video fetched five seconds apart answered two different hosts, and each
video gets its own.

```
/watch/b4jlKRJ69mQ  load 1  https://navigate-marc-combine-copper.trycloudflare.com/master/HkkMtzwtMMuQzwkQeMQQ
/watch/b4jlKRJ69mQ  load 2  https://cage-throw-induced-resort.trycloudflare.com/master/HkkMtzwtMMuQzwkQeMQQ
/watch/oQjZO0veLx9          https://frost-dame-livestock-locking.trycloudflare.com/master/HkkMtzwtMMuQzwkQeMeM
/watch/Vpg80K5v6gA          https://direction-sanyo-iii-claimed.trycloudflare.com/master/HxztQetownuQzwketkte
```

So: read from the page, decode **per load**, never cached across loads. Nothing is
templated — a remembered host is a dead source on the next play.

Two page shapes are handled, both structural:

1. the player's master URL is already rendered into the inline script (live today);
2. the page ships only the base as `Pt="<base64>"`, which the player `atob`s at runtime —
   completed with `encid` from `div.share-video[data-encid]`.

A decoded token counts as a base **only when it is a bare https origin**. Every watch page
also carries an *ad* token (`Yn=atob("aHR0cHM6…")`) that decodes to
`https://wideeyedchance.com/d.m-F/…` — a URL **with a path**; appending `/master/<encid>` to
it would invent a nonexistent source, so the path-carrying decode is rejected (test
`an ad token that decodes to a path is not a stream base`).

And the base is read from **the player's own variable** (`Pt="<b64>"`), never from "any
quoted base64": the page carries encrypted ids and content hashes in that same shape, so an
unscoped scan lets any bare-origin string on the page become this video's stream host
(§3.1).

Master transcript (`/watch/b4jlKRJ69mQ`):

```
$ curl -sS -A '<Firefox UA>' -D - https://navigate-marc-combine-copper.trycloudflare.com/master/HkkMtzwtMMuQzwkQeMQQ
HTTP/2 200
content-type: application/x-mpegurl
access-control-allow-origin: *
cache-control: private, max-age=21298

#EXTM3U
#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=343407,RESOLUTION=256x144,NAME="144p"
https://navigate-marc-combine-copper.trycloudflare.com/playlist/HkkMtzwtMMuQzwkQeMQQ/0
#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=566615,RESOLUTION=426x240,NAME="240p"
… 360p / 480p / 720p …
#EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=5894864,RESOLUTION=1920x1080,NAME="1080p"
https://navigate-marc-combine-copper.trycloudflare.com/playlist/HkkMtzwtMMuQzwkQeMQQ/5
```

Full length and real media behind it (the clip lengths match the page's own
`data-duration`/`og:duration`, i.e. the whole movie, not a teaser):

```
master      200 application/x-mpegurl  1012 B
variant /5  200 application/x-mpegurl 54557 B  328 segments  total 3275.54 s   page data-duration 3275 s
segment /0  206 video/MP2T  content-range: bytes 0-2047/2123648  access-control-expose-headers: X-Delivery-Type,…
/watch/1ZxelzPwKjy master 200 application/x-mpegurl → variant 233 segments total 2329.12 s   page 2329 s
/watch/Vpg80K5v6gA master 200 application/x-mpegurl → variant   3 segments total   26.43 s   page   26 s
```

`loadLinks()` emits that master as `ExtractorLinkType.M3U8` with `referer = mainUrl`. No
quality label is attached: the ladder is the site's own and the player reads it from the
playlist (§5).

## 3.1 Repair round — the master is tied to this video on both paths

Finding (review round 1): the parse could still emit a stream that is **not this video's** —
(a) the base-token scan matched *any* quoted base64 that decoded to a bare origin, not the
player's own token, and (b) with no `data-encid` it took the first rendered master on the
page, which on a page carrying a rail's own player can be a related video's.

Both are now fail-closed, and the two new tests were run **red first** (stubbed against the
old parse: both failed) then green:

- `a rendered master is not this video's without the page's encid` — the encid is the page's
  only statement of which stream is this video's, so no encid yields no candidate; the
  preview fallback still plays *this* video instead of another one.
- `a quoted base64 that is not the player token is not a base` — a bare-origin base64 under
  another variable is not a base; the same string in `Pt="…"` still is.

Live re-probe (2026-11-28, same day; two watch pages, one long-form one shorts) — every
quoted base64 on the page, decoded, classified:

```
/watch/b4jlKRJ69mQ  quoted: 7  Pt tokens: 1
  bare origin:      https://frost-dame-livestock-locking.trycloudflare.com   (is the Pt token)
  path-bearing ad:  https://wideeyedchance.com/d.m-F/zcdnGANAvzZU…           (Yn=atob(…))
/watch/Vpg80K5v6gA  quoted: 7  Pt tokens: 1
  bare origin:      https://introductory-acrylic-soonest-calculate.trycloudflare.com  (is the Pt token)
  path-bearing ad:  https://wideeyedchance.com/d.m-F/zcdnGANAvzZU…           (Yn=atob(…))
```

So the scoping changes nothing on today's pages — one bare-origin base, and it is the player's
— while removing the class of mis-attribution. `data-encid` matched the single rendered
`/master/<encid>` on both pages, so the rendered path is unchanged. `./gradlew PornSlash:test`
green (49 tests in `PornSlashParseTest`), `./gradlew PornSlash:make` clean.

## 4. The stop rule — a partial page is the stop; `last + 1` is never probed

This site punishes the "probe one page past the end" idiom:

```
https://www.pornslash.com/videos/?p=5001        → 500  text/html
https://www.pornslash.com/cat/milf/?p=5001      → 500  text/html
https://www.pornslash.com/search/milf/?p=5001   → 404  text/html
https://www.pornslash.com/get/shorts?p=5001     → 200  application/json (10 fresh items)
```

A **partial page** is therefore the only unambiguous stop, and that is what
`hasNext(count, pageSize)` implements: `count == pageSize` ⇒ another page may exist,
anything less ⇒ stop. Live evidence that the rule is not merely theoretical: `/cat/`'s
curated rows end on a partial page (`cat-partial.html` fixture: 1 card → stop), while p1/p2
of every listing surface are full and disjoint (§6).

Shorts is the one surface whose feed is **unbounded** — it answers 10 fresh items at any
offset, with no overlap and no clamp (the site's own infinite scroll):

```
p1     10 items
p1000  10 items   p1 ∩ p1000    = 0
p100000 10 items  p1 ∩ p100000  = 0   p1000 ∩ p100000 = 0
p8 10 / p9 10 / p10 10
```

So the Shorts row keeps paging (matching the site) and only a sub-10-item page would stop
it. Recorded, not a defect.

## 5. Single-quality note — `data-quality` is the truth

`data-quality` on `div.share-video` (and on the card's `div.video-info`) is the page's own
label: `1080`, `720`, `480`, `360` are the observed values, normalised `"1080" → "1080p"`,
and anything without digits (absent, blank, `"HD"`) yields **null** rather than an invented
rung — a fabricated quality label misleads the player. `quality(null)`/`quality("HD")` are
pinned as null in `quality normalises a bare number and invents nothing`.

No ladder is fabricated anywhere: the only multi-rung ladder the app sees is the site's own
HLS master (144p…1080p, §3), served as-is.

## 6. Verification transcript — `verify.sh`, live site, 2026-11-28

```
$ .pi/skills/verify-provider/scripts/verify.sh \
    --search-url 'https://www.pornslash.com/search/milf/' \
    --search-url 'https://www.pornslash.com/search/milf/?p=2' \
    --search-selector 'div.video-item' \
    --search-title-selector 'h2.video-title a' \
    --search-poster-selector 'div.thumbnail img' \
    --home-url 'https://www.pornslash.com/videos/' \
    --home-url 'https://www.pornslash.com/videos/?p=2' \
    --home-url 'https://www.pornslash.com/cat/milf/' \
    --home-url 'https://www.pornslash.com/cat/milf/?p=2' \
    --home-selector 'div.video-item' \
    --video-url 'https://www.pornslash.com/watch/qnmWAyrRMxK' \
    --video-url 'https://www.pornslash.com/watch/ykm1XvbDQg0' \
    --video-url 'https://www.pornslash.com/watch/b4jlKRJ69mQ' \
    --video-url 'https://www.pornslash.com/watch/1ZxelzPwKjy' \
    --video-url 'https://www.pornslash.com/watch/Vpg80K5v6gA' \
    --stream-selector 'div.share-video[data-encid]' \
    --stream-url '…/master/HkkMtzwtMMuQzwkQeoko' --stream-url '…/master/HkkMtzwtMMuQzwkQeQzz' \
    --stream-url '…/master/HkkMtzwtMMuQzwkQeMQQ' --stream-url '…/master/HknzxwzknMuQzwketenn' \
    --stream-url '…/master/HxztQetownuQzwketkte' \
    --video-title-selector 'h1.video-page-title' \
    --video-poster-selector 'meta[property=og:image]' \
    --video-plot-selector 'meta[name=description]' \
    --video-tags-selector 'a.video-tag' \
    --related-selector 'div.related div.video-item' \
    --provider-src PornSlash \
    --load-response 'recommendations,tags,plot,duration,posters'

── check 1: search pages (2)
GET https://www.pornslash.com/search/milf/ → 200; 'div.video-item' matches: 25
GET https://www.pornslash.com/search/milf/?p=2 → 200; 'div.video-item' matches: 25
── check 1a: homepage pages (4)
GET https://www.pornslash.com/videos/ → 200; 'div.video-item' matches: 50
GET https://www.pornslash.com/videos/?p=2 → 200; 'div.video-item' matches: 50
GET https://www.pornslash.com/cat/milf/ → 200; 'div.video-item' matches: 25
GET https://www.pornslash.com/cat/milf/?p=2 → 200; 'div.video-item' matches: 25
── check 1b: quick search (0)
NOTE: no --quick-search-url — required when FINDINGS records a distinct quick-search endpoint; its absence must be explicit in FINDINGS
── check 2: video pages (5 URLs)
GET https://www.pornslash.com/watch/qnmWAyrRMxK → 200; 'div.share-video[data-encid]' matches: 1
GET stream (https://wires-chi-madonna-influenced.trycloudflare.com/master/HkkMtzwtMMuQzwkQeo…) → 200 application/x-mpegurl
GET https://www.pornslash.com/watch/qnmWAyrRMxK → 'div.related div.video-item' matches: 50
GET https://www.pornslash.com/watch/ykm1XvbDQg0 → 200; 'div.share-video[data-encid]' matches: 1
GET stream (https://wires-chi-madonna-influenced.trycloudflare.com/master/HkkMtzwtMMuQzwkQeQ…) → 200 application/x-mpegurl
GET https://www.pornslash.com/watch/ykm1XvbDQg0 → 'div.related div.video-item' matches: 50
GET https://www.pornslash.com/watch/b4jlKRJ69mQ → 200; 'div.share-video[data-encid]' matches: 1
GET stream (https://introductory-acrylic-soonest-calculate.trycloudflare.com/master/HkkMtzwt…) → 200 application/x-mpegurl
GET https://www.pornslash.com/watch/b4jlKRJ69mQ → 'div.related div.video-item' matches: 50
GET https://www.pornslash.com/watch/1ZxelzPwKjy → 200; 'div.share-video[data-encid]' matches: 1
GET stream (https://wires-chi-madonna-influenced.trycloudflare.com/master/HknzxwzknMuQzwkete…) → 200 application/x-mpegurl
GET https://www.pornslash.com/watch/1ZxelzPwKjy → 'div.related div.video-item' matches: 50
GET https://www.pornslash.com/watch/Vpg80K5v6gA → 200; 'div.share-video[data-encid]' matches: 1
GET stream (https://direction-sanyo-iii-claimed.trycloudflare.com/master/HxztQetownuQzwketkt…) → 200 application/x-mpegurl
GET https://www.pornslash.com/watch/Vpg80K5v6gA → 'div.related div.video-item' matches: 50
field 'tags': present on all 5 sampled pages
NOTE: no --video-actors-selector — exposure not asserted; FINDINGS must state whether the site exposes actors
NOTE: no --video-year-selector — exposure not asserted; FINDINGS must state whether the site exposes year
NOTE: no --video-duration-selector — exposure not asserted; FINDINGS must state whether the site exposes duration
── check 5: search ↔ load agreement
── check 6: LoadResponse completeness (code vs FINDINGS)
field 'recommendations': 2 assignment(s) in PornSlash
field 'tags': 2 assignment(s) in PornSlash
field 'plot': 1 assignment(s) in PornSlash
field 'duration': 4 assignment(s) in PornSlash
field 'posters': 2 assignment(s) in PornSlash
RESULT: PASS
```

`--stream-url` carries the master each video page resolved at probe time: the site builds the
stream in JS (`n.loadSource("…/master/<encid>")`) and derives the tunnel host per load, so the
static HTML carries no `m3u8` path the script's extractor could match — the skill's documented
override for JS-built sources. The tunnel rotates between fetches, which is precisely the rule
the provider implements (§3): verify.sh's own page fetch and the supplied master are two
different but equivalent loads.

Check 5 printed no failure because agreement held — the card and the load page agree on both
title and poster path for the two search-sampled videos:

```
card  /watch/qnmWAyrRMxK  "anal-angels / teenmegaworld - lily cat (seduction diva) milf"
load  h1.video-page-title  "anal-angels / teenmegaworld - lily cat (seduction diva) milf"
load  data-title           "anal-angels / teenmegaworld - lily cat (seduction diva) milf"
poster card == poster load: https://wolfcipher56-1343.pages.dev/13426/t/HkkMtzwtMMuQzwkQeoko.jpeg
```

Listing pagination, page 1 vs page 2, distinct ids computed from the `data-id` set (no card
repeats within a page, no shared card across pages):

```
/videos/       p1 50 ids / p2 50 ids  overlap 0
/cat/milf/     p1 25 ids / p2 25 ids  overlap 0
/search/milf/  p1 25 ids / p2 25 ids  overlap 0
```

## 7. Surfaces and fields the verifier cannot assert (explicit, not unstated)

- **Quick search: none.** `hasQuickSearch = false`. `/search/suggest?q=milf` answers the
  search page itself (`200 text/html`, not JSON) and `/suggest` + `/api/suggest` are 404.
  The S5 model directory `/suggest/straight/<q>` is JSON, but it suggests **models**, not
  videos — it drives `search()`'s model-browse branch, not a typeahead over videos.
- **Shorts home row is JSON, not DOM.** `getMainPage` renders it from `/get/shorts`, so a CSS
  `--home-selector` cannot match it; it is asserted by §8's transcript instead of check 1a.
- **Duration has no text node.** It lives only in attributes — `div.share-video[data-duration]`
  and `og:duration`, both bare seconds (3275 / 1409 / 2329 / 2191 / 26 on the samples) — so the
  verifier's text-based field probe cannot assert it; exposure is shown by those attributes and
  by `durationMinutes`' fixture tests.
- **Actors: not exposed.** No actor/model element on the watch page (only `og:video:tag`
  tags); the provider populates `tags`, never `actors`.
- **Year: not exposed.** No release-year field on the watch page; `uploadDate` exists only in
  the shorts JSON and is not a year field, so the provider populates none.
- **Related rail:** `div.related div.video-item` = 50 per page on 5/5 (the provider's cap is
  50); the rail never links the video itself and repeats no href.

## 8. Shorts feed transcript (`/get/shorts`)

```
p1: 200 application/json 17778 B  10 items
p2: 200 application/json 18998 B  10 items
p1 ∩ p2 = 0 shared item_id, 20 distinct across the two pages
```

Feed fields per item (2026-11-28): `item_id`, `title`, `poster`, `image`, `ptid`, `encid`,
`quality`, `duration`, `views`, `likes`, `uploadDate`, `tags`, `timelineImg`. **No `url`
field any more** — the ephemeral playback base the S4 story kept out of the play path is now
absent from the feed entirely, so there is nothing to keep out.

Each `item_id` resolves through the same `/watch/<id>` route as a long-form movie, which is
why the shorts surface inherits the whole detail/stream path with no special case
(`/watch/Vpg80K5v6gA` → master 200 `application/x-mpegurl`, 26.43 s of media for a 26 s item,
§3).

## 9. Tests and build (this run)

```
$ ./gradlew PornSlash:test
BUILD SUCCESSFUL   (PornSlashParseTest: 47 tests; spliced shared+provider suite: 462
                    debug+release test cases, 0 failures)

$ ./gradlew PornSlash:make
BUILD SUCCESSFUL   PornSlash/build/PornSlash.cs3 (174573 B)
                   build/intermediates/manifest.json → {"pluginClassName":"com.rjbiermann.PornSlashPlugin",
                   "name":"PornSlash","version":7,…}
```

The new tests were run red first (the seam stubbed to `return null`): the four positive
`hlsMaster` tests failed, the negative one passed by construction; implementation turned them
green without touching any earlier assertion.

New tests (S6): `detail carries the hls master the page's own player loads`,
`a base token is decoded when the page renders no master url`,
`the tunnel base is decoded per load, never remembered`,
`an ad token that decodes to a path is not a stream base`,
`a page carrying several master urls yields this video's`,
`no player base yields no hls candidate`.

## 10. Version

`version` 6 → **7** (the single shipped-v1 number). `version = 1` scaffolding is long gone;
the per-story increments (2 → 3 → 4 → 5 → 6) are folded into this one bump, so the shipped v1
is `7` in `PornSlash/build.gradle.kts` and in the built `manifest.json`.
