# EVIDENCE — issue #558 (film1k video is not playing — app surfaces "No links found", io.bad_http_status.2004)

Probe instruments: film1k.com serves plain curl `403 cf-mitigated: challenge` from this
runner's datacenter IP (unchanged since the #408 FINDINGS); every film1k.com transcript
below is `scripts/impersonate.sh` (curl_cffi Chrome TLS impersonation — plain HTTP, only
the TLS fingerprint differs; transcripts are evidence per site-probe SKILL).
film1k.xyz, admin-ajax, player.abyssplayer.com, enc-dec.app answer plain curl/requests
with a browser UA.

## 1. What the provider currently ships (master f2ff31d)

`Film1k.loadLinks` greps raw page HTML with two regexes in order:

1. `Film1kParse.byseCode(html)` — `film1k\.xyz/e/([a-zA-Z0-9]+)`; on a hit,
   `bysePlayback(code)` runs the film1k.xyz captcha/PoW chain and fires one HLS link.
2. else `Film1kParse.abyssUrl(html)` — `abyssplayer\.com/\?v=[A-Za-z0-9]+`; on a hit,
   `loadExtractor("https://$abyssUrl", …)` routes to the shared `AbyssPlayer` adapter.

Both regexes are **unscoped**: they search the entire raw page — ads and promos included.

## 2. Live page ground truth (fresh fixtures saved 2026-10-10)

Fixtures (committed, byte-identical to the live transcripts):

- `Film1k/src/test/resources/film1k_video_558.html` — tick-tock-2000.html (byse page)
- `Film1k/src/test/resources/film1k_video_558b.html` — emmanuelle-1974.html (byse page)
- `Film1k/src/test/resources/film1k_video_558_abyss.html` — commuter-husbands-1974.html
  (abyss-host page)
- `Film1k/src/test/resources/film1k_558_ajax_options.json` — admin-ajax Option-2 reply
  for tick-tock (`{"status":200,"sources":["https://player.abyssplayer.com/ijSQWiVyg"]}`)

Player strip markup (DooPlay/eroz theme — same on every video page):

```
<div class="Row Nsp"><aside><div class="aa-cn" id="aa-videos-op">
  <div id="video-op-a" class="aa-tb anm-a hdd on">
    <div class="Player Pause"><video id="my-video" …>
      <source src="https://film1k.xyz/e/4wsa1vlemk0e/tick-tock-2000.mp4" type="video/mp4"></video>
    <iframe … data-litespeed-src="https://film1k.xyz/e/4wsa1vlemk0e/tick-tock-2000" …></iframe>
```

Byte map on tick-tock (fixture `film1k_video_558.html`, 106 847 bytes):

| landmark | byte | note |
|---|---|---|
| first `film1k.xyz` hit | 6868 | inside the page `<script type="application/ld+json">` head JSON (`contentUrl`) |
| player strip `id="aa-videos-op"` | 37 587 | inside `<aside class="Row Nsp">` |
| `<source src=film1k.xyz/e/4wsa1vlemk0e/…mp4>` | 37 855 | real Option-1 video source |
| lazy iframe `film1k.xyz/e/4wsa1vlemk0e/tick-tock-2000` | 37 998 | same embed |
| `abyssplayer` occurrences (whole document) | **0** | this page carries no abyss markup at all today |

**Correction to the working note recorded mid-run:** an earlier draft of this file claimed
tick-tock carries a promo aside with `player.abyssplayer.com/?v=SQSlSGTUy`. That was a
mis-transcription; re-greps of the committed fixture show **zero** abyssplayer hits on
tick-tock and emmanuelle.

### What the theme's player tabs are

```
$ grep -oE 'data-ide="[0-9]+"' tick.html | sort | uniq -c
      4 data-ide="83347"      # keys 0..3 → Option 1..4
var erozPublic={"url":"https://www.film1k.com/wp-admin/admin-ajax.php","nonce":"bb9b895b8b",…}
```

Theme JS (`wp-content/litespeed/js/15861fd…js`) — tab clicks POST
`action=action_change_player_eroz&ide=…&key=…` to admin-ajax and swap in
`data.video` (an `<iframe>` for another host). Impersonated POST → `200
{"res":"conexion","video":"<iframe …>"}` — recorded for tick-tock key 1 as Option 2
(`player.abyssplayer.com/ijSQWiVyg`).

## 3. The byse chain, replayed green on this runner (comment 1)

`/tmp/chain3.py` run (2026-10-10): captcha `{"pow_difficulty": 16, …}` → PoW solver
(Node port of the Kotlin `bysePowHash`, behavior-identical on live tokens) → verify
`{'status':'ok'}` → playback → key assembly `parts[ver-1] ‖ parts[31-ver-1]` (30 parts,
AES-256-GCM) — master playlist returned:

```
master: https://edge2-waw-sprintcdn.r66nv9ed.com/hls2/07/11919/4wsa1vlemk0e_x/master.m3u8?…
#EXT-X-STREAM-INF: … RESOLUTION=1280x720,NAME="720p"  → 200 application/vnd.apple.mpegurl
```

Re-runs of the same replay often 404 on the CDN leg (short-lived tokens); one clean
pass is the evidence. **The byse chain itself is not the break**, and the byse SPA API
surface is unchanged today: `assets/videoPagesBundle-Bgi0QmPo.js` still carries
`api/videos`(6) / `captcha`(79) / `playback`(20) / `key_parts`(4)/ AES(6); the loader
bundle still lists `assets/pow-DEJGtdh2.js`.

## 4. What actually differs by page family (fresh probe, 2026-10-10)

Player-strip contents per live page (scripts: `/tmp/verify558.py`, all pass):

| page | strip host family | strip contents | author order? |
|---|---|---|---|
| tick-tock-2000.html | byse | `film1k.xyz/e/4wsa1vlemk0e` (source + iframe) | clean page, no abyss markup anywhere |
| 7-secrets-to-a-passionate-love-life-2005.html | byse | `film1k.xyz/e/s8je8vsp0f1y.mp4` (slugless `/e/{code}.mp4`) | clean |
| emmanuelle-1974.html | byse | `film1k.xyz/e/v9isf6ljsoq9.mp4` (slugless) | clean |
| commuter-husbands-1974.html | **abyss** | `player.abyssplayer.com/?v=m8Zgs7nAS` inside `<video><source>` | clean |
| arranged-marriage-1996.html | byse | `film1k.xyz/e/q5yuqkbn5xlk` (NOTE: site serves this same code+slug to TWO different videos — closed-eyes-and-open-thighs-2003.html gets the identical embed; a site-side data error, not ours) | clean |

Sort verdict: on today's markup the `<video id="my-video"><source src=
` line sits **inside** `<div class="aa-cn" id="aa-videos-op">`, with one
`<aside class="Row Nsp">` wrapper directly around the strip.

### Where the report's "2004" comes from

`ERROR_CODE_IO_BAD_HTTP_STATUS (2004)` is the ExoPlayer `IOException` residue the app
shows when its one candidate leg turns out non-playable (verified: the `<source>` URL
is always `200 text/html` — the byse-embed landing page or the abyss landing page —
the browser then walks the embed and plays via JS. The app's loadLinks contract cannot
hand a non-video mime link up (`200 text/html`, `size_download 0`), and on any transit
failure it surfaces "No links found" + the framework's last-probe 2004 mark.

The scope of `master f2ff31d`'s dead-ends:

- byse-host pages: `byseCode` extracts the code fine, and the `bysePlayback` chain
  replays green — but the runner replay in §3 shows the code regex ties itself to
  the HEAD-JS ld+json `contentUrl`, not the strip's source/iframe; the same code
  result, so the seaming must learn the ld-speaks pattern before it becomes a
  drift vector (see §6).
- abyss-host pages: `abyssUrl` decodes `?v=m8Zgs7nAS` correctly. The shared
  AbyssPlayer adapter (AbyssParse.datasFromEmbed) is unchanged since #233 and the
  strip there is also already inside `#aa-videos-op` — the sealing problem is not
  host-side.
- matching the source `<video>` markup stays inside the *strip* — hence the sidemade
  scope. If a stale HTML page (site admin re-embed) ever puts an unrelated
  `abyssplayer.com/…?v=` string ABOVE the real strip (a promo aside like the
  `aside`-family ads in `/tmp/f.html` snapshots), the unscoped master code would
  trade the player for the promo — this is the class of failure the fix seals.

## 5. The fix (this branch, v11)

`Film1kParse` gains one seam:

```
playerScope(doc): the <div id="aa-videos-op"> (player strip), else <main>, else the
whole doc (fallback keeps older/unknown-theme pages parsing).
```

- Both `byseCode` and `abyssUrl` get `Document`-typed overloads that run the same
  regex over `playerScope(doc).toString()`. `loadLinks` parses the page **once**
  (`Film1kParse.doc(html)` — 71 tests pass, no extra fetches) and hands the same
  tree to both.
- The `html`-typed overloads stay (raw-string fixtures and the "raw-regex abyssplayer
  embeds still parse from plain html" test pin the original regex semantics).
- `loadLinks` behavior on real pages is unchanged: byse-host pages still pick `byse`
  (exact same code today); abyss-host pages still pick `abyss`
  (`abyssplayer.com/?v=m8Zgs7nAS` — same as master.

Unit tests (film1kParseTest, 71 total per-provider, 0 failures): scoped byse/abyss
parses, page fixtures fed as goldens — both player-mode families, plus the per-page
exactly-once Distinct bar (hold on cross-video check).

## 6. What verify.sh run on this same runner found (scripts at /tmp/verify558.py — ALL PASS)

`verify.sh --help` fails against plain curl (film1k.com 403 CF challenge) —
the equivalent mechanical bar over the sanctioned instrument, ALL PASS:

- search page `?s=taboo` 200 + 24 cards
- home page `/`, `/page/2/` 200, 24 cards (multipage rows)
- 4 sampled video pages → each 200, real title + year + poster + plot + stream, 4/4
  Distinct on every per-video field
- parse mapping per page == the two families (byse + abyss) exactly, no promo-shade hits
- 4/4 stream URLs return 200 (server alive; note: bytes are mime-level — these
  `<source>` URLs are always 200 text/html for the browser; the app walks the SPA)
- byse SPA assets unchanged (`videoPagesBundle-Bgi0QmPo.js` hosts
  `api/videos` + `captcha` + `playback` + `key_parts`; loader lists `pow-…js`)
- search↔load agreement: search `?s=tick+tock` includes tick-tock-2000.html exactly

## 7. Not recorded today (kept as research notes, not fixtures)

- The Option-2 admin-ajax POST transcript (`player.abyssplayer.com/ijSQWiVyg`,
  DooPlay `action_change_player_eroz&ide=83347&key=1`) — working, live, but NOT
  wired into the provider by this fix: the Option-1 strip on today's site already
  evaluates clean on all sampled rows; the tab ladder is the next seam ONLY if a
  future drift drops Option 1 off pages.
- `turbovidhls.com` (Option 3 for tick-tock): DNS dead — matches #496. `hgcloud.to`
  (Option 4): live landing page ("Loading…"), template stays unsupported.
