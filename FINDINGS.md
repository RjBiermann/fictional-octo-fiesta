# FINDINGS — issue #452 (7mmtv.sx provider)

## Probe (live, 2026-09-22)

- Site: https://7mmtv.sx — JAV streaming (censored / uncensored / amateurjav / reducing-mosaic / amateur). English face at `/en/`.
- Access: the site face gates some plain-HTTP requests by TLS fingerprint (Cloudflare-style 403 on fingerprinted curl, while urllib/curl_cffi `chrome` impersonation gets 200). **In-app NiceHttp (OkHttp) risk is documented, not mitigated** — one plain curl to `/en/censored_content/...` returned 403, all embed/CDN hosts serve 200 to plain curl. If challenged in-app this is a runner-side TLS-fingerprint thing, out of provider control (MissAV carries the same class of note).
- **Listings**: `https://7mmtv.sx/en/{group}_latest/all/<n>.html`, groups = `censored | uncensored | amateurjav | reducing-mosaic | amateur`, pagination `<n>.html`.
- **Search**: form POSTs `searchform_search`, lands on the same GET shape `https://7mmtv.sx/en/searchall_search/all/<kw>/<n>.html` — verified live at 200 (kw `harem`, page 2). No quick-search/suggest endpoint ⇒ `hasQuickSearch = false`.
- **Card shape** (listings/search/related): `div.video` > `a` > `img[data-src]`, `h3.video-title > a` (href carries the content url `_content/<id>/<NAME>.html`). Same shape on the video page's related strip (`div.video.video-related`, 8 cards). Search results legitimately repeat the same movie (censored + reducing-mosaic pipelines, different pairwise ids AND poster urls — jpg vs webp — same movie code in the href). Provider dedupes cards by the href-derived movie code (`…/<code>.html`; amateur pages carry a numeric id instead of a code, `…/134651/content.html`).
- **Video page**: `h1.fullvideo-title`; `div.fullvideo-details div.d-flex > span` = [code, date `2026-09-18`, duration `203分`]; `div.categories a` tags; `div.fullvideo-idol > span > a` actors; `div.fullvideo-text article p` plot; `div.content_main_cover img` poster (`n1.1024cdn.sx/...`).

## The server-row scheme (core mechanism)

Video pages carry `var mvarr[...]` rows; each is `[['<frameId>','<0/1/c garble>','<iframe html>','<urlPrefix>','</iframe>',...]]` with:

1. split on `c`, parse each base-2 chunk, XOR **23** (page vars `hadeedg252=23` / `hcdeedg252=2`, stable across all sampled pages of every group)
2. concat string chars = a base64 blob
3. AES-**CBC**/**PKCS7**-decrypt with **page-local** 16-char key (`var argdeqweqweqwe = '...'` and its 5 alias vars) and IV (`var hdddedg252 = '...'`)
4. prepend the row's urlPrefix (unless it is `https://emturbovid.com/t/`, where the decrypted value already IS the full src)

Verified decoded targets across four content groups:

- prefix `https://mmsi02.com/e/` — "SW" rows → **StreamHG** (streamwish-family jwplayer with packed-eval `links={hls2,hls3,hls4}`, same shape as javclan.com) → new shared registry row `javclanMirror("https://mmsi02.com", "StreamHG")`.
- prefix `https://mmvh02.com/v/` — "VH" rows → VidHide family (same packed-eval shape) → new shared registry row `vidHidePro("https://mmvh02.com", "VidHide")`.
- prefix `https://playmogo.com/e/` — **DoodStream** family; adapter (dood) and registry row already existed in the registry.
- prefix `//7mmtv.sx/assets/js/play/play.php?id=...` — "SP" rows: plain HTML player page containing `videoSources=[{src:'...m3u8'}] (480p/720p) — provider parses it directly (streamsuperpro.com CDN; master m3u8 verified 200).
- prefix `https://emturbovid.com/t/` — "TV" rows: decrypted src is a 7mmtv **`/en/<group>_iframeencrypteda/...` chain page** → wrapped iframe (`*iframeencryptedb*`, src may be absolute `https://` not just `//`) → hop to `turbovidhls.com/t/<id>` page whose `urlPlay`/`data-hash` gives the emturbovid m3u8. Parsing is in pure Parse functions (`SevenMm.turbovidWrapped`, `SevenMm.turbovidM3u8`), fixture-tested against the live chain pages (turbovid-chain-a/b.html).

## Decisions

- Parse seam per ADR-0005: `SevenMm.serverRows` (mvarr decode, AES via javax.crypto), `SevenMm.turbovidWrapped` + `SevenMm.turbovidM3u8``, `SevenMm.playSources`, `SevenMm.meta`, recs/cards reuse shared `SearchCard` (standard card shape). Fixtures: live HTML for all four issue example groups — censored (mism456-video.html), Chinese-AV (am134651-video.html), uncensored (fc24979713-video.html), reducing-mosaic (jufe321-video.html; related strip trimmed, per-page key/IV alias pairing verified against each) + TURBOVID chain pages (turbovid-chain-a/b.html) + trimmed play.php player page. JUnit4 `SevenMmParseTest`, including DistinctBar over recs and search cards, movie-key dedupe, and the TURBOVID chain.
- No cloudstreamproxy tricks — real simple: listing / search GET endpoints, per-page pagination, direct m3u8s for SP, framework dispatch for registered embed families, one inline resolution only for the TV chain (Javmost precedent).
- Language `ja`.

## Verification (live)

- `gradlew 7mmtv:test` green; `gradlew 7mmtv:make` builds `7mmtv/build/7mmtv.cs3`.
- Full-chain probe (python mirror of the decode + curl): fresh MISM-456 page → all four rows decoded → play.php returned two m3u8 masters (200 with curl) → mmsi02/mmvh02 pages 200 → premilkyway master m3u8 200 → turbovidhls chain got the `urlPlay` m3u8.

## Risks / notes

- **In-app access to 7mmtv.sx face may be Cloudflare-challenged** depending on the runner's TLS fingerprint (403 ≠ consistently; embed+CDN hosts always OK). In-app verify at merge time is the gate; if the face is blocked but the embed hosts work, every SP/TV row still fails since the FIRST hop is the challenged face. Fallback note: if NiceHttp is challenged hard, mirror landings like `mmsi02/mmvh02/playmogo` rows still function.
- Search results duplicate movies (site-side); deduped by href-derived movie key — unrelated entries sharing a poster stay (poster urls differ between pipeline repeats anyway, so a poster key was blind to some repeats).
- Some pages (e.g. `jufe321` group page variants) had only a subset of the four server rows — behavior verified not to throw on empty or partial rows.
- The upstream cloudstream3 pre-release jar changed (digest `bbd246…` → `66611c…`) — pinned in root build.gradle.kts as required by `bootstrapCloudstream` (mavenLocal jar was byte-identical to the live asset).
