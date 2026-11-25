# FINDINGS-1461 — Mangoporn (mangoporn.net) video-source pass

Run: 2026-11-25. Issue **#1461** — *S23 — Mangoporn video sources*, story 23 of 27 of the
video-sources audit (#1354). Scope is the **video-source leg (D2/D3/D4)** only.

**Verdict: `ok` — no drift, no code change in this run.**
`drift_confirmed` stays **3** (`last_deep` 2026-10-17 → **2026-11-25**).
Instrument tier: **plain-curl** (CDN/UA-shaped — see Instrument notes).
One **confirmed data-completeness condition** (three unregistered `data-servers` hosts) is
already owned by the standing issue **#1512**; this run contributes its evidence there
instead of filing a duplicate and instead of pre-empting that story's fix (Findings
lifecycle, below).

---

## Phase 0 — calibration (ran before touching the site)

| canary | expected | observed (2026-11-25) | reading |
|---|---|---|---|
| `film1k-challenge-pair` | plain 403 / TLS 200 | **plain 200** / TLS tier not runnable | `expectation-stale` per **#1435** — recorded, **no** `environment-degraded` branch |
| `eporner-healthy` | plain 200 | plain 200 | match |
| `pandamovies-redirect-origin` | plain 302 / TLS 200 @ .org | plain **302** (`location: https://pandamovies.org/`) | match (no `-L`, so the 302 is the evidence) |

```
$ curl -s -o /dev/null -w '%{http_code}\n' https://www.film1k.com/        → 200   (#1435: expectation-stale)
$ curl -s -o /dev/null -w '%{http_code}\n' https://www.eporner.com/       → 200
$ curl -sI https://pandamovies.pw/ | head -2                              → HTTP/1.1 302 / location: https://pandamovies.org/
```

**Escalation tier: unavailable (#1434, background).** The ladder's TLS rung is not runnable in
this container — `curl_cffi` is not installed and cannot be, because the #1434 workaround venv
is not present here — and the browser rung is unavailable too:

```
$ python3 -c 'import curl_cffi'   → ModuleNotFoundError: No module named 'curl_cffi'
$ bash .pi/skills/site-probe/scripts/impersonate.sh https://www.film1k.com/
    impersonate.sh: curl_cffi missing for python3 …
$ python3 -c 'import playwright'  → ModuleNotFoundError: No module named 'playwright'
```

Therefore, per the issue spec — with #1434 cited as the recorded *reason* the TLS rung is
unusable, not as a live gate; that issue is closed NOT_PLANNED (2026-11-24) and the
`ModuleNotFoundError` transcript above is the actual justification — `escalation_tier:
unavailable` goes in the registry `last_runs` entry, every `Blocked` this run is at most
**`Suspected`**, and nothing in this document is filed as a finding from challenge evidence.
Phase 0 was otherwise healthy (2/2 applicable canaries matched, `film1k` mismatch adjudicated
stale).

---

## D3 — listing types

Mangoporn's taxonomy is WordPress `genre` / `studios` / `pornstar` / `year` / `director`
plus a two-value **category** namespace under `/genres/`. `tag` does not exist.

| type | exists? | evidence |
|---|---|---|
| new / latest | **yes** | `/` and `/movies/`, both paginate |
| old | **yes, as the catalogue tail** — no ascending sort (`Them` tabs are Latest / Most Viewed / Longest / Top Rated / Random only; no `?filter=oldest`) | `/page/4610/`, `/movies/page/4560/` |
| popular | **yes** | `/?filter=most-viewed` (4,261 pages), `/?filter=popular` = *Top Rated* (7 pages), `/?filter=longest` |
| genres | **yes** | `/movies/genre/<slug>/` — 968 pages for `anal`; `mango-genres/` lists 315 genre slugs |
| categories | **yes** | `/genres/porn-movies/` (4,583 pages) and `/genres/gay-porn-movies/` (27 pages); the watch page's *Category* row links exactly these two |
| tags | **absent** | `GET /movies/tag/anal/` → 404, `GET /tags/` → 404 |

```
$ for u in /movies/tag/anal/ /tags/ /movies/category/gay-porn-movies/ /genres/
    404 404 404 404        (taxonomy is genre+category; no tag route exists)
$ GET /genres/porn-movies/ → 200 27 cards    GET /genres/gay-porn-movies/ → 200 27 cards
```

### Pagination (listing correctness only as far as enumeration needed, D5)

Every listing surface is path-segment paginated and **p1 ∩ p2 = 0**; `a.next.page-numbers`
is present on every non-final page, 404 past the end.

| surface | p1 | p2 | overlap | tail | past tail |
|---|---|---|---|---|---|
| `/` (new) | 27 cards | 27 | 0 | `/page/4610/` 9 cards | `/page/4611/` **404** |
| `/movies/` (new, archive) | 27 | 27 | 0 | `/movies/page/4560/` 11 cards | `/movies/page/4561/` 200 27 cards; 404 only at `/movies/page/5000/`¹ |
| `/?filter=most-viewed` | 27 | 27 | 0 | `/page/4261/?filter=most-viewed` 9 | `/page/4262/…` **404** |
| `/?filter=popular` | 27 | — | — | `/page/7/?filter=popular` 15 | `/page/8/?filter=popular` **404** |
| `/movies/genre/anal/` | 27 | 27 (`/page/2/`) | 0 (vs p500) | `/page/968/` 13 | `/page/969/` **404** |
| `/genres/porn-movies/` | 27 | 27 | 0 | `/page/4583/` announced | — |
| `/genres/gay-porn-movies/` | 27 | 27 | 0 | `/page/27/` 4 | `/page/28/` **404** |

¹ listing hygiene note, not this leg: the `/movies/` archive's announced last page
(4,560) is stale — the true end 404s at 5,000. Backlog, not a finding.

Pagination was **not** re-audited beyond enumeration: the provider's card grammar
(`div.grid:not(.grid--blk) article.card`, #657) parsed every sampled page with the exact
card count (27/27/27/27/27/27, tail pages 9/11/13/4/15), and `verify.sh` reports p1⊥p2 on
home + search. Card-field correctness stays out of scope (issues #632–#659).

---

## D4 — evidence rows (≥2 video pages per type, both catalogue ends)

One row per listing type. `stream host` = the host that actually served the stream this run.
`quality`: the `data-servers` payload carries no quality field (`{u,t,l,h}` only) — quality
reaches CloudStream from the extractor, and the sampled players expose it at playback
(lulu `master.m3u8` with a `1280x720` variant, vidara `master.m3u8`, mixdrop single
rendition). The provider passes the emitted link through untouched, so "quality passed to
CloudStream where the site exposes it" holds.

| type | sample URL (listing) | video page | instrument tier | stream host | quality | verdict |
|---|---|---|---|---|---|---|
| new / latest | `/` p1, `/page/2/` p2, `/movies/` p1 | `teen-bliss`, `the-double-agents`, `dont-be-shy-2` | plain-curl | lulustream.com (→ tnmr.org HLS), mixdrop.ag (→ mxcontent mp4), vidara.so (97bf1 HLS) | HLS master 720p+ / mp4 | **pass** 3/3 resolved |
| old (catalogue tail) | `/page/4610/`, `/movies/page/4560/` | `love-drenched-closeness-returns-shivers-repeat-counting-fades`, `bdsm-hookup` | plain-curl | `love-drenched…`: **dood/playmogo only** → Cloudflare-Turnstile shell from this IP → **Suspected**; `bdsm-hookup`: lulustream HLS + mixdrop mp4 | HLS / mp4 | **pass (1 Suspected)** |
| popular | `/?filter=most-viewed` p1, `/?filter=popular` (Top Rated) p1 | `world-of-bangbros-big-tit-interracials-2`, `anal-cumsumption` | plain-curl | lulustream (tnmr HLS 200), mixdrop.ag (206 mp4) | HLS / mp4 | **pass** 2/2 resolved |
| genres | `/movies/genre/anal/` p1 + p500 | `happily-sodomized-teenagers-5`, `swedish-erotica-0353-soft-sell` | plain-curl | `happily…`: lulustream HLS + mixdrop mp4; `swedish-erotica…`: **dood/playmogo only** → Turnstile → **Suspected** | HLS / mp4 | **pass (1 Suspected)** |
| categories | `/genres/porn-movies/` p1, `/genres/gay-porn-movies/` p1 + p2 | `asian-schoolgirls-2`, `andre-cruise-used-by-francisco-2`, `discipline-tales-vol-367` | plain-curl | mixdrop.ag (206 mp4), lulustream (200 HLS), vidara.so (206 HLS) | HLS / mp4 | **pass** 3/3 resolved |
| tags | — | — | — | — | — | **absent** (`/movies/tag/…`, `/tags/` 404 — recorded, never a finding) |

12 video pages sampled in total — the D4 minimum is ≥2 per type across the five exposed types,
i.e. 10, so this run is **two over the minimum on two types** (new/latest 3, categories 3); the
other three types carry exactly the minimum of 2. **10/12 resolved end-to-end from the runner**,
the other 2 are dood-only tail uploads that serve the datacenter-IP challenge class (their
per-row transcripts are missing — declared in *Stream transcripts*, below).

### Host coverage across the 12 samples (`data-servers` payload)

| host | pages | registry (`shared/HostRegistry.kt`) | this run |
|---|---|---|---|
| `playmogo.com` (DoodStream) | 12 values on 10 pages | ✅ `dood("https://playmogo.com")` | Turnstile shell from this IP → **Suspected** |
| `mixdrop.ag` | 8 pages | ✅ framework `MixDropAg()` (→ 302 `mxdrop.click`) | ✅ `206 video/mp4` |
| `lulustream.com` | 7 pages | ✅ `lulu("https://lulustream.com")` (→ 301 `lplaygo.com`) | ✅ `200 application/vnd.apple.mpegurl` |
| `voe.sx` | 6 pages | ✅ framework `Voe()` (→ JS hop `caseyseemavoid.com`) | page 200, payload obfuscated (adapter territory) |
| `peeplayer.com` (PeeStream) | 4 pages | ❌ none | "This video is being prepared" on 4/4 → nothing to extract |
| `vinovo.to` | 4 pages | ❌ none | player page 200, `meta[name=token]`+`/api/file/url/` (reCAPTCHA-gated) — see below |
| `vidara.so` | 2 pages | ✅ `VidaraSo()` | ✅ `206 application/vnd.apple.mpegurl` |
| `content.st` | 1 page | ❌ none | `403 "Please turn off your VPN or proxy to watch this video"` → IP-gated, **Suspected** |

## Stream transcripts (one per host class)

lulustream → tnmr.org HLS (embed 301s to `lplaygo.com`, packed jwplayer config unpacks to
a tokenised master):

```
$ GET https://lulustream.com/e/n11d7oqjbpnv        → 301 location: https://lplaygo.com/e/n11d7oqjbpnv
$ GET https://lplaygo.com/e/n11d7oqjbpnv           → 200, eval(function(p,a,c,k,e,d)…) with "m3u8"
  unpacked: jwplayer("vplayer").setup({sources:[{file:"https://8VvLiO0Nv6Xr.tnmr.org/hls2/03/04757/n11d7oqjbpnv_h/master.m3u8?t=…&e=28800&…"}]…
$ GET <that master.m3u8>                           → 200 application/vnd.apple.mpegurl
  #EXT-X-STREAM-INF:…RESOLUTION=1280x720…
```

mixdrop.ag → mxdrop.click → packed `MDCore.wurl`:

```
$ GET https://mixdrop.ag/e/k00grww9u7xg6o          → 302 → https://mxdrop.click/e/k00grww9u7xg6o (200)
  unpacked: MDCore.wurl="//ebij8ni1d.mxcontent.net/v2/k00grww9u7xg6o.mp4?s=…&e=…"
$ GET <that mp4> with Range: bytes=0-64            → 206 video/mp4 (ftyp… body)
```

vidara.so (the provider's own default host since #657):

```
$ POST https://vidara.so/api/stream  {"filecode":"Y9BD6NEsthfq5wq1eF4HKcquqg1UoMRB","device":"web"}
  → {"streaming_url":"https://s6-s501740.97bf1.com/hls/<…>/master.m3u8?token=…"}
$ GET <that master.m3u8>                           → 206 application/vnd.apple.mpegurl
```

dood/playmogo (the host class behind the 2 `Suspected` rows):

```
$ GET https://playmogo.com/e/g73dyikrihl2          → 200 (5,317 bytes),
    <title>BDSM Hookup - DoodStream.com</title>,
    <script src="//challenges.cloudflare.com/turnstile/v0/api.js">, .captcha-player CSS,
    no /pass_md5/<expiry>/<token> anywhere in the body
```

### Row scope of that capture (repair round 1 correction)

The block above is the dood/playmogo shell this runner IP gets, and its `<title>` names
*BDSM Hookup* — the `bdsm-hookup` page, a **`pass`** row of the `old` slot that carries a dood
value alongside its lulu + mixdrop ones. It is a **host-class** capture, not the per-row
transcript the D4 rule asks of a non-`pass` row: the run did not retain the embed codes of
the two `Suspected` rows (`love-drenched-closeness-returns-…`,
`swedish-erotica-0353-soft-sell`). Stated instead of implied — **the per-row transcripts for
the two `Suspected` rows are missing from this report.**

Repair round 1 tried to close the gap and could not. `mangoporn.net` now answers 403 to
every HTML *and* JSON surface from this runner IP (the implement run got 200 with a
`Referer`; only search needed it then), so neither video page can be re-fetched for its
`data-servers` payload and the embed codes are not derivable from any other surface:

```
$ GET https://mangoporn.net/movies/swedish-erotica-0353-soft-sell/     → 403 (3,291 bytes, br)
$ GET https://mangoporn.net/movies/genre/anal/                         → 403 (the listing it came from)
$ GET https://mangoporn.net/                                           → 403
$ GET 'https://mangoporn.net/wp-json/turbosearch/v1/suggest?q=swedish' → 403 (the D2 JSON surface)
$ GET https://playmogo.com/e/g73dyikrihl2                              → 200 (embed host still reachable;
                                                                          the shell above reproduces)
```

Verdict scope: both rows stay **`Suspected`** — the escalation ladder permits nothing stronger
(challenge from the datacenter probe IP, `escalation_tier: unavailable`) and nothing weaker is
warranted, since the host *is* a registered Dood adapter and the observation is the same shell
reproduced above. What is missing is evidence strength, not the verdict's direction. The next
run that can reach the site should capture each row's `data-servers` payload and playmogo embed
before re-asserting the row verdict.

## Load-surface spot checks (same pages, no drift)

Needed because `loadLinks` is fed by this page and `verify.sh` asserts these fields:

- title `span.bc__c` (breadcrumb) — present 12/12; `h1.vid__t` no longer renders (dead
  fallback, backlog note)
- poster: JSON-LD `VideoObject.thumbnailUrl` **array form** + `meta[property=og:image]`
  agree (card `?resize=360,540` vs load `?resize=720,1080` — same artwork)
- duration: JSON-LD ISO-8601 (`PT2H19M`, `PT18M`, `PT29M`, `PT48M`)
- plot: `div.desc p` — present 11/12; `teen-bliss` has **no** `div.desc` at all (site-side
  content gap, not drift) → excluded from any plot all-or-none claim
- actors `div.row--stars a.chip--star` 2–5 per page, tags `a[href*=/genre/]` 18–35,
  year `a[href*=/year/]` present 12/12
- related `section.sec article.card` = 11 cards on every page
- quick search: `/wp-json/turbosearch/v1/suggest?q=bdsm` → 200 (JSON surface; unit-tested
  in `MangopornParseTest`, never fed to the HTML harness)

## Instrument notes (why the transcripts look odd; none of this is site drift)

1. **Search needs browser-shaped headers from this IP.** `GET /page/1/?s=bdsm` returns a
   787-byte `403 Forbidden` with UA only, and 200 with `Referer: https://mangoporn.net/`.
   Datacenter-IP WAF class → `Suspected`, never a finding; the app's own request path is
   unaffected on a residential IP.
2. **tnmr.org (lulu CDN) 403s an `Accept-Language: en-US,…` header** — reproducible 3/3,
   200 without it, so the verify run passes no `Accept-Language`.
3. **mxcontent (mixdrop CDN) 403s a publisher `Referer`** (hotlink rule) and 403s bursts;
   spaced requests serve 206. The passing verify run therefore uses lulu + vidara streams
   (both accept the publisher referer) and keeps mixdrop evidence in this file.
4. **UA-sensitive CDNs:** the verify harness default UA (Firefox 130/Linux) is refused by
   tnmr.org; the run passes `--user-agent` with the shared `FIREFOX_UA` (144/Win), i.e. the
   provider's own runtime UA.

---

## Findings lifecycle — dedup, and why this run changes no code

`gh issue list --search Mangoporn|vinovo|peeplayer|content.st --state all` returns one
standing issue for the confirmed condition:

> **#1512 [OPEN]** *PandaMovies: three embed hosts in the data-servers rotation have no
> shared/ adapter (peeplayer.com, vinovo.to, content.st)* — filed 2026-11-22 by the S9
> video-source audit (#1447), `status/queued`, `triage/from-agent`.

Both providers are the same PandaMovies-skeleton family and serve the same `data-servers`
rotation, so this is **the same condition**, not a new one:

- a standing issue exists → this run's evidence goes there as a comment, never a duplicate
  issue (audit-providers Phase 3, rule 2);
- the fix rides that story, not this one — #1512 already defines what a fix needs
  (a bespoke PeeStream WebCrypto adapter, a vinovo reCAPTCHA-session decision, a
  non-datacenter re-probe of content.st), and `shared/` fixes are fleet-wide scope that
  must not be pre-empted by one provider's audit lane (scope discipline);
- nothing on the video-source leg is *broken*: 10/12 sampled videos play end-to-end through
  registered hosts, so `drift_confirmed` is not incremented and no `Blocked` is recorded.

**New evidence contributed to #1512 from this run:** the framework jar already ships
`com.lagradost.cloudstream3.extractors.VinovoTo` with `mainUrl = "https://vinovo.to"`, and
the live page carries exactly its parse targets (`meta[name=token]`,
`meta[name="file_code"]`, `meta[name=recaptcha]`, `video[data-base]`) — i.e. vinovo.to may
need a **registry row**, not a hand-written adapter (the recaptcha hop goes through
`APIHolder.getCaptchaToken`, app-side). Mangoporn's 4/10 sample and PandaMovies' 5/15 agree.

---

## Delivery gate

Repair round 1 touched this document only (no provider, `shared/` or registry change), so the
build gates below stand as the implement run recorded them; `check-findings.sh` was re-run on
the corrected text.

- `./gradlew Mangoporn:test` → BUILD SUCCESSFUL (provider + shared suites)
- `./gradlew Mangoporn:make` → BUILD SUCCESSFUL → `Mangoporn/build/Mangoporn.cs3`
- `.pi/skills/audit-providers/scripts/check-findings.sh` → `findings-lint: PASS`
  (registry untouched in the diff, ADR-0013)
- `.pi/skills/verify-provider/scripts/verify.sh` (live, plain-curl + shared `FIREFOX_UA`) →
  **RESULT: PASS**: search 27 cards; home p1 38 / p2 27 (extraction 27/27, p1⊥p2);
  5/5 video pages; **5/5 streams** (`200 application/vnd.apple.mpegurl` ×4,
  `206 application/vnd.apple.mpegurl` ×1); related 11/11 titled; tags/actors/year on 5/5;
  search↔load agreement exercised (`?s=bdsm` → `bdsm-hookup`, title match + poster-variant
  NOTE); LoadResponse 7/7 populated.

## Backlog (not fixed here)

- `Mangoporn.getMainPage` still maps the `movies` row to `/page/N/` and its comment still
  claims `/movies/` is 404 (it healed: 200, 27 cards, 4,583+ pages). Listing/row surface,
  outside the video-source leg.
- `/movies/` archive announces 4,560 pages while 5,000 is the first 404.
- `load()`'s `h1.vid__t` fallback title carries `"Now Playing: "` glue and the element no
  longer renders — dead fallback, breadcrumb is primary.
- `teen-bliss` (and similar) publish no synopsis → `plot` null is correct behaviour.

## Registry deltas (posted as a comment on #1461, never in the diff — ADR-0013)

`verdict: ok` (was `ok-drift`), `tier: plain-curl (lulu/vidara HLS + mixdrop mp4;
dood Turnstile not runner-resolvable)`, `drift_confirmed: 3` (unchanged),
`standing_issue: 1512`, `last_deep: 2026-11-25`, plus one `last_runs` entry
(`false_positives: []`, `escalation_tier: unavailable`).

**Anchor status (repair round 1).** The implement run's delta comment on #1461 did not begin
with `<!-- findings-registry-entry -->`, so it was unanchored. Nothing is owed by this repair:
the orchestrator's `_registry_candidate` self-heal (#777) re-posted the entry *anchored* on
#1461 at 2026-11-25T19:15:55Z, and `registry_sync` landed it in `audits/findings.json` via
**#1620** (merged 2026-11-25T19:24:07Z, commit `c54511e`). The registry is therefore current
without a further comment from this lane; the lesson recorded for future implement runs is to
open the delta comment with the anchor so the self-heal does not have to.
