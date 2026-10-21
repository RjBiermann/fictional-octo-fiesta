# FINDINGS-778 — xvideos.com/embedframe SSR embed resolver (shared, ixiporn shape-B hub tier)

Run: https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/39538809670 (2026-10-21).
Spec: #778 (plan round 3 of #708). Evidence base: #708 observation + FINDINGS-655.md.

## Deliverable

- `shared/src/main/kotlin/com/kraptor/XvideosParse.kt` — pure Parse function: SSR embed
  page → stream URLs (progressive mp4 qualities + HLS), no framework types (ADR-0005 seam).
- `shared/src/main/kotlin/com/kraptor/HostAdapters.kt` — `Xvideos` `ExtractorApi` adapter
  wrapping the Parse function (List-return style, deleted shell → empty list, no throw).
- `shared/src/main/kotlin/com/kraptor/HostRegistry.kt` — one `Xvideos()` row in
  `sharedHostRegistry()` (the single registration point). Fleet version bump on all 26
  providers (`// #778: shared xvideos registry row (fleet bump)`, #670 precedent).
- Fixtures (verbatim live captures): `shared/src/test/resources/xvideos_embed_live.html`
  (5891 B; only the giant `var video_related=[…]` line dropped) and
  `shared/src/test/resources/xvideos_embed_deleted.html` (1049 B shell).
- TDD: `XvideosParseTest` (8 tests) written red (`Unresolved reference 'XvideosParse'`)
  → green. Fleet-wide green after the registry row: 79 test executions × debug+release
  via `./gradlew EPorner:test` (includes `HostRegistryTest` invariants over the new row).

## Probe transcript (plain curl, 2026-10-21)

UA: Firefox 144 (`FIREFOX_UA`), Referer `https://www.xvideos.com/`.

```
$ curl -sL -A "$UA" https://www.xvideos.com/embedframe/61306335
HTTP 200, 53201 bytes, title "Xvideos.com embed video"
```

### Live embed page shape (video 61306335 — the one live target of #708's 1/3 sample)

SSR carries the player config as `html5player.setVideo*('…')` single-quoted calls:

```
html5player.setVideoTitle('EXTREME CLOSE UP POV Pussy Eating until CUM on TONGUE - …');
html5player.setEncodedIdVideo('kammodfd377');
html5player.setVideoUrlLow('https://mp4-cdn77.xvideos-cdn.com/12ba560c-…/video_240p.mp4?secure=<b64>,<epoch>');
html5player.setVideoUrlHigh('https://mp4-cdn77.xvideos-cdn.com/12ba560c-…/video_360p.mp4?secure=<b64>,<epoch>');
html5player.setVideoHLS('https://hls-cdn77.xvideos-cdn.com/<b64>,<epoch>/12ba560c-…/0/hls.m3u8');
```

- `setVideoUrlHigh` + `setVideoUrlLow` = the 2 progressive mp4s; quality is in the
  filename (`video_360p.mp4` → 360). `setVideoHLS` = the adaptive manifest.
- `secure=` tokens rotate **per request** (two fetches minutes apart returned different
  tokens/epochs) — the app fetches its own at play time; the parser never depends on
  the token value. The 42 `.mp4` refs in the raw HTML are thumbs/preview clips
  (`thumbs-*/thumb-*.xvideos-cdn.com/…/preview.mp4`), NOT streams — only the
  `setVideoUrl*`/`setVideoHLS` args are streams.

### Deleted shell (verified on 61306334, 61306336, 52918133, 41347899 — all HTTP 200, 1049 B)

```html
<body><h3>This video has been deleted</h3><h3><a href="https://www.xvideos.com/" target="_blank">Watch more porn</a></h3></body>
```

No `setVideo*` calls → resolver returns an empty list: no throw, no best-effort dead
URLs (#778 deleted-shell contract). Live check: 61306334 resolved DELETED, no streams.

## Verification (live, plain HTTP — mirrors app runtime)

`resolve` = fetch embed URL, extract `setVideoUrl(High|Low|HLS)` args (same semantics as
`XvideosParse`), then range-request each stream with UA + Referer:

```
61306335: 240p mp4 → 206 video/mp4; 360p mp4 → 206 video/mp4; HLS → 206 application/vnd.apple.mpegurl
61306334: deleted shell detected → no streams emitted
RESULT: PASS
```

## Gates

- `./gradlew EPorner:test` — 79 × debug+release, 0 failures (8 new XvideosParseTest).
- `./gradlew EPorner:make` — green, `EPorner.cs3` assembled (registry + adapter compile fleet-wide).
- Live Verification — PASS (above).

## Scope note

Per the #778 spec: resolver + adapter + registry row only. Chaining it from ixiporn's
`player-x.php` b64 payload (shape-B loadLinks) is the follow-on step and was NOT in
scope here. The hub varies per video (xvideos/redtube, FINDINGS-655) — the redtube hub
still has no resolver.

## Risks/blockers

- xvideos CDN served this datacenter IP without a challenge on every request of this
  probe (plain-curl tier, no escalation needed) — but Cloudflare posture can change;
  a future drift here looks like a 403 shell, not a parser break.
- `requiresReferer = true`; link referer/headers pinned to `https://www.xvideos.com/`.
