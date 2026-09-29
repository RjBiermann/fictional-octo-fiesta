# FINDINGS-487 — cat3film.com "video doesn't play" (issue #487)

Reported URL: `https://cat3film.com/watch/summer-affair?sv=1&part=1` — video doesn't play.

Probe date: 2026-09-28 (issue run). Machine: plain curl + okhttp/mobile UAs as noted.

## Chain re-probe (the provider's exact chain, live)

| Step | URL / call | Result |
|---|---|---|
| detail | `GET /summer-affair` | 200; `h1.info-title` "Summer Affair"; badges `HD, Movie, EN Sub` → `Parse.movieTypeTag` = "Movie" |
| watch | `GET /watch/summer-affair?sv=1&part=1` | 200; single `.wserver` ("Watch Online") → `.season-pane .epbtn[data-ep="1798"]`, `data-no=1`, `data-name="Full"` |
| sources | `GET /api/v1/episodes/1798/sources` (Referer `https://cat3film.com/`) | 200 JSON (shape below) |
| playlist | `GET <token>/index.m3u8` | 200 `application/vnd.apple.mpegurl`, clean `#EXTM3U` VOD: 1377 × `#EXTINF` (+`#EXT-X-ENDLIST`), no `KEY`/DRM |
| segment | `GET player.abyssssss.top/.../seg-1-v1-a1.jpg` (range) | 206 image/jpeg — real video bytes |

Sources API response (identical shape for ep 1765/452/886/405/307 re-probes):
```
{"backup":"https://player.abyssplayer.com/yXQfibqVK",
 "sources":[{"file":"https://abyssssss.top/S6VL1N0SPDPy-I37I2vHD0ReltNU95XfMAU3vw9YmH8","type":"hls"}],
 "subs":[],"success":true}
```

UA matrix (detail/watch/sources/m3u8 all 200, challenge HTML seen 0 times):
desktop Chrome UA, `okhttp/4.9.2`, `Dalvik/2.1.0 (Linux; U; Android 13)`, Android Chrome mobile UA — with and without Referer. **No server-side drift anywhere on the provider's chain.** No reproduction of zero-links from server state on this runner as of today.

`/index.json` (what the site serves to desktop/Android UAs per site.js) serves the **same playlist body** as `/index.m3u8` — suffix choice is not the break.

Cross-check: 12 fresh ep ids from home page 1 (1788–1799) + 1765/452/886/405/307 → `sources` non-empty + `backup` present on **all 17**. The "sources empty" state is not observable from this runner today.

## What the site's own player does that the provider doesn't (the finding)

Watch page inline config: `window.__playerOpts = {..., "backupAuto":true, "backupServer":true, ...}`.
`/static/js/site.js` (fetched live this run):

- `fetchSources(epId)` = the same `/api/v1/episodes/{id}/sources` the provider calls.
- `backupURL = (d && d.backup) || ""` — the JSON's top-level `backup` embed is a **first-class route** the site treats as its auto-fallback:
  - `if (!srcs.length || !srcs[0].file) { if (backupURL) { showBackup("This episode is only available on the backup server."); return; } ... }` — backup-only episodes are a real state the site covers.
  - `jw.on("error", e => { ...; if (fallbackToBackup("jw error")) return; })` and `vid.addEventListener("error", ... if (fallbackToBackup(...)) return)` — a main source that present-fails at playback also falls back automatically.
  - Comment block at site.js:685: the mirror exists precisely because "the box down, segments 403, an ISP throttling that host" — i.e. the failing state #487 names.

The provider's `loadLinks` (v11) reads **only** `sources[].file`; `backup` is never parsed → no second route → any of the site's fallback conditions = zero playable links = "video doesn't play".

## Backup route extraction (proven live)

`backup` is a **SoTrym/abyssplayer embed**: `GET https://player.abyssplayer.com/yXQfibqVK` → 200 HTML with
`const datas = "<base64 config>"`. Shared adapter `AbyssPlayer` (HostAdapters.kt, issue #233) handles it:
POSTs the blob to `https://enc-dec.app/api/dec-abyss` →
```
{"status":200,"result":{"sources":[
  {"url":"https://xrfcm3jr936.sssrr.org/sora/534418915/<tok>","size":534418915,"type":"480p","codec":"h264","status":true},
  {"url":"https://ygfi0yfyc10.sssrr.org/sora/1050392394/<tok>","size":1050392394,"type":"720p","codec":"h264","status":true},
  {"url":"https://o32l05c213.sssrr.org/sora/2077804816/<tok>","size":2077804816,"type":"1080p","codec":"h264","status":true}]}}
```
Direct GET of a sora URL from the runner: **403 "Attention Required! | Cloudflare"** (datacenter-IP
block, not geo/UA — `Accept-Encoding`, `Range`, browser UA all rejected from this IP).
So the abyssplayer mirror is hydrax-host-gated: verified working per its site-of-record flow in
issue #233, but unreachable from this runner's IP today. Recording as such — the fallback link is
emitted for in-app devices to use, not verified media-streaming from the probe box.

`AbyssPlayer()` is already registered repo-globally (`registerHostExtractors()`; Cat3Film's plugin
`load()` calls it), so emitting the backup from `loadLinks` costs one `loadExtractor(...)` call
(Cat3Movie precedent) — no new extractor.

## Verify.sh fresh run (same session, before fix)

- home `/movies` + `?page=2`: 200, 30 cards each — page-disjoint PASS.
- video pages (summer-affair, the-handmaiden, hache, impregnation-nation, russian-lolita):
  200, title + `section#related a.card` (4 recs) on all 5; `.info-people` blocks with
  Genre:/Cast:/Director: links live-confirmed present (verify.sh NOTE on `.info-people a` is a
  script artifact: its simple-selector engine takes the first bare-`a` block on the page — the
  header logo — not site drift; findability shown by `.info-people` grep on all fetched pages).
- search `/_ajax/search?q=handmaiden`: 200 valid JSON, 1 hit (`the-handmaiden`) — the tool's HTML
  card check again fails on the JSON search surface (recorded FAIL-by-tool shape, FINDINGS.md #292
  run precedent: same endpoint, same conclusion). Selector fields it flags NOTE on:
  `.info-people a` / `span.duration` — verify.sh's simple-selector engine takes the first
  bare-`a` block on the page (the header logo) and cannot compile a leading-class chain, so
  exposure must be explicit here instead: tags (genres) and actors ARE exposed on all probed
  detail pages (`.info-people` blocks with `ip-label` Genre:/Cast: and `<a>` links — live
  grep on /summer-affair + the 4 other sampled pages this run), duration IS exposed via the
  ld+json first block (`PT94M`-style ISO-8601, JsonLdParse.minutes in provider code).
- positional per-chain `--stream-url` (sources-API → `/index.m3u8`) 200
  `application/vnd.apple.mpegurl`.

## Drift ledger

Closed broken provider/drift issues for Cat3Film: #410 (silent no-links, CF path), #416 (movie
type), #487 (no second route). Third recurrence → per AGENTS.md this run ships the hardening fix
(multi-host fallback), not another point swap.

## Fix decision (minimal, evidence-driven)

`loadLinks` additionally parses the top-level `backup` embed and emits it **after** the main
sources via `loadExtractor(backup, "$mainUrl/", ...)` (shared AbyssPlayer adapter already
registered). Ordering keeps the direct token route first (it is the faster/primary one); the
second entry reproduces the site's own "second route" that CloudStream users pick (or retry)
when the first fails. `Parse` gains a `backupUrl(json)`-style pure helper, TDD'd red → green.

## 2026-09-29 restore-run verification (repair round — dd8a8b3 content recovered)

The branch's working tree had lost the spec deliverable (only 3 of dd8a8b3's 4 files, no
FINDINGS-487.md, no version bump, no tests). This repair restored the dd8a8b3 content on
`devloop/issue-487`: `Parse.backupEmbed` + second `loadExtractor` route, v11→12, fixture tests
(12/12), and this document. `./gradlew Cat3Film:test Cat3Film:make` clean; Cat3FilmParseTest
12/12 green, `Cat3Film.cs3` produced from the restored tree.

Live re-probe, same day:
- Detail pages `/summer-affair|the-handmaiden|hache|impregnation-nation|russian-lolita`: 200;
  `h1.info-title`, `og:image`, `og:description` present on all 5; `section#related a.card`
  = 4 recs on all 5 (note: dd8a8b3's earlier "section#related a.card matches: -1" row was the
  tool's selector-compile artifact — `-1` — re-verified as 4 here).
- `/watch/summer-affair?sv=1&part=1` (issue page) serves the `.wserver`/`season-pane`
  (`data-ep="1798"`) but currently renders no related section and an empty `og:title` — the
  detail page is where both live; the provider resolves data from the ep id, so playback is
  unaffected.
- Sources chain refs: 17/17 non-empty; ago ep ids 1798/405/449/886/307 fresh: `sources[0].file`
  + `backup` both live. Token playlist → `/index.m3u8` 200 `application/vnd.apple.mpegurl`
  on all 5 (5 distinct token paths, cross-video Distinct ✓).
- Backup embed `player.abyssplayer.com/yXQfibqVK`: curl_cffi 200, `const datas` blob cached,
  `dec-abyss` 200 → 3 direct sora URLs — all 3 404 from the probe box today (rotating-tunnel
  TTL expires; FINDINGS's #233 verified the flow live). Response cache path recorded as
  evidence, not a guessed URL.

Verify.sh transcript (same session, after restore): equal to the pre-fix record —
- check 1 search: FAIL-by-tool — the only search surface is the AJAX JSON endpoint
  `/_ajax/search?q=`; verify.sh asserts HTML card selectors, so no card can exist. Site shape,
  not a provider defect (find through curl: `?q=affair+` 0 result refs, `?q=handmaiden` 1 →
  `/the-handmaiden`).
- check 1a home `/movies` (+p2) and `/tv-series` (+p2): 200, 30/30/14/0 cards on `/movies`+p1,
  `/movies`+p2, `/tv-series`+p1, `/tv-series`+p2 — no duplicates within/across pages. The
  `/tv-series` p2 empty row is the same single-page listing shape (the `/tv-series` row has
  14 items total); home cards page-jump p2 → FAIL row while `/movies` p2 PASSes — matches
  the transcript both pre-fix and post-fix.
- check 2 video pages: 200 + title on all 5; `--stream-selector` FAIL row: the stream URLs are
  XHR-built (the provider itself fetches `/api/v1/episodes/{id}/sources`); positional
  `--stream-url` overrides carry the stream proof instead (5×200 m3u8 above).
- check 4 related: 4 rec titles, distinct, non-self on all 5 detail pages.
- check 5 agreement: handmaiden JSON-search result ↔ `/the-handmaiden` load page — same title
  "The Handmaiden" and same poster (curl re-proof in this session).
- check 6: `recommendations, tags, plot, duration, year, actors` all have assignments in
  Cat3Film.kt.
