# FINDINGS-496 — Film1k: turbovid embed family DNS-dead

Issue: #496 (ai-fix, 2026-10-01). Fleet-audit finding from #494, verified live this run.

## DNS cross-check — the embed host family is NODATA

Four instruments agree; MX still answers (deliberate removal, not a resolver blip):

| probe | host | result |
|---|---|---|
| `dig +short … A` | turbovidhls.com / turboviplay.com / turbosplayer.com / cdn{1,3,5}.turboviplay.com | empty (no A) |
| Google DoH `dns.google/resolve?…type=A` | turbovidhls.com | `Status:0` NODATA, SOA only in Authority |
| Cloudflare DoH `cloudflare-dns.com/dns-query?…type=A` | turbovidhls.com | NODATA (SOA only) |
| curl_cffi chrome impersonation | `https://turbovidhls.com/t/694ec0c39ccbb` | raised `DNSError` |
| Google DoH type=MX | turbovidhls.com / turboviplay.com | MX answers (`eforward*.registrar-servers.com` / `mx2.privateemail.com`) |
| control | film1k.xyz | A: 104.21.63.28, 172.67.142.169 — alive |

## Live video pages (TLS-impersonated, chrome fingerprint)

| page | HTTP | embeds on page |
|---|---|---|
| tarzan-x-shame-of-jane-1994.html | 200 | `turbovidhls.com/t/694ec0c39ccbb` only (source + lazy iframe) |
| malabimba-1979.html | 200 | `turbovidhls.com/t/6aba0a9b1778a` only |
| the-true-story-of-the-nun-of-monza-1980.html | 200 | `turbovidhls.com/t/6aba095c3e2ab` only |
| boca-1994.html | 200 | `film1k.xyz/e/u5iq5hndmnok` only |
| tiger-claws-ii-1996.html | 200 | `film1k.xyz/e/3h23qfuj8hbg` only |
| taboo-1980.html | 200 | `abyssplayer.com/?v=LTjOBeDQK` only (re-probed at fix-verify: no byse embed on this page — an earlier probe note saying "byse + abyss" was wrong) |

Main site surfaces healthy this run: home page 1/2, search (`?s=`), category pages all 200
with `article.loop-post` cards (TLS tier; plain curl still gets the usual 403 challenge).
No `abyssplayer.com/?v=` seen on the sampled turbovid-only pages — the fall-through below it
is moot for them, but harmless.

## Site surfaces (re-checked for verify)

- Homepage: `https://www.film1k.com/` + `/page/2` — both 200, `article.loop-post` cards.
- Search: `https://www.film1k.com/?s={q}` — 200, ≥1 card for tarzan/taboo (page>1 returns
  empty since #408; `/page/2` after a 200-status fetch yields zero loop-post cards locally —
  pattern held: verify uses page-1 only for search).
- Related: `article.loop-post` block under the Related Videos header, present on probed pages
  (20 cards on tarzan-x).
- og: fields intact on every probed page (title/image/description); runtime/genres/actors
  markup unchanged.

## Stream sources

- turbovidhls path: embed fetch is impossible — DNS gone, nothing to request, no stream.
  _This ceiling is deliberate: score it dead and fall through, never raise._
- byse path (film1k.xyz): alive (embed URL 200).

## Risks / blockers

- Embed-host loss is a permanent-looking condition (NODATA across resolvers, MX-only family).
  Videos whose only source is turbovidhls have zero reachable streams; provider surface
  (home/search/load/related) unaffected.
- Run from US IP (GitHub Actions runner region), chrome TLS fingerprint for site surfaces.

## Post-merge re-verification (2026-09-30 follow-up run)

Issue closed via PR #500 (merged 2026-09-30); this run re-proved the live condition against
the current state rather than assuming it:

- **turbovid family still DNS-dead.** Google DoH `type=A` on turbovidhls.com → `Status:0`
  NODATA (SOA only in Authority); cdn1.turboviplay.com → `Status:3` NXDOMAIN with SOA;
  turbosplayer.com → NODATA same shape. turbovid family is still gone, not resurrected.
- **main site healthy on the TLS-impersonated tier** (curl_cffi chrome 0.15): home `/?s=`
  tarzan video page → 200 each; still plain-curl 403 with `cf-mitigated: challenge`
  (fingerprint wall, UA-independent).
- **tarzan-x page unchanged**: only `<source src="https://turbovidhls.com/t/694ec0c39ccbb.mp4">`
  + lazy iframe — zero reachable streams, the reported condition, handled by the shipped
  clean fall-through. No byse/abyss embed on the page.
- **load() fields intact** on the turbovid-only page: `h1.title`, og:title/image/description,
  `strong:containsOwn(Runtime/Genres/Actors)` adjacency, Related Videos section inside
  `<main><section class="Eroz-Thumbs List">` with `article.loop-post` cards.
- **byse chain end-to-end live again**: first fresh captcha fetch → `pow_difficulty: 16`;
  the PoW was re-solved in C with the golden-parity instrument (`/tmp/bysepow` matched the
  pinned `BysePowTest` solutions "19" (deadbeef,12) and "100367" (abc123,16) bit-for-bit
  before touching the live challenge); verify → token ok; playback → AES-256-GCM envelope;
  key = parts[v-1]+parts[31-v-1] (v=7, 30-element list) decrypted; source 480p master
  `edge2-waw-sprintcdn.r66nv9ed.com/.../master.m3u8?...` → HTTP 200
  `application/vnd.apple.mpegurl` with a real `#EXT-X-STREAM-INF` playlist.
- **Film1k (+ shared-regression suite)**: `gradlew Film1k:test Film1k:make` green after
  `bootstrapCloudstream` (fresh checkout needed it); plugin builds `Film1k.cs3`, version 9
  (no provider change this run → no bump).

No drift found; this run ships no code change — the merged fix remains current.

## Fix direction (shipped — branch deletion + guarded page fetch)

1. The turbovid branch's `app.get` on the dead host could only ever throw (DNS) — and it
   hardcoded turbovidhls.com, so it could not produce a stream for any matched page even
   while alive. Branch deleted together with `turbovidCode`; a turbovid-only page now
   falls through cleanly (`return false`, no stream, no exception). Resurrection recipe
   (fixtures + `turbovidStreamUrl` Parse function + its tests) stays in the repo.
2. The #448-class secondary bug — an uncaught exception out of `loadLinks` when the page
   host itself is unreachable — is closed by guarding the `loadLinks` page fetch with
   try/catch: no stream, not LoadResponse-sourced, skip the ladder cleanly.
3. The abyssplayer branch (issue #233) now goes through the Parse function
   `Film1kParse.abyssUrl` instead of an inline regex; nothing else changed.

## Stream serve evidence (fix-verify run, same day)

- **byse (film1k.xyz) chain, full PoW + AES-GCM re-derivation live** — the PoW was re-solved
  and the playback envelope re-decrypted for five codes (boca, tiger-claws-ii, a-noite-das-taras,
  dead-tides, bare-behind-bars); all five masters served:
  - `edge{1,2}-waw-sprintcdn.r66nv9ed.com` / `edge1-moscow-sprintcdn.owphbf24.com` master.m3u8
    → HTTP 200 `application/vnd.apple.mpegurl` (token TTL ≈15 min, reusable within TTL),
    variant + `.ts` segments → 206 `video/MP2T`.
- **abyss chain (taboo-1980)** — embed + enc-dec.app decode return status:true sources, but
  the sora hosts (`*.sssrr.org`) show token-heat behavior this run: an unreferenced plain GET
  403s, Chrome-TLS GET 404s, and a plain GET with `Referer: https://abyssplayer.com` + any
  sane UA serves 206 `video/mp4` — but only on a URL not touched by a prior impersonated-TLS
  request (first chrome request burns the token; later plain requests 404). Shared-adapter
  mechanics, untouched by this fix; noted as instrument noise, not provider drift.
- **turbovid-only pages** (tarzan-x, malabimba, nun-of-monza): no reachable embed — zero
  streams is the ceiling for those three; the product of the reported condition, expected
  (and now handled as clean fall-through instead of an exception).

## verify.sh run (fix gate) — PASS, exit 0

Instrument: verify.sh cannot fetch film1k.com itself (plain-curl TLS challenge wall). The run
used a curl shim (curl_cffi) that impersonates chrome **only for `film1k.com`** (the walled
host; explicit UA headers are dropped there so the UA matches the impersonated fingerprint)
and passes every other host (CDNs) through plain TLS so their stream tokens are not burned.
All script mechanics (fetch/status/count/dedupe/stream-serve) ran unchanged.

- search `?s=boca` → 200, 17 `article.loop-post` cards, no dupes (search does not paginate —
  recorded above, single page).
- home `/` + `/page/2/` → 200, 24 cards each, no dupes within/across pages.
- 5 video pages (boca / tiger / a-noite / dead-tides / bare-behind-bars — varied listings,
  all byse) → 200, `source[src]` ×1 each, `h1.title` present, year present on all 5.
- 5 `--stream-url` overrides (fresh PoW-derived masters, position-matched) → all 200 m3u8,
  distinct paths, none shared across videos.
- related `article.loop-post` → 20 cards on every sampled page, no empty/self/dup titles.
- search ↔ load agreement: boca matched (no mismatch FAIL).
- `--load-response recommendations,tags,plot,duration,year,actors,posters` → all populated in
  the provider Kotlin.
- NOTEs (expected, per the "never unstated" rule): no distinct quick-search endpoint (the
  site has none — provider `hasQuickSearch` stays false). tags/actors/duration selectors
  omitted: the site exposes them via `strong:containsOwn(Genres/Actors/Runtime)` markup,
  which verify.sh's simple selector language cannot express — exposure is covered by the
  Parse-function unit tests against the live-shaped fixtures instead.
