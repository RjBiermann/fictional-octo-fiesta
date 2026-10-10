# FINDINGS-522 — cat3movie and cat3film videos doesn't play ("No links found")

Probe date: 2026-10-09 (runner IP). All evidence below re-tested live on this run unless noted.

## What the app reports

Opening a video and pressing play ends with CS3 "No links found" — the zero-callbacks
`loadLinks` symptom. Same report for **both** cat3 providers (same site operator,
different engines: cat3movie.org = halimmovies/WordPress player.php, cat3film.com =
custom JSON-API + abyss token CDN).

## Cat3Movie (cat3movie.org)

Chain replay with the app's exact request shape (framework default UA is a desktop
Chrome UA — verified in the framework jar `MainActivityKt`: app-level default headers
set `user-agent: Mozilla/5.0 … Chrome/149.0.0.0 Safari/537.36`; NiceHttp's own default
"NiceHttp" only applies when unset):

```
GET /joy-1983 … /watermelon-man-1970 (10 fresh posts sampled)  → 200, "post_id":<int>,
    body[data-nonce="<hex10>"] — Parse.streamConfig inputs intact
player.php?episode_slug=full&server_id={1,2,3}&post_id&nonce&custom_var=[&_=<ts>]
    (Referer per-server watch URL + X-Requested-With) → 200, iframe embed per sv
```

### sv survey on 5 fresh posts (2026-10-09):

```
watermelon-man-1970    sv1=hlsfree/1170       sv2=loadvid(play/<hash>)  sv3=playmate.to/watch/RXzd0bHW0ivs
dr-jekylls-…-1979      sv1=hlsfree/1171       sv2=loadvid               sv3=playmate.to/watch/vtdPI6hiPT1D
penitentiary-1979      sv1=hlsfree/1172       sv2=loadvid               sv3=playmate.to/watch/FFGiZkfTrEgd
hud-1963               sv1=hlsfree/1169       sv2=loadvid               sv3=playmate.to/watch/GiIgjV52c1Al
american-graffiti-1973 sv1=hlsfree/1167       sv2=loadvid               sv3=playmate.to/watch/9KcVfBuTmMzY
```

**Drift #1 (reproducible server-side): sv3 embed host changed hlsfast → `playmate.to/watch/<code>`.**
Older posts (joy-1983 etc.) still serve adjacent CF-cached `hlsfast.com/#<hash>` responses
for their player.php URLs, so the old per-movie url np. per-post. The shared `Playmate`
adapter (Extractorlar.kt) extracts the code with `Regex("""(?:embed|e)/([a-zA-Z0-9]+)""")`
— `/watch/` contains no `e/` sequence (`p-l-a-y-m-a-t-e.to/watch/…` has `e` only before `.`),
so `getUrl` returns silently → **sv3 dead on every current movie**, no error surfaced.

### Chains verified end-to-end on this runner (app-request shape):

- **hlsfree (sv1)**: embed page 200 (needs any Referer; without Referer → "domain not
  authorized" 403), `defaultHlsUrl` token regex still matches, `/api/hls/serve?token=…`
  → 200 `application/vnd.apple.mpegurl` under both NiceHttp and Chrome/149 UA + Referer
  `https://hlsfree.com/`; token reusable; segments at s1.cat3hls.com are CFR-WAF-gated
  (403 CF block page without `Referer: https://hlsfree.com/`, 200 with it — TS body
  camouflaged as jpg). Provider emits link with Referer header map (#247 parity) — healthy.
- **playmate (sv3)**: `POST https://playmate.to/api/s {"c":"<code>","d":"web"}` with
  Origin/Referer/UA → 200 JSON `sx` = `https://frv2.plauymito.live/hls/<token>/master.txt`
  → 200 `application/vnd.apple.mpegurl`; sub playlist → segments
  `…/FuF4ZYH6_000.css` (TS camouflaged) 200 with Referer `https://playmate.to/` +
  browser UA. The site flow works; only the adapter's URL matching breaks it.
- **hlsfast (legacy sv3 on CF-cached responses of old posts)**: `/api/v1/video?id=…&w=…&h=…&r=cat3movie.org`
  → 200 + AES(hex, key `kiemtienmua911ca`, iv `1234567890oiuytr`) → cfNative m3u8 200 →
  segment 200, all under the Chrome/149 app UA. NOTE: with a non-Mozilla UA (empty, "NiceHttp",
  okhttp/4.x) the same endpoint now returns **400 `{"message": "Request is invalid"}`** — the
  framework app UA is a browser one, so this leg is healthy in-app today; recorded because any
  UA-gate tightening is the next drift candidate.
- **TLS**: every host in both providers' chains has valid LE/GTS certs (abyssssss.top,
  hlsfree.com, player.abyssplayer.com, sldx.quantumedgepartners.space, frv2.plauymito.live,
  s1.cat3hls.com) — no okhttp cert-pinning failure in-app.
- headers emission follows the app: Intercept assertions referenced consistent responses (see build log later here).

## Cat3Film (cat3film.com)

Full chain replay on 5 fresh titles (search `_ajax/search?q=` → detail → watch
`?sv=1&part=1` → `/api/v1/episodes/<id>/sources` → abyss CDN):

```
API search 200 (the-bed-hostesses, the-slut, the-erotic-man, in-the-future, skin-to-the-max)
watch page 200, .wserver + .epbtn[data-ep] intact (movies: 1 ep; series: per-ep ids)
sources   200 → sources=[ <//abyssssss.top token>, backup=//player.abyssplayer.com/<id> ]
abyssssss.top/<token>/index.m3u8  → 200 application/vnd.apple.mpegurl (master+media+segments 200)
abyssplayer backup embed  200, `datas = "…"`, enc-dec.app/api/dec-abyss → 200, sora sources
```

**No site-side drift.** `_ajax/search`, watch markup, sources API, both the abyss token
CDN and the abyssplayer backup route replay healthy under the app's UA. The only
historical zero-links path remains the per-client CF challenge (#410: CF decisions are
client- and IP-scoped — the runner gets 200s where some app clients get a
"Just a moment…" interstitial), already addressed by the Cat3Film CloudflareKiller
interceptor wiring. No observable change in the challenge surface from here.

## Diagnosis

- **Cat3Movie**: primary (sv1 hlsfree) healthy, but the server-rotate ladder lost its
  sv3 leg silently via the playmate `/watch/` shape change. With only sv2 (blob-gated,
  no adapter) and sv3 dead, every current movie is a **single-source provider** — one
  hlsfree CF hiccup (tokens rotate/expire per embed load, #247 family) reproduces
  "No links found" for a whole session. Fix: teach the shared Playmate adapter the
  `/watch/<code>` shape.
- **Cat3Film**: no reproducible server-side defect; chain healthy. PR documents the
  re-verification instead of a speculative change (fix-provider rule: never fix what
  can't be reproduced or observed).

## Fix shipped (this run)

`shared/src/main/kotlin/com/kraptor/Extractorlar.kt`: code extraction lifted into pure
`PlaymateParse.code()` (regex now `/(?:embed|e|watch)/`), `Playmate.getUrl` calls it.
Red → green: `shared/src/test/kotlin/com/kraptor/PlaymateParseTest.kt` (watch/legacy/reject
non-playmate). Cat3Movie version 11 → 12. No Cat3Film change (chain verified healthy).

## verify.sh run (2026-10-09)

### Cat3Film — RESULT: PASS

- check 1/1a `/movies` 200, 30 `a.card`; no dupes. (`_ajax/search?q=` is JSON — the
  script asserts HTML selectors, tool-artifact, same note as the #292-era run; provider
  search works against the JSON endpoint, agreement re-proven by probe above.)
- check 2: 5 video pages 200, `og:title` 5/5, related 4/4; streams 5/5 →
  **200 application/vnd.apple.mpegurl** (positional `--stream-url` per chain in
  `/api/v1/episodes/{id}/sources` → `/index.m3u8`).
- check 6: recommendations/tags/plot/year/duration/actors/**score** all ≥1 assignment.
- NOTE-only artifacts: `.ip-label`/year selectors "match nothing" — the tool's count
  doesn't handle `^=` attr selectors; manual grep confirms `/year/YYYY` badge on 5/5 pages.

### Cat3Movie — checks 1/4/6 pass; check 2 FAIL is the documented tool artifact

- check 1: search `/search/watermelon` 200, 5 unique `article.thumb` cards.
- check 2 (streams): all 5 sv1 hlsfree links → **200 application/vnd.apple.mpegurl**
  (positional per-video `--stream-url`, tokens fresh per video); playmate sv3 route
  (after fix): `POST /api/s` → `sx` master 200 + sub 200 + segment 200 (TS body),
  with the emitted headers (Origin+UA, Referer playmate.to).
- **Tool artifacts (same family as the standing #204/#266 script artifacts):**
  - check 2 page FAIL: the site serves no `<video>`/`<iframe>` in movie-page HTML —
    streams live behind the ajax player.php chain (documented since issue #180-probe); the
    tool's stream assertions moved to `--stream-url` and those all pass 200 m3u8.
  - check 2 "stream path shared": the tool's `url_path()` strips query strings, and
    hlsfree/playmate stream URLs differ only by token query → distinct tokens collapse
    to identical paths.
  - check 5 title mismatch: cat3movie card titles exist only in `title`/`alt`
    attributes (popover markup); the tool reads card title from inner text only.
    Manual agreement: `/search/watermelon` → href `watermelon-man-1970`, title attr
    `"Watermelon Man (1970)"` = load page `h1 Watermelon Man (1970)`.
  - home duplicate cards: raw site renders the newest-10 strip twice (issue #203); the
    provider's `SearchCard.homeCards()` dedupes, the tool sees raw markup.
- check 6: recommendations/tags/plot/year/actors all ≥1 assignment.

### Remaining risk (not fixed, recorded)

- In-app "no links found" with **every** leg 200 from the runner maps to the per-client
  Cloudflare challenge family (#410/#411/#417 precedent — CF decisions are client- and
  IP-scoped; the runner's clean fingerprints get 200s where a challenged app client gets
  the interstitial). The providers already route fetches through CloudflareKiller; no
  further server-side evidence was observable.
- hlsfast `/api/v1/video` now UA-gates non-Mozilla UAs (400) — healthy under the
  framework's Chrome/149 app UA today; next drift candidate if it tightens.

## Re-verification on the follow-up run (post-merge #552, branch re-probe)

Context: PR #552 (the playmate `/watch/<code>` fix) is already **merged to main**; the issue
stayed open, so this run re-probed both chains live instead of assuming the earlier result.
No new drift found; no further code change needed.

### Cat3Movie — fresh sample, all sv1 legs resolve

5 fresh posts (search `/search/watermelon`, category `/classic-porn/page/2`, related section
of california-gigolo-1979) → post_id + body[data-nonce] intact → player.php 200 per sv →
sv1 hlsfree token → `/api/hls/serve` **200 application/vnd.apple.mpegurl** each:

```
watermelon-man-1970                pid=34733 sv1=hlsfree/8fefac81a5c5 → 200 mpegurl
a-climax-of-blue-power-1974        pid=30921 sv1=hlsfree/45154da19a2b → 200 mpegurl
a-thousand-and-one-erotic-nights-1982 pid=30945 sv1=hlsfree/b29f99be1d01 → 200 mpegurl
accidental-incest-2014             pid=20039 sv1=hlsfree/06150f9420a0 → 200 mpegurl
bella-1980                         pid=34723 sv1=hlsfree/9483de5eb850 → 200 mpegurl
```

sv3 playmate `/api/s` → `sx` master re-proven on california-gigolo-1979
(`c=PxQf7qRUGYQR` → master.txt 200 mpegurl → `index_avc_720p.txt` 200 mpegurl → segment
`e2FOJvaZ_000.css` 200, 1.27 MB TS-camouflaged body, Referer `https://playmate.to/`).
The merged `PlaymateParse` regex matches this `/watch/<code>` shape (unit tests green:
`./gradlew Cat3Movie:test Cat3Film:test` — build green on this branch).

### Cat3Film — fresh sample, chain healthy

search `_ajax/search` 200 → watch `?sv=1&part=1` 200 (`data-ep` intact) →
`/api/v1/episodes/{id}/sources` 200 → `abyssssss.top/<token>/index.m3u8`
**200 application/vnd.apple.mpegurl** for all 5 sampled episodes: the-bed-hostesses (ep
1137), hache S1E1 (ep 449, series), impregnation-nation (ep 886), jailhouse-wardress (ep
1765), body-love (ep 1131, from the related grid of the-bed-hostesses). Segment
`seg-1-v1-a1.jpg` 200, 1.09 MB, `Referer: https://cat3film.com/`. Search↔load agreement
re-proven by curl: `_ajax/search?q=body+love` → `{"slug":"body-love","title":"Body
Love","year":1977}` = load page `h1.info-title "Body Love"`; `?q=hache` → format `"TV"`
(=#416 tie-breaker evidence, unchanged). NOTE: `/draft-1669` from the Sep-11 probe is now
**404** — that one video was removed upstream; sample refreshed above. Both providers:
no distinct quick-search endpoint (search page is the only URL surface;
`hasQuickSearch` stays default-false).

### verify.sh reruns (follow-up run)

**Cat3Film — RESULT: PASS.** checks 1/1a: `/movies` + `?page=2` 200, 30 `a.card` each,
page 2 fresh. check 2: 5 video pages 200, `h1.info-title` 5/5, `section#related a.card`
4/4, streams 5/5 → **200 application/vnd.apple.mpegurl** (positional per-episode
`--stream-url`). check 6: recommendations/tags/plot/year/actors ≥1 assignment. Tool NOTEs
only: `.info-people a` tags count "matches nothing" (tool's part-split reads the last
selector element; the genre anchors are real — provider assigns them); year/duration
exposure asserted by curl + ld+json documentation instead of tool flags.

**Cat3Movie — checks 1/2(streams)/4/6 pass; FAIL artifacts unchanged from the previous
run's documentation.** check 1: `/search/watermelon` 200, ≥1 card. check 2: 5 video pages
200, sv1 streams 5/5 → **200 application/vnd.apple.mpegurl**. Same documented tool
artifacts as above: (a) home dup dump — raw site renders the newest strip twice (#203);
provider dedupes, tool sees raw markup; (b) "stream path shared" — url_path() strips the
token query, distinct hlsfree tokens collapse to `/api/hls/serve`; (c) check 5 title
mismatch — card titles live in `title` attributes (popover markup), tool reads inner text;
search↔load agreement re-proven manually (`/search/watermelon` → href
`watermelon-man-1970`, title attr "Watermelon Man (1970)" = load `h1.entry-title`).
search/homepage pagination unchanged from FINDINGS (search 404s on page 2; homepage rows
do not paginate). Tool section markers: verify.sh requires `--stream-selector` even when
streams are asserted via positional `--stream-url`; both sites serve no `<video>/<iframe>`
in listing/detail HTML (documented since #180/#204), so `script` is passed as the page
marker — the stream assertions are the positional URLs above.

### Verdict

The merged fix resolves the reproducible part of #522 (cat3movie sv3 `playmate.to/watch`
silently dropped); both chains replay healthy end-to-end from this runner. No further code
change shipped on this run — FINDINGS upkeep only. Remaining in-app risk stays the
per-client CF-challenge family + hlsfast UA gate recorded above.

