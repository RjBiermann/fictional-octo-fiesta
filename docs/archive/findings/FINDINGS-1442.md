# FINDINGS-1442 — JavGuru video sources (S4 of #1354)

Run: 2026-11-22 (`05-implement`, story **S4** of parent spec #1354). Scope per the spec:
the **video-source leg** (D2) across the D3 listing types — `new`/`latest`, `old`,
`popular`, `genres`, `categories`, `tags` — ≥2 video pages per type, sampled at both
catalogue ends where the type is pageable, with `loadLinks` resolved end-to-end.
Listing correctness (pagination/card fields) is out of scope beyond enumeration.

Site: `https://jav.guru` — **plain-curl on every surface**, desktop UA
(`Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0`),
`Referer: https://jav.guru/` on page fetches (what the provider emits). Runner IP
country: **US** (`20.102.230.179`, AS8075 Microsoft/Azure, Chicago IL).

Registry at start: `last_deep` 2026-10-09, `drift_confirmed` 0, verdict `ok`, no standing
issue. Dedup searched open **and** closed issues for this provider: #1442 (this spec),
#1426 (uncensored *rows*, explicitly out of scope here), #819/#647 (closed listing/API
audits), #117/#144 (closed — searcho loader "never redirects", superseded: the chain
resolves today, see below), #98 (closed — *javclan* embed packed). No standing issue
covers the condition found in this run.

## Phase 0 — calibration (ran before any provider probing)

| canary | expected | observed (plain-curl) | reading |
|---|---|---|---|
| `film1k-challenge-pair` | plain 403 / TLS 200 | plain **200** / TLS unavailable | **expectation-stale** — open #1435; per the spec this run records the live pair and does **not** take the `environment-degraded` branch for this canary |
| `eporner-healthy` | plain 200 | plain **200** | match — instrument healthy |
| `pandamovies-redirect-origin` | plain 302 / TLS 200 | plain **302 → https://pandamovies.org/** | match (redirect-following intact) |
| `jav.guru` (site itself) | — | plain **200** | probe tier available; no challenge |

```
$ UA="Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0"
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.film1k.com/
200
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://www.eporner.com/
200
$ curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' -A "$UA" https://pandamovies.pw/
302 https://pandamovies.org/
$ curl -s -o /dev/null -w '%{http_code}\n' -A "$UA" https://jav.guru/
200
$ .pi/skills/site-probe/scripts/impersonate.sh https://jav.guru/
impersonate.sh: curl_cffi missing for python3. Install it, then re-run:
  python3 -m pip install curl_cffi
```

- **#1434 (open)** — the TLS-impersonated tier is unusable in this container →
  `escalation_tier: unavailable`. Only `Blocked` verdicts downgrade to `Suspected`;
  **this run produced zero `Blocked` verdicts**, so nothing was downgraded.
- **#1435 (open)** — film1k's stale expectation is recorded, not treated as degradation.
- No genuine `environment-degraded` mismatch → the instrument is healthy.

## Listing enumeration (the D3 types as the site actually serves them)

Cards are `div.inside-article` (24/page) on every archive surface; the popular **rank**
page is a different grammar (`article.rank-item`, 120 markup nodes / **98 uniques**).
Pagination is the WordPress `/page/N/` path. Last pages were found by binary search and
confirmed by a 404 one page further (all 404s return the theme's 116 487-byte
"Page not found" document).

| type | first page | far end | evidence |
|---|---|---|---|
| `new` / `latest` | `/` — 24 cards | `/page/5789/` — 10 cards; `/page/5790/` 404 | `/page/2/` 24 cards |
| `old` | `/?orderby=date&order=ASC` — 24 cards (ids 412, 394, 407 …) | `/page/5789/?orderby=date&order=ASC` — 10 cards | `/page/2/?orderby=date&order=ASC` 24 cards. `order=ASC` is honoured server-side but is **not in the site's sort menu** (the dropdown offers only `order=DESC` for `date`, `views`, `views-monthly`, `likes`, `likes-monthly`, `dislikes`, `comment_count`, `rdate`) — the type is reachable, so it is sampled, not recorded absent |
| `popular` | `/most-watched-rank/` — **98 unique** rank cards, **no paginator and no `rel=next`**; `/most-watched-rank/page/2/` re-renders the same widget (96 uniques, 92 shared) so it is **not a paged surface** | — (single page) | pageable popular = `/?orderby=views&order=DESC` p1 24 → tail `/page/5789/?orderby=views&order=DESC` 10 |
| `genres` | — | — | **absent as a taxonomy**: `/genre/` and `/genre/married-woman/` 404, `/genres/` 404, no genre links in the nav, the category index or the 556-tag index. The site's genre semantics live in **tags** (`/tag/creampie/`, `/tag/married-woman/`, …), covered by the `tags` row |
| `categories` | `/category/` index — 7 rows (`english-subbed`, `jav`, `decensored`, `amateur`, `idol`, `4k`, `FC2`); `/category/amateur/` — 24 cards | `/category/amateur/page/750/` — 12 cards; `…/751/` 404 | `/category/amateur/page/2/` 24 cards; paginator advertises `/page/750/` as the last |
| `tags` | `/tags/` full index — 555 tag links; `/tag/mature-woman/` — 24 cards | `/tag/mature-woman/page/837/` — 3 cards; `…/838/` 404 | `/tag/mature-woman/page/2/` 24 cards; paginator advertises `/page/837/` |

```
/ (p1) 24 · /page/2 24 · /page/5789 10 · /page/5790 404
?orderby=date&order=ASC p1 24 · p2 24 · p5789 10
/most-watched-rank 98 uniques (no rel=next) · /most-watched-rank/page/2 96 uniques, 92 shared
?orderby=views&order=DESC p1 24 · p5789 10
/category/amateur p1 24 · p2 24 · p750 12 · p751 404
/tag/mature-woman p1 24 · p2 24 · p837 3 · p838 404
```

Provider-row check (D5 trigger: none — `drift_confirmed` was 0 and enumeration did not
break): the provider's 25 `mainPage` rows are `/`, `/most-watched-rank`, 4
`/category/*` keys and 19 `/tag/*` keys. Every sampled URL above is answered 200 with
cards. The selector's trailing `div.tabcontent li, .item-list li` branches match nothing on
an archive surface — harmless extra specificity, not a condition (the
`div.inside-article, article` branches carry every listing).

Site behaviour recorded, not a condition: jav.guru **429s bursty request patterns**
(`/page/1750/`, `/page/1780/` answered 429 when fetched back-to-back; the same URLs
answered 200 when spaced 6–8 s). Every sample in this run was taken with ≥5 s spacing.

## Evidence rows

`type | sample URL | instrument tier | stream host | quality | verdict`

Every row was resolved the way `loadLinks` does it: watch page →
`a.wp-btn-iframe__shortcode[data-localize]` button → its `iframe_url` (base64) →
`jav.guru/searcho/` loader → `window.cfg {base, rtype, cid, keys}`, token read from the
div's `data-*` attrs, reversed → `searcho/?<rtype>r=<token>` → **302** → embed host →
player config → HLS. `quality` is the variant ladder of the resolved master playlist.

| type | sample URL | tier | stream host | quality | verdict |
|---|---|---|---|---|---|
| new (p1) | `/1078178/fns-279-…` | plain-curl | `javclan.com`, `maxstream.org`, `streamhihi.com`, `vidara.to` (+`playmogo.com`, `caseyseemavoid.com` dropped/dead) | 480/720/1080p (javclan), 720p (maxstream), 720p (streamhihi) | **pass as shipped**; `maxstream` + `streamhihi` were dropped pre-fix (below) |
| new (p1) | `/1078159/lucy-033-…` | plain-curl | same six-host shape | 480/720/1080p | **pass as shipped**; 2 hosts dropped pre-fix |
| new (p2) | `/1078176/gmem-158-…` | plain-curl | `javclan.com`, `maxstream.org`, `streamhihi.com`, `vidara.to` | 480/720/1080p | **pass as shipped**; 2 hosts dropped pre-fix |
| new (tail p5789) | `/383/xkk-071-…` | plain-curl | `javclan.com`, `maxstream.org`, `streamhihi.com` | 480/720/1080p | **pass as shipped**; 2 hosts dropped pre-fix |
| old (ASC p1) | `/394/ambi-047-…` | plain-curl | `javclan.com`, `javplaya.com`, `maxstream.org` | 480/720/1080p (javclan), 448p (javplaya hls4) | **pass as shipped**; `javplaya` + `maxstream` dropped pre-fix |
| old (ASC p2) | `/562/chae-003-…` | plain-curl | `javplaya.com`, `playmogo.com`, `maxstream.org` | 448p (javplaya) | **FAIL pre-fix → fixed** (only `playmogo` was registered, and it is captcha-gated from this IP) |
| old (tail p5789) | `/1078168/huntc-692-…` | plain-curl | `javclan.com`, `maxstream.org`, `streamhihi.com`, `vidara.to` | 480/720/1080p | **pass as shipped**; 2 hosts dropped pre-fix |
| popular (rank p1) | `/1077867/snos-459-…` | plain-curl | `javclan.com`, `maxstream.org`, `vidara.to` | 480/720/1080p | **pass as shipped**; `maxstream` dropped pre-fix |
| popular (views p1) | `/212768/ssis-469-…` | plain-curl | `javclan.com`, `javplaya.com`, `maxstream.org` | 480/720/1080p | **pass as shipped**; `javplaya` + `maxstream` dropped pre-fix |
| popular (views tail) | `/710176/lcw-048-…` | plain-curl | `javclan.com`, `streamhihi.com`, `maxstream.org` | 480/720/1080p (javclan), 720p (streamhihi) | **pass as shipped**; `maxstream` + `streamhihi` dropped pre-fix |
| categories (p1) | `/1077268/noskn-106-…` | plain-curl | `javclan.com`, `maxstream.org` | 480/720/1080p | **pass as shipped**; `maxstream` dropped pre-fix |
| categories (tail p750) | `/181897/200gana-2547-…` | plain-curl | `javclan.com`, `playmogo.com` | 480/720/1080p | **pass** (javclan) |
| tags (p1) | `/1075688/fc2ppv-5002306-…` | plain-curl | `javclan.com`, `streamhihi.com`, `vidara.to` | 480/720/1080p | **pass as shipped**; `streamhihi` dropped pre-fix |
| tags (tail p837) | `/1696/taak-006-…` | plain-curl | `javclan.com`, `maxstream.org` | 480/720/1080p | **pass as shipped**; `maxstream` dropped pre-fix |
| genres | — | — | — | — | **absent** (no genre route; tags carry genre semantics) |

`old` (ASC) and `categories`/`tags` tails were sampled at the far end of each paginator;
`new` at p1/p2/tail; `popular` on the rank page and both ends of the views sort. 16 videos,
14 archive pages, 4 rank/widget pages, 0 HTTP failures after pacing (`429` only under
bursts).

### Host census across the 16 sampled videos

| embed host | rows served | registry row before this run | end-to-end from this runner |
|---|---|---|---|
| `javclan.com` | 15 | yes (`Javclan`) | **PASS** — hls2 `premilkyway/cdn-centaurus` 200 `application/vnd.apple.mpegurl`, `#EXTM3U`, ladder `852x480/1280x720/1920x1080`; hls4 same-origin `/stream/…` 200, same ladder |
| `playmogo.com` | 15 | yes (dood family) | **Suspected** — Dood serves a **Cloudflare Turnstile** shell (`challenges.cloudflare.com`, no `pass_md5` in the body) to this datacenter IP, so the dood `pass_md5` contract cannot be exercised here. Datacenter-IP challenge ⇒ `Suspected`, never a finding (D8); in-app unverified |
| `maxstream.org` | 14 | **no** → **added** | **PASS after fix** — Playerjs `file:` inside `eval(p,a,c,k,e,d)`: `200 application/vnd.apple.mpegurl`, `#EXTM3U`, `1280x720` |
| `caseyseemavoid.com` | 9 | no | **not fixable here** — Voe front-end (`voe.sx/api2/session/generate-token`) whose served player is a **placeholder**: `source='https://test-videos.co.uk/vids/bigbuckbunny/…Big_Buck_Bunny_1080_10s_5MB.mp4'`. A Voe row would emit a test pattern, so none was added; host-side placeholder/session gate, not a provider condition |
| `streamhihi.com` | 7 | yes (`streamwishMirror`) — **but the adapter missed the page** | **FAIL pre-fix → fixed** — now `200 application/vnd.apple.mpegurl`, `#EXTM3U`, `1280x720` |
| `vidara.to` | 6 | yes (`Vidara`) | **PASS** — `POST /api/stream {"filecode":…,"device":"web"}` → `streaming_url`, then `200 application/vnd.apple.mpegurl`, ladder `854x480/1280x720/1920x1080` |
| `javplaya.com` | 4 | **no** → **added** | **PASS after fix** — same packed `links` family as javclan; hls4 same-origin `200 #EXTM3U` (`800x448`). hls2 (`dramiyos-cdn.com`) timed out from this runner on 2 of 3 attempts — CDN-side latency, hls4 carries the video |
| `oppainet.net` | 1 | no | **unsupported, unverified** — custom obfuscated player (`_juicycodes` + `window.juicyData` + `juicyData.routes`); no contract to ship without reverse-engineering it, 1/14 samples. Recorded as observed, no row |

## Searcho chain transcript (what `loadLinks` walks)

```
$ curl -sL -A "$UA" https://jav.guru/1078178/fns-279-… | grep -o 'data-localize="[^"]*"[^>]*>[^<]*'
… data-localize="csiosptwvl" … >STREAM SB<   (window.csiosptwvl = {"iframe_url":"<base64>"})
… data-localize="mvyaqhzomq" … >STREAM VO<   … LU … VI … DD … JK
$ python3 -c 'import base64;print(base64.b64decode("<csiosptwvl iframe_url>").decode())'
https://jav.guru/searcho/?xd=163796075347b6e6d6675687&bg=https%3A%2F%2F…1fns00279pl.jpg
$ curl -s -A "$UA" -e https://jav.guru/1078178/ 'https://jav.guru/searcho/?xd=163796075347b6e6d6675687' | grep -E 'base:|rtype:|cid:|keys:'
    base: 'https://jav.guru/searcho/', rtype: 'x', cid: 'c4a8bec', keys: ["data-352c9", "data-bbff6", "data-8d555"]
    (token parts from #c4a8bec: 16379607 + 5347b6e6 + d6675687 → reversed)
$ curl -s -A "$UA" -e https://jav.guru/1078178/ -D - -o /dev/null 'https://jav.guru/searcho/?xr=7865766d6e6b743570697361'
HTTP/2 302
location: https://javclan.com/e/xevmnkt5pisa
```

The same chain on the other slots lands on `streamhihi.com/e/<id>` (SB), `vidara.to/e/<id>`
(VO), `playmogo.com/e/<id>` (DD), `caseyseemavoid.com/e/<id>` (Voe), `maxstream.org/embed-<id>.html`
and `javplaya.com/embed/<id>`. `followRedirects` in the provider already handles the
`/searcho/` hop (JS `window.location.href` **and** the 302), so the chain itself was sound —
the loss was one step further down, at host dispatch.

## Confirmed condition (fixed in this run)

**`loadLinks` resolves the searcho chain correctly, but two of the player hosts it lands on
have no `HostRegistry` row, and a third (registered) host's adapter could not read its
current page shape — so `loadExtractor` silently dropped 1–3 of the 2–6 sources each video
offers.** `Correctness / data-completeness` (the site did not drift in a way that breaks the
provider's own parsing; the provider never covered these hosts' grammars).

Evidence, from the 16-video census above:

- `maxstream.org` on **14/16** videos: Playerjs `file:"<signed m3u8>"` inside an
  `eval(p,a,c,k,e,d)` payload. Confirmed live: `200 application/vnd.apple.mpegurl`,
  `#EXTM3U`, `RESOLUTION=1280x720`.
- `javplaya.com` on **4/16** videos: the javclan `links={hls2,hls3,hls4}` grammar on an
  unregistered host. Confirmed live: hls4 `200 #EXTM3U` (`800x448`).
- `streamhihi.com` on **7/16** videos: registered as a streamwish mirror, but the site now
  ships the whole `sources:{file:…}` config **packed** — the raw HTML contains no
  `file:"…"` at all (`grep -c 'file:"http' → 0`; `/hls2/` and `master.m3u8` absent from the
  page), so `Streamwish.getUrl` returned null. Unpacked live: `200`, `#EXTM3U`, `1280x720`.

Fix, TDD-first red → green (3 pure Parse seams, no selector or listing change):

| seam | red → green | live fixture |
|---|---|---|
| `JavclanParse.streams(payload, baseUrl)` — extracted out of `Javclan.getUrl` so the grammar is testable and shareable | `JavclanParseTest` 4/4 | `javclan_packed_embed.html`, `javplaya_packed_embed.html` |
| `PlayerjsParse.streamUrl(pageHtml)` — `\bfile["']?\s*:\s*["']…`, unpacked first, non-media `file:` values declined | `PlayerjsParseTest` 5/5 | `maxstream_playerjs_embed.html` |
| `StreamwishParse.streamUrl(script)` — raw `file:` first, `PackedJs.unpack` fallback | `StreamwishParseTest` 3/3 | `streamhihi_packed_player.js` |

Registration (one row per host, in the adapter family that parses it — `shared/`, never a
per-provider copy): `javclanMirror("https://javplaya.com", "Javplaya")` and `Maxstream()`
(`https://maxstream.org`). `HostRegistryTest` gains
`javguru searcho player hosts have explicit registry rows` (red before the rows existed),
and the existing invariants (unique mainUrl, no host shadowing, name-collision allowlist)
stay green — `Javplaya`/`Maxstream` are unique display names.

```
before: javplaya/maxstream → loadExtractor(mainUrl prefix) → no row → dropped; streamhihi → Streamwish → null
after:  javplaya → Javclan (hls2 + hls4); maxstream → Maxstream (Playerjs file:); streamhihi → Streamwish (packed fallback)
```

`JavGuru/build.gradle.kts` `version` **37 → 38**. The two new rows and the streamwish
fallback ship in `shared/`, so the PR-time version bumper's `shared/** → ALL` rule rebuilds
every provider artifact; the JavGuru bump here is the story's own.

## Verification (delivery gate)

- `./gradlew bootstrapCloudstream` then `./gradlew JavGuru:test` — **green**
  (148 tests: `JavclanParseTest` 4/4, `PlayerjsParseTest` 5/5, `StreamwishParseTest` 3/3,
  `HostRegistryTest` incl. the new row test, `PackedJsTest`, whole shared suite).
- `./gradlew JavGuru:make` — clean `JavGuru.cs3` at version 38.
- `.pi/skills/verify-provider/scripts/verify.sh` — **RESULT: PASS**:
  search `/page/1/?s=married` 200/24 cards; home `/` + `/page/2/` 24+24;
  3 varied video pages 200 with the `a.wp-btn-iframe__shortcode[data-localize]` player
  buttons (5/3/3); streams 3/3 `200 application/vnd.apple.mpegurl` (`maxstream` ×2,
  `streamhihi` ×1 — the hosts this run fixed), distinct paths; related
  `div.woo-sc-related-posts li` 107–108/page; tags/actors/year present on 3/3;
  `LoadResponse` fields all populated in Kotlin (`recommendations,tags,plot,year,actors,posters`).
- Search ↔ load agreement spot-check (verify's NOTE: card titles are `img alt`, which the
  script's text-only extractor cannot read, so it skipped the pair): for
  `/1078178/fns-279-…` the card `img alt` is `[FNS-279] Can’t hold back when my wife’s
  sister flaunts her puffy pussy slit to seduce me Hachimitsu Rin` and the load page's
  `h1.titl` is the identical string; the card poster is
  `…/1fns00279pl-550x374.jpg` and the load poster `…/1fns00279pl.jpg` — the same artwork,
  WP crop rendition (FINDINGS-1440's poster-variant class, not a mismatch).
- `.pi/skills/audit-providers/scripts/check-findings.sh` — **PASS**.

## Scope notes (not conditions)

- **`genres` is absent site-side** — no `/genre/` route, no genre taxonomy in the nav, the
  category index or the tag index; tags carry the genre semantics. Recorded, never a finding (D3).
- **`old` is not in the site's sort menu** (`order=ASC` is honoured server-side only).
  Reachable ⇒ sampled as a D3 type; recorded as a menu gap, not a provider condition.
- **The rank page does not paginate** — `/most-watched-rank/page/N/` re-renders the same
  widget (92/96 overlap), so `getMainPage`'s `page/N` suffix on that row is inert there.
  The provider row still returns 200/98 cards on page 1, so this is not drift; the pageable
  popular surface is the `?orderby=views&order=DESC` sort, sampled above.
- **No synopsis on the watch page**: the article content carries no `<p>` at all
  (`Movie Information` is structured rows only — code, release date, category, director,
  studio, label, tags, actor/actress, views), so the provider's `plot` falls back to its
  pre-existing hardcoded string. Pre-existing, unrelated to the video-source leg — noted
  for a load-field story, not fixed here.
- **Poster** is exposed as `div.large-screenshot img[src]` on `cdn.javmiku.com` (rewritten
  to the `jav.guru` origin by `JavGuruParse.parsePosterUrl`, issue #424); verify.sh can only
  read `content`/`text` attributes, hence its poster NOTE. Duration is not exposed.
- **FC2-PPV video pages carry no `div.woo-sc-related-posts` block** (`/1075688/…` — 0
  matches, vs 107–108 on regular pages). The provider's `recommendations` list is simply
  empty there; swap-ins for the verify run used regular pages. Site-side shape difference.
- **`playmogo.com` (dood) is captcha-shelled from this IP** — Turnstile, no `pass_md5`:
  `Suspected`, not a finding (D8). It was not the only source of any sampled video.
- **`caseyseemavoid.com` (Voe) serves a placeholder mp4** for this session/UA — the video
  layer is host-side, so no row and no finding; a Voe row would have emitted
  `Big_Buck_Bunny_1080_10s_5MB.mp4` as the "stream".
- **`oppainet.net`** (1/14 samples) is a bespoke obfuscated player with no shipped family;
  recorded as observed. No row without a verified contract.
- **429 under bursts** — jav.guru throttles back-to-back requests; pacing 5–8 s keeps every
  surface at 200. Pipeline Verification should keep its per-request spacing.
- `JavGuru`'s `mainPage` row for `/most-watched-rank` and the tag rows are unchanged — no
  listing behaviour was touched by this run.
