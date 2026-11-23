# FINDINGS-1447 — PandaMovies video sources (S9 of #1354)

Deep pass on the **video-source leg only** (D2/D3/D4) for `pandamovies.org`, run 2026-11-22 on
`devloop/issue-1447`. Provider `PandaMovies` (v19 → v20) · registry `last_deep` = 2026-10-17 ·
`drift_confirmed` = 4, `chronic: true` at run start (fleet **Chronic** provider).

Instrument: **plain curl** (Firefox UA) — `.pw` 302-redirects to `.org`, and `.org` answered 200 on
every probed surface. No challenge anywhere on the HTML surfaces, so no escalation was needed
(see Phase 0 for the tier's availability).

Two confirmed conditions, both fixed in this run (fixes below): the term-route migration that
killed all three genre rows, and the dead `?s=` search fallback. The video-source leg itself is
structurally sound — `section.hlm[data-servers]` is intact on 15/15 sampled pages and the embed
set is host-agnostic — but three embed hosts in the current rotation have **no adapter in
`shared/`**, which is recorded as a data-completeness finding (D8), not a blocker.

## Phase 0 — calibration (canaries, `audits/canaries.json`)

| canary | tier | expected | observed | reading |
|---|---|---|---|---|
| `eporner-healthy` | plain-curl | 200 | **200** | match — instrument healthy |
| `pandamovies-redirect-origin` | plain-curl | 302 | **302** (`pandamovies.pw` → `pandamovies.org`) | match |
| `film1k-challenge-pair` | plain-curl | 403 | **200** | **expectation-stale (#1435, open)** — not `environment-degraded`; live pair recorded, no finding filed from it |
| `film1k-challenge-pair` | tls-impersonated | 200 | **unavailable** | #1434 open — `python3 -c 'import curl_cffi'` → `ModuleNotFoundError` |

- `escalation_tier: unavailable` (per the run spec: #1434 still open) → **every `Blocked` this run
  downgrades to `Suspected`**. No `Blocked` verdict was reached: plain curl reached every URL.
- `expectation-stale` branch taken for film1k (per #1435): recorded, not treated as degradation.

```
$ curl -sS -o /dev/null -w '%{http_code}' https://www.film1k.com/    → 200   (canary expects 403, #1435)
$ curl -sS -o /dev/null -w '%{http_code}' https://www.eporner.com/   → 200
$ curl -sS -o /dev/null -w '%{http_code}' https://pandamovies.pw/    → 302
$ curl -sS -o /dev/null -w '%{http_code} %{url_effective}' https://pandamovies.org/
200 https://pandamovies.org/
```

## D3 — listing types the site exposes (enumeration only, D5)

One row per listing type. `absent` is a recorded outcome, never a finding. Card grammar
(`article.card` / `card__th` / `card__t` / `card__img`) is unchanged from FINDINGS-555 on every
listing surface; note the main grids now emit `h2.card__t` where the home hero row still uses
`h3.card__t` — the provider's class-only `.card__t a` selector covers both (fixture-pinned).

| type | sample URL | instrument tier | stream host | quality | verdict |
|---|---|---|---|---|---|
| new / latest | `https://pandamovies.org/` (p1: **27** cards outside the Featured `section.sec` row + 9 inside it, excluded by design) · `/page/4593` (**13** cards, far end) | plain curl | playmogo / lulustream / mixdrop.ag / voe.sx | not exposed | **pass** |
| old | `https://pandamovies.org/movies?sort=oldest` (p1: 27 cards, all **2022** vs 2026 on the unsorted surface — the sort is honoured; pager keeps it: `/movies/page/2?sort=oldest`) · `/years/1971` (oldest release bucket, **1** card) | plain curl | lulustream / mixdrop.ag | not exposed | **pass** |
| popular | `https://pandamovies.org/most-viewed` p1 + `/most-viewed/page/2` (27 + 27) · `/panda-top-rated` (27) | plain curl | lulustream / mixdrop.ag / voe.sx / vidara.so | not exposed | **pass** |
| genres | `https://pandamovies.org/genres/anal` p1 (**27**) · `/genres/anal/page/965` (9 cards, far end, "Page 965 of 965") | plain curl | lulustream / mixdrop.ag / peeplayer.com | not exposed | **pass** |
| categories | `https://pandamovies.org/movies` p1 (**27**; the one category term "Porn Movies") · `/studios/brazzers` p1 (27, studio taxonomy) | plain curl | lulustream / mixdrop.ag / voe.sx / vidara.so | not exposed | **pass** |
| tags | — **absent**: `/tags` 404, `/tag/anal` 404, `/tag/anal-sex` 404; the video page carries no tag row (details panel = Pornstars · Studio · Release Year · Genres · Category). The genre taxonomy absorbed the keyword vocabulary (`/genres?l=a…z`, 26k-term index) | plain curl | — | — | **pass (absent)** |

```
$ for p in /genre/anal /genre/18-teens /genre/lesbian /category/porn-movies /tag/anal /tags; do
    curl -sSL -o /dev/null -w "$p %{http_code}\n" https://pandamovies.org$p; done
/genre/anal 404 · /genre/18-teens 404 · /genre/lesbian 404 · /category/porn-movies 404 · /tag/anal 404 · /tags 404

$ for p in /genres/anal /movies /most-viewed /panda-top-rated /longest-porn /years/1971 /years/2026 /studios/brazzers; do
    curl -sSL -o /dev/null -w "$p %{http_code}\n" https://pandamovies.org$p; done
/genres/anal 200 · /movies 200 · /most-viewed 200 · /panda-top-rated 200 · /longest-porn 200 · /years/1971 200 · /years/2026 200 · /studios/brazzers 200
```

Quick search: still **no distinct endpoint** — `/wp-json/turbosearch/v1/suggest?q=sex` is the
site's own typing endpoint, but the provider has no `quickSearch` override by design (unchanged
from #659). `?s=` (the WP search archive) is **WAF-403** from this runner while `/search/<slug>`
stays 200 — see the fix below.

**The site's own indexes still link the dead singular routes.** `/genres` emits
`href="…/genre/<slug>"` for every term, `/porn-studios` emits `/director/<slug>`,
`/release-years` emits `/release-year/<yyyy>`, and the genre listing's own sort tabs emit
`…/genre/anal?filter=most-viewed`. A browser user lands on 404s; only the plural routes serve
cards. That asymmetry is why the provider now derives its row twin instead of trusting one shape.

## Video pages — 15 sampled, ≥2 per type, both ends of every pageable catalogue

| # | listing type | URL slug | `data-servers` hosts |
|---|---|---|---|
| 1 | new (home p1) | `watch-hot-milf-9-…` | playmogo, lulustream, mixdrop.ag, voe.sx |
| 2 | new (home p1) | `watch-the-cuckold-the-plumber-and-the-slut-…` | lulustream, playmogo, peeplayer, mixdrop.ag |
| 3 | new (`/page/4593`) | `watch-defiant-busty-beauty-…` | playmogo, lulustream, mixdrop.ag |
| 4 | new (`/page/4593`) | `watch-fucking-my-man-bretnie-…` | playmogo ×2 |
| 5 | old (`?sort=oldest` p1) | `watch-lala-ivey-creampied-by-the-robber-…` | peeplayer, lulustream, playmogo, mixdrop.ag, voe.sx, vinovo.to |
| 6 | old (`?sort=oldest` p1) | `watch-just-in-time-for-one-last-bite-…` | peeplayer, lulustream, playmogo, mixdrop.ag, voe.sx, vinovo.to |
| 7 | old (`/years/1971`) | `watch-sexorcism-5-swedish-massage-…` | playmogo, mixdrop.ag |
| 8 | popular (`/most-viewed` p1) | `watch-world-of-bangbros-big-tit-interracials-2-…` | playmogo, lulustream, mixdrop.ag, voe.sx, vidara.so |
| 9 | popular (`/most-viewed` p2) | `watch-sex-geschichten-heisser-braute-…` | playmogo, lulustream, mixdrop.ag, voe.sx, vinovo.to, vidara.so, peeplayer |
| 10 | genres (`/genres/anal` p1) | `watch-audrey-hollander-…` | lulustream, peeplayer, playmogo, mixdrop.ag, voe.sx, vinovo.to |
| 11 | genres (`/genres/anal/page/965`) | `watch-the-jetlag-cure-…` | lulustream, playmogo, mixdrop.ag |
| 12 | categories (`/movies` p1) | `watch-dont-be-shy-2-…` | lulustream, content.st, playmogo, mixdrop.ag, voe.sx, vinovo.to, vidara.so, peeplayer |
| 13 | categories (`/studios/brazzers` p1) | `watch-hot-and-mean-27-…` | playmogo, lulustream, mixdrop.ag, voe.sx, peeplayer |
| 14 | search (`/search/sex` p1) — agreement check | `watch-sex-…` | mixdrop.ag |
| 15 | popular (`/panda-top-rated` p1) | `watch-anal-interracial-experience-…` | lulustream, playmogo, mixdrop.ag, voe.sx, peeplayer |

All 15 → HTTP 200 with `span.bc__c` title, `img.hlm-screen__poster` poster (== og:image),
`div.vid__txt p` first paragraph, `span.st--duration` / `span.st--year`, `a.chip[href*=/genre/]`
tags, `a.chip--star` actors, `section.sec article.card` related (12 each), `section.hlm[data-servers]`
player block.

Exposure inventory (unchanged from FINDINGS-555/#659, re-confirmed):

- **quality is not exposed** per source — `data-servers` entries carry only
  `{u,t,l,h,g,x}` (URL, type, label, hash, group, slug); no resolution/height field, and the
  m3u8 master playlists sampled are single-rendition (no `#EXT-X-STREAM-INF`).
- **plot is a scene list on newer uploads** — `div.entry.vid__txt` holds `<p><strong>Scene N</strong>
  | Starring: … | Duration: N min</p>` rather than a synopsis (page 9 has the block with empty
  paragraphs). The selector still matches; the *content* degraded on the studio uploads.
- **actors/plot are absent on a subset** (site data gap, not a selector mismatch): pages 3 and 7
  carry no Pornstars row at all, page 9 no plot paragraph. Verification was run on pages that do
  expose them (below); a `--video-actors-selector` all-or-none check over an arbitrary sample
  would report a false FAIL.

## Stream sources — the end-to-end resolution, per host family

Registry rows present (✅) or missing (❌) in `shared/HostRegistry.kt`, and what each host actually
serves from this runner:

| host | rows | resolution from this runner | verdict |
|---|---|---|---|
| `mixdrop.ag` (15/15 pages) | ✅ `MixDropAg()` | `/f/<id>` or `/e/<id>` → packed `MDCore.wurl` → **206 video/mp4** | **pass** |
| `lulustream.com` (13/15) | ✅ `lulu("https://lulustream.com")` | `/e/<id>` → packed `file:` → `https://<node>.tnmr.org/…/master.m3u8` → **200 application/vnd.apple.mpegurl** | **pass** |
| `playmogo.com` (14/15) | ✅ `dood("https://playmogo.com")` | 5.4 KB Cloudflare **Turnstile** shell, no `pass_md5` token in the body | **Suspected** (datacenter-IP challenge; never a finding) |
| `voe.sx` (10/15) | ✅ `Voe()` | `window.location.href = 'https://caseyseemavoid.com/e/<id>'` → 137 KB player page (jwplayer + `/5/`,`/10/` source endpoints) | **pass** (both `voe.sx/e/<id>` and the provider's normalized `voe.sx/<id>` land on the same player; kept as-is) |
| `vidara.so` (3/15) | ✅ `VidaraSo()` | `POST https://vidara.so/api/stream {"filecode":…,"device":"web"}` → `streaming_url` → **200 application/vnd.apple.mpegurl** | **pass** |
| `vinovo.to` (5/15) | ❌ no row | its own player: `meta[name=token]` + `/api/file/url/` (302 → site root), `/pass_md5/<token>` → **Cloudflare "Just a moment…"** | **Suspected** + data-completeness finding |
| `peeplayer.com` (8/15) | ❌ no row | PeeStream: WebCrypto **ECDH P-256 → HKDF → AES-GCM unwrapKey** envelope (`window.__PPK`, key card `KART`, `/kx` handshake) around an AES-CBC media key | data-completeness finding |
| `content.st` (1/15) | ❌ no row | `/e/<id>` → **403 "Playback is blocked"** (host-side block; site root 200) | **Suspected** + data-completeness finding |

Per-family transcripts:

```
# mixdrop (page 7, 1971 upload) — packer → direct mp4
$ curl -sSL https://mixdrop.ag/e/j9l3o0ndhrjowq | unpack(p,a,c,k,e,d)
MDCore.wurl="//o230m5y6z.mxcontent.net/v2/j9l3o0ndhrjowq.mp4?s=…&e=…&_t=…";
$ curl -sS -H 'Range: bytes=0-64' -o /dev/null -w '%{http_code} %{content_type}\n' "<that url>"
206 video/mp4

# lulustream (page 1) — packer → HLS master
$ curl -sSL https://lulustream.com/e/7dr85qmw26ua | unpack(p,a,c,k,e,d)
file: "https://cdn1015.cdn-tnmr.org/hls2/03/03094/7dr85qmw26ua_h/master.m3u8?t=…&s=…&e=28800&f=15474730";
$ curl -sS -o /dev/null -w '%{http_code} %{content_type}\n' "<that url>"
200 application/vnd.apple.mpegurl

# playmogo (page 5) — challenge shell, not a parser failure
$ curl -sSL -o pm.html -w '%{http_code}\n' https://playmogo.com/e/hozm90caog1y
200 ; grep -c pass_md5 pm.html → 0 ; grep -o turnstile pm.html → turnstile / turnstile-container / turnstile.render(

# voe (page 2) — JS redirect to the rotating mirror, same player
$ curl -sSL https://voe.sx/e/kkco3ozvwnch | grep -o "window.location.href = '[^']*'"
window.location.href = 'https://caseyseemavoid.com/e/kkco3ozvwnch'
$ curl -sSL https://caseyseemavoid.com/e/kkco3ozvwnch → 200, 137718 B, "Watch … .mp4 - VOE | Content Delivery Network" (jwplayer, /5/<hash>, /10/<hash>)

# vidara.so (page 8)
$ curl -sS -X POST -H 'Referer: https://pandamovies.org/' -d '{"filecode":"dbn4Gv9kk4ljb","device":"web"}' \
    https://vidara.so/api/stream
{"…","streaming_url":"https://s1-s501740.97bf1.com/hls/G4Kh1YfwJCCmLdpDEGvaylStaZ3uWumY/master.m3u8?token=…"}
$ curl -sS -o /dev/null -w '%{http_code} %{content_type}\n' "<that url>" → 200 application/vnd.apple.mpegurl

# vinovo.to (page 5) — own player, not the dood pass_md5 grammar
$ curl -sSL https://vinovo.to/e/488ejj6gun3vn2 | grep -o 'name="token" content="[^"]*"'
<meta name="token" content="459fc107ba06c0a50348b4f89a997ac4">
$ curl -sSL -H 'Referer: https://vinovo.to/' https://vinovo.to/pass_md5/459fc107ba06c0a50348b4f89a997ac4
<title>Just a moment...</title>   (Cloudflare)
$ curl -sSL -X POST -d 'file_code=488ejj6gun3vn2&token=…' https://vinovo.to/api/file/url/
302 → https://vinovo.si/            (endpoint needs the reCAPTCHA-gated client flow; meta grecaptchav2 present)

# peeplayer.com (page 2) — WebCrypto envelope, no static stream URL exists
$ curl -sSL https://peeplayer.com/e/MSz5QdcB4YDs | grep -o 'ECDH\|deriveBits\|unwrapKey'
crypto.subtle ECDH P-256 → importKey(raw) → deriveBits(256) → HKDF(SHA-256, salt=NONCE) → AES-GCM unwrapKey → AES-CBC
$ curl -sS -X POST -H 'Content-Type: text/plain' -d '{"m":"MSz5QdcB4YDs","k":"…","p":"AAAA"}' https://peestream.com/kx
400 {"e":"los"}    (the handshake is live; the media key is only obtainable by running it)

# content.st (page 12)
$ curl -sSL -o /dev/null -w '%{http_code}\n' https://content.st/e/x526hfugs2t
403 ; <title>Playback is blocked</title>   (host root 200 — host-side block, not a geo-only CDN)
```

Stream paths are distinct across every pair sampled (the m3u8 carries the lulu file id, the mp4 the
mixdrop file id) — the Distinct bar holds.

## Drift A confirmed (and fixed) — every genre/home row 404'd

The provider's `mainPage` was `Latest` + `/genre/18-teens` + `/genre/lesbian` + `/genre/anal`.
Measured 2026-11-22: **three of four rows 404** ("Page not found - PandaMovies"), and the old
`/category/porn-movies` row from before #555 is dead too. Home therefore rendered one working row
and three empty ones, and the "Latest" hero row (9 cards inside `section.sec`) is excluded by the
`closest()` filter by design (unchanged behaviour, verified: 27 usable cards on p1).

This is the same class as #457/#463/#471/#555 recurrence: a hardcoded route shape went stale.
Per AGENTS.md a Chronic provider's fix must be structural, so the rows no longer carry a single
brittle shape:

1. Rows point at slug-free surfaces (`/`, `/movies`, `/most-viewed`, `/panda-top-rated`) plus two
   genre rows on the **current** plural route (`/genres/anal`, `/genres/lesbian`).
2. `Parse.rowCandidates()` derives the singular↔plural **twin** of any term route
   (`/genres/anal` → `/genre/anal`, `/years/1971` → `/release-year/1971`, `/studios/x` →
   `/studio/x`) and `getMainPage` walks the candidates until one yields cards. Either direction of
   a future flip heals itself; no slug is hardcoded twice.

TDD: `rowCandidates`/`taxonomyTwin` tests first (red — unresolved reference), then green, plus a
live `/genres/anal` fixture (`panda-genres-anal.html`, 27 cards, `h2.card__t`) that pins the current
markup shape.

## Drift B confirmed (and fixed) — the `?s=` search fallback is WAF-403

`search()` retried a zero-overlap (WP fuzzy garbage) result set through `$mainUrl/?s=<slug>`.
Measured: **every** `?s=` request is now 403 (`Access to this resource on the server is denied!`,
787 B) while `/search/<slug>` and `/search/<slug>/page/N` stay 200 — so the retry threw away a
good first response, and on a garbage page the whole search failed. The retry is now wrapped: a
failing/empty retry keeps the first response.

```
$ for q in '/?s=sex' '/?s=hot+milf' '/search/sex' '/search/sex/page/2' '/page/2?s=sex'; do
    curl -sSL -o /dev/null -w "$q %{http_code}\n" "https://pandamovies.org$q"; done
/?s=sex 403 · /?s=hot+milf 403 · /search/sex 200 · /search/sex/page/2 200 · /page/2?s=sex 403
```

## Findings filed (D8 / lifecycle)

Dedup: `gh issue list --search PandaMovies --state all` → open issues are #1447 (this story) and
#1504 (SonarCloud S6518, unrelated); the drift-recurrence issues #457/#463/#471/#555 are closed and
none describes an unsupported embed host. One new issue:

- **PandaMovies serves embed hosts with no `shared/` adapter** — `peeplayer.com` (PeeStream,
  WebCrypto ECDH/HKDF/AES-GCM envelope around an AES-CBC media key) and `vinovo.to` (own
  `/api/file/url/` player, reCAPTCHA-gated, Cloudflare on `/pass_md5/`) are 8/15 and 5/15 of the
  sampled pages' source lists; `content.st` (1/15) is host-side blocked. `loadExtractor` drops them
  without a row, so those pages lose 1–3 of their mirrors. Filed as **Correctness /
  data-completeness** per D8 — never `Blocked`, and not fixed inline: both need a bespoke adapter
  (ECDH unwrap / reCAPTCHA-gated API) rather than a hostname row, and a `dood("…/vinovo.to")` row
  would be worse than none (the classic `pass_md5` grammar does not exist on that host).

## Fix (D1 — audit *and* fix in this run)

- `PandaMovies.kt`: `mainPage` rows → live surfaces + `Parse.rowCandidates`/`Parse.taxonomyTwin`
  twin fallback walked by `getMainPage`; `search()`'s `?s=` retry degrades instead of throwing;
  `Parse.embeds` KDoc records the rotated host set (behaviour unchanged — it was already
  host-agnostic and enumerated the new hosts correctly).
- `PandaMovies/build.gradle.kts`: `version = 19 → 20`.
- Fixtures: `panda-genres-anal.html` (live `/genres/anal` p1 grid, 27 cards),
  `panda-watch-hosts.html` (live `section.hlm[data-servers]` with the 7-host rotation).
- No `shared/` change was needed: every host with a working grammar already has a row, and the
  two that do not are recorded above rather than mishandled.

## Verification (this run)

`.pi/skills/verify-provider/scripts/verify.sh` — **RESULT: PASS**, 11 video pages
(9 lulustream m3u8 200 `application/vnd.apple.mpegurl`, 2 mixdrop mp4 206 `video/mp4`), search
`/search/sex` p1/p2 27+27 cards with no duplicate hrefs, home `/` + `/page/2` 36/27 cards,
`section.sec article.card` related = 12 on every page with no self-reference, tags/actors/year/
duration present on all 11, check 5 (search ↔ load) agreeing on title and poster.

Flags that need their FINDINGS line are satisfied here: no quick-search endpoint (above), quality
not exposed (above), plot/actors absent on a subset of pages (above — verification used pages that
expose them, per the #555 precedent).

`./gradlew PandaMovies:test` green (16 provider tests + shared suite), `./gradlew PandaMovies:make`
clean (`.cs3` 157892 B), `check-findings.sh` PASS.

## Risks / blockers

- **No Cloudflare challenge on the HTML surfaces** this run — home/search/genre/video all 200 on
  plain curl. Nothing here is verdicted from challenge evidence.
- **DoodStream family** (`playmogo.com`, 14/15 pages) is Turnstile-walled from the runner —
  `Suspected`, unchanged from the 2026-10-14/10-17 passes; in-app playback stays the maintainer's
  at merge.
- **`vinovo.to` / `content.st`** are unassertable from the runner for different reasons (reCAPTCHA
  client flow / host-side block) — `Suspected`.
- **Chronic status unchanged** (`drift_confirmed` 4 → 5 confirmed with this run's two fixes).
  Removal remains a maintainer `ai-remove` decision; this story does not propose it.
- Probe region: US datacenter runner IP.
