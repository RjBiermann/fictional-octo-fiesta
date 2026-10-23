# FINDINGS — tube.perverzija.com (2026-09 audit)

## Re-probe for issue #161 fix (2026-02, builder)
- `span.runtime` → 0 matches on the live page (selector dead; issue #161 confirmed).
- JSON-LD `<script type="application/ld+json">` VideoObject exposes `"duration":"PT34M59S"` on the same page.
- Recommendations: `div.srelacionados` gone from the page — no related-videos section exposed anymore; not a data gap.
- Fix: parse ISO-8601 duration from JSON-LD (PT#H#M#S → total minutes) instead of the dead span.
- verify.sh: search 200 ×69 `div.col-md-3`; video page 200, `iframe[src*=xtremestream]` ×1; canned script still cannot do the two-hop stream (sends no Referer, xs1.php 403s) — manual chain evidence: player page 200 → `video_id` → `xs1.php?data=<id>` with player referer → 200 body starting `#EXTM3U` (854x480 variant listed).
- Gradle gate (2026-02): `./gradlew PerverZija:make` BUILD SUCCESSFUL (jitpack issue below no longer reproduces in CI).

## Verdict: BROKEN (extractor domain mismatch)

## Search — OK
- `/?s=red` → 200, `div.col-md-3` ×69, `div.title a` hrefs OK.

## Video page
- `iframe src="https://pervl1.xtremestream.xyz/player/index.php?data=a667f4e7b0c8a3babe331569d3eac6bd"` (pervl1!).

## Stream — extractor not matched
- Provider registers ONLY `PerverZijaExtractor` with `mainUrl=https://pervl2.xtremestream.xyz`. Player iframe serves from **pervl1** → loadExtractor finds no matching extractor → no stream.
- Player page: `m3u8_loader_url = https://pervl1.xtremestream.xyz/player/xs1.php?data=`; xs1.php serves m3u8 **only with referer of the player domain** (`pervl1.xtremestream.xyz/player/index.php` OK, `pervl2...` OK, `tube.perverzija.com` 403, none 403). q=480/720/1080 all 200.

## Fix
Register extractor for both subdomains (pervl1 + pervl2), keep referer on extractor mainUrl.

## Re-probe for issue #99 fix (2026-09, builder)
- Player subdomain VARIES per video: observed pervl1, pervl2, pervl3, pervl6, pervm1, j2 across search results. Registering fixed subdomains is insufficient.
- xs1.php accepts referer from ANY *.xtremestream.xyz/player/index.php (pervl6 stream with pervl1 referer → 200); tube.perverzija.com or none → 403.
- Fix applied: provider calls PerverZijaExtractor().getUrl(iframe, ...) directly in loadLinks (extractor derives link referer from the iframe URL), so any pervlN/pervmN/jN subdomain works. No loadExtractor mainUrl matching needed.

## Reviewer round-1 verification (independent, 2026-09)
- Reproduced: search ×1 (200, `div.col-md-3`), 4+ varied video pages; iframes on pervm1, pervl2, pervl1, j2 subdomains (subdomain set wider than pervlN — confirms fixed-subdomain registration is insufficient).
- Exact code-emitted referer form `https://<sub>.xtremestream.xyz/` (url.substringBefore("/player/")+"/") → xs1.php`q=720` returns 200 `application/vnd.apple.mpegurl` body; no referer → 403. Fix validated.
- Non-video pages (e.g. /2257-exemption-statement/, discord iframe) skipped by the `contains("xtremestream.xyz")` guard — correct.
- verify.sh transcript not produced: canned verify.sh cannot pass here (it sends no Referer on the stream GET, so xs1.php 403s). Manual curl evidence substituted.

## Gradle gate: BLOCKED from CI (environment)
`./gradlew PerverZija:make` fails at root-project configuration: jitpack no longer serves
`com.github.recloudstream:gradle:-SNAPSHOT` (gradle--32895aedb6-1.pom → 404; maven-metadata lists
only `-32895aedb6-1` whose pom is also 404). Failure is repo-wide (root buildscript classpath),
affects baseline master identically — not caused by this change. Needs a root build.gradle.kts
version bump (out of scope for an ai-fix per repo rules).

## Re-probe for issue #211 fix (2026-09-09, builder)
- Tags/actors selectors were case-broken: `strong:contains(tags)` / `strong:contains(stars)` matched 0 on every video page; live markup uses `<strong>Tags: </strong>` / `<strong>Stars: </strong>` (capitalized).
- Fix applied: `strong:contains(Tags)` / `strong:contains(Stars)`.
- jsoup-equivalent proof (soupsieve, same selectors) on the Vixen/Rikako Katayama page [- 0 old tags] / 23 new tags ("Asian", "Big Dick", …); 0 old actors / 2 new actors ("Alberto Blanco", "Rikako Katayama"). Same `<div class="item-tax-list">` block on both sampled pages.
- verify.sh canned run: search /page/* 200 ×69 `div.col-md-3` no dupes; home /featured-scenes/ + page/2 200 ×64 `div.col-md-3.col-xs-6` no dupes (plain `div.col-md-3` fails only on duplicated sidebar widgets — tag cloud/calendar — shared across pages; not video rows). Stream check unrunnable in canned script: stream is two-hop (iframe → extractor xs1.php needs Referer), script can't follow the chain. Manual transcript 2026-09-09:
  - vixen-rikako… → iframe `https://j2.xtremestream.xyz/player/index.php?data=7752af…` → `curl -H "Referer: https://j2.xtremestream.xyz/" …/xs1.php?…&q=720` → 200, body `#EXTM3U`.
  - elegantangel-the-red-door… → iframe `pervl4.xtremestream.xyz` → same chain → 200 `#EXTM3U`. (Subdomain variance j2/pervl4 confirmed; direct-call extractor handles it.)
  - Checker limitations recorded: verify.sh's simple selector parser cannot compile `:contains()`/`:has(...)` selectors or bare tag+digit (`h1`) — tags/actors/title asserted via the soupsieve transcript above instead.

## Re-probe for issue #212 fix (year), 2026-09-09 builder
- Dead selector confirmed: `div.extra span.C a` → 0 matches on sampled video pages (`class="C"` absent from HTML entirely).
- Year now parsed from JSON-LD `datePublished` (same `script[type=application/ld+json]` block as duration): `Parse.year(document)` — regex first 4 digits; relative "x ago" post-dates simply yield null via JSON-LD being present but a valid date always having a year.
- Live evidence (curl, 4 sampled pages): vixen-rikako…→2026, wildoncam-harper…→2022, backroommilf-violet…→2022, brazzersexxtra-abigaiil…→2026.
- Unit tests: `PerverZija/src/test/kotlin/com/kraptor/ParseTest.kt` (datePublished year, missing datePublished → null, no ld+json script → null).
- verify.sh canned run: search/home/video fetch + selectors pass; stream check unrunnable (two-hop, no Referer — same known limitation recorded by #211); source completeness check found year/plot/duration assignments. Year exposure asserted manually above.

## Re-probe for issue #273 fix (recommendations), 2026-09-10 builder
- `div.srelacionados` → 0 matches on both a scene page (1111customs-graycee-baybee…) and a full-movie page (private-milf-cafe-2009). WP theme now serves `div.xs-related-item` cards (scene page 18, movie page 14): `a[href]` wrapping `img[src]`, real title in sibling `div.xs-related-title a`.
- `img[alt]`/`[title]` on related items repeat the HOST page's title (wrong on every probed page) — must never be used for rec titles. Posters are plain `img[src]`, no `data-src`.
- Fix: `PerverZijaParse` (related/titleOf/posterOf) + `toRecommendationResult` reads href from `a`, title from `div.xs-related-title a`, poster from `a img[src]`. TDD: `PerverZijaParseTest` (4 tests, fixture `perverzija_video_related.html` cut from a live scene page) red→green; `./gradlew PerverZija:test` 4/4, `make` BUILD SUCCESSFUL, version 7→8.
- verify.sh canned run (2026-09-10): search `div.col-md-3.col-sm-3` ×45 no dupes; home /featured-scenes/ + page/2 `div.col-md-3.col-xs-6` ×64 each no dupes; all 5 video pages 200 with `iframe[src*=xtremestream]` ×1 and `div.xs-related-item` ×12–18. NOTE: no quick-search endpoint (plain WP `?s` only — same endpoint as search); score: site exposes no rating element (`dt_rating_vgs` 0 hits) — correct null.
- Canned-script limitations (same as #211/#212, evidence substituted manually):
  - Stream two-hop: script sends no Referer → xs1.php 403 (CF challenge HTML). Manual chain transcript, app-emitted request (player page fetched first, referer `<sub>.xtremestream.xyz/`): pervl2/pervl4/pervl8/j2/pervl5 xs1.php?q=720 → 200, body `#EXTM3U` (5/5 distinct CDN paths).
  - Rec titles: script's regex block parser truncates `div.xs-related-item` at the first `</div>` (before `xs-related-title`), so its card titles are always empty. Soupsieve (jsoup-equivalent) on the same 5 live pages: 12/18/18/14/18 items, titles from `div.xs-related-title a` all non-empty, pairwise distinct, never the host video, on every page; img alt confirmed wrong (host-page title) on all 5.
  - Tags/actors selectors use `:has(...):contains(...)` (uncompilable by the script) — unchanged from #211 where they passed via soupsieve; this fix touches only the recommendations mapping.
  - Search page 2 intermittently 429s under script rate (single-page run passed; in-app pagination unaffected).

## Re-probe for issue #333 fix (listing-table sidebar sweep + dead quality links), 2026-09-11 builder
- Defect 1 confirmed live: every listing page (home p1/p2 all 4 rows, search p1/p2) embeds a
  `div id="sidebar" class="col-md-3"` widget column. Old selector `div.col-md-3` consumed it →
  one bogus card per page (title from a sidebar img, href `/advanced-search/`), cross-page duplicate,
  loads "Advanced Search" page (no iframe, no stream).
- Live class census (curl 2026-09-11): search p1/p2 → 64 `class="col-md-3 col-sm-3"` + 1 bare (sidebar);
  home/featured-scenes → 64 `class="col-md-3 col-sm-6 col-xs-6 "` + 1 bare; /studio/ and /full-movie/ same.
  Sidebar is always the bare `col-md-3`. Fix selector: `div.col-md-3:not(#sidebar)` in
  PerverZijaParse.listingCards() — robust regardless of which card-class variant the theme serves.
- Unit tests red→green: `PerverZijaListingTest` (fixture `perverzija_listing_cards.html` cut from live
  search.html with 3 real cards + the sidebar): listingCards → 3 (old selector → 4), no `/advanced-search/`
  cards, card title maps to own img/link.
- verify.sh canned run 2026-09-11: search `div.col-md-3.col-sm-3` ×64 p1 (p2 hit 429 first run —
  runner rate-limit, re-fetched manually: 64 cards, sidebar present, only the sidebar carries
  `/advanced-search/`); home `div.col-md-3.col-sm-6.col-xs-6` ×64 on all 4 rows, duplicate check PASS
  (sidebar excluded). Script cannot compile `:not(#sidebar)` (credited selectors used; `:not` itself
  proven by the unit test + manual census).
- Defect 2 confirmed live on 4 varied videos (pervl2/pervl3/pervl5/moviekh referers all HTTP 200):
  master xs1.php WITHOUT q → `#EXTM3U` + #EXT-X-STREAM-INF variants on every sampled video;
  q loop emits dead links whenever the video caps lower (pervl3 sample: 480/720 ok, 1080/2160 →
  plain-text "Could not find video playlist for quality [N] or Np"; pervl2 sample: only 1080 ok).
  Fix: emit single master-playlist link (no q), lib app picks adaptive variant. quality=Unknown.
  End-to-end: master variant (q=1080 entry) → media playlist → segment
  `https://pervl2.xspcdn01.click/cdn/down/4e0db76ef57112b337ab492a727a6069/1080/10800.html` → 200,
  1,309,420 bytes, first byte 0x47 (MPEG-TS).
- Re-verified unchanged data surface: recommendations `div.xs-related-item` ×12–18/page (script
  "empty recommendation title" FAILs are the known canned rec-title parser limitation; fixtures +
  PerverZijaParseTest cover real titles via `div.xs-related-title a`), tag cloud `<strong>Tags: ` and
  star list `<strong>Stars: ` present on sampled videos, og:description present, JSON-LD
  `datePublished`/`duration` present. No quick-search endpoint — hasQuickSearch stays false; no
  rating element — score stays null.
- Gate: `./gradlew PerverZija:make` BUILD SUCCESSFUL; `./gradlew PerverZija:test` BUILD SUCCESSFUL.
  version 10 → 11.

## Deep audit re-run for #631 spec (2026-10-16 builder, run 38969803445 / issue #651) — verdict ok
- Full-surface audit at tier plain-curl (canaries 3/3: film1k plain 403, eporner 200, pandamovies
  302→200@.org — no challenge anywhere; escalation ladder not needed).
- Home pagination: featured-scenes / studio / full-movie / tag/4k-quality / tag/classic, p1 & p2
  all 200 ×64 cards, 0 href overlap p1↔p2, 0 within-page dupes → carousel-dedup home contract
  holds on every row (verify.sh wrapper `div.col-md-3.col-xs-6`).
- Search `/?s=anal` p1/p2 200 ×64 item rows (`div.post_ajax_tm`, title via `h3 a`), 0 overlap/dupes.
- quickSearch absent (re-confirmed): no suggest endpoint (`?do=suggest` 404), "quick view" is a
  client-side lightbox (`data-embed` attr on listing cards only). hasQuickSearch stays false.
- 29 video pages sampled: `iframe[src*=xtremestream]` ×1, h1/og:image/Tags/Stars intact,
  `div.xs-related-item` ×15–18/page (1 site-native title repeat on the veronica-leal page only),
  `dt_rating_vgs` absent → score null correct. JSON-LD emitted as an **array** by saswp now —
  regex-based shared `JsonLdParse` unaffected (name/duration/datePublished still match).
- Stream chain: iframe subdomain variety now includes `moviekh.xtremestream.xyz` alongside
  perv./pervl1–9/pervm1/j2 — all handled since the provider calls `PerverZijaExtractor()` directly
  (referer derives from the iframe URL; no host registry match needed). Masters `xs1.php?data=<tok>`
  → `#EXTM3U` on 18/18 checked tokens. End-to-end variant+segment live on 9/18 sampled tokens
  (MPEG-TS 0x47 under the player-domain referer); the rest die at the variant/segment hop —
  per-token upstream CDN deadness, site-side, unchanged class (extractor handles them).
- NEW (1/29 video pages, `allanal-lana-anal-rebel-rhyderanal-cravings/`): embed is
  `//mixdrop.to/e/enl4jzdjt76r6q` instead of xtremestream. Live probe: 302 → `mxdrop.click/e/…` →
  200 "WE ARE SORRY / We can't find the video" (reproduced ×3) ⇒ the file is dead at the source;
  the provider's xtremestream guard misses nothing playable. Latent fleet gap recorded:
  `shared/HostRegistry` has no `mixdrop.to` row (only .is/.my/.ag) — filed upstream for when a
  live mixdrop file appears. Not a confirmed provider condition → findings-only.
- verify.sh: 7 invocations (5 home rows × p1/p2 disjoint, search p1/p2, 4 deep videos with
  tags/actors/related selectors + LoadResponse completeness). All PASS except one instrument
  plateau: `path` key flattens all masters to `/player/xs1.php` (HLS identity lives in
  subdomain + `?data=` token) → stream-distinctness check mismatches the query-token shape.
  Manual substitution: 4 distinct masters (perv./pervl7/j2) each serve `#EXTM3U` with distinct
  tokens (fba9d881/46fc943e/f69543a0/c14a2a57). Poster/title NOTES: card thumbs are WP responsive
  variants (`-365x235.jpg`) while load page exposes og:image full-size — mechanically unmatchable,
  poster exposure asserted directly (og:image on 4/4 video pages).
- Gates: `./gradlew :PerverZija:test` 65 × debug+release, 0 failures; `./gradlew clean
  :PerverZija:make` BUILD SUCCESSFUL. No code change → version stays 13.

## FINDINGS-840 — XSFTK signed-token probe (2026-10-21, implement story #846)

**## Verdict: FIXABLE** — the token exchange is fully satisfiable without JS execution.
Everything the player computes is reproducible with plain JVM/HTTP: the boot token is
plaintext in the player HTML, the manifest is XOR-SHA-256, segments are standard AES-128-CBC
with an HLS-expressible IV. Story #845 (fix) is viable; the removal fallback (#844) is not
needed. Full chain reproduced with curl on two live videos end-to-end (16-byte key → decoded
manifest JSON → signed segment URL → AES-decrypted MPEG-TS with clean sync bytes).

### Symptom root cause (#840)
The current extractor path (tokenless `xs1.php`) still answers 200 with valid playlists, but
every segment URL in them is dead — this is the in-app ExoPlayer 2004 "io bad http status":

```
$ curl -s -o xs1seg.bin -w "%{http_code} bytes %{size_download}\n" \
    "https://pervl3.xspcdn01.click/cdn/down/4e316bf7.../480/4800.html"
404 bytes 146      # body: <html><head><title>404 Not Found</title>...  (nginx)
```

Url-shape note: legacy playlists use `.../480/<q><i>.html`; the new signed chain uses
`.../480_enc/<q><i>.html` (also `.ts`-shaped paths under `cdn/down/<id>/`).

### New player layer (deobfuscated ftk_engine.js, javascript-obfuscator output)
- Player iframe page (`<sub>.xtremestream.xyz/player/index.php?data=<id>`) loads
  `ftk/ftk_engine.js` + `ftk/xsad.js` and exposes the boot call **in plain text**:
  `XSFTK.boot("<id>", "<hex>.<expiry>", player)` — id and usable token with zero JS run.
- Both JS files are string-array-obfuscated; ftk_engine.js deobfuscates statically to
  ~950 readable lines defining `window.XSFTK` (VM-extract string arrays, then
  alias-propagation substitution — transcript notes in the run log).
- API endpoint: `/player/proto_api.php` (relative to the player subdomain). Query:
  `?data=<id>&op=<op>&vt=<token>`. **Header `X-XS-P: 1` required on every op** —
  `op=key` and `op=seg` return 403 without it; referer is NOT required (200 with none).
- `vt` and per-segment `n` tokens are `<hex-random>.<epoch>` / base64 of
  `<epoch>.<hex>.<md5-hex>` — server-issued, regenerate per player fetch (observed epoch
  expiry ~05:00Z on 2026-10-21, i.e. hours-long validity window).

### Protocol (all live-verified; video 1 = red/ scene, video 2 = rkprime-abella scene)
| op | header | request | response |
|----|--------|---------|----------|
| `key` | `X-XS-P: 1` | none extra | 16 raw bytes (AES-128-CBC video/segment key) |
| `manifest` | (none) | none extra | octet-stream, XOR SHA-256(`M\|<id>\|<vt>`) → JSON |
| `sign` | `X-XS-P: 1` | `&q=<q>&i=<i>` | base64 signed segment token `n` |
| `seg` | `X-XS-P: 1` | `&q=<q>&i=<i>&n=<n>` (urlencoded) | AES-CBC bytes, IV = 16-byte BE(i) → TS |

### Curl transcript (fresh 2026-10-21, unaltered key outputs)

```console
$ PLAYER=https://pervl3.xtremestream.xyz/player/index.php?data=4e316bf7cad703ffd97bd72c174b72c6
$ curl -sL -A Mozilla/5.0 "$PLAYER" | grep -o 'XSFTK.boot[^)]*'
XSFTK.boot("4e316bf7cad703ffd97bd72c174b72c6", "7cb593191aaa3bed708b7930.1792558800", player)
$ ID=4e316bf7cad703ffd97bd72c174b72c6
$ VT=7cb593191aaa3bed708b7930.1792558800
$ API="https://pervl3.xtremestream.xyz/player/proto_api.php?data=$ID&vt=$VT"
$ HOST="https://pervl3.xtremestream.xyz/player/index.php?data=$ID"
# ---- op=key
$ curl -sL -A Mozilla/5.0 -H "Referer: $HOST" -H "X-XS-P: 1" -o key.bin \
    -w "key %{http_code} bytes %{size_download}\n" "$API&op=key"
key 200 bytes 16
$ xxd key.bin
00000000: 2a54 591a 5196 f997 d091 22f7 4cd5 6fde
# ---- X-XS-P required, referer not
$ curl -sL -A Mozilla/5.0 -o k2.bin -w "%{http_code}\n" "$API&op=key"      # no X-XS-P
403
$ curl -sL -A Mozilla/5.0 -H "X-XS-P: 1" -o k3.bin -w "%{http_code}\n" "$API&op=key"  # no referer
200
# ---- op=manifest → XOR with SHA-256("M|<id>|<vt>") (node -e crypto) → JSON
$ curl -sL -A Mozilla/5.0 -H "Referer: $HOST" -o manifest.bin \
    -w "manifest %{http_code} bytes %{size_download}\n" "$API&op=manifest"
manifest 200 bytes 13639
$ <decode> {"480":{"td":8,"n":734,...,"res":"854x480"},"720":{"td":8,"n":777,...},
            "cdn":["pervl3.xspcdn01.click"... through xspcdn10.click]}
# ---- op=sign
$ curl -sL -A Mozilla/5.0 -H "X-XS-P: 1" "$API&op=sign&q=480&i=0"
MTc5MjU1MTM0OC5hMzQ2N2YyNDhhZjAuNzI1ZmIwMWZiMzMxOGIwOGUxMGFiNzAyN2Y5NDg1NjM
# (base64 → "1792551348.a3467f248af0.725fb01fb3318b08e10bab7027f948563")
# ---- op=seg → AES-128-CBC decrypt, IV = 16-byte big-endian segment index
$ curl -sL -A Mozilla/5.0 -H "Ref: $HOST" -H "X-XS-P: 1" -o seg0.bin \
    -w "seg %{http_code} bytes %{size_download}\n" "$API&op=seg&q=480&i=0&n=$B64"
seg 200 bytes 304384
$ <node aes-128-cbc, key=key.bin, iv=LE-free BE(0)> first byte 0x47, TS sync 4/4 beats
#    (and BE(1), BE(2), and a 720p seg → 8/8 sync bytes each)
```

Video 2 (`https://tube.perverzija.com/rkprime-abella-danger-squirt-surge/`, host
`perv.xtremestream.xyz`, note the short subdomain) reproduces the identical chain:
boot `XSFTK.boot("b7644abae9d51f072401f013b5d42d53","bdea0f38d5716e78c627000c.1792558800")`,
key 200/16 bytes, manifest XOR → `{1080(244 segs),720,...,cdn[]}`, sign+seg 200,
segment TS first byte 0x47 / 8-of-8 sync. Legacy host `pervl2` / `index.php` shapes all hold.

### Decoy (why "just fix the subdomain" fails)
`player/xs1.php?data=<id>&q=<q>` (tokenless) STILL serves a well-formed VOD master with
480/720 STREAM-INF entries, pointing at `<sub>.xspcdnNN.click/cdn/down/<id>/<q>/<q><i>.html`
— every one of them is nginx-404. The old scheme is a decoy playlist provider; the URIs now
resolve only through the managed `proto_api` round-trip.

### IV note (read before fixing in-app segments)
The JS builds the per-segment IV by nibble-dividing the segment index from byte 15 down —
this is the **big-endian 16-byte counter**, not little-endian as a first glance suggests.
`#EXTM3U playback` with `#EXT-X-KEY:METHOD=AES-128,URI=<proto_api key url>,IV=0x<BE counter>`
is byte-order-compatible with native HLS players; key URL must send `X-XS-P: 1` (headers get
carried on `ExtractorLink`s, and `data:`/`#EXT-X-` synthesized playlists keep the key
session in one request context — otherwise the player emits no IV by default and only
mediaSequence=0 stays correct).

### Sort-relevant code landed (story-1 scope, TDD red → green)
- `PerverZijaParse.xsftkBoot(playerHtml)` → `XsftkBoot(id, token)` from the plain-text boot call.
- `PerverZijaParse.protoApiUrl(playerUrl, boot, op, extra)` → canonical proto_api endpoint builder.
- `PerverZijaParse.decodeXsManifest(raw, boot)` → SHA-256(`M|id|vt`) XOR → JSON text (null guard).
- Fixtures (live-derived): `perverzija_player_xsftk.html` (boot line verbatim),
  `perverzija_proto_manifest.bin` (13639-byte XOR manifest for 4e316bf7…, decodes to 480/720).
- Gates: `./gradlew PerverZija:test` 5/5 PerverZijaXsftkTest GREEN (73 tests debug run, 0 failures);
  `./gradlew PerverZija:make` BUILD SUCCESSFUL (`PerverZija.cs3`). Version 14 → 15.

## FINDINGS-845 — XSFTK fix: synthesized native-HLS playback (2026-10-21, fix story #845)

**Root cause confirmed live:** the shared `PerverZijaExtractor` emitted the decoy
tokenless `xs1.php` master (playlist 200, every segment 404 = in-app ExoPlayer 2004).
The sign/sep round-trip is NOT needed in-app: the AES-CBC segment files sit **open** on
the CDN, so the fix synthesizes one native VOD playlist per quality and lets the player
do the AES layer itself.

### New live evidence (2026-10-21 ~03:3xZ, from this CI runner; video 2 = rkprime-abella,
### host `perv.xtremestream.xyz`, embed URL `perv.xtremestream.xyz/player/index.php?data=b7644aba…`)

- Player page: 200 **only with the video-page referer** (bare UA got
  `You can't access the video directly`, 35 B). Boot in plain text:
  `XSFTK.boot("b7644aba…", "5704a6fb8188bef064788210.1792562400", player)`
- `proto_api op=manifest` → 200, XOR-SHA-256 decodes:
  `{1080{td:10,n:244,bw:2683080,res:1920x1080}, 480{264}, 720{n:255,durs[0]=1.418089}, cdn[45 hosts: perv.xspcdn01…xspcdn35.click]}`
- **`op=key` → 200/16 bytes with `X-XS-P: 1`; 403 without (re-verified).**
- `op=sign&q=720&i=0` → base64 (`1792553636.3283aea01767.2766b1c…`); `op=seg` 403s
  without `n` — both **not needed in-app** because:
- **CDN `_enc` segments are open:** `https://<cdn>/cdn/down/<id>/720_enc/<q><i>.html`
  → 200 with NO token, NO referer, NO UA requirement (re-verified bare `curl`),
  identical bytes from every cdn host, i=0..n-1 contiguous (720254 fetched 200).
  Legacy `<q>/<q><i>.html` shape: still nginx 404 (decoy confirmed again).
- **Decryption re-verified:** AES-128-CBC with `op=key` bytes; `IV=BE(0)` → seg 0 →
  first bytes `47401110`, TS sync 15/15 at 188-byte stride; `BE(1)` → `47401111`, 15/15
  (openssl `aes-128-cbc`, key `2a5459…`-shaped 16 raw bytes).

### Player simulation of the shipped design (playlist → data: URI → native HLS)
A full offline simulation that consumes the synthesized playlist exactly the way
ExoPlayer does (parse playlist → resolve `#EXT-X-KEY` URI → fetch key WITH header →
fetch segments in order → decrypt with IV = media sequence number, i.e. **no-IV
default**): playlist rebuilt from the live manifest (255 segs, TARGETDURATION 11
= ceil(max dur), MEDIA-SEQUENCE 0, key line
`#EXT-X-KEY:METHOD=AES-128,URI="<proto_api op=key>"` no IV=), data: roundtrip decodes
back byte-exact, key fetch with `X-XS-P: 1` → 16 B, segments 0 and 1 both decrypt →
MPEG-TS, sync 15/15 each. **SIM PASSED.**

### In-app confirmation (maintainer-only, not run here)
Per FINDINGS-840 the key URL must keep `X-XS-P: 1`; the extractor sets it on the
ExtractorLink `headers` map (HlsFree #247 precedent). The one un-testable-from-CI
assumption remains **ExoPlayer opening the `data:application/vnd.apple.mpegurl;base64,`
playlist URI** (DataSchemeDataSource) — if the app instead rejects `data:` scheme
playlists, the fallback is a per-segment presign sweep (734 sign calls at 480p) which
is explicitly NOT shipped. In-app playback test is merge-time, not an agent deliverable.

### Code landed (TDD red → green, ADR-0005)
- NEW `shared/src/main/kotlin/com/kraptor/XsftkParse.kt` — single source of the XSFTK
  protocol helpers: `xsftkBoot` / `protoApiUrl` / `decodeXsManifest` (moved from the
  provider parse, same package), plus `qualities`, `xsSegmentUrl`, `variantPlaylist`
  (no-IV contract + RFC-8216 target-duration guard), `encodeBase64NoWrap` /
  `dataUri`. Root cause lives in the shared adapter, so the helpers moved to shared
  (ADR-0002); never copy-paste between providers.
- `shared/HostAdapters.kt` `PerverZijaExtractor.getUrl` rewritten: fetch player page
  (video-page referer) → boot → manifest decode → one synthesized M3U8 `data:` link
  per quality (highest first, `quality` from `<name>`, `headers = {"X-XS-P": "1"}`
  for the op=key fetch). xs1.php emission demoted to a legacy-only fallback
  (no-XSFTK pages) and no longer serves gated videos (decoy).
- `PerverZijaParse` keeps thin delegates pinning the #846 API surface.
- NEW test `PerverZijaSynthTest` (7 tests): segment URL shape, quality ordering,
  playlist structure (key line URI exact, **no `IV=` attribute** — absence is a
  contract, MEDIA-SEQUENCE 0, segment names/indices, ENDLIST, target-duration rule),
  tiny-manifest determinism, unknown-quality null, data: roundtrip. Red first
  (unresolved `XsftkParse`), then green.
- Gates: `./gradlew PerverZija:test` BUILD SUCCESSFUL (0 failures; 7/7 new + 5/5
  #846 probe tests + full suite); `./gradlew PerverZija:make` BUILD SUCCESSFUL
  (`PerverZija.cs3`). Version 15 → 16.
- #844 (fallback/removal story): moot under this verdict — no action taken.

## FINDINGS-906 — failing-class census (2026-10-23, census story #906, parent #903)

Full report: `FINDINGS-906.md` at the repo root. Headline: **335/336 sampled video pages are
green through their live chain from CI; the reported #903 URL is green.** Two player generations
coexist — XSFTK gated (319) and legacy video.js (16) — and they use **opposite CDN layouts**:
gated videos serve only `/<id>/<q>_enc/…` (legacy `/<q>/…` is the 404 decoy), legacy videos serve
only `/<id>/<q>/…` as plain MPEG-TS. So the tokenless `xs1.php` fallback in
`PerverZijaExtractor` is **correct for the 16 legacy videos and must be kept**; the decoy is only
a risk when a gated page's boot line is missed. Shapes confirmed: single-quality XSFTK manifests
(34/319, mostly 360/480) and one alternate iframe host (`mixdrop.to`, upstream file dead).
No real failing-video fixture exists to ship (the only failure is dead upstream), so #906's
failing-fixture request is recorded as a spec deviation in `FINDINGS-906.md`.
Fixtures for #907: `perverzija_player_legacy_bootmiss.html` + `perverzija_legacy_{master,variant480}.m3u8`,
`perverzija_player_{reported,singleq}_xsftk.html` + `perverzija_manifest_{reported,singleq}.bin`.

## FINDINGS-907 — TDD red→green on the #906 census fixtures (2026-10-23, unit story #907, parent #903)

**Scope:** Parse layer only (`shared/XsftkParse.kt` + `PerverZijaParse` delegates + tests). The
extractor wiring (per-variant fallback chain, `loadLinks` host tolerance) stays S3 (#908); this
story ships the tested seam S3 consumes — "green tests define done" (#908 depends on #907).

### Red → green transcript
1. Added `PerverZijaResidualShapesTest` (11 tests) against the #906 fixtures. **Red:**
   `Unresolved reference 'isGatedPlayer'` — the decoy-guard seam did not exist.
2. Added `isGatedPlayer` (marker detection only) → **red at runtime:** the boot-format
   tolerance test failed for all three real-world formatting variants (`window.XSFTK.boot`,
   `XSFTK["boot"]`, newline-separated args) — the old regex accepted exactly one byte shape.
3. Broadened `bootRe` (optional `window.`, dot/bracket access, single/double quotes, `\s*`
   between arguments, case-insensitive hex) → **green: 11/11**, full provider suite 112 tests
   × debug+release = 224, 0 failures; `clean :PerverZija:make` BUILD SUCCESSFUL; version 16 → 17.

### Honest red/green split (spec traceability)
`decodeXsManifest` / `qualities` / `variantPlaylist` were **already green** on the new shapes
(they are pinned as regressions, not fake reds): the reported manifest decodes to
`1080/720/480` (80/76/74 segments, target 9 — manifest `td=8` undershoots its own 8.341667s
max), the single-quality manifest decodes to `360` only (1411 segments, target 11 — `td=10`
vs 10.427078s max). The genuinely failing class is **boot/gate detection**: a gated page that
loses its inline `XSFTK.boot(...)` call currently has no structural marker to stop the
extractor landing on the tokenless `xs1.php` decoy — the exact owner-visible 2004 shape
(FINDINGS-906). `isGatedPlayer` + tolerant boot matching close that at the Parse layer.

### Tests (all fixture-driven)
- reported (#903 URL): boot id/token, gate true, manifest decode, ordered qualities,
  per-quality playlist (key URI with `X-XS-P`-gated op=key URL, no `IV=`, own CDN host,
  first/last segment, `#EXT-X-ENDLIST`, target 9).
- single-quality (`wicked-hercules-2002`): boot, gate, one quality, 1411-segment playlist,
  non-existent quality → null.
- legacy boot-miss: `xsftkBoot == null` **and** `isGatedPlayer == false` — the live plain-TS
  `xs1.php` fallback must stay for these 16/336 videos (FINDINGS-906).
- decoy trap: the reported page with its `XSFTK.boot` line stripped still classifies gated
  (boot null, gate true) — the guard S3 wires.
- legacy playlists: master variants `480/720/1080`, media playlist 269 plain segments, none
  `_enc`. Observation: the legacy CDN roster mixes **`xspcdnNN` and `xscasacdnNN`** hosts
  (60/269 segments) — host families are wider than the gated manifest's; `xsSegmentUrl`
  already takes the host from the manifest/playlist, so no code change, but `_enc` vs plain
  is the family discriminator, not the host name.

### Statically verified decoder trap
`decodeXsManifest` with a wrong boot token returns null (XOR garbage does not pass the
`{` + `"cdn"` shape check) — no throw on hostile input.

### Repair round (review findings, 2026-10-23)
- **Sonar S5843 blocker:** the single boot regex (complexity 27 > 20) failed the quality
  gate. Split into `bootDotRe` (dot access) and `bootBracketRe` (bracket access), tried in
  order; each is 16/17, so the gate passes without narrowing what matches (the `window.`
  prefix needs no special case — the match starts at `XSFTK`).
- **Gate-marker evidence:** `proto_api` appeared in no gated fixture, so it was dropped
  rather than allowed to suppress the working legacy fallback. Added
  `asset markers alone keep a boot-miss page gated without XSFTK`, which removes every
  `XSFTK` reference and pins `ftk_engine` and `ftk/xsad` as deciding markers (all three
  gone → not gated).
- **Re-verify:** `:PerverZija:test` green (12/12 in the residual class, 0 failures),
  `:PerverZija:make` BUILD SUCCESSFUL.
