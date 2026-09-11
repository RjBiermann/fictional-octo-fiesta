# FINDINGS — jav.guru (updated by fix for #144; re-probed 2026-09-07/14)

## Verdict: FIXED (mirror-level) — 5/5 varied videos yield ≥1 verified m3u8 (turboviplay and/or vidara)

## Re-probe 2026-09 (issue #144)
- Search `https://jav.guru/search/teacher/` → 200, 78 × `div.inside-article`. Unchanged.
- Post pages still carry 6 base64 `iframe_url` entries; all decode to
  `https://jav.guru/searcho/?<xd|ud|td|cd|hd|od>=…&bg=<poster>` (unchanged).
- The plain `?xd=` loader page (200, no redirect) now embeds the SAME gateway pattern as
  before: `window.cfg = {cid, base, rtype, keys:['data-*']}` + `div#<cid>` carrying the
  data-attr token (filled WITHOUT cookies). `?xr=<reversed token>` → 302 → per-server embed:
  - xd → `https://javclan.com/e/<hash>` — **player changed**: no more packed `eval(p,a,c,k,e,d)`;
    now a jwplayer page whose stream comes from the obfuscated `/assets/jquery/hg-function.js`
    (`encStatus('<id>',1)`). Shared `Javclan` extractor (packed-eval) no longer matches → null.
  - ud → `emturbovid.com/t/<id>` → redirect to `turbovidhls.com` page with literal m3u8
    (`https://cdn3.turboviplay.com/.../<id>.m3u8`) → 200 application/vnd.apple.mpegurl.
    Still caught by loadLinks' literal-m3u8 regex. **WORKING.**
  - td → `javlesbians.com` → now redirects to a jwplayer Vue app (eugenemakedraw.com build
    assets); no literal m3u8, no extractor matches.
  - cd → `vidara.to/e/<filecode>` — NEW simple API: `POST /api/stream` with
    `{"filecode": ..., "device": "web"}` → JSON `streaming_url` (m3u8, IP-bound token) →
    200 application/vnd.apple.mpegurl. **WORKING (new extractor added).**
  - hd → `vide0.net` → 403 from runner (unchanged). od → `maxstream.org` (no extractor match).
- m3u8s serve 200 without any Referer (turboviplay verified both with and without).

## Fix (this change, JavGuru dir only)
- New `Vidara` extractor (`VidaraExtractor.kt`): filecode from `/e/<fc>` → POST
  `https://vidara.to/api/stream` → regex `streaming_url` → M3U8 link, referer = embed URL.
- Registered via `registerSharedExtractors(listOf(Vidara()))` in the plugin class.
- Version bumped 20 → 21.
- javclan's new hg-function.js flow is fully obfuscated and was NOT reverse-engineered;
  the shared packed-eval `Javclan` extractor stays (harmlessly returns null). javlesbians /
  vide0.net / maxstream mirrors remain unhandled — every post still has ≥1 working mirror.

## Search — OK
- `https://jav.guru/page/N/?s=q` (provider) and `/search/q/` both work; `div.inside-article`.

## Video pages — OK (5 varied probed)
- search ×2, category/jav-uncensored, most-watched-rank ×2: `h1.tit1`,
  `div.large-screenshot img`, `iframe_url` base64 ×6, related items in `li img`.

## Stream verification (chain.py replication, per #117 precedent)
- verify.sh's single-hop stream check still cannot express the 4-hop searcho chain (and its
  contentUrl fallback trips on the site's escaped JSON-LD logo → curl exit 3 abort), so
  stream verification replicates loadLinks end-to-end over 5 varied videos:
  **5/5 videos yielded ≥1 m3u8, each confirmed 200 application/vnd.apple.mpegurl**
  (turboviplay on all 5; vidara on 2 of 5).

## Headers / referer
- vidara extractor sends `referer = <vidara embed url>`; m3u8s verified playable without
  special headers.

## Pagination
- `/page/N/?s=` for search; `/page/N/` for listings.

## Risks / blockers
- No Cloudflare/IP blocks observed on jav.guru / vidara.to / turbovidhls.com.
- vide0.net 403s the runner (unchanged); javclan + javlesbians mirrors now JS-gated.
- If turboviplay/vidara both die, javclan's hg-function.js deobfuscation is the upgrade path.

## Host-registry refactor verification (issue #169, this run)
Search: `https://jav.guru/search/teacher/` → 200, 24 × `div.inside-article`; `/page/2/` → 200, 24 (verify.sh check 1 PASS).
Stream chain (chain multi-hop > verify.sh single-hop; replicated manually):
iframe_url base64 → searcho (base/rtype/cid keyed attrs, reversed token) → 302 → vidara.to/e/<code> → POST /api/stream → streaming_url m3u8 → 200 `application/vnd.apple.mpegurl` (#EXTM3U). 4 posts verified end-to-end; sequential over the remaining posts of the probe did not complete in-budget (chain per-post is 4 hops).
Displacement: PerverZijaExtractor Vidara adapter moved to `shared/` — code byte-identical, registration via `registerHostExtractors()` (`loadExtractor` prefix-matched on mainUrl; reverse order = priority; Levenshtein >80 mirror-domain fallback overrides PerverZija schema/subdomain variance).
LoadResponse fields present in provider (verify.sh check 6: recommendations 2, tags 2, year 1, actors 3).
NOTE: verify.sh `field` bar cannot express `h1.titl` (mini-selector regex `[a-zA-Z]+` rejects digit tag names) and no m3u8 in page HTML (multi-hop) → check 2 stream bar N/A here, chain replicated above. og:meta absent on video pages (WP theme, anything-for-titles).

## Fix run 2026-09-09 (issue #210 — actors pollution + dead h1 selector)
- Actors selector was `li.w1 strong:not(:contains(tags)) ~ a` — the jsoup `:contains`
  excludes the Tags li but NOT the Series li, so the series name was fed to addActors
  (live-confirmed on jjbk-087: Series row "Mature Women Only: Home Visit" appeared in actors).
- Fix: actors now parsed from the Actress row only — `li.w1:has(strong:containsOwn(Actress)) a`
  (pure `JavGuruParse.parseActors`), live on 5 varied pages: actress rows yield 0..4 <a>
  anchors each; pages without an Actress row yield none; no Tags/Series leakage.
- Title selector `h1.tit1` never matched (live pages carry `h1.titl`) — parseTitle now uses
  `h1.titl` guarded by the plain-`h1` fallback (unchanged behavior).
- TDD: fixture `JavGuru/src/test/resources/jav-guru-video-meta.html` (real meta markup incl.
  multi-actress row) → `JavGuruParseTest` 2 tests red→green; `gradlew JavGuru:test` + `make` clean.
- Version 22 → 23.
- verify.sh: search ×2 + home ×2 all 200 with 24 × `div.inside-article` (checks 1/1a PASS).
  Check 2/2a selectors remain NOT expressible in verify.sh's mini-selectors (h-tag names with
  digits, `li:has(...)` colon pseudos), og:meta absent, and streams need the 4-hop searcho
  chain (per precedent) — chain replicated manually for the issue video:
  iframe_url(base64) → searcho cfg (cid cb0c29d, keys data-d91eb/6d6ea/cc5d6) → reverse-concat
  token `6d763666337932777763366b` → `?xr=` 302 → javclan.com/e/mv6f3y2wwc6k (packed-eval)
  → unpacked hls2 master.m3u8 → **200 application/vnd.apple.mpegurl, #EXTM3U body**.
- quick search: still absent (no distinct endpoint; hasQuickSearch=false).

## Fix run 2026-09-10 (issue #267 — male-actor row completeness)
- parseActors previously matched the Actress row only; the site's separate `Actor:` row
  (male actors, on ~60% of JAV posts) never reached addActors.
- Fix: `li.w1:has(strong:matchesOwn(Actor:|Actress:)) a` — matches both rows, still skips
  Tags/Series (#210 regression held). First attempt used a comma-separated selector
  (`…Actor)), …Actress)) a`) — jsoup's comma splits selectors, so the actor `li` itself
  leaked in as a text blob; matchesOwn is the one-selector fix.
- TDD: fixture `/jav-guru-video-meta.html` extended with an Actor row (two names, real
  mixed markup `Name</a> , <a`), new test asserts Actor names precede Actress names;
  new `/jav-guru-uncensored-meta.html` (Tags-only, no actor rows) guards the #210
  regression. `JavGuru:test` + `make` clean, version 24 → 25.
- Live re-check: /1051298 (Yuta Aoi/Miru), /1047618 (Shinya Matsuyama/Amau Ririka),
  /1050913 (Narcissus Kobayashi, Tyson Tsubasa/Suzumori Remu) — all on the page.
- verify.sh (2026-09-10): search 24/page1, home 24+24, 3 videos → streams
  turboviplay + vidara-API m3u8s all 206 application/vnd.apple.mpegurl; tags+actors
  present on all 3. RESULT: PASS. (plot/poster remain N/A — site-wide meta only.)
- Chain reproduction note: emturbovid 302 hop carries raw control bytes in the Location;
  urllib/python redirects that lose the raw bytes end up on a tokenless player page with
  no m3u8. NiceHttp follows them raw, which is why the Kotlin provider still works.

## Reviewer verification 2026-09-10 (PR #272 review of the #267 fix)
- Independent live probe (reviewer, not Builder evidence): `parseActors`' exact selector
  `li.w1:has(strong:matchesOwn(Actor:|Actress:)) a` on live /1051298, /1047618, /1050913
  returns the actor+actress anchors only — e.g. /1050913 → Narcissus Kobayashi, Tyson
  Tsubasa, Suzumori Remu; the pre-fix `containsOwn(Actress)` selector misses the Actor row
  (bug claim confirmed); no Tags/Series leak. jsoup 1.23.2 (same as the unit-test
  classpath). Fixture Actor row made byte-exact vs the live /1050913 row (`</strong> <a`,
  `</a>, <a`).
- `JavGuru:test`: 26/26 pass (debug + release), `JavGuru:make` clean, version 24 → 25.
- verify.sh re-run by reviewer, 5 varied video URLs (issue #267 ×3 + most-watched-rank
  /1050095, /1043863): **RESULT: PASS** — search 24/page1, home 24+24; all 5 video pages
  200 with `h1.titl` + related block `div.woo-sc-related-posts` (5/5); streams via the
  FINDINGS 4-hop chain (replicated): turboviplay literal m3u8 ×4 + vidara-API
  streaming_url ×1, all **206 application/vnd.apple.mpegurl** (vidara token is IP-bound,
  regenerated per run).
- verify.sh bars that stay N/A for this site (unchanged limitation, see #210 note): check
  2a field bars (colon pseudos like `li:has(...)` not expressible — actors/tags exposure
  asserted by TDD fixture + the probe above, not by bars); check 5 (search-card extraction
  is truncated by the theme's nested grid — verify.sh regex-DOM limit). No bar exists for
  a card grid wrapped in an ancestor div, so card-based checks get empty TSVs.
- Stream drift noted for the Monitor (streams untouched by this PR; all 5 sampled above
  play): the searcho `?ur=` hop is CF-cached 200-empty for some posts — /1051244 and
  /1051278 currently resolve NO working mirror (emturbovid hop → jwplayer JS player page
  with no literal m3u8; xd/td/hd/od mirrors dead/unsupported per FINDINGS). Last verified
  full-play sample set: 5/5 above.

## Fix run 2026-09-11 (issue #310 — ud/turbovidhls literal-MP4 drift)
- Some `/searcho/?ud=…` chains now land on `turbovidhls.com/t/<id>` player pages that serve
  a **literal MP4** in `var urlPlay` instead of an m3u8 (reproduced end-to-end on
  /1051302: `var urlPlay = 'https://e06.etvp.cc/uploads/6aa2e4c4ba2ef.mp4'`; MP4 serves
  206 video/mp4, `ftypisom` magic). Both page shapes (m3u8 and MP4) are live simultaneously.
- Fix: `JavGuruParse.parseUdMp4(playerHtml)` (pure parse fn per ADR-0005) extracts the
  urlPlay MP4; the ud branch of `loadLinks` emits it as an `ExtractorLink` (VIDEO) with an
  etvp-origin Referer when no m3u8 is present, else loadExtractor fallback as before.
- Fixture `jav-guru-turbovidhls-mp4.html` captured from the live page (issue #310 step 3);
  tests red → green (`parseUdMp4` MP4 + m3u8-negative cases). `JavGuru:test` +
  `JavGuru:make` clean, version 25 → 26.
- verify.sh (2026-09-11, /1051302 with --stream-url = provider-resolved MP4):
  search 24/page1, home 24+24 (checks 1/1a), video page 200 h1 match, stream
  `https://e06.etvp.cc/uploads/6aa2e4c4ba2ef.mp4` → **206 video/mp4**, related block
  1/1, check 6 all fields. **RESULT: PASS**. N/A limitations unchanged (see #210 note).
