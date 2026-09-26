# FINDINGS-488.md — Cat3Movie (issue #488: "video doesn't play", carnal-olympics-1983)

Probe date: 2026-09-26. UA throughout:
`Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36`
(cat3movie.org serves it 200; #411's CF JSD gate applies to plain curl only).

## Reported page state

`https://cat3movie.org/watch-carnal-olympics-1983/full-sv1.html` — 200, healthy watch page:
`"post_id":34471` in `halim_cfg`/`data-post_id`, `body[data-nonce="35d3222271"]`, exactly
3 servers (`jsonEpisodes` = sv1/sv2/sv3, matching the provider's hardcoded `for (sv in 1..3)`).
Base movie page `https://cat3movie.org/carnal-olympics-1983` (what the provider's cards link
to, what `load()` fetches) — 200, same post_id/nonce, `h1.entry-title`, og:image, plot,
`p.released`, `p.category`, `p.actors`, related section all present. Note
`/watch-carnal-olympics-1983` (watch- form, no episode suffix) → 404, the #250/#417 shape.

## Symptom replay — exact provider chain, per server (2026-09-26)

player.php exactly as `loadLinks` sends it (per-server referer, X-Requested-With, cache-buster):

```
sv1 → 200 → <iframe src="https://hlsfree.com/embed/hls/958">   ← only surviving host
sv2 → 200 → <iframe src="https://cdn.loadvid.com/videos/play/SbHsQJHbcylgEuNOVelq">
             cdn.loadvid.com → HTTP 522 (CF origin down) — NEW: host was 403/blob-gated
             alive in #250/#417 probes, now dead entirely
sv3 → 200 → <iframe src="https://hlsfast.com/#o3izje">
      api/v1/video?id=o3izje (Referer https://hlsfast.com/) →
      200 {"message":"Video not found or deleted"} — copy deleted upstream (site-side,
      bamboo-house-of-dolls precedent)
```

sv1 hlsfree leg (what `loadExtractor` → shared `HlsFree` adapter runs) — fully healthy:

```
embed    GET hlsfree.com/embed/hls/958 (any referer) → 200, defaultHlsUrl token=e21ace7c90dc
         (also 200 with okhttp/4.9.3 UA and with python-requests TLS — CF-fronted but NOT
         challenging non-browser clients today, so the #411-style CFK gap in the shared
         adapter is unobservable and adding it would be speculative)
manifest GET /api/hls/serve?token → 200 application/vnd.apple.mpegurl, #EXTM3U, 479 segments
segment  s1.cat3hls.com/…/622edf7cf8f6af7d.js → 403 without referer,
         200 with Referer: https://hlsfree.com/ — 3.4 MB MPEG-TS (image/png type)
```

Broad sweep, 9 titles' full sv1 chains (player.php → embed → fresh token → manifest):
9/9 → 200 mpegurl (carnal-olympics-1983, joy-1983, heat-1986, baby-cat-1983, blue-money-1972,
american-rampage-1989, desire-the-interior-life-1980, bats-1999, antony-and-cleopatra-1972).

## Conclusion on the report (does not reproduce server-side)

Every leg the provider exercises for carnal-olympics-1985…-1983 works from this runner; the
emitted-link hardening is complete since #247 (HlsFree header map) and #417 (HlsFast header
map) — no emitted link lacks the segment-referer map any more. No provider-side defect found;
nothing to fix without speculating. Third consecutive non-reproducible "doesn't play" report
(#250, #417, #488); closed drift issues on this provider now number six (#143, #180, #203,
#250, #411, #417) — past the Chronic threshold (CONTEXT.md: four or more). Chronic means
removal candidate; that decision stays maintainer-only (`ai-remove-site`), flagged in the PR.

Remaining in-app suspects are the same as #250/#417 recorded: datasource header path on the
user's device, CF challenge on the user's client, first-play token staleness — app-side,
not fixable server-side. Practical site-side reality: only 1 of 3 servers yields a playable
source per title now (loadvid dying, hlsfast copies being deleted), so the app's success
rides entirely on the hlsfree chain.

## verify.sh run (recorded 2026-09-26)

Mechanically asserted (`/search/carnal`, home page 1, 5 video pages incl. the reported one,
position-matched `--stream-url` chain-resolved manifests, related selector, full field set):

- search: 200, 18 cards, no duplicates — PASS
- home page 1: 200, 61 cards — dup FAIL is the recorded #203 site artifact (latest-10
  carousel widget + main grid double-render; provider's SearchCard dedupes by href)
- video pages: 5/5 200, `div[data-post_id]` matches 1, titles/posters/plots present, tags/
  actors/year present on all 5 — PASS; duration not exposed (FINDINGS records absence)
- streams: 5/5 chain-resolved manifests → 200 application/vnd.apple.mpegurl — PASS
  (path-distinctness FAIL is the recorded #417 artifact: `/api/hls/serve` is one token-varying
  proxy path)
- related: 11 matches/page — empty-title FAIL is the recorded #417 tool artifact
  (halim-popover escaped payload truncates the regex block extractor on 3 cards; provider's
  SearchCard drops cards without real title/link)
- search↔load agreement title mismatch: same artifact family — the tool reads card text only,
  the provider reads `title` attr (`title="Carnal Olympics (1983)"`, direct-probed == load title)
- LoadResponse completeness (recommendations,tags,plot,year,actors): PASS

RESULT: FAIL only on the four recorded artifact classes above; every mechanically-checkable
surface passes. No provider change, no version bump (issue-#480 precedent).
