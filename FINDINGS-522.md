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

